package com.geonotes.backend.domain.service

import com.geonotes.backend.domain.NotFoundException
import com.geonotes.backend.domain.model.Device
import com.geonotes.backend.domain.model.Platform
import com.geonotes.backend.domain.model.UserId
import com.geonotes.backend.domain.repository.DeviceRepository
import java.time.Clock

class DeviceService(
    private val devices: DeviceRepository,
    private val userService: UserService,
    private val clock: Clock,
) {
    /**
     * Registers (or re-registers) a device. If the same deviceId was previously bound to another
     * account (sign-out/sign-in on the same phone), ownership moves to the caller.
     */
    suspend fun register(userId: UserId, deviceId: String, fcmToken: String, publicKey: ByteArray, platform: Platform): Device {
        userService.requireRegistered(userId)
        val now = clock.instant()
        val existing = devices.find(deviceId)
        return devices.upsert(
            Device(
                id = deviceId,
                userId = userId,
                fcmToken = fcmToken,
                publicKey = publicKey,
                platform = platform,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    suspend fun unregister(userId: UserId, deviceId: String) {
        if (!devices.delete(userId, deviceId)) throw NotFoundException("Device not found", "device_not_found")
    }
}
