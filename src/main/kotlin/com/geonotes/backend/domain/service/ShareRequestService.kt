package com.geonotes.backend.domain.service

import com.geonotes.backend.domain.ConflictException
import com.geonotes.backend.domain.ForbiddenException
import com.geonotes.backend.domain.LimitExceededException
import com.geonotes.backend.domain.NotFoundException
import com.geonotes.backend.domain.ValidationException
import com.geonotes.backend.domain.model.Share
import com.geonotes.backend.domain.model.ShareRecipient
import com.geonotes.backend.domain.model.ShareRequest
import com.geonotes.backend.domain.model.ShareRequestStatus
import com.geonotes.backend.domain.model.ShareRequestView
import com.geonotes.backend.domain.model.Transition
import com.geonotes.backend.domain.model.UserId
import com.geonotes.backend.domain.repository.DeviceRepository
import com.geonotes.backend.domain.repository.ShareRequestRepository
import com.geonotes.backend.domain.repository.TransactionRunner
import com.geonotes.backend.push.PushMessage
import com.geonotes.backend.push.PushSender
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Share requests: the *watcher* asks a friend to share arrivals at a place. The friend (target) is the
 * future share owner — their device evaluates the geofence — so a share only starts after their explicit
 * consent ([accept]). The server only ever sees opaque ciphertext and sealed keys.
 */
class ShareRequestService(
    private val requests: ShareRequestRepository,
    private val devices: DeviceRepository,
    private val friendService: FriendService,
    private val shareService: ShareService,
    private val userService: UserService,
    private val pushSender: PushSender,
    private val tx: TransactionRunner,
    private val clock: Clock,
    private val ttl: Duration = Duration.ofDays(7),
    private val maxPendingOutgoing: Int = 50,
    private val maxEncryptedPlaceBytes: Int = 8 * 1024,
    private val maxKeysPerSide: Int = 50,
    private val maxNoteLength: Int = 140,
) {
    data class NewShareRequest(
        val targetId: UserId,
        val encryptedPlace: ByteArray,
        /** Keys for the target's devices (role OWNER once accepted). */
        val ownerKeys: List<ShareRecipient>,
        /** Keys for the requester's own devices (role RECIPIENT once accepted). */
        val recipientKeys: List<ShareRecipient>,
        val transitions: Set<Transition>,
        val note: String?,
    )

    data class Accepted(val request: ShareRequestView, val share: Share)

    suspend fun create(requesterId: UserId, cmd: NewShareRequest): ShareRequestView {
        val created = tx.inTransaction {
            userService.requireRegistered(requesterId)
            validate(requesterId, cmd)
            val now = clock.instant()
            if (requests.countPendingOutgoing(requesterId, now) >= maxPendingOutgoing) {
                throw LimitExceededException("At most $maxPendingOutgoing pending share requests", "share_request_limit_reached")
            }
            requests.create(
                ShareRequest(
                    id = UUID.randomUUID(),
                    requesterId = requesterId,
                    targetId = cmd.targetId,
                    encryptedPlace = cmd.encryptedPlace,
                    transitions = cmd.transitions,
                    note = cmd.note?.trim()?.ifEmpty { null },
                    status = ShareRequestStatus.PENDING,
                    shareId = null,
                    createdAt = now,
                    updatedAt = now,
                    expiresAt = now.plus(ttl),
                    ownerKeys = cmd.ownerKeys,
                    recipientKeys = cmd.recipientKeys,
                ),
            )
        }
        push(created.targetId, mapOf("type" to "share_request", "requestId" to created.id.toString(), "fromUserId" to requesterId))
        return view(created)
    }

    suspend fun list(userId: UserId): List<ShareRequestView> = requests.listForUser(userId, clock.instant())

    /**
     * The target consents: creates an active share owned by the caller whose recipients are the requester's
     * devices (sealed keys are reused). Counts towards the owner's active-share limit.
     */
    suspend fun accept(callerId: UserId, requestId: UUID): Accepted {
        val accepted = tx.inTransaction {
            val request = requireTarget(callerId, requestId)
            requirePending(request)
            if (!friendService.areFriends(callerId, request.requesterId)) {
                throw ForbiddenException("Not an accepted friend", "not_a_friend")
            }
            // Device rows may have been deleted since the request was made (keys cascade away).
            if (request.recipientKeys.isEmpty()) {
                throw ConflictException("The requester has no registered devices left", "recipient_device_invalid")
            }
            val share = shareService.create(
                callerId,
                ShareService.NewShare(
                    encryptedPlace = request.encryptedPlace,
                    recipients = request.recipientKeys,
                    transitions = request.transitions,
                    ownerKeys = request.ownerKeys,
                ),
            )
            val now = clock.instant()
            if (!requests.resolve(request.id, ShareRequestStatus.ACCEPTED, share.id, now)) {
                throw ConflictException("Share request is no longer pending", "share_request_not_pending")
            }
            Accepted(view(request.copy(status = ShareRequestStatus.ACCEPTED, shareId = share.id, updatedAt = now)), share)
        }
        push(
            accepted.request.request.requesterId,
            mapOf(
                "type" to "share_request_accepted",
                "requestId" to accepted.request.request.id.toString(),
                "shareId" to accepted.share.id.toString(),
                "fromUserId" to callerId,
            ),
        )
        return accepted
    }

    /** The target declines. No push is sent: declining quietly is the kinder default. */
    suspend fun decline(callerId: UserId, requestId: UUID): ShareRequestView = tx.inTransaction {
        val request = requireTarget(callerId, requestId)
        requirePending(request)
        val now = clock.instant()
        if (!requests.resolve(request.id, ShareRequestStatus.DECLINED, null, now)) {
            throw ConflictException("Share request is no longer pending", "share_request_not_pending")
        }
        view(request.copy(status = ShareRequestStatus.DECLINED, updatedAt = now))
    }

    /** The requester withdraws the request. Does not affect a share that was already created from it. */
    suspend fun cancel(callerId: UserId, requestId: UUID) {
        val request = requests.find(requestId) ?: throw NotFoundException("Share request not found", "share_request_not_found")
        if (request.requesterId != callerId) {
            if (request.targetId == callerId) throw ForbiddenException("Only the requester can cancel", "not_share_requester")
            throw NotFoundException("Share request not found", "share_request_not_found")
        }
        requests.delete(requestId)
    }

    /** Hourly housekeeping (7-day TTL). */
    suspend fun cleanup(): Int = requests.deleteExpired(clock.instant())

    private suspend fun requireTarget(callerId: UserId, requestId: UUID): ShareRequest {
        val request = requests.find(requestId) ?: throw NotFoundException("Share request not found", "share_request_not_found")
        // Do not reveal other users' requests to third parties.
        if (request.targetId != callerId) {
            if (request.requesterId == callerId) throw ForbiddenException("Only the requested friend can answer", "not_share_request_target")
            throw NotFoundException("Share request not found", "share_request_not_found")
        }
        return request
    }

    private suspend fun view(request: ShareRequest) = ShareRequestView(
        request = request,
        requesterDisplayName = userService.requireRegistered(request.requesterId).displayName,
        targetDisplayName = userService.requireRegistered(request.targetId).displayName,
    )

    private fun requirePending(request: ShareRequest) {
        if (request.isExpiredAt(clock.instant())) throw ConflictException("Share request expired", "share_request_expired")
        if (request.status != ShareRequestStatus.PENDING) {
            throw ConflictException("Share request is no longer pending", "share_request_not_pending")
        }
    }

    private suspend fun validate(requesterId: UserId, cmd: NewShareRequest) {
        if (cmd.targetId == requesterId) throw ValidationException("You cannot request a share from yourself", "share_request_self")
        if (cmd.encryptedPlace.isEmpty() || cmd.encryptedPlace.size > maxEncryptedPlaceBytes) {
            throw ValidationException("encryptedPlace must be 1..$maxEncryptedPlaceBytes bytes")
        }
        if (cmd.transitions.isEmpty()) throw ValidationException("transitions must not be empty")
        if (cmd.note != null && cmd.note.length > maxNoteLength) throw ValidationException("note must be at most $maxNoteLength characters")
        if (!friendService.areFriends(requesterId, cmd.targetId)) {
            throw ForbiddenException("Not an accepted friend", "not_a_friend")
        }
        validateKeys("ownerKeys", cmd.ownerKeys, cmd.targetId)
        validateKeys("recipientKeys", cmd.recipientKeys, requesterId)
    }

    private suspend fun validateKeys(field: String, keys: List<ShareRecipient>, expectedUser: UserId) {
        if (keys.isEmpty()) throw ValidationException("$field must not be empty")
        if (keys.size > maxKeysPerSide) throw ValidationException("At most $maxKeysPerSide $field")
        if (keys.map { it.deviceId }.toSet().size != keys.size) throw ValidationException("Duplicate deviceId in $field")
        if (keys.any { it.sealedKey.isEmpty() || it.sealedKey.size > 1024 }) throw ValidationException("sealedKey must be 1..1024 bytes")
        val known = devices.findByIds(keys.map { it.deviceId }).associateBy { it.id }
        for (k in keys) {
            if (known[k.deviceId]?.userId != expectedUser) {
                throw ValidationException("Device ${k.deviceId} in $field does not belong to $expectedUser", "share_request_device_invalid")
            }
        }
    }

    private suspend fun push(userId: UserId, data: Map<String, String>) {
        val tokens = devices.listByUser(userId).mapNotNull { it.fcmToken }
        if (tokens.isEmpty()) return
        val result = pushSender.send(tokens.map { PushMessage(it, data) })
        if (result.unregisteredTokens.isNotEmpty()) devices.clearFcmTokens(result.unregisteredTokens)
    }
}
