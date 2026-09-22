package com.geonotes.backend.api.model

import kotlinx.serialization.Serializable

@Serializable
data class VerifyPurchaseRequest(val purchaseToken: String, val productId: String)

@Serializable
data class EntitlementResponse(val pro: Boolean, val expiresAt: String? = null, val productId: String? = null)
