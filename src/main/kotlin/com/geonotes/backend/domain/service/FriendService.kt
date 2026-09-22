package com.geonotes.backend.domain.service

import com.geonotes.backend.domain.ConflictException
import com.geonotes.backend.domain.ForbiddenException
import com.geonotes.backend.domain.NotFoundException
import com.geonotes.backend.domain.ValidationException
import com.geonotes.backend.domain.model.Device
import com.geonotes.backend.domain.model.Friend
import com.geonotes.backend.domain.model.FriendPair
import com.geonotes.backend.domain.model.Invite
import com.geonotes.backend.domain.model.UserId
import com.geonotes.backend.domain.repository.DeviceRepository
import com.geonotes.backend.domain.repository.FriendshipRepository
import com.geonotes.backend.domain.repository.InviteRepository
import com.geonotes.backend.domain.repository.ShareRepository
import com.geonotes.backend.domain.repository.ShareRequestRepository
import com.geonotes.backend.domain.repository.TransactionRunner
import com.geonotes.backend.domain.repository.UserRepository
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration

fun interface InviteCodeGenerator {
    fun next(): String
}

/** 8 chars from an unambiguous alphabet (no 0/O/1/I/L) → ~40 bits; codes are single-use and expire. */
class RandomInviteCodeGenerator(private val random: SecureRandom = SecureRandom()) : InviteCodeGenerator {
    override fun next(): String = buildString(LENGTH) { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    companion object {
        const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
        const val LENGTH = 8
    }
}

class FriendService(
    private val users: UserRepository,
    private val userService: UserService,
    private val invites: InviteRepository,
    private val friendships: FriendshipRepository,
    private val shares: ShareRepository,
    private val shareRequests: ShareRequestRepository,
    private val devices: DeviceRepository,
    private val tx: TransactionRunner,
    private val clock: Clock,
    private val codeGenerator: InviteCodeGenerator = RandomInviteCodeGenerator(),
    private val inviteTtl: Duration = Duration.ofHours(48),
) {
    suspend fun createInvite(inviterId: UserId): Invite {
        userService.requireRegistered(inviterId)
        val now = clock.instant()
        return invites.create(Invite(code = codeGenerator.next(), inviterId = inviterId, createdAt = now, expiresAt = now.plus(inviteTtl)))
    }

    /** Accepting an invite creates a mutual friendship. Invites are single-use. */
    suspend fun acceptInvite(accepterId: UserId, rawCode: String): Friend = tx.inTransaction {
        userService.requireRegistered(accepterId)
        val code = normalizeCode(rawCode)
        val now = clock.instant()
        val invite = invites.find(code) ?: throw NotFoundException("Invite not found", "invite_not_found")
        if (invite.inviterId == accepterId) throw ValidationException("You cannot accept your own invite", "invite_self")
        if (!invite.expiresAt.isAfter(now)) throw ConflictException("Invite expired", "invite_expired")
        if (invite.acceptedBy != null && invite.acceptedBy != accepterId) throw ConflictException("Invite already used", "invite_used")
        if (invite.acceptedBy == null && !invites.markAccepted(code, accepterId, now)) {
            throw ConflictException("Invite already used", "invite_used")
        }
        friendships.add(FriendPair.of(invite.inviterId, accepterId), now)
        val inviter = users.find(invite.inviterId) ?: throw NotFoundException("Inviter no longer exists", "invite_not_found")
        Friend(userId = inviter.id, displayName = inviter.displayName, since = now)
    }

    suspend fun listFriends(userId: UserId): List<Friend> = friendships.listFriends(userId)

    /** Either side can unfriend. Also revokes every share and pending share request between the two users. */
    suspend fun unfriend(userId: UserId, friendId: UserId) = tx.inTransaction {
        if (userId == friendId) throw ValidationException("Cannot unfriend yourself")
        if (!friendships.delete(FriendPair.of(userId, friendId))) throw NotFoundException("Not friends", "friend_not_found")
        shareRequests.deletePendingBetween(userId, friendId)
        shares.removeRecipientsBetween(userId, friendId)
    }

    suspend fun areFriends(a: UserId, b: UserId): Boolean = a != b && friendships.exists(FriendPair.of(a, b))

    /** Public keys of a friend's devices, used by the client to seal per-device share keys. */
    suspend fun friendDevices(userId: UserId, friendId: UserId): List<Device> {
        if (!areFriends(userId, friendId)) throw ForbiddenException("Not an accepted friend", "not_a_friend")
        return devices.listByUser(friendId)
    }

    private fun normalizeCode(raw: String): String {
        val code = raw.trim().uppercase()
        if (code.length != RandomInviteCodeGenerator.LENGTH || code.any { it !in RandomInviteCodeGenerator.ALPHABET }) {
            throw NotFoundException("Invite not found", "invite_not_found")
        }
        return code
    }
}
