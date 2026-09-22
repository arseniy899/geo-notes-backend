package com.geonotes.backend.api.model

import kotlinx.serialization.Serializable

/** A content key sealed to one device's public key. */
@Serializable
data class DeviceSealedKey(
    val deviceId: String,
    /** Base64 content key sealed to the device public key; opaque to the server. */
    val sealedKey: String,
)

@Serializable
data class CreateShareRequestRequest(
    /** The friend who is asked to share their arrivals (the future share owner). */
    val toUserId: String,
    /** Base64 ciphertext of the place definition (encrypted client-side). */
    val encryptedPlace: String,
    /** Content key sealed to each of the friend's devices, so they can decrypt and geofence the place. */
    val ownerKeys: List<DeviceSealedKey>,
    /** Content key sealed to each of the requester's own devices. */
    val recipientKeys: List<DeviceSealedKey>,
    val transitions: List<String> = listOf("ENTER"),
    /** Optional short message shown to the friend (plaintext, max 140 chars). Never put the place here. */
    val note: String? = null,
)

@Serializable
data class ShareRequestResponse(
    val id: String,
    /** INCOMING (caller is the asked friend) | OUTGOING (caller asked). */
    val direction: String,
    val fromUserId: String,
    val fromDisplayName: String,
    val toUserId: String,
    val toDisplayName: String,
    val encryptedPlace: String,
    val transitions: List<String>,
    val note: String?,
    /** PENDING | ACCEPTED | DECLINED */
    val status: String,
    /** Set once accepted: the share owned by `toUserId`. */
    val shareId: String?,
    val createdAt: String,
    val updatedAt: String,
    val expiresAt: String,
    /** Incoming view only: keys sealed to the caller's devices. Empty for outgoing requests. */
    val ownerKeys: List<DeviceSealedKey> = emptyList(),
)

@Serializable
data class ShareRequestsResponse(val incoming: List<ShareRequestResponse>, val outgoing: List<ShareRequestResponse>)

@Serializable
data class AcceptShareRequestResponse(val request: ShareRequestResponse, val share: ShareResponse)
