package com.geonotes.backend.controllers

import com.geonotes.backend.api.model.DeviceResponse
import com.geonotes.backend.api.model.RegisterDeviceRequest
import com.geonotes.backend.domain.ValidationException
import com.geonotes.backend.domain.model.Platform
import com.geonotes.backend.domain.model.UserId
import com.geonotes.backend.domain.service.DeviceService

class DevicesController(private val devices: DeviceService) {
    suspend fun register(caller: UserId, request: RegisterDeviceRequest): DeviceResponse {
        val platform = Platform.entries.firstOrNull { it.name.equals(request.platform, ignoreCase = true) }
            ?: throw ValidationException("platform must be one of ${Platform.entries.joinToString()}")
        val publicKey = Parse.base64("publicKey", request.publicKey)
        if (publicKey.size !in 32..512) throw ValidationException("publicKey must be 32..512 bytes")
        return devices.register(caller, request.deviceId, request.fcmToken, publicKey, platform).toResponse()
    }

    suspend fun unregister(caller: UserId, deviceId: String) = devices.unregister(caller, deviceId)
}
