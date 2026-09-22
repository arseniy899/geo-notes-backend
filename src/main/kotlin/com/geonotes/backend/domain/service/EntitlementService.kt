package com.geonotes.backend.domain.service

import com.geonotes.backend.billing.PlayPurchase
import com.geonotes.backend.billing.PlayPurchaseVerifier
import com.geonotes.backend.domain.ConflictException
import com.geonotes.backend.domain.UpstreamUnavailableException
import com.geonotes.backend.domain.ValidationException
import com.geonotes.backend.domain.model.Entitlement
import com.geonotes.backend.domain.model.EntitlementState
import com.geonotes.backend.domain.model.NotificationOutcome
import com.geonotes.backend.domain.model.PlayNotification
import com.geonotes.backend.domain.model.ProductCatalog
import com.geonotes.backend.domain.model.ProductKind
import com.geonotes.backend.domain.model.PurchaseTokens
import com.geonotes.backend.domain.model.UserId
import com.geonotes.backend.domain.repository.EntitlementRepository
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Pro entitlements backed by Google Play purchases.
 *
 * Rules:
 * - A purchase token unlocks Pro for **one** account only (409 `purchase_token_in_use`).
 * - One entitlement row per user. A new token replaces it, except that a still-valid entitlement is never
 *   replaced by a non-Pro result, and a valid lifetime purchase is never replaced by a subscription.
 * - Upgrades/downgrades: the new subscription's `linkedPurchaseToken` marks the old token's row REPLACED
 *   (or, via RTDN, moves the owner of the old token onto the new one).
 * - REVOKED (refund/void) and REPLACED are sticky: later re-verification cannot resurrect them.
 * - Paid, unacknowledged purchases are acknowledged server-side; an acknowledge failure is logged and retried
 *   on the next verification (Play auto-refunds purchases not acknowledged within 3 days).
 */
class EntitlementService(
    private val verifier: PlayPurchaseVerifier,
    private val entitlements: EntitlementRepository,
    private val userService: UserService,
    private val clock: Clock,
    private val packageName: String,
    private val allowTestPurchases: Boolean = true,
    private val reverifyAfter: Duration = Duration.ofHours(24),
) {
    private val log = LoggerFactory.getLogger(EntitlementService::class.java)

    /** POST /v1/entitlements/verify: checks [purchaseToken] with Play and binds it to [userId]. */
    suspend fun verify(userId: UserId, productId: String, purchaseToken: String): Entitlement {
        userService.requireRegistered(userId)
        val kind = ProductCatalog.kindOf(productId) ?: throw ValidationException("Unknown productId", "unknown_product")
        val bound = entitlements.findByTokenHash(PurchaseTokens.hash(purchaseToken))
        if (bound != null && bound.userId != userId) {
            throw ConflictException("Purchase token already bound to another account", "purchase_token_in_use")
        }
        if (bound != null && bound.state in STICKY_STATES) return bound

        val purchase = verifier.verify(kind, productId, purchaseToken)
        val candidate = build(userId, productId, purchaseToken, purchase)
        if (candidate.state != EntitlementState.INVALID) replaceLinked(purchase.linkedPurchaseToken, keepFor = userId)

        val now = clock.instant()
        val current = entitlements.find(userId)
        if (current != null && current.purchaseToken != purchaseToken && current.isProAt(now) &&
            (!candidate.isProAt(now) || current.isLifetime)
        ) {
            return current
        }
        return entitlements.upsert(acknowledgeIfNeeded(candidate))
    }

    /**
     * GET /v1/entitlements: the caller's entitlement, re-verified with Play when it looks outdated
     * (subscription expiry passed and not checked since, or the last check is older than [reverifyAfter]).
     * If Play is unavailable the stored state is returned.
     */
    suspend fun current(userId: UserId): Entitlement? {
        userService.requireRegistered(userId)
        val stored = entitlements.find(userId) ?: return null
        if (!isDueForReverification(stored, clock.instant())) return stored
        return try {
            refresh(stored)
        } catch (e: UpstreamUnavailableException) {
            log.warn("Re-verification skipped, Play unavailable: {}", e.code)
            stored
        }
    }

    /**
     * Handles an authentic Real-time Developer Notification. Throws [UpstreamUnavailableException] when Play
     * cannot be reached, so the push endpoint answers 503 and Pub/Sub redelivers later.
     */
    suspend fun handleNotification(notification: PlayNotification): NotificationOutcome {
        if (notification.packageName != null && notification.packageName != packageName) {
            log.warn("RTDN for foreign package ignored")
            return NotificationOutcome.IGNORED
        }
        return when (notification) {
            is PlayNotification.Test, is PlayNotification.Other -> NotificationOutcome.IGNORED
            is PlayNotification.Subscription -> onSubscriptionNotification(notification)
            is PlayNotification.OneTimeProduct -> {
                val bound = entitlements.findByTokenHash(PurchaseTokens.hash(notification.purchaseToken))
                if (bound == null) NotificationOutcome.UNKNOWN_TOKEN else refresh(bound).let { NotificationOutcome.UPDATED }
            }
            is PlayNotification.VoidedPurchase -> onVoided(notification)
        }
    }

    private suspend fun onSubscriptionNotification(n: PlayNotification.Subscription): NotificationOutcome {
        val bound = entitlements.findByTokenHash(PurchaseTokens.hash(n.purchaseToken))
        if (bound != null) {
            refresh(bound)
            return NotificationOutcome.UPDATED
        }
        // New purchase not yet bound by the app. If it continues an upgrade chain, move the old owner onto it.
        if (n.type != null && n.type != SUBSCRIPTION_PURCHASED) return NotificationOutcome.UNKNOWN_TOKEN
        val purchase = verifier.verify(ProductKind.SUBSCRIPTION, "", n.purchaseToken)
        val linked = purchase.linkedPurchaseToken?.let { entitlements.findByTokenHash(PurchaseTokens.hash(it)) }
        if (linked == null || linked.state in STICKY_STATES) return NotificationOutcome.UNKNOWN_TOKEN
        val next = build(linked.userId, linked.productId, n.purchaseToken, purchase)
        if (next.state == EntitlementState.INVALID) return NotificationOutcome.UNKNOWN_TOKEN
        // `linked` is that user's only row (one row per user), so replacing it keeps the one-token-one-account rule.
        entitlements.upsert(acknowledgeIfNeeded(next))
        return NotificationOutcome.UPDATED
    }

    private suspend fun onVoided(n: PlayNotification.VoidedPurchase): NotificationOutcome {
        if (n.refundType == PlayNotification.REFUND_TYPE_PARTIAL) return NotificationOutcome.IGNORED
        val bound = entitlements.findByTokenHash(PurchaseTokens.hash(n.purchaseToken)) ?: return NotificationOutcome.UNKNOWN_TOKEN
        entitlements.upsert(bound.copy(state = EntitlementState.REVOKED, autoRenewing = false, lastVerifiedAt = clock.instant()))
        log.info("Entitlement revoked after voided purchase (productId={})", bound.productId)
        return NotificationOutcome.REVOKED
    }

    /** Re-reads a bound entitlement from Play and stores the result. Sticky states are never changed. */
    private suspend fun refresh(e: Entitlement): Entitlement {
        if (e.state in STICKY_STATES) return e
        val kind = ProductCatalog.kindOf(e.productId) ?: return e
        val purchase = verifier.verify(kind, e.productId, e.purchaseToken)
        val next = build(e.userId, e.productId, e.purchaseToken, purchase)
        return entitlements.upsert(acknowledgeIfNeeded(next))
    }

    /** Old token of an upgrade chain: if another account holds it, that account loses the replaced purchase. */
    private suspend fun replaceLinked(linkedToken: String?, keepFor: UserId) {
        val old = linkedToken?.let { entitlements.findByTokenHash(PurchaseTokens.hash(it)) } ?: return
        if (old.userId == keepFor || old.state in STICKY_STATES) return
        entitlements.upsert(old.copy(state = EntitlementState.REPLACED, autoRenewing = false, lastVerifiedAt = clock.instant()))
    }

    private fun build(userId: UserId, requestedProductId: String, token: String, p: PlayPurchase): Entitlement {
        val playProductKnown = p.productId == null || ProductCatalog.kindOf(p.productId) != null
        val state = when {
            !playProductKnown -> EntitlementState.INVALID
            p.testPurchase && !allowTestPurchases -> EntitlementState.INVALID
            else -> p.state
        }
        return Entitlement(
            userId = userId,
            productId = p.productId?.takeIf { playProductKnown } ?: requestedProductId,
            purchaseToken = token,
            state = state,
            expiresAt = p.expiresAt,
            autoRenewing = p.autoRenewing,
            acknowledged = p.acknowledged,
            testPurchase = p.testPurchase,
            lastVerifiedAt = clock.instant(),
        )
    }

    private suspend fun acknowledgeIfNeeded(e: Entitlement): Entitlement {
        if (e.acknowledged || !e.isProAt(clock.instant())) return e
        val kind = ProductCatalog.kindOf(e.productId) ?: return e
        return try {
            verifier.acknowledge(kind, e.productId, e.purchaseToken)
            e.copy(acknowledged = true)
        } catch (ex: UpstreamUnavailableException) {
            log.warn("Acknowledge failed ({}); will retry on next verification", ex.code)
            e
        }
    }

    private fun isDueForReverification(e: Entitlement, now: Instant): Boolean {
        if (e.state !in REVERIFIABLE_STATES) return false
        val stale = !e.lastVerifiedAt.plus(reverifyAfter).isAfter(now)
        val expiresAt = e.expiresAt
        return when {
            // Lifetime purchases are final unless an RTDN voids them; only a pending one-time purchase is re-checked.
            expiresAt == null -> e.state == EntitlementState.PENDING && stale
            expiresAt.isAfter(now) -> false
            // Expired: check once right after expiry (renewal may have been missed), then at most every [reverifyAfter].
            else -> stale || !e.lastVerifiedAt.isAfter(expiresAt)
        }
    }

    companion object {
        private const val SUBSCRIPTION_PURCHASED = 4
        private val STICKY_STATES = setOf(EntitlementState.REVOKED, EntitlementState.REPLACED)
        private val REVERIFIABLE_STATES = setOf(
            EntitlementState.ACTIVE, EntitlementState.IN_GRACE_PERIOD, EntitlementState.CANCELED,
            EntitlementState.ON_HOLD, EntitlementState.PAUSED, EntitlementState.PENDING,
        )
    }
}
