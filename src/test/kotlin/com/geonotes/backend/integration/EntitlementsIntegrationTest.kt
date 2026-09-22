package com.geonotes.backend.integration

import com.geonotes.backend.api.model.EntitlementResponse
import com.geonotes.backend.api.model.MeResponse
import com.geonotes.backend.api.model.RtdnResponse
import com.geonotes.backend.api.model.VerifyPurchaseRequest
import com.geonotes.backend.billing.PlayPurchase
import com.geonotes.backend.domain.model.EntitlementState
import com.geonotes.backend.domain.model.PurchaseTokens
import com.geonotes.backend.support.FakePlayPurchaseVerifier
import com.geonotes.backend.support.IntegrationTest
import com.geonotes.backend.support.TestDatabase
import com.geonotes.backend.support.errorCode
import com.geonotes.backend.support.expect
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntitlementsIntegrationTest : IntegrationTest() {
    private val play = FakePlayPurchaseVerifier()
    private val pkg = "com.ars899.geonotes"

    private fun active(now: Instant, days: Long = 30, ack: Boolean = false) =
        PlayPurchase("pro_monthly", EntitlementState.ACTIVE, now.plus(Duration.ofDays(days)), autoRenewing = true, acknowledged = ack)

    private fun subNotification(token: String, type: Int) =
        """{"version":"1.0","packageName":"$pkg","eventTimeMillis":"1780308000000","subscriptionNotification":{"version":"1.0","notificationType":$type,"purchaseToken":"$token"}}"""

    private fun voided(token: String) =
        """{"version":"1.0","packageName":"$pkg","eventTimeMillis":"1780308000000","voidedPurchaseNotification":{"purchaseToken":"$token","orderId":"GPA.9","productType":1,"refundType":1}}"""

    @Test
    fun `verify stores Play state by token hash and acknowledges the purchase`() = apiTest(purchaseVerifier = play) {
        user("alice")
        play.purchases["real-token-1"] = active(clock.instant())

        val r = post("/v1/entitlements/verify", "alice", VerifyPurchaseRequest("real-token-1", "pro_monthly"))
            .expect(HttpStatusCode.OK).body<EntitlementResponse>()
        assertTrue(r.pro)
        assertEquals("ACTIVE", r.state)
        assertTrue(r.autoRenewing)
        assertEquals(1, play.acknowledged.size)

        val hash = PurchaseTokens.hash("real-token-1")
        assertEquals(1, TestDatabase.count("entitlements", "user_id = 'alice' AND token_hash = '$hash' AND state = 'ACTIVE' AND acknowledged"))
        assertTrue(get("/v1/me", "alice").body<MeResponse>().entitlement.pro)
        assertEquals(r, get("/v1/entitlements", "alice").expect(HttpStatusCode.OK).body<EntitlementResponse>())
    }

    @Test
    fun `token cannot be bound to a second account`() = apiTest(purchaseVerifier = play) {
        user("alice"); user("bob")
        play.purchases["t"] = active(clock.instant())
        post("/v1/entitlements/verify", "alice", VerifyPurchaseRequest("t", "pro_monthly")).expect(HttpStatusCode.OK)
        assertEquals(
            "purchase_token_in_use",
            post("/v1/entitlements/verify", "bob", VerifyPurchaseRequest("t", "pro_monthly")).expect(HttpStatusCode.Conflict).errorCode(),
        )
    }

    @Test
    fun `Play outage is a 503 with Retry-After`() = apiTest(purchaseVerifier = play) {
        user("alice")
        play.unavailable = true
        val res = post("/v1/entitlements/verify", "alice", VerifyPurchaseRequest("t", "pro_monthly")).expect(HttpStatusCode.ServiceUnavailable)
        assertEquals("30", res.headers[HttpHeaders.RetryAfter])
        assertEquals("billing_unavailable", res.errorCode())
    }

    @Test
    fun `entitlement endpoints require a user token`() = apiTest(purchaseVerifier = play) {
        get("/v1/entitlements", null).expect(HttpStatusCode.Unauthorized)
        client.post("/v1/entitlements/verify") {
            contentType(ContentType.Application.Json); setBody(VerifyPurchaseRequest("t", "pro_monthly"))
        }.expect(HttpStatusCode.Unauthorized)
        user("alice")
        val empty = get("/v1/entitlements", "alice").expect(HttpStatusCode.OK).body<EntitlementResponse>()
        assertFalse(empty.pro)
        assertEquals(null, empty.state)
    }

    @Test
    fun `GET re-verifies an expired subscription with Play`() = apiTest(purchaseVerifier = play) {
        user("alice")
        play.purchases["t"] = active(clock.instant(), days = 1, ack = true)
        post("/v1/entitlements/verify", "alice", VerifyPurchaseRequest("t", "pro_monthly")).expect(HttpStatusCode.OK)
        clock.advance(Duration.ofDays(2))
        play.purchases["t"] = active(clock.instant(), days = 29, ack = true)
        assertTrue(get("/v1/entitlements", "alice").body<EntitlementResponse>().pro)
    }

    @Test
    fun `RTDN rejects missing, invalid and user bearer tokens with 401`() = apiTest(purchaseVerifier = play) {
        user("alice")
        assertEquals("unauthorized", rtdn(subNotification("t", 2), bearer = null).expect(HttpStatusCode.Unauthorized).errorCode())
        rtdn(subNotification("t", 2), bearer = "forged.jwt.token").expect(HttpStatusCode.Unauthorized)
        rtdn(subNotification("t", 2), bearer = "dev:alice").expect(HttpStatusCode.Unauthorized)
        // And a Pub/Sub token is not a user token.
        client.get("/v1/entitlements") { bearerAuth(com.geonotes.backend.support.FakePubSubTokenVerifier.VALID) }.expect(HttpStatusCode.Unauthorized)
    }

    @Test
    fun `RTDN acknowledges test and unknown-token notifications with 200`() = apiTest(purchaseVerifier = play) {
        assertEquals(
            "IGNORED",
            rtdn("""{"version":"1.0","packageName":"$pkg","eventTimeMillis":"1","testNotification":{"version":"1.0"}}""")
                .expect(HttpStatusCode.OK).body<RtdnResponse>().outcome,
        )
        assertEquals("UNKNOWN_TOKEN", rtdn(subNotification("nobody", 2)).expect(HttpStatusCode.OK).body<RtdnResponse>().outcome)
        assertEquals("IGNORED", rtdn("not json at all").expect(HttpStatusCode.OK).body<RtdnResponse>().outcome)
    }

    @Test
    fun `RTDN renewal updates and voided purchase revokes the entitlement`() = apiTest(purchaseVerifier = play) {
        user("alice")
        play.purchases["tok"] = active(clock.instant(), days = 1, ack = true)
        post("/v1/entitlements/verify", "alice", VerifyPurchaseRequest("tok", "pro_monthly")).expect(HttpStatusCode.OK)

        play.purchases["tok"] = active(clock.instant(), days = 31, ack = true)
        assertEquals("UPDATED", rtdn(subNotification("tok", 2)).expect(HttpStatusCode.OK).body<RtdnResponse>().outcome)
        val renewed = get("/v1/entitlements", "alice").body<EntitlementResponse>()
        assertEquals(clock.instant().plus(Duration.ofDays(31)).toString(), renewed.expiresAt)

        assertEquals("REVOKED", rtdn(voided("tok")).expect(HttpStatusCode.OK).body<RtdnResponse>().outcome)
        val revoked = get("/v1/entitlements", "alice").body<EntitlementResponse>()
        assertFalse(revoked.pro)
        assertEquals("REVOKED", revoked.state)
        assertEquals(1, TestDatabase.count("entitlements", "user_id = 'alice' AND state = 'REVOKED'"))
    }

    @Test
    fun `RTDN answers 503 when Play is down so Pub-Sub retries`() = apiTest(purchaseVerifier = play) {
        user("alice")
        play.purchases["tok"] = active(clock.instant(), ack = true)
        post("/v1/entitlements/verify", "alice", VerifyPurchaseRequest("tok", "pro_monthly")).expect(HttpStatusCode.OK)
        play.unavailable = true
        rtdn(subNotification("tok", 3)).expect(HttpStatusCode.ServiceUnavailable)
    }
}
