package com.geonotes.backend.api.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@Serializable
data class ShareRecipientRequest(
    val userId: String,
    val deviceId: String,
    /** Base64 content key sealed to the recipient device's public key. */
    val sealedKey: String,
)

@Serializable
data class CreateShareRequest(
    /** Base64 ciphertext of the place definition (encrypted client-side). */
    val encryptedPlace: String,
    val recipients: List<ShareRecipientRequest>,
    val transitions: List<String> = listOf("ENTER"),
)

/**
 * PATCH semantics: absent field = unchanged; `"pausedUntil": null` clears the pause.
 * Built from the raw JSON object so "absent" and "null" can be told apart.
 */
data class UpdateShareRequest(
    val active: Boolean?,
    val pausedUntil: String?,
    val pausedUntilSet: Boolean,
) {
    companion object {
        fun fromJson(json: JsonObject): UpdateShareRequest {
            val unknown = json.keys - setOf("active", "pausedUntil")
            require(unknown.isEmpty()) { "Unknown fields: $unknown" }
            val active = json["active"]?.let { el ->
                require(el is JsonPrimitive && !el.isString && el.booleanOrNull != null) { "active must be a boolean" }
                el.booleanOrNull
            }
            val pausedEl = json["pausedUntil"]
            val paused = when (pausedEl) {
                null, JsonNull -> null
                is JsonPrimitive -> if (pausedEl.isString) pausedEl.content else throw IllegalArgumentException("pausedUntil must be an ISO-8601 string or null")
                else -> throw IllegalArgumentException("pausedUntil must be an ISO-8601 string or null")
            }
            return UpdateShareRequest(active, paused, pausedUntilSet = json.containsKey("pausedUntil"))
        }
    }
}

@Serializable
data class ShareRecipientResponse(val userId: String, val deviceId: String, val sealedKey: String)

@Serializable
data class ShareResponse(
    val id: String,
    val ownerId: String,
    /** Only set for received shares. */
    val ownerDisplayName: String? = null,
    val encryptedPlace: String,
    val transitions: List<String>,
    val active: Boolean,
    val pausedUntil: String?,
    val createdAt: String,
    val updatedAt: String,
    /** Owner view: all recipient devices. Recipient view: only the caller's own devices' sealed keys. */
    val recipients: List<ShareRecipientResponse>,
    /**
     * Owner view only: content key sealed to the owner's own devices (shares created by accepting a share
     * request, where the requester encrypted the place). Always empty in the recipient view.
     */
    val ownerKeys: List<DeviceSealedKey> = emptyList(),
)

@Serializable
data class SharesResponse(val owned: List<ShareResponse>, val received: List<ShareResponse>)
