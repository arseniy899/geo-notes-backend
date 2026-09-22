package com.geonotes.backend.billing

import com.geonotes.backend.domain.UpstreamUnavailableException
import com.geonotes.backend.domain.model.EntitlementState
import com.geonotes.backend.domain.model.ProductKind
import com.google.auth.oauth2.GoogleCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException

/** Supplies OAuth2 access tokens with the `androidpublisher` scope. */
fun interface PlayAccessTokenProvider {
    suspend fun accessToken(): String
}

/** Service-account credentials (JSON key file) → cached, auto-refreshed access tokens. */
class ServiceAccountAccessTokenProvider(private val credentials: GoogleCredentials) : PlayAccessTokenProvider {
    override suspend fun accessToken(): String = withContext(Dispatchers.IO) {
        credentials.refreshIfExpired()
        credentials.accessToken?.tokenValue ?: throw IOException("No access token issued for the Play service account")
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/androidpublisher"

        /** Loads a service-account JSON key from [path], or Application Default Credentials when null. */
        fun fromFile(path: String?): ServiceAccountAccessTokenProvider {
            val base = path?.let { p -> FileInputStream(p).use { GoogleCredentials.fromStream(it) } }
                ?: GoogleCredentials.getApplicationDefault()
            return ServiceAccountAccessTokenProvider(base.createScoped(listOf(SCOPE)))
        }
    }
}

/**
 * Real Google Play verification via the Android Publisher API v3 (plain REST over Ktor client).
 *
 * - Subscriptions: `GET purchases/subscriptionsv2/tokens/{token}`; acknowledge with the v1
 *   `POST purchases/subscriptions/{productId}/tokens/{token}:acknowledge` (there is no v2 acknowledge).
 * - One-time products: `GET purchases/productsv2/tokens/{token}`; acknowledge with
 *   `POST purchases/products/{productId}/tokens/{token}:acknowledge`.
 *
 * Error mapping: 400/404/410 → [PlayPurchase.INVALID]; 401/403 (service account not linked in Play Console,
 * API disabled, wrong package) → 503; 429/5xx/timeouts/IO → 503 with Retry-After. Purchase tokens are never logged.
 */
class GooglePlayPurchaseVerifier(
    private val packageName: String,
    private val accessTokens: PlayAccessTokenProvider,
    private val httpClient: HttpClient = defaultHttpClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
) : PlayPurchaseVerifier, Closeable {
    private val log = LoggerFactory.getLogger(GooglePlayPurchaseVerifier::class.java)
    private val appPath get() = "$baseUrl/androidpublisher/v3/applications/${packageName.encodeURLPathPart()}/purchases"

    override suspend fun verify(kind: ProductKind, productId: String, purchaseToken: String): PlayPurchase {
        val (op, url) = when (kind) {
            ProductKind.SUBSCRIPTION -> "subscriptionsv2.get" to "$appPath/subscriptionsv2/tokens/${purchaseToken.encodeURLPathPart()}"
            ProductKind.ONE_TIME -> "productsv2.get" to "$appPath/productsv2/tokens/${purchaseToken.encodeURLPathPart()}"
        }
        val response = send(op) { httpClient.get(url) { bearerAuth(accessTokens.accessToken()) } }
        return when {
            response.status.isSuccess() -> {
                val body = response.bodyAsText()
                try {
                    when (kind) {
                        ProductKind.SUBSCRIPTION -> PlayMapping.subscription(PlayJson.decodeFromString<SubscriptionPurchaseV2>(body))
                        ProductKind.ONE_TIME -> PlayMapping.product(PlayJson.decodeFromString<ProductPurchaseV2>(body))
                    }
                } catch (e: SerializationException) {
                    log.error("Play {} returned an unparseable body", op)
                    throw UpstreamUnavailableException("Google Play returned an unexpected response", cause = e)
                }
            }
            response.status.value in TOKEN_INVALID_STATUSES -> {
                log.info("Play {} → {} (token unknown/expired) for productId={}", op, response.status.value, productId)
                PlayPurchase.INVALID
            }
            else -> throw upstreamError(op, response)
        }
    }

    override suspend fun acknowledge(kind: ProductKind, productId: String, purchaseToken: String) {
        val resource = when (kind) {
            ProductKind.SUBSCRIPTION -> "subscriptions"
            ProductKind.ONE_TIME -> "products"
        }
        val url = "$appPath/$resource/${productId.encodeURLPathPart()}/tokens/${purchaseToken.encodeURLPathPart()}:acknowledge"
        val op = "$resource.acknowledge"
        val response = send(op) {
            httpClient.post(url) {
                bearerAuth(accessTokens.accessToken())
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
        }
        when {
            response.status.isSuccess() -> log.info("Play {} ok for productId={}", op, productId)
            // Already acknowledged / not in an acknowledgeable state / token gone: nothing left to do (idempotent).
            response.status.value in ACK_NOOP_STATUSES -> log.info("Play {} → {} (treated as no-op) for productId={}", op, response.status.value, productId)
            else -> throw upstreamError(op, response)
        }
    }

    override fun close() = httpClient.close()

    private suspend fun send(op: String, block: suspend () -> HttpResponse): HttpResponse = try {
        block()
    } catch (e: HttpRequestTimeoutException) {
        throw timeout(op, e)
    } catch (e: CancellationException) {
        throw e
    } catch (e: UpstreamUnavailableException) {
        throw e
    } catch (e: IOException) {
        // Includes connect/socket timeouts and access-token refresh failures.
        log.warn("Play {} failed: {}", op, e.javaClass.simpleName)
        throw UpstreamUnavailableException("Google Play is unreachable; retry later", retryAfterSeconds = RETRY_AFTER_SECONDS, cause = e)
    }

    private fun timeout(op: String, e: Throwable): UpstreamUnavailableException {
        log.warn("Play {} timed out", op)
        return UpstreamUnavailableException("Google Play timed out; retry later", retryAfterSeconds = RETRY_AFTER_SECONDS, cause = e)
    }

    private suspend fun upstreamError(op: String, response: HttpResponse): UpstreamUnavailableException {
        val status = response.status.value
        val googleStatus = runCatching { PlayJson.decodeFromString<GoogleApiErrorEnvelope>(response.bodyAsText()).error?.status }.getOrNull()
        return when (status) {
            401, 403 -> {
                log.error(
                    "Play {} → {} {}: service account lacks access (link it in Play Console, enable the Android Publisher API, check PLAY_PACKAGE_NAME)",
                    op, status, googleStatus,
                )
                UpstreamUnavailableException("Purchase verification is misconfigured", retryAfterSeconds = MISCONFIG_RETRY_AFTER_SECONDS)
            }
            429 -> {
                val retry = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull() ?: RATE_LIMIT_RETRY_AFTER_SECONDS
                log.warn("Play {} rate limited (429)", op)
                UpstreamUnavailableException("Google Play rate limit; retry later", retryAfterSeconds = retry)
            }
            else -> {
                log.warn("Play {} → {} {}", op, status, googleStatus)
                UpstreamUnavailableException("Google Play error; retry later", retryAfterSeconds = RETRY_AFTER_SECONDS)
            }
        }
    }

    private fun HttpStatusCode.isSuccess() = value in 200..299

    companion object {
        const val DEFAULT_BASE_URL = "https://androidpublisher.googleapis.com"
        const val RETRY_AFTER_SECONDS = 30L
        const val RATE_LIMIT_RETRY_AFTER_SECONDS = 60L
        const val MISCONFIG_RETRY_AFTER_SECONDS = 300L
        private val TOKEN_INVALID_STATUSES = setOf(400, 404, 410)
        private val ACK_NOOP_STATUSES = setOf(400, 404, 409, 410)

        fun defaultHttpClient(engine: HttpClientEngine? = null): HttpClient {
            val config: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
                expectSuccess = false
                install(HttpTimeout) {
                    connectTimeoutMillis = 5_000
                    requestTimeoutMillis = 10_000
                    socketTimeoutMillis = 10_000
                }
            }
            return if (engine != null) HttpClient(engine, config) else HttpClient(CIO, config)
        }
    }
}

internal val PlayJson = Json { ignoreUnknownKeys = true }

/** Pure mapping from Play API responses to [PlayPurchase]. Unit-tested with recorded JSON fixtures. */
internal object PlayMapping {
    fun subscription(p: SubscriptionPurchaseV2): PlayPurchase {
        val state = when (p.subscriptionState) {
            "SUBSCRIPTION_STATE_ACTIVE" -> EntitlementState.ACTIVE
            "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" -> EntitlementState.IN_GRACE_PERIOD
            "SUBSCRIPTION_STATE_CANCELED" -> EntitlementState.CANCELED
            "SUBSCRIPTION_STATE_ON_HOLD" -> EntitlementState.ON_HOLD
            "SUBSCRIPTION_STATE_PAUSED" -> EntitlementState.PAUSED
            "SUBSCRIPTION_STATE_PENDING" -> EntitlementState.PENDING
            "SUBSCRIPTION_STATE_EXPIRED", "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED" -> EntitlementState.EXPIRED
            else -> EntitlementState.INVALID
        }
        // The latest-expiring line item defines access (one item per purchase for our products).
        val item = p.lineItems.maxByOrNull { parseInstant(it.expiryTime) ?: Instant.MIN }
        return PlayPurchase(
            productId = item?.productId,
            state = state,
            expiresAt = item?.let { parseInstant(it.expiryTime) },
            autoRenewing = item?.autoRenewingPlan?.autoRenewEnabled == true,
            acknowledged = p.acknowledgementState != "ACKNOWLEDGEMENT_STATE_PENDING",
            testPurchase = p.testPurchase != null,
            linkedPurchaseToken = p.linkedPurchaseToken?.takeIf { it.isNotBlank() },
        )
    }

    fun product(p: ProductPurchaseV2): PlayPurchase {
        val state = when (p.purchaseStateContext?.purchaseState) {
            "PURCHASED" -> EntitlementState.ACTIVE
            "PENDING" -> EntitlementState.PENDING
            // Canceled = refunded/voided/charged back (or pending purchase abandoned).
            "CANCELLED", "CANCELED" -> EntitlementState.REVOKED
            else -> EntitlementState.INVALID
        }
        return PlayPurchase(
            productId = p.productLineItem.firstOrNull()?.productId,
            state = state,
            expiresAt = null,
            autoRenewing = false,
            acknowledged = p.acknowledgementState != "ACKNOWLEDGEMENT_STATE_PENDING",
            testPurchase = p.testPurchaseContext != null,
        )
    }

    private fun parseInstant(value: String?): Instant? = try {
        value?.let(Instant::parse)
    } catch (_: DateTimeParseException) {
        null
    }
}
