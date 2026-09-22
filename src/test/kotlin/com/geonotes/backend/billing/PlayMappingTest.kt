package com.geonotes.backend.billing

import com.geonotes.backend.domain.model.EntitlementState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayMappingTest {
    private fun sub(name: String) = PlayMapping.subscription(PlayJson.decodeFromString<SubscriptionPurchaseV2>(PlayFixtures.load(name)))
    private fun product(name: String) = PlayMapping.product(PlayJson.decodeFromString<ProductPurchaseV2>(PlayFixtures.load(name)))

    @Test
    fun `active subscription maps state, expiry, product, auto-renew and pending acknowledgement`() {
        val p = sub("subscriptionsv2_active")
        assertEquals(EntitlementState.ACTIVE, p.state)
        assertEquals("pro_monthly", p.productId)
        assertEquals(Instant.parse("2026-07-01T09:59:10.123Z"), p.expiresAt)
        assertTrue(p.autoRenewing)
        assertFalse(p.acknowledged)
        assertFalse(p.testPurchase)
        assertNull(p.linkedPurchaseToken)
    }

    @Test
    fun `grace period keeps access`() {
        val p = sub("subscriptionsv2_grace")
        assertEquals(EntitlementState.IN_GRACE_PERIOD, p.state)
        assertEquals("pro_yearly", p.productId)
        assertTrue(p.acknowledged)
    }

    @Test
    fun `on hold, expired and pending subscriptions map to non-access states`() {
        assertEquals(EntitlementState.ON_HOLD, sub("subscriptionsv2_on_hold").state)
        val expired = sub("subscriptionsv2_expired")
        assertEquals(EntitlementState.EXPIRED, expired.state)
        assertFalse(expired.autoRenewing)
        val pending = sub("subscriptionsv2_pending")
        assertEquals(EntitlementState.PENDING, pending.state)
        assertNull(pending.expiresAt)
    }

    @Test
    fun `canceled subscription keeps its expiry and has auto-renew off`() {
        val p = sub("subscriptionsv2_canceled_not_expired")
        assertEquals(EntitlementState.CANCELED, p.state)
        assertEquals(Instant.parse("2026-06-15T10:00:00Z"), p.expiresAt)
        assertFalse(p.autoRenewing)
    }

    @Test
    fun `upgrade exposes linkedPurchaseToken and the testPurchase flag`() {
        val p = sub("subscriptionsv2_upgrade_test")
        assertEquals("old-monthly-token", p.linkedPurchaseToken)
        assertTrue(p.testPurchase)
        assertEquals("pro_yearly", p.productId)
    }

    @Test
    fun `unknown or missing subscription state is invalid`() {
        assertEquals(EntitlementState.INVALID, PlayMapping.subscription(SubscriptionPurchaseV2(subscriptionState = "SUBSCRIPTION_STATE_UNSPECIFIED")).state)
        assertEquals(EntitlementState.INVALID, PlayMapping.subscription(SubscriptionPurchaseV2()).state)
        assertEquals(
            EntitlementState.EXPIRED,
            PlayMapping.subscription(SubscriptionPurchaseV2(subscriptionState = "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED")).state,
        )
    }

    @Test
    fun `purchased one-time product is a lifetime purchase needing acknowledgement`() {
        val p = product("productsv2_purchased")
        assertEquals(EntitlementState.ACTIVE, p.state)
        assertEquals("pro_lifetime", p.productId)
        assertNull(p.expiresAt)
        assertFalse(p.acknowledged)
        assertFalse(p.testPurchase)
    }

    @Test
    fun `refunded one-time product is revoked`() {
        val p = product("productsv2_refunded")
        assertEquals(EntitlementState.REVOKED, p.state)
        assertTrue(p.acknowledged)
        assertTrue(p.testPurchase)
    }

    @Test
    fun `pending one-time product grants nothing yet`() {
        assertEquals(EntitlementState.PENDING, PlayMapping.product(ProductPurchaseV2(purchaseStateContext = PurchaseStateContext("PENDING"))).state)
    }
}
