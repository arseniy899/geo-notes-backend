package com.geonotes.backend.controllers

import com.geonotes.backend.api.model.MeResponse
import com.geonotes.backend.api.model.UpsertMeRequest
import com.geonotes.backend.domain.model.UserId
import com.geonotes.backend.domain.service.UserService
import java.time.Clock

class MeController(private val users: UserService, private val clock: Clock) {
    suspend fun upsert(caller: UserId, request: UpsertMeRequest): MeResponse = users.upsert(caller, request.displayName).toResponse()

    suspend fun get(caller: UserId): MeResponse = users.get(caller).toResponse()

    suspend fun delete(caller: UserId) = users.delete(caller)

    private fun UserService.Profile.toResponse() = MeResponse(
        userId = user.id,
        displayName = user.displayName,
        createdAt = user.createdAt.toString(),
        entitlement = entitlement.toResponse(clock.instant()),
    )
}
