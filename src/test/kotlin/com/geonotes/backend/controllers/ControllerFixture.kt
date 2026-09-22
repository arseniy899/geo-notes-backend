package com.geonotes.backend.controllers

import com.geonotes.backend.api.model.RegisterDeviceRequest
import com.geonotes.backend.api.model.UpsertMeRequest
import com.geonotes.backend.domain.service.DeviceService
import com.geonotes.backend.domain.service.EventService
import com.geonotes.backend.domain.service.FriendService
import com.geonotes.backend.domain.service.ShareService
import com.geonotes.backend.domain.service.UserService
import com.geonotes.backend.support.DirectTransactionRunner
import com.geonotes.backend.support.FakePushSender
import com.geonotes.backend.support.InMemoryDeviceRepository
import com.geonotes.backend.support.InMemoryEntitlementRepository
import com.geonotes.backend.support.InMemoryEventRepository
import com.geonotes.backend.support.InMemoryFriendshipRepository
import com.geonotes.backend.support.InMemoryInviteRepository
import com.geonotes.backend.support.InMemoryShareRepository
import com.geonotes.backend.support.InMemoryUserRepository
import com.geonotes.backend.support.MutableClock
import java.util.Base64

/** Wires real services + controllers (the ViewModel layer) over in-memory fakes — no Ktor, no DB. */
class ControllerFixture(maxActiveShares: Int = 20) {
    val clock = MutableClock()
    val push = FakePushSender()
    val users = InMemoryUserRepository()
    val devices = InMemoryDeviceRepository()
    val invites = InMemoryInviteRepository()
    val friendships = InMemoryFriendshipRepository(users)
    val shares = InMemoryShareRepository(users)
    val events = InMemoryEventRepository()
    val entitlements = InMemoryEntitlementRepository()

    private val tx = DirectTransactionRunner
    val userService = UserService(users, entitlements, clock)
    val deviceService = DeviceService(devices, userService, clock)
    val friendService = FriendService(users, userService, invites, friendships, shares, devices, tx, clock)
    val shareService = ShareService(shares, devices, friendService, userService, tx, clock, maxActiveSharesPerOwner = maxActiveShares)
    val eventService = EventService(shareService, events, devices, invites, push, clock)

    val me = MeController(userService, clock)
    val devicesController = DevicesController(deviceService)
    val friendsController = FriendsController(friendService)
    val sharesController = SharesController(shareService)
    val eventsController = EventsController(eventService)

    suspend fun user(id: String, deviceId: String = "$id-device-1") {
        me.upsert(id, UpsertMeRequest(displayName = id.replaceFirstChar { it.uppercase() }))
        devicesController.register(id, RegisterDeviceRequest(deviceId, "fcm-$deviceId", b64(ByteArray(32) { 7 }), "android"))
    }

    suspend fun befriend(a: String, b: String) {
        val invite = friendsController.createInvite(a)
        friendsController.acceptInvite(b, invite.code)
    }

    companion object {
        fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    }
}
