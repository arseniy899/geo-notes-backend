package com.geonotes.backend.controllers

import com.geonotes.backend.api.model.AcceptShareRequestResponse
import com.geonotes.backend.api.model.CreateShareRequestRequest
import com.geonotes.backend.api.model.DeviceSealedKey
import com.geonotes.backend.api.model.ShareRequestResponse
import com.geonotes.backend.api.model.ShareRequestsResponse
import com.geonotes.backend.domain.model.ShareRecipient
import com.geonotes.backend.domain.model.ShareRequestView
import com.geonotes.backend.domain.model.UserId
import com.geonotes.backend.domain.service.ShareRequestService

/** ViewModel for `/v1/share-requests`: parses request DTOs, calls [ShareRequestService], maps to responses. */
class ShareRequestsController(private val requests: ShareRequestService) {
    suspend fun create(caller: UserId, request: CreateShareRequestRequest): ShareRequestResponse {
        val cmd = ShareRequestService.NewShareRequest(
            targetId = request.toUserId,
            encryptedPlace = Parse.base64("encryptedPlace", request.encryptedPlace),
            ownerKeys = request.ownerKeys.keys("ownerKeys", request.toUserId),
            recipientKeys = request.recipientKeys.keys("recipientKeys", caller),
            transitions = request.transitions.mapIndexed { i, t -> Parse.transition("transitions[$i]", t) }.toSet(),
            note = request.note,
        )
        return requests.create(caller, cmd).toResponse(caller)
    }

    suspend fun list(caller: UserId): ShareRequestsResponse {
        val all = requests.list(caller).map { it.toResponse(caller) }
        return ShareRequestsResponse(
            incoming = all.filter { it.direction == INCOMING },
            outgoing = all.filter { it.direction == OUTGOING },
        )
    }

    suspend fun accept(caller: UserId, requestId: String): AcceptShareRequestResponse {
        val accepted = requests.accept(caller, Parse.uuid("id", requestId))
        return AcceptShareRequestResponse(
            request = accepted.request.toResponse(caller),
            share = accepted.share.toResponse(),
        )
    }

    suspend fun decline(caller: UserId, requestId: String): ShareRequestResponse =
        requests.decline(caller, Parse.uuid("id", requestId)).toResponse(caller)

    suspend fun cancel(caller: UserId, requestId: String) = requests.cancel(caller, Parse.uuid("id", requestId))

    private fun List<DeviceSealedKey>.keys(field: String, userId: UserId) = mapIndexed { i, k ->
        ShareRecipient(userId = userId, deviceId = k.deviceId, sealedKey = Parse.base64("$field[$i].sealedKey", k.sealedKey))
    }

    private fun ShareRequestView.toResponse(caller: UserId): ShareRequestResponse = with(request) {
        val incoming = targetId == caller
        ShareRequestResponse(
            id = id.toString(),
            direction = if (incoming) INCOMING else OUTGOING,
            fromUserId = requesterId,
            fromDisplayName = requesterDisplayName,
            toUserId = targetId,
            toDisplayName = targetDisplayName,
            encryptedPlace = encryptedPlace.b64(),
            transitions = transitions.map { it.name }.sorted(),
            note = note,
            status = status.name,
            shareId = shareId?.toString(),
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
            expiresAt = expiresAt.toString(),
            // The requester never needs the friend's keys; the friend only gets keys for their own devices.
            ownerKeys = if (incoming) ownerKeys.filter { it.userId == caller }.map { DeviceSealedKey(it.deviceId, it.sealedKey.b64()) } else emptyList(),
        )
    }

    private companion object {
        const val INCOMING = "INCOMING"
        const val OUTGOING = "OUTGOING"
    }
}
