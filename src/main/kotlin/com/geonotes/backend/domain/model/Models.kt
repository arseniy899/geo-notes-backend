package com.geonotes.backend.domain.model

import com.geonotes.backend.domain.model.EntitlementState.ACTIVE
import com.geonotes.backend.domain.model.EntitlementState.CANCELED
import com.geonotes.backend.domain.model.EntitlementState.IN_GRACE_PERIOD
import java.security.MessageDigest
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

/**
 * Lifecycle of a Google Play purchase as the backend sees it. Mirrors Play's `subscriptionState` /
 * `purchaseState`, plus backend-only outcomes (REVOKED, REPLACED, INVALID).
 */
enum class EntitlementState {
    /** Subscription active, or one-time (lifetime) product purchased. */
    ACTIVE,
    /** Renewal payment failed, user keeps access during the grace period. */
    IN_GRACE_PERIOD,
    /** Auto-renew off; access continues until `expiresAt`. */
    CANCELED,
    /** Account hold after the grace period: no access. */
    ON_HOLD,
    PAUSED,
    /** Purchase awaiting payment (e.g. cash): no access yet. */
    PENDING,
    EXPIRED,
    /** Refunded / voided / revoked by Google or the developer. */
    REVOKED,
    /** Superseded by an upgrade/downgrade (the new token's `linkedPurchaseToken` pointed here). */
    REPLACED,
    /** Play does not know the token (404/410/400), or the product is not ours. */
    INVALID,
}

data class Entitlement(
    val userId: UserId,
    val productId: String,
    /**
     * Raw Play purchase token. Kept because re-verification (GET /v1/entitlements, RTDN) must call the
     * Play Developer API with it; it is useless without our service-account credentials. Lookups use [tokenHash].
     */
    val purchaseToken: String,
    val state: EntitlementState,
    val expiresAt: Instant?,
    val autoRenewing: Boolean,
    val acknowledged: Boolean,
    val testPurchase: Boolean,
    val lastVerifiedAt: Instant,
) {
    /** SHA-256 (hex) of [purchaseToken]; unique index, used for RTDN lookups and the one-token-one-account rule. */
    val tokenHash: String get() = PurchaseTokens.hash(purchaseToken)

    fun isProAt(now: Instant): Boolean = when (state) {
        ACTIVE, IN_GRACE_PERIOD -> expiresAt == null || expiresAt.isAfter(now)
        CANCELED -> expiresAt != null && expiresAt.isAfter(now)
        else -> false
    }

    /** A lifetime (one-time) purchase that is still valid. */
    val isLifetime: Boolean get() = state == ACTIVE && expiresAt == null
}

object PurchaseTokens {
    fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
