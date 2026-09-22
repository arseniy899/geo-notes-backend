package com.geonotes.backend.api.model

import kotlinx.serialization.Serializable

/**
 * Pub/Sub push envelope (https://cloud.google.com/pubsub/docs/push#receive_push):
 * `{"message":{"data":"<base64 DeveloperNotification>","messageId":"…","attributes":{…}},"subscription":"projects/…"}`.
 */
@Serializable
data class PubSubPushRequest(val message: PubSubMessage, val subscription: String? = null)

@Serializable
data class PubSubMessage(
    val data: String? = null,
    val messageId: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

/** Google Play `DeveloperNotification` (https://developer.android.com/google/play/billing/rtdn-reference). */
@Serializable
data class DeveloperNotificationDto(
    val version: String? = null,
    val packageName: String? = null,
    val subscriptionNotification: SubscriptionNotificationDto? = null,
    val oneTimeProductNotification: OneTimeProductNotificationDto? = null,
    val voidedPurchaseNotification: VoidedPurchaseNotificationDto? = null,
    val testNotification: TestNotificationDto? = null,
)

@Serializable
data class SubscriptionNotificationDto(val version: String? = null, val notificationType: Int? = null, val purchaseToken: String? = null)

@Serializable
data class OneTimeProductNotificationDto(
    val version: String? = null,
    val notificationType: Int? = null,
    val purchaseToken: String? = null,
    val sku: String? = null,
)

@Serializable
data class VoidedPurchaseNotificationDto(
    val purchaseToken: String? = null,
    val orderId: String? = null,
    val productType: Int? = null,
    val refundType: Int? = null,
)

@Serializable
data class TestNotificationDto(val version: String? = null)

/** 200 body of POST /v1/play/rtdn. `outcome`: UPDATED, REVOKED, UNKNOWN_TOKEN, IGNORED. */
@Serializable
data class RtdnResponse(val outcome: String)
