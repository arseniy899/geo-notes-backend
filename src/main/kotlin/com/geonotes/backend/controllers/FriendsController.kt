package com.geonotes.backend.controllers

import com.geonotes.backend.api.model.DeviceKeysResponse
import com.geonotes.backend.api.model.FriendResponse
import com.geonotes.backend.api.model.FriendsResponse
import com.geonotes.backend.api.model.InviteResponse
import com.geonotes.backend.domain.model.UserId
import com.geonotes.backend.domain.service.FriendService

class FriendsController(private val friends: FriendService) {
    suspend fun createInvite(caller: UserId): InviteResponse =
        friends.createInvite(caller).let { InviteResponse(code = it.code, expiresAt = it.expiresAt.toString()) }

    suspend fun acceptInvite(caller: UserId, code: String): FriendResponse = friends.acceptInvite(caller, code).toResponse()

    suspend fun list(caller: UserId): FriendsResponse = FriendsResponse(friends.listFriends(caller).map { it.toResponse() })

    suspend fun unfriend(caller: UserId, friendId: UserId) {
        friends.unfriend(caller, friendId)
    }

    suspend fun friendDevices(caller: UserId, friendId: UserId): DeviceKeysResponse =
        DeviceKeysResponse(userId = friendId, devices = friends.friendDevices(caller, friendId).map { it.toResponse() })
}
