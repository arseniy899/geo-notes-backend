package com.geonotes.backend.domain.repository

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
import java.time.Instant
import java.util.UUID

/**
 * Repository interfaces owned by the domain layer. Implementations live in `persistence/`
 * (Exposed/PostgreSQL) and in test sources (in-memory fakes).
 */

/** Runs [block] atomically. Repository calls made inside share one DB transaction. */
interface TransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

interface UserRepository {
    suspend fun find(id: UserId): User?
    suspend fun upsert(id: UserId, displayName: String, now: Instant): User
    /** Deletes the user; all dependent rows are removed via ON DELETE CASCADE. */
    suspend fun delete(id: UserId): Boolean
}

interface DeviceRepository {
    suspend fun upsert(device: Device): Device
    suspend fun find(id: String): Device?
    suspend fun findByIds(ids: Collection<String>): List<Device>
    suspend fun listByUser(userId: UserId): List<Device>
    suspend fun delete(userId: UserId, deviceId: String): Boolean
    /** Forget FCM tokens reported as unregistered by FCM (keeps the device + its sealed keys). */
    suspend fun clearFcmTokens(tokens: Collection<String>)
}

interface InviteRepository {
    suspend fun create(invite: Invite): Invite
    suspend fun find(code: String): Invite?
    /** Marks the invite accepted if it is still unused. Returns false if it was already used. */
    suspend fun markAccepted(code: String, acceptedBy: UserId, at: Instant): Boolean
    suspend fun deleteExpired(now: Instant): Int
}

interface FriendshipRepository {
    /** Idempotent: returns false if the friendship already existed. */
    suspend fun add(pair: FriendPair, now: Instant): Boolean
    suspend fun exists(pair: FriendPair): Boolean
    suspend fun delete(pair: FriendPair): Boolean
    suspend fun listFriends(userId: UserId): List<Friend>
}

interface ShareRepository {
    suspend fun create(share: Share): Share
    suspend fun find(id: UUID): Share?
    suspend fun listOwned(ownerId: UserId): List<Share>
    /** Shares where [userId] is a recipient; recipients are filtered to that user's devices. */
    suspend fun listReceived(userId: UserId): List<ReceivedShare>
    suspend fun countActive(ownerId: UserId): Int
    suspend fun update(id: UUID, active: Boolean, pausedUntil: Instant?, now: Instant): Share?
    suspend fun delete(id: UUID): Boolean
    /** Removes recipient rows linking the two users in either direction (used on unfriend). */
    suspend fun removeRecipientsBetween(userA: UserId, userB: UserId): Int
}

interface EventRepository {
    suspend fun insert(event: FriendEvent): FriendEvent
    suspend fun deleteExpired(now: Instant): Int
    suspend fun countForShare(shareId: UUID): Int
}

/** One entitlement row per user. Purchase tokens are looked up by their SHA-256 hash. */
interface EntitlementRepository {
    suspend fun find(userId: UserId): Entitlement?
    /** [tokenHash] = [com.geonotes.backend.domain.model.PurchaseTokens.hash] of the raw token. */
    suspend fun findByTokenHash(tokenHash: String): Entitlement?
    /** Inserts or replaces the user's entitlement. */
    suspend fun upsert(entitlement: Entitlement): Entitlement
}
