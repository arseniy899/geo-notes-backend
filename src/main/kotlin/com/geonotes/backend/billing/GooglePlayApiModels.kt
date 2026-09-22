package com.geonotes.backend.billing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * Minimal wire models of the Android Publisher API v3 responses we read. Unknown fields are ignored.
 * Reference: https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2
 *            https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.productsv2
 */

/** `purchases.subscriptionsv2.get` → SubscriptionPurchaseV2. */
@Serializable
internal data class SubscriptionPurchaseV2(
    val subscriptionState: String? = null,
    val lineItems: List<SubscriptionLineItem> = emptyList(),
    val linkedPurchaseToken: String? = null,
    val acknowledgementState: String? = null,
    /** Present (as an empty object) only for license-tester purchases. */
    val testPurchase: JsonObject? = null,
    val startTime: String? = null,
)

@Serializable
internal data class SubscriptionLineItem(
    val productId: String? = null,
    val expiryTime: String? = null,
    val autoRenewingPlan: AutoRenewingPlan? = null,
)

@Serializable
internal data class AutoRenewingPlan(val autoRenewEnabled: Boolean? = null)

/** `purchases.productsv2.getproductpurchasev2` → ProductPurchaseV2. */
@Serializable
internal data class ProductPurchaseV2(
    val productLineItem: List<ProductLineItem> = emptyList(),
    val purchaseStateContext: PurchaseStateContext? = null,
    /** Present only for test purchases (`fopType: TEST`). */
    val testPurchaseContext: JsonObject? = null,
    val acknowledgementState: String? = null,
    val purchaseCompletionTime: String? = null,
)

@Serializable
internal data class ProductLineItem(val productId: String? = null)

@Serializable
internal data class PurchaseStateContext(val purchaseState: String? = null)

/** Google API error envelope `{"error":{"code":404,"status":"NOT_FOUND",...}}` (only read for diagnostics). */
@Serializable
internal data class GoogleApiErrorEnvelope(val error: GoogleApiError? = null)

@Serializable
internal data class GoogleApiError(val code: Int? = null, val status: String? = null)
