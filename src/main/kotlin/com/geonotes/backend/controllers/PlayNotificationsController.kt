package com.geonotes.backend.controllers

import com.geonotes.backend.api.model.DeveloperNotificationDto
import com.geonotes.backend.api.model.PubSubPushRequest
import com.geonotes.backend.api.model.RtdnResponse
import com.geonotes.backend.domain.model.PlayNotification
import com.geonotes.backend.domain.service.EntitlementService
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * ViewModel of the Pub/Sub push endpoint for Google Play RTDN. The caller is already authenticated
 * (Pub/Sub OIDC token). Decodes `message.data` → DeveloperNotification → domain [PlayNotification].
 *
 * An authentic but undecodable message is acknowledged (IGNORED) so Pub/Sub stops redelivering it.
 */
class PlayNotificationsController(private val entitlements: EntitlementService) {
    private val log = LoggerFactory.getLogger(PlayNotificationsController::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun handle(request: PubSubPushRequest): RtdnResponse {
        val notification = decode(request.message.data)
        val outcome = entitlements.handleNotification(notification)
        log.info("RTDN {} messageId={} → {}", notification::class.simpleName, request.message.messageId, outcome)
        return RtdnResponse(outcome.name)
    }

    internal fun decode(data: String?): PlayNotification {
        if (data.isNullOrBlank()) return PlayNotification.Other(null)
        val dto = try {
            json.decodeFromString<DeveloperNotificationDto>(String(Base64.getDecoder().decode(data.trim()), Charsets.UTF_8))
        } catch (_: IllegalArgumentException) {
            log.warn("RTDN message.data is not valid base64")
            return PlayNotification.Other(null)
        } catch (_: SerializationException) {
            log.warn("RTDN message.data is not a DeveloperNotification")
            return PlayNotification.Other(null)
        }
        val pkg = dto.packageName
        dto.subscriptionNotification?.purchaseToken?.takeIf { it.isNotBlank() }?.let {
            return PlayNotification.Subscription(pkg, it, dto.subscriptionNotification.notificationType)
        }
        dto.oneTimeProductNotification?.purchaseToken?.takeIf { it.isNotBlank() }?.let {
            return PlayNotification.OneTimeProduct(pkg, it, dto.oneTimeProductNotification.sku, dto.oneTimeProductNotification.notificationType)
        }
        dto.voidedPurchaseNotification?.purchaseToken?.takeIf { it.isNotBlank() }?.let {
            val v = dto.voidedPurchaseNotification
            return PlayNotification.VoidedPurchase(pkg, it, v.productType, v.refundType)
        }
        if (dto.testNotification != null) return PlayNotification.Test(pkg)
        return PlayNotification.Other(pkg)
    }
}
