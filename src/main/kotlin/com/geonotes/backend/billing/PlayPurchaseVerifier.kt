package com.geonotes.backend.billing

import com.geonotes.backend.domain.model.EntitlementState
import com.geonotes.backend.domain.model.ProductKind
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * What Google Play reports for one purchase token, already mapped to domain terms.
 *
 * @property productId the product Play says was bought (may differ from what the client claimed).
 * @property expiresAt subscription expiry (max over line items); null for one-time products.
 * @property linkedPurchaseToken the previous token of an upgrade/downgrade/resubscribe chain, if any.
 */
data class PlayPurchase(
    val productId: String?,
    val state: EntitlementState,
    val expiresAt: Instant?,
    val autoRenewing: Boolean = false,
    val acknowledged: Boolean = true,
    val testPurchase: Boolean = false,
    val linkedPurchaseToken: String? = null,
) {
    companion object {
        /** Play does not know this token (404/410/400). */
        val INVALID = PlayPurchase(productId = null, state = EntitlementState.INVALID, expiresAt = null)
    }
}

/**
 * Port to the Google Play Developer API. Implementations must never log purchase tokens.
 *
 * Failures that are not about the token itself (auth misconfiguration, 5xx, timeouts, 429) are thrown as
 * [com.geonotes.backend.domain.UpstreamUnavailableException] → HTTP 503 with `Retry-After`.
 */
interface PlayPurchaseVerifier {
    /**
     * Looks up [purchaseToken]. For [ProductKind.SUBSCRIPTION] the [productId] is informational
     * (subscriptionsv2 needs only the token); for [ProductKind.ONE_TIME] it is informational too
     * (productsv2 needs only the token). Unknown tokens return [PlayPurchase.INVALID].
     */
    suspend fun verify(kind: ProductKind, productId: String, purchaseToken: String): PlayPurchase

    /** Acknowledges a purchase server-side. Idempotent: an already-acknowledged purchase is not an error. */
    suspend fun acknowledge(kind: ProductKind, productId: String, purchaseToken: String)
}

/**
 * Local/testing verifier (AUTH_MODE=dev and tests). Never talks to Google.
 *
 * Never grants Pro, except when [allowTestTokens] is true and the token starts with `test-valid`:
 * subscriptions get 30 days of ACTIVE, one-time products a lifetime purchase.
 */
class StubPlayPurchaseVerifier(
    private val clock: Clock,
    private val allowTestTokens: Boolean,
) : PlayPurchaseVerifier {
    override suspend fun verify(kind: ProductKind, productId: String, purchaseToken: String): PlayPurchase {
        if (!allowTestTokens || !purchaseToken.startsWith("test-valid")) return PlayPurchase.INVALID
        return when (kind) {
            ProductKind.SUBSCRIPTION -> PlayPurchase(
                productId = productId,
                state = EntitlementState.ACTIVE,
                expiresAt = clock.instant().plus(Duration.ofDays(30)),
                autoRenewing = true,
                testPurchase = true,
            )
            ProductKind.ONE_TIME -> PlayPurchase(productId = productId, state = EntitlementState.ACTIVE, expiresAt = null, testPurchase = true)
        }
    }

    override suspend fun acknowledge(kind: ProductKind, productId: String, purchaseToken: String) = Unit
}
