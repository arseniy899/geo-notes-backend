package com.geonotes.backend.api.model

import kotlinx.serialization.Serializable

@Serializable
data class InviteResponse(val code: String, val expiresAt: String)

@Serializable
data class FriendResponse(val userId: String, val displayName: String, val since: String)

@Serializable
data class FriendsResponse(val friends: List<FriendResponse>)
