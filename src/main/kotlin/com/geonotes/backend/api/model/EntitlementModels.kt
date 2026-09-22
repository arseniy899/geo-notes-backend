package com.geonotes.backend.api.model

import kotlinx.serialization.Serializable

@Serializable
data class VerifyPurchaseRequest(val purchaseToken: String, val productId: String)

/**
 * The caller's Pro entitlement. `pro` is the only field clients need to gate features; the rest is informational.
 * `state` is one of ACTIVE, IN_GRACE_PERIOD, CANCELED, ON_HOLD, PAUSED, PENDING, EXPIRED, REVOKED, REPLACED, INVALID
 * (null when the user never verified a purchase).
 */
@Serializable
data class EntitlementResponse(
    val pro: Boolean,
    val expiresAt: String? = null,
    val productId: String? = null,
    val state: String? = null,
    val autoRenewing: Boolean = false,
    val lastVerifiedAt: String? = null,
)
