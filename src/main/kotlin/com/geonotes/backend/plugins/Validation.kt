package com.geonotes.backend.plugins

import com.geonotes.backend.api.model.CreateShareRequest
import com.geonotes.backend.api.model.PostEventRequest
import com.geonotes.backend.api.model.RegisterDeviceRequest
import com.geonotes.backend.api.model.UpsertMeRequest
import com.geonotes.backend.api.model.VerifyPurchaseRequest
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.plugins.requestvalidation.ValidationResult

/** Syntactic validation of request DTOs. Semantic rules (friendship, quotas…) live in domain services. */
fun Application.configureValidation() {
    install(RequestValidation) {
        validate<UpsertMeRequest> { r ->
            rules(rule(r.displayName.isNotBlank() && r.displayName.length <= 64, "displayName must be 1..64 characters"))
        }
        validate<RegisterDeviceRequest> { r ->
            rules(
                rule(r.deviceId.length in 8..64 && r.deviceId.all { it.isLetterOrDigit() || it in "-_" }, "deviceId must be 8..64 chars of [A-Za-z0-9_-]"),
                rule(r.fcmToken.isNotBlank() && r.fcmToken.length <= 4096, "fcmToken must be 1..4096 characters"),
                rule(r.publicKey.isNotBlank() && r.publicKey.length <= 1024, "publicKey must be base64, max 1024 chars"),
                rule(r.platform.isNotBlank(), "platform is required"),
            )
        }
        validate<CreateShareRequest> { r ->
            rules(
                rule(r.encryptedPlace.isNotBlank() && r.encryptedPlace.length <= 12_000, "encryptedPlace must be base64, max ~8KiB"),
                rule(r.recipients.isNotEmpty() && r.recipients.size <= 50, "recipients must contain 1..50 entries"),
                rule(r.transitions.isNotEmpty() && r.transitions.size <= 2, "transitions must contain 1..2 entries"),
                rule(
                    r.recipients.all { it.userId.isNotBlank() && it.deviceId.isNotBlank() && it.sealedKey.isNotBlank() },
                    "recipients[].userId, deviceId and sealedKey are required",
                ),
            )
        }
        validate<PostEventRequest> { r ->
            rules(
                rule(r.shareId.isNotBlank(), "shareId is required"),
                rule(r.transition.isNotBlank(), "transition is required"),
                rule(r.occurredAt.isNotBlank(), "occurredAt is required"),
            )
        }
        validate<VerifyPurchaseRequest> { r ->
            rules(
                rule(r.purchaseToken.isNotBlank() && r.purchaseToken.length <= 4096, "purchaseToken must be 1..4096 characters"),
                rule(r.productId.isNotBlank() && r.productId.length <= 64, "productId must be 1..64 characters"),
            )
        }
    }
}

private fun rule(ok: Boolean, message: String): String? = if (ok) null else message

private fun rules(vararg failures: String?): ValidationResult {
    val messages = failures.filterNotNull()
    return if (messages.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(messages)
}
