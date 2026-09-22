package com.geonotes.backend.domain.service

import com.geonotes.backend.domain.ForbiddenException
import com.geonotes.backend.domain.LimitExceededException
import com.geonotes.backend.domain.NotFoundException
import com.geonotes.backend.domain.ValidationException
import com.geonotes.backend.domain.model.ReceivedShare
import com.geonotes.backend.domain.model.Share
import com.geonotes.backend.domain.model.ShareRecipient
import com.geonotes.backend.domain.model.Transition
import com.geonotes.backend.domain.model.UserId
import com.geonotes.backend.domain.repository.DeviceRepository
import com.geonotes.backend.domain.repository.ShareRepository
import com.geonotes.backend.domain.repository.TransactionRunner
import java.time.Clock
import java.time.Instant
import java.util.UUID

class ShareService(
    private val shares: ShareRepository,
    private val devices: DeviceRepository,
    private val friendService: FriendService,
    private val userService: UserService,
    private val tx: TransactionRunner,
    private val clock: Clock,
    /** Android allows ~100 geofences per app; we reserve 20 for friend shares. */
    private val maxActiveSharesPerOwner: Int = 20,
    private val maxEncryptedPlaceBytes: Int = 8 * 1024,
    private val maxRecipients: Int = 50,
) {
    data class NewShare(
        val encryptedPlace: ByteArray,
        val recipients: List<ShareRecipient>,
        val transitions: Set<Transition>,
        /** Content key sealed to the owner's own devices (see [Share.ownerKeys]). */
        val ownerKeys: List<ShareRecipient> = emptyList(),
    )

    data class Shares(val owned: List<Share>, val received: List<ReceivedShare>)

    suspend fun create(ownerId: UserId, request: NewShare): Share = tx.inTransaction {
        userService.requireRegistered(ownerId)
        validate(ownerId, request)
        if (shares.countActive(ownerId) >= maxActiveSharesPerOwner) {
            throw LimitExceededException("At most $maxActiveSharesPerOwner active shares per user", "share_limit_reached")
        }
        val now = clock.instant()
        shares.create(
            Share(
                id = UUID.randomUUID(),
                ownerId = ownerId,
                encryptedPlace = request.encryptedPlace,
                transitions = request.transitions,
                active = true,
                pausedUntil = null,
                createdAt = now,
                updatedAt = now,
                recipients = request.recipients,
                ownerKeys = request.ownerKeys,
            ),
        )
    }

    suspend fun list(userId: UserId): Shares = Shares(shares.listOwned(userId), shares.listReceived(userId))

    suspend fun update(userId: UserId, shareId: UUID, active: Boolean?, pausedUntil: Instant?, pausedUntilSet: Boolean): Share =
        tx.inTransaction {
            val share = requireOwned(userId, shareId)
            val newActive = active ?: share.active
            if (newActive && !share.active && shares.countActive(userId) >= maxActiveSharesPerOwner) {
                throw LimitExceededException("At most $maxActiveSharesPerOwner active shares per user", "share_limit_reached")
            }
            val newPausedUntil = if (pausedUntilSet) pausedUntil else share.pausedUntil
            shares.update(shareId, newActive, newPausedUntil, clock.instant())
                ?: throw NotFoundException("Share not found", "share_not_found")
        }

    suspend fun delete(userId: UserId, shareId: UUID) {
        requireOwned(userId, shareId)
        shares.delete(shareId)
    }

    suspend fun requireOwned(userId: UserId, shareId: UUID): Share {
        val share = shares.find(shareId) ?: throw NotFoundException("Share not found", "share_not_found")
        // Do not reveal existence of other users' shares to non-recipients.
        if (share.ownerId != userId) {
            if (share.recipients.any { it.userId == userId }) throw ForbiddenException("Only the owner can modify this share", "not_share_owner")
            throw NotFoundException("Share not found", "share_not_found")
        }
        return share
    }

    private suspend fun validate(ownerId: UserId, request: NewShare) {
        if (request.encryptedPlace.isEmpty() || request.encryptedPlace.size > maxEncryptedPlaceBytes) {
            throw ValidationException("encryptedPlace must be 1..$maxEncryptedPlaceBytes bytes")
        }
        if (request.transitions.isEmpty()) throw ValidationException("transitions must not be empty")
        if (request.recipients.isEmpty()) throw ValidationException("recipients must not be empty")
        if (request.recipients.size > maxRecipients) throw ValidationException("At most $maxRecipients recipient devices")
        if (request.recipients.map { it.deviceId }.toSet().size != request.recipients.size) {
            throw ValidationException("Duplicate recipient deviceId")
        }
        if (request.recipients.any { it.sealedKey.isEmpty() || it.sealedKey.size > 1024 }) {
            throw ValidationException("sealedKey must be 1..1024 bytes")
        }
        for (recipientId in request.recipients.map { it.userId }.toSet()) {
            if (recipientId == ownerId) throw ValidationException("Owner cannot be a recipient", "recipient_is_owner")
            if (!friendService.areFriends(ownerId, recipientId)) {
                throw ForbiddenException("Recipient $recipientId is not an accepted friend", "recipient_not_friend")
            }
        }
        val knownDevices = devices.findByIds(request.recipients.map { it.deviceId }).associateBy { it.id }
        for (r in request.recipients) {
            val device = knownDevices[r.deviceId]
            if (device == null || device.userId != r.userId) {
                throw ValidationException("Device ${r.deviceId} does not belong to ${r.userId}", "recipient_device_invalid")
            }
        }
        validateOwnerKeys(ownerId, request.ownerKeys)
    }

    private suspend fun validateOwnerKeys(ownerId: UserId, ownerKeys: List<ShareRecipient>) {
        if (ownerKeys.isEmpty()) return
        if (ownerKeys.size > maxRecipients) throw ValidationException("At most $maxRecipients owner devices")
        if (ownerKeys.map { it.deviceId }.toSet().size != ownerKeys.size) throw ValidationException("Duplicate owner deviceId")
        if (ownerKeys.any { it.sealedKey.isEmpty() || it.sealedKey.size > 1024 }) {
            throw ValidationException("sealedKey must be 1..1024 bytes")
        }
        val known = devices.findByIds(ownerKeys.map { it.deviceId }).associateBy { it.id }
        for (k in ownerKeys) {
            if (k.userId != ownerId || known[k.deviceId]?.userId != ownerId) {
                throw ValidationException("Device ${k.deviceId} does not belong to the owner", "owner_device_invalid")
            }
        }
    }
}
