package com.geonotes.backend.domain.model

import java.time.Instant
import java.util.UUID

/** Firebase UID (or `dev:<uid>` subject in dev mode). */
typealias UserId = String

data class User(
    val id: UserId,
    val displayName: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class Platform { ANDROID, IOS }

data class Device(
    val id: String,
    val userId: UserId,
    val fcmToken: String?,
    /** Device public key (X25519 / P-256, raw bytes). Opaque to the server. */
    val publicKey: ByteArray,
    val platform: Platform,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun equals(other: Any?): Boolean =
        other is Device && id == other.id && userId == other.userId && fcmToken == other.fcmToken &&
            publicKey.contentEquals(other.publicKey) && platform == other.platform &&
            createdAt == other.createdAt && updatedAt == other.updatedAt

    override fun hashCode(): Int = id.hashCode()
}

data class Invite(
    val code: String,
    val inviterId: UserId,
    val createdAt: Instant,
    val expiresAt: Instant,
    val acceptedBy: UserId? = null,
    val acceptedAt: Instant? = null,
)

/**
 * A friendship is stored once, with canonical ordering `userA < userB`.
 * Use [FriendPair.of] to build one from two arbitrary user ids.
 */
data class FriendPair private constructor(val userA: UserId, val userB: UserId) {
    fun other(me: UserId): UserId = if (me == userA) userB else userA

    companion object {
        fun of(x: UserId, y: UserId): FriendPair {
            require(x != y) { "A user cannot befriend themselves" }
            return if (x < y) FriendPair(x, y) else FriendPair(y, x)
        }
    }
}

data class Friend(
    val userId: UserId,
    val displayName: String,
    val since: Instant,
)

enum class Transition { ENTER, EXIT }

data class ShareRecipient(
    val userId: UserId,
    val deviceId: String,
    /** Per-device sealed content key; opaque ciphertext. */
    val sealedKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is ShareRecipient && userId == other.userId && deviceId == other.deviceId &&
            sealedKey.contentEquals(other.sealedKey)

    override fun hashCode(): Int = deviceId.hashCode()
}

data class Share(
    val id: UUID,
    val ownerId: UserId,
    /** E2E-encrypted place definition. The server never sees coordinates. */
    val encryptedPlace: ByteArray,
    val transitions: Set<Transition>,
    val active: Boolean,
    val pausedUntil: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val recipients: List<ShareRecipient>,
    /**
     * Content key sealed to the owner's own devices (userId = owner). Present for shares created by
     * accepting a [ShareRequest]: the requester encrypted the place, so the owner needs a key to decrypt
     * and geofence it. Empty for shares the owner encrypted themselves. Only exposed to the owner.
     */
    val ownerKeys: List<ShareRecipient> = emptyList(),
) {
    fun isPausedAt(now: Instant): Boolean = pausedUntil != null && pausedUntil.isAfter(now)

    override fun equals(other: Any?): Boolean =
        other is Share && id == other.id && ownerId == other.ownerId &&
            encryptedPlace.contentEquals(other.encryptedPlace) && transitions == other.transitions &&
            active == other.active && pausedUntil == other.pausedUntil && recipients == other.recipients &&
            ownerKeys == other.ownerKeys

    override fun hashCode(): Int = id.hashCode()
}

/** A share as seen by one of its recipients, with the owner's display name attached. */
data class ReceivedShare(
    val share: Share,
    val ownerDisplayName: String,
)

enum class ShareRequestStatus { PENDING, ACCEPTED, DECLINED }

/**
 * A watcher ([requesterId]) asks a friend ([targetId]) to share their arrivals at a place. The place is
 * encrypted by the requester; [ownerKeys] seal the content key to the target's devices (so they can decrypt
 * and geofence it after accepting) and [recipientKeys] to the requester's own devices. Accepting turns it
 * into an active [Share] owned by the target.
 */
data class ShareRequest(
    val id: UUID,
    val requesterId: UserId,
    val targetId: UserId,
    val encryptedPlace: ByteArray,
    val transitions: Set<Transition>,
    val note: String?,
    val status: ShareRequestStatus,
    val shareId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val expiresAt: Instant,
    val ownerKeys: List<ShareRecipient>,
    val recipientKeys: List<ShareRecipient>,
) {
    fun isExpiredAt(now: Instant): Boolean = !expiresAt.isAfter(now)

    override fun equals(other: Any?): Boolean =
        other is ShareRequest && id == other.id && requesterId == other.requesterId && targetId == other.targetId &&
            encryptedPlace.contentEquals(other.encryptedPlace) && transitions == other.transitions && note == other.note &&
            status == other.status && shareId == other.shareId && ownerKeys == other.ownerKeys &&
            recipientKeys == other.recipientKeys

    override fun hashCode(): Int = id.hashCode()
}

/** A share request with both parties' display names, for listing. */
data class ShareRequestView(
    val request: ShareRequest,
    val requesterDisplayName: String,
    val targetDisplayName: String,
)

data class FriendEvent(
    val id: UUID,
    val shareId: UUID,
    val ownerId: UserId,
    val transition: Transition,
    val occurredAt: Instant,
    val receivedAt: Instant,
    val expiresAt: Instant,
)

data class Entitlement(
    val userId: UserId,
    val productId: String,
    val purchaseToken: String,
    val pro: Boolean,
    val expiresAt: Instant?,
    val verifiedAt: Instant,
) {
    fun isProAt(now: Instant): Boolean = pro && (expiresAt == null || expiresAt.isAfter(now))
}
