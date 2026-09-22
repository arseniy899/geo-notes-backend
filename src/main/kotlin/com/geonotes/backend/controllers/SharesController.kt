package com.geonotes.backend.controllers

import com.geonotes.backend.api.model.CreateShareRequest
import com.geonotes.backend.api.model.ShareResponse
import com.geonotes.backend.api.model.SharesResponse
import com.geonotes.backend.api.model.UpdateShareRequest
import com.geonotes.backend.domain.model.ShareRecipient
import com.geonotes.backend.domain.model.UserId
import com.geonotes.backend.domain.service.ShareService

class SharesController(private val shares: ShareService) {
    suspend fun create(caller: UserId, request: CreateShareRequest): ShareResponse {
        val newShare = ShareService.NewShare(
            encryptedPlace = Parse.base64("encryptedPlace", request.encryptedPlace),
            recipients = request.recipients.mapIndexed { i, r ->
                ShareRecipient(userId = r.userId, deviceId = r.deviceId, sealedKey = Parse.base64("recipients[$i].sealedKey", r.sealedKey))
            },
            transitions = request.transitions.mapIndexed { i, t -> Parse.transition("transitions[$i]", t) }.toSet(),
        )
        return shares.create(caller, newShare).toResponse()
    }

    suspend fun list(caller: UserId): SharesResponse {
        val result = shares.list(caller)
        return SharesResponse(
            owned = result.owned.map { it.toResponse() },
            received = result.received.map { it.share.toResponse(ownerDisplayName = it.ownerDisplayName) },
        )
    }

    suspend fun update(caller: UserId, shareId: String, request: UpdateShareRequest): ShareResponse {
        val id = Parse.uuid("shareId", shareId)
        val pausedUntil = request.pausedUntil?.let { Parse.instant("pausedUntil", it) }
        return shares.update(caller, id, request.active, pausedUntil, request.pausedUntilSet).toResponse()
    }

    suspend fun delete(caller: UserId, shareId: String) = shares.delete(caller, Parse.uuid("shareId", shareId))
}
