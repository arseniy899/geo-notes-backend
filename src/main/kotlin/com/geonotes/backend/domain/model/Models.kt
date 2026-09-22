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
) {
    fun isPausedAt(now: Instant): Boolean = pausedUntil != null && pausedUntil.isAfter(now)

    override fun equals(other: Any?): Boolean =
        other is Share && id == other.id && ownerId == other.ownerId &&
            encryptedPlace.contentEquals(other.encryptedPlace) && transitions == other.transitions &&
            active == other.active && pausedUntil == other.pausedUntil && recipients == other.recipients

    override fun hashCode(): Int = id.hashCode()
}

/** A share as seen by one of its recipients, with the owner's display name attached. */
data class ReceivedShare(
    val share: Share,
    val ownerDisplayName: String,
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
