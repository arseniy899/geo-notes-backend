package com.geonotes.backend.support

import com.geonotes.backend.domain.ConflictException
import com.geonotes.backend.domain.model.Device
import com.geonotes.backend.domain.model.Entitlement
import com.geonotes.backend.domain.model.Friend
import com.geonotes.backend.domain.model.FriendEvent
import com.geonotes.backend.domain.model.FriendPair
import com.geonotes.backend.domain.model.Invite
import com.geonotes.backend.domain.model.ReceivedShare
import com.geonotes.backend.domain.model.Share
import com.geonotes.backend.domain.model.User
import com.geonotes.backend.domain.model.UserId
import com.geonotes.backend.domain.repository.DeviceRepository
import com.geonotes.backend.domain.repository.EntitlementRepository
import com.geonotes.backend.domain.repository.EventRepository
import com.geonotes.backend.domain.repository.FriendshipRepository
import com.geonotes.backend.domain.repository.InviteRepository
import com.geonotes.backend.domain.repository.ShareRepository
import com.geonotes.backend.domain.repository.TransactionRunner
import com.geonotes.backend.domain.repository.UserRepository
import java.time.Instant
import java.util.UUID

/** In-memory fakes for controller/service unit tests (no DB, no Ktor). */
object DirectTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
}

class InMemoryUserRepository : UserRepository {
    val users = linkedMapOf<UserId, User>()
    override suspend fun find(id: UserId) = users[id]
    override suspend fun upsert(id: UserId, displayName: String, now: Instant): User {
        val u = users[id]?.copy(displayName = displayName, updatedAt = now) ?: User(id, displayName, now, now)
        users[id] = u
        return u
    }
    override suspend fun delete(id: UserId) = users.remove(id) != null
}

class InMemoryDeviceRepository : DeviceRepository {
    val devices = linkedMapOf<String, Device>()
    override suspend fun upsert(device: Device): Device = device.also { devices[it.id] = it }
    override suspend fun find(id: String) = devices[id]
    override suspend fun findByIds(ids: Collection<String>) = ids.mapNotNull { devices[it] }
    override suspend fun listByUser(userId: UserId) = devices.values.filter { it.userId == userId }
    override suspend fun delete(userId: UserId, deviceId: String) =
        devices[deviceId]?.takeIf { it.userId == userId }?.let { devices.remove(deviceId) } != null
    override suspend fun clearFcmTokens(tokens: Collection<String>) {
        devices.replaceAll { _, d -> if (d.fcmToken in tokens) d.copy(fcmToken = null) else d }
    }
}

class InMemoryInviteRepository : InviteRepository {
    val invites = linkedMapOf<String, Invite>()
    override suspend fun create(invite: Invite) = invite.also { invites[it.code] = it }
    override suspend fun find(code: String) = invites[code]
    override suspend fun markAccepted(code: String, acceptedBy: UserId, at: Instant): Boolean {
        val i = invites[code] ?: return false
        if (i.acceptedBy != null) return false
        invites[code] = i.copy(acceptedBy = acceptedBy, acceptedAt = at)
        return true
    }
    override suspend fun deleteExpired(now: Instant): Int {
        val expired = invites.values.filter { !it.expiresAt.isAfter(now) }.map { it.code }
        expired.forEach { invites.remove(it) }
        return expired.size
    }
}

class InMemoryFriendshipRepository(private val users: InMemoryUserRepository) : FriendshipRepository {
    val pairs = linkedMapOf<FriendPair, Instant>()
    override suspend fun add(pair: FriendPair, now: Instant) = pairs.putIfAbsent(pair, now) == null
    override suspend fun exists(pair: FriendPair) = pair in pairs
    override suspend fun delete(pair: FriendPair) = pairs.remove(pair) != null
    override suspend fun listFriends(userId: UserId) = pairs.filterKeys { it.userA == userId || it.userB == userId }
        .map { (p, since) -> val other = p.other(userId); Friend(other, users.users[other]?.displayName ?: "?", since) }
}

class InMemoryShareRepository(private val users: InMemoryUserRepository) : ShareRepository {
    val shares = linkedMapOf<UUID, Share>()
    override suspend fun create(share: Share) = share.also { shares[it.id] = it }
    override suspend fun find(id: UUID) = shares[id]
    override suspend fun listOwned(ownerId: UserId) = shares.values.filter { it.ownerId == ownerId }
    override suspend fun listReceived(userId: UserId) = shares.values
        .filter { s -> s.recipients.any { it.userId == userId } }
        .map { s -> ReceivedShare(s.copy(recipients = s.recipients.filter { it.userId == userId }), users.users[s.ownerId]?.displayName ?: "?") }
    override suspend fun countActive(ownerId: UserId) = shares.values.count { it.ownerId == ownerId && it.active }
    override suspend fun update(id: UUID, active: Boolean, pausedUntil: Instant?, now: Instant): Share? {
        val s = shares[id] ?: return null
        return s.copy(active = active, pausedUntil = pausedUntil, updatedAt = now).also { shares[id] = it }
    }
    override suspend fun delete(id: UUID) = shares.remove(id) != null
    override suspend fun removeRecipientsBetween(userA: UserId, userB: UserId): Int {
        var removed = 0
        shares.replaceAll { _, s ->
            val other = when (s.ownerId) { userA -> userB; userB -> userA; else -> return@replaceAll s }
            val kept = s.recipients.filter { it.userId != other }
            removed += s.recipients.size - kept.size
            s.copy(recipients = kept)
        }
        return removed
    }
}

class InMemoryEventRepository : EventRepository {
    val events = mutableListOf<FriendEvent>()
    override suspend fun insert(event: FriendEvent) = event.also { events += it }
    override suspend fun deleteExpired(now: Instant): Int {
        val before = events.size
        events.removeAll { !it.expiresAt.isAfter(now) }
        return before - events.size
    }
    override suspend fun countForShare(shareId: UUID) = events.count { it.shareId == shareId }
}

class InMemoryEntitlementRepository : EntitlementRepository {
    val entitlements = linkedMapOf<UserId, Entitlement>()
    override suspend fun find(userId: UserId) = entitlements[userId]
    override suspend fun findByTokenHash(tokenHash: String) = entitlements.values.firstOrNull { it.tokenHash == tokenHash }
    override suspend fun upsert(entitlement: Entitlement): Entitlement {
        // Mirrors UNIQUE(token_hash).
        if (entitlements.values.any { it.tokenHash == entitlement.tokenHash && it.userId != entitlement.userId }) {
            throw ConflictException("Purchase token already bound to another account", "purchase_token_in_use")
        }
        return entitlement.also { entitlements[it.userId] = it }
    }
}
