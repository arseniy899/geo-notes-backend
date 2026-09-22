package com.geonotes.backend.api.model

import kotlinx.serialization.Serializable

@Serializable
data class RegisterDeviceRequest(
    val deviceId: String,
    val fcmToken: String,
    /** Base64 (standard or URL-safe) device public key. */
    val publicKey: String,
    /** ANDROID | IOS (case-insensitive). */
    val platform: String,
)

@Serializable
data class DeviceResponse(
    val deviceId: String,
    val platform: String,
    val publicKey: String,
    val updatedAt: String,
)

@Serializable
data class DeviceKeysResponse(val userId: String, val devices: List<DeviceResponse>)
