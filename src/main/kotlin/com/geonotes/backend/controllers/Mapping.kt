package com.geonotes.backend.controllers

import com.geonotes.backend.api.model.DeviceResponse
import com.geonotes.backend.api.model.EntitlementResponse
import com.geonotes.backend.api.model.FriendResponse
import com.geonotes.backend.api.model.ShareRecipientResponse
import com.geonotes.backend.api.model.ShareResponse
import com.geonotes.backend.domain.ValidationException
import com.geonotes.backend.domain.model.Device
import com.geonotes.backend.domain.model.Entitlement
import com.geonotes.backend.domain.model.Friend
import com.geonotes.backend.domain.model.Share
import com.geonotes.backend.domain.model.Transition
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

/** Request parsing helpers: API strings → domain types, failing with 400 `validation_failed`. */
internal object Parse {
    fun base64(field: String, value: String): ByteArray {
        val v = value.trim()
        return try {
            if (v.contains('-') || v.contains('_')) Base64.getUrlDecoder().decode(v) else Base64.getDecoder().decode(v)
        } catch (_: IllegalArgumentException) {
            throw ValidationException("$field must be valid base64")
        }
    }

    fun instant(field: String, value: String): Instant = try {
        Instant.parse(value)
    } catch (_: DateTimeParseException) {
        throw ValidationException("$field must be an ISO-8601 instant, e.g. 2026-01-31T12:00:00Z")
    }

    fun uuid(field: String, value: String): UUID = try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw ValidationException("$field must be a UUID")
    }

    fun transition(field: String, value: String): Transition =
        Transition.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw ValidationException("$field must be one of ${Transition.entries.joinToString()}")
}

internal fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)

internal fun Device.toResponse() = DeviceResponse(deviceId = id, platform = platform.name, publicKey = publicKey.b64(), updatedAt = updatedAt.toString())

internal fun Friend.toResponse() = FriendResponse(userId = userId, displayName = displayName, since = since.toString())

internal fun Entitlement?.toResponse(now: Instant) = EntitlementResponse(
    pro = this?.isProAt(now) ?: false,
    expiresAt = this?.expiresAt?.toString(),
    productId = this?.productId,
)

internal fun Share.toResponse(ownerDisplayName: String? = null) = ShareResponse(
    id = id.toString(),
    ownerId = ownerId,
    ownerDisplayName = ownerDisplayName,
    encryptedPlace = encryptedPlace.b64(),
    transitions = transitions.map { it.name }.sorted(),
    active = active,
    pausedUntil = pausedUntil?.toString(),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    recipients = recipients.map { ShareRecipientResponse(it.userId, it.deviceId, it.sealedKey.b64()) },
)
