package com.geonotes.backend.billing

import com.geonotes.backend.domain.UpstreamUnavailableException
import com.geonotes.backend.domain.model.EntitlementState
import com.geonotes.backend.domain.model.ProductKind
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GooglePlayPurchaseVerifierTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    private fun verifier(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): GooglePlayPurchaseVerifier {
        val engine = MockEngine { req -> requests += req; handler(req) }
        return GooglePlayPurchaseVerifier(
            packageName = "com.ars899.geonotes",
            accessTokens = { "ya29.test-access-token" },
            httpClient = GooglePlayPurchaseVerifier.defaultHttpClient(engine),
            baseUrl = "https://play.test",
        )
    }

    private fun MockRequestHandleScope.json(name: String, status: HttpStatusCode = HttpStatusCode.OK, extra: Pair<String, String>? = null) =
        respond(
            PlayFixtures.load(name), status,
            if (extra == null) headersOf(HttpHeaders.ContentType, "application/json")
            else headersOf(HttpHeaders.ContentType to listOf("application/json"), extra.first to listOf(extra.second)),
        )

    @Test
    fun `subscription lookup calls subscriptionsv2 get with the service-account bearer`() = runTest {
        val p = verifier { json("subscriptionsv2_active") }.verify(ProductKind.SUBSCRIPTION, "pro_monthly", "tok-abc.123_x")
        assertEquals(EntitlementState.ACTIVE, p.state)
        val req = requests.single()
        assertEquals(HttpMethod.Get, req.method)
        assertEquals("/androidpublisher/v3/applications/com.ars899.geonotes/purchases/subscriptionsv2/tokens/tok-abc.123_x", req.url.encodedPath)
        assertEquals("Bearer ya29.test-access-token", req.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `one-time lookup calls productsv2`() = runTest {
        val p = verifier { json("productsv2_purchased") }.verify(ProductKind.ONE_TIME, "pro_lifetime", "life-token")
        assertEquals(EntitlementState.ACTIVE, p.state)
        assertEquals("/androidpublisher/v3/applications/com.ars899.geonotes/purchases/productsv2/tokens/life-token", requests.single().url.encodedPath)
    }

    @Test
    fun `404, 410 and 400 mean the token is invalid (not pro), not an outage`() = runTest {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.Gone, HttpStatusCode.BadRequest)) {
            val p = verifier { json("error_404", status) }.verify(ProductKind.SUBSCRIPTION, "pro_monthly", "t")
            assertEquals(PlayPurchase.INVALID, p, "status $status")
        }
    }

    @Test
    fun `401 and 403 are an upstream misconfiguration`() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            val e = assertFailsWith<UpstreamUnavailableException> {
                verifier { json("error_403", status) }.verify(ProductKind.SUBSCRIPTION, "pro_monthly", "t")
            }
            assertEquals("billing_unavailable", e.code)
            assertEquals(GooglePlayPurchaseVerifier.MISCONFIG_RETRY_AFTER_SECONDS, e.retryAfterSeconds)
        }
    }

    @Test
    fun `5xx and 429 are retryable outages, 429 honours Retry-After`() = runTest {
        val e5 = assertFailsWith<UpstreamUnavailableException> {
            verifier { respond("oops", HttpStatusCode.ServiceUnavailable) }.verify(ProductKind.SUBSCRIPTION, "pro_monthly", "t")
        }
        assertEquals(GooglePlayPurchaseVerifier.RETRY_AFTER_SECONDS, e5.retryAfterSeconds)
        val e429 = assertFailsWith<UpstreamUnavailableException> {
            verifier { json("error_403", HttpStatusCode.TooManyRequests, HttpHeaders.RetryAfter to "17") }
                .verify(ProductKind.SUBSCRIPTION, "pro_monthly", "t")
        }
        assertEquals(17, e429.retryAfterSeconds)
    }

    @Test
    fun `timeouts and IO failures become 503 with retry-after`() = runTest {
        val timeout = assertFailsWith<UpstreamUnavailableException> {
            verifier { throw HttpRequestTimeoutException("https://play.test", 10_000) }.verify(ProductKind.SUBSCRIPTION, "pro_monthly", "t")
        }
        assertEquals(GooglePlayPurchaseVerifier.RETRY_AFTER_SECONDS, timeout.retryAfterSeconds)
        assertFailsWith<UpstreamUnavailableException> {
            verifier { throw IOException("connection reset") }.verify(ProductKind.ONE_TIME, "pro_lifetime", "t")
        }
        // Access-token refresh failure (e.g. revoked key) is also an outage, never a 500.
        val noToken = GooglePlayPurchaseVerifier(
            "com.ars899.geonotes", { throw IOException("invalid_grant") },
            GooglePlayPurchaseVerifier.defaultHttpClient(MockEngine { json("subscriptionsv2_active") }), "https://play.test",
        )
        assertFailsWith<UpstreamUnavailableException> { noToken.verify(ProductKind.SUBSCRIPTION, "pro_monthly", "t") }
    }

    @Test
    fun `unparseable success body is an outage, not a crash`() = runTest {
        assertFailsWith<UpstreamUnavailableException> {
            verifier { respond("<html>", HttpStatusCode.OK) }.verify(ProductKind.SUBSCRIPTION, "pro_monthly", "t")
        }
    }

    @Test
    fun `acknowledge uses the v1 subscriptions and products endpoints`() = runTest {
        val v = verifier { respond("", HttpStatusCode.OK) }
        v.acknowledge(ProductKind.SUBSCRIPTION, "pro_yearly", "sub-token")
        v.acknowledge(ProductKind.ONE_TIME, "pro_lifetime", "life-token")
        assertEquals(listOf(HttpMethod.Post, HttpMethod.Post), requests.map { it.method })
        assertEquals(
            listOf(
                "/androidpublisher/v3/applications/com.ars899.geonotes/purchases/subscriptions/pro_yearly/tokens/sub-token:acknowledge",
                "/androidpublisher/v3/applications/com.ars899.geonotes/purchases/products/pro_lifetime/tokens/life-token:acknowledge",
            ),
            requests.map { it.url.encodedPath },
        )
    }

    @Test
    fun `acknowledge is idempotent for already-acknowledged or gone purchases`() = runTest {
        for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.Conflict, HttpStatusCode.NotFound, HttpStatusCode.Gone)) {
            verifier { respond("{}", status) }.acknowledge(ProductKind.SUBSCRIPTION, "pro_monthly", "t")
        }
        assertFailsWith<UpstreamUnavailableException> {
            verifier { respond("{}", HttpStatusCode.Forbidden) }.acknowledge(ProductKind.SUBSCRIPTION, "pro_monthly", "t")
        }
        assertTrue(requests.size == 5)
    }
}
