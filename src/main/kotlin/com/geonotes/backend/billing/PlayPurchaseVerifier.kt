package com.geonotes.backend.billing

import java.time.Clock
import java.time.Duration
import java.time.Instant

data class PurchaseVerification(
    val valid: Boolean,
    val expiresAt: Instant?,
)

/** Verifies a Google Play purchase/subscription token server-side. */
interface PlayPurchaseVerifier {
    suspend fun verify(productId: String, purchaseToken: String): PurchaseVerification
}

/**
 * Placeholder until the real Google Play Developer API integration exists.
 *
 * TODO: implement with the Android Publisher API (`purchases.subscriptionsv2.get`) using a
 *  service account, acknowledge purchases, and consume Real-Time Developer Notifications (RTDN)
 *  via Pub/Sub to handle renewals/cancellations/refunds.
 *
 * Behaviour: never grants Pro, except when [allowTestTokens] is true (AUTH_MODE=dev) and the
 * token starts with `test-valid`, which grants 30 days of Pro. Lets the app exercise the flow.
 */
class StubPlayPurchaseVerifier(
    private val clock: Clock,
    private val allowTestTokens: Boolean,
) : PlayPurchaseVerifier {
    override suspend fun verify(productId: String, purchaseToken: String): PurchaseVerification =
        if (allowTestTokens && purchaseToken.startsWith("test-valid")) {
            PurchaseVerification(valid = true, expiresAt = clock.instant().plus(Duration.ofDays(30)))
        } else {
            PurchaseVerification(valid = false, expiresAt = null)
        }
}
