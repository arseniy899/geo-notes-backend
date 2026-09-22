package com.geonotes.backend.domain.model

/** Which Play API family a product belongs to. */
enum class ProductKind { SUBSCRIPTION, ONE_TIME }

/** The Play products that unlock Pro, and which Play API family each belongs to. */
object ProductCatalog {
    val products: Map<String, ProductKind> = mapOf(
        "pro_monthly" to ProductKind.SUBSCRIPTION,
        "pro_yearly" to ProductKind.SUBSCRIPTION,
        "pro_lifetime" to ProductKind.ONE_TIME,
    )

    fun kindOf(productId: String): ProductKind? = products[productId]
}

/**
 * A Google Play Real-time Developer Notification (decoded from the Pub/Sub message), in domain terms.
 * Reference: https://developer.android.com/google/play/billing/rtdn-reference
 */
sealed interface PlayNotification {
    val packageName: String?

    /** `subscriptionNotification`; [type] is Play's notificationType (e.g. 2 RENEWED, 3 CANCELED, 13 EXPIRED). */
    data class Subscription(override val packageName: String?, val purchaseToken: String, val type: Int?) : PlayNotification

    /** `oneTimeProductNotification`; type 1 PURCHASED, 2 CANCELED. */
    data class OneTimeProduct(override val packageName: String?, val purchaseToken: String, val productId: String?, val type: Int?) : PlayNotification

    /** `voidedPurchaseNotification` (refund, chargeback, revoke). refundType 1 = full, 2 = partial (quantity-based). */
    data class VoidedPurchase(
        override val packageName: String?,
        val purchaseToken: String,
        val productType: Int?,
        val refundType: Int?,
    ) : PlayNotification

    /** `testNotification` sent from Play Console → Monetization setup. */
    data class Test(override val packageName: String?) : PlayNotification

    /** Authentic but not relevant to us (e.g. pendingRefundReviewNotification) or unparseable. */
    data class Other(override val packageName: String?) : PlayNotification

    companion object {
        const val REFUND_TYPE_PARTIAL = 2
    }
}

/** What handling an RTDN did; returned in the 200 response body for observability. */
enum class NotificationOutcome {
    /** The bound entitlement was re-verified/updated. */
    UPDATED,
    /** Entitlement revoked (voided purchase). */
    REVOKED,
    /** Token not bound to any account (yet): nothing to do. The client's verify call will bind it. */
    UNKNOWN_TOKEN,
    /** Test, foreign-package, partial refund or unsupported notification. */
    IGNORED,
}
