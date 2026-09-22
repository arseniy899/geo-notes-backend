package com.geonotes.backend.controllers

import com.geonotes.backend.api.model.VerifyPurchaseRequest
import com.geonotes.backend.billing.PlayPurchase
import com.geonotes.backend.billing.StubPlayPurchaseVerifier
import com.geonotes.backend.domain.ConflictException
import com.geonotes.backend.domain.UpstreamUnavailableException
import com.geonotes.backend.domain.ValidationException
import com.geonotes.backend.domain.model.EntitlementState
import com.geonotes.backend.domain.model.ProductKind
import com.geonotes.backend.domain.service.EntitlementService
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntitlementsControllerTest {
    private fun ControllerFixture.inDays(days: Long): Instant = clock.instant().plus(Duration.ofDays(days))

    private fun ControllerFixture.sub(
        productId: String = "pro_monthly",
        state: EntitlementState = EntitlementState.ACTIVE,
        days: Long = 30,
        acknowledged: Boolean = true,
        test: Boolean = false,
        linked: String? = null,
    ) = PlayPurchase(productId, state, inDays(days), autoRenewing = true, acknowledged = acknowledged, testPurchase = test, linkedPurchaseToken = linked)

    private val lifetime = PlayPurchase("pro_lifetime", EntitlementState.ACTIVE, expiresAt = null, acknowledged = false)

    @Test
    fun `active subscription grants pro, is stored and acknowledged server-side`() = runTest {
        val f = ControllerFixture(); f.user("alice")
        f.play.purchases["tok-1"] = f.sub(acknowledged = false)

        val r = f.entitlementsController.verify("alice", VerifyPurchaseRequest("tok-1", "pro_monthly"))

        assertTrue(r.pro)
        assertEquals("ACTIVE", r.state)
        assertEquals("pro_monthly", r.productId)
        assertTrue(r.autoRenewing)
        assertEquals(listOf(ProductKind.SUBSCRIPTION to "tok-1"), f.play.acknowledged)
        val stored = f.entitlements.entitlements.getValue("alice")
        assertTrue(stored.acknowledged)
        assertEquals(64, stored.tokenHash.length)
    }

    @Test
    fun `already acknowledged purchases are not acknowledged again`() = runTest {
        val f = ControllerFixture(); f.user("alice")
        f.play.purchases["tok-1"] = f.sub(acknowledged = true)
        f.entitlementsController.verify("alice", VerifyPurchaseRequest("tok-1", "pro_monthly"))
        f.entitlementsController.verify("alice", VerifyPurchaseRequest("tok-1", "pro_monthly"))
        assertTrue(f.play.acknowledged.isEmpty())
    }

    @Test
    fun `acknowledge failure still grants pro and is retried on the next verification`() = runTest {
        val f = ControllerFixture(); f.user("alice")
        f.play.purchases["tok-1"] = f.sub(acknowledged = false)
        f.play.failAcknowledge = true
        assertTrue(f.entitlementsController.verify("alice", VerifyPurchaseRequest("tok-1", "pro_monthly")).pro)
        assertFalse(f.entitlements.entitlements.getValue("alice").acknowledged)

        f.play.failAcknowledge = false
        f.entitlementsController.verify("alice", VerifyPurchaseRequest("tok-1", "pro_monthly"))
        assertTrue(f.entitlements.entitlements.getValue("alice").acknowledged)
    }

    @Test
    fun `grace period and canceled-but-not-expired keep pro, on hold, paused, pending and expired do not`() = runTest {
        val f = ControllerFixture()
        val cases = mapOf(
            EntitlementState.IN_GRACE_PERIOD to true,
            EntitlementState.CANCELED to true,
            EntitlementState.ON_HOLD to false,
            EntitlementState.PAUSED to false,
            EntitlementState.PENDING to false,
            EntitlementState.EXPIRED to false,
        )
        cases.entries.forEachIndexed { i, (state, pro) ->
            val uid = "user$i"
            f.user(uid)
            f.play.purchases["tok-$i"] = f.sub(state = state, days = if (state == EntitlementState.EXPIRED) -1 else 5)
            val r = f.entitlementsController.verify(uid, VerifyPurchaseRequest("tok-$i", "pro_monthly"))
            assertEquals(pro, r.pro, "state $state")
            assertEquals(state.name, r.state)
        }
    }

    @Test
    fun `canceled subscription loses pro at expiry`() = runTest {
        val f = ControllerFixture(); f.user("alice")
        f.play.purchases["tok"] = f.sub(state = EntitlementState.CANCELED, days = 2)
        assertTrue(f.entitlementsController.verify("alice", VerifyPurchaseRequest("tok", "pro_monthly")).pro)
        f.clock.advance(Duration.ofDays(3))
        f.play.purchases["tok"] = f.sub(state = EntitlementState.EXPIRED, days = -1)
        assertFalse(f.entitlementsController.current("alice").pro)
    }

    @Test
    fun `unknown token is stored as INVALID and not pro`() = runTest {
        val f = ControllerFixture(); f.user("alice")
        val r = f.entitlementsController.verify("alice", VerifyPurchaseRequest("garbage", "pro_monthly"))
        assertFalse(r.pro)
        assertEquals("INVALID", r.state)
    }

    @Test
    fun `unknown product is rejected before calling Play`() = runTest {
        val f = ControllerFixture(); f.user("alice")
        val e = assertFailsWith<ValidationException> { f.entitlementsController.verify("alice", VerifyPurchaseRequest("t", "pro_forever")) }
        assertEquals("unknown_product", e.code)
        assertTrue(f.play.verified.isEmpty())
    }

    @Test
    fun `a product Play reports that is not in our catalog is not pro`() = runTest {
        val f = ControllerFixture(); f.user("alice")
        f.play.purchases["t"] = f.sub(productId = "someone_elses_sub")
        val r = f.entitlementsController.verify("alice", VerifyPurchaseRequest("t", "pro_monthly"))
        assertFalse(r.pro)
        assertEquals("INVALID", r.state)
    }

    @Test
    fun `Play productId wins over what the client claimed`() = runTest {
        val f = ControllerFixture(); f.user("alice")
        f.play.purchases["t"] = f.sub(productId = "pro_yearly", days = 365)
        assertEquals("pro_yearly", f.entitlementsController.verify("alice", VerifyPurchaseRequest("t", "pro_monthly")).productId)
    }

    @Test
    fun `a token can unlock pro for one account only`() = runTest {
        val f = ControllerFixture(); f.user("alice"); f.user("bob")
        f.play.purchases["tok"] = f.sub()
        f.entitlementsController.verify("alice", VerifyPurchaseRequest("tok", "pro_monthly"))
        val e = assertFailsWith<ConflictException> { f.entitlementsController.verify("bob", VerifyPurchaseRequest("tok", "pro_monthly")) }
        assertEquals("purchase_token_in_use", e.code)
    }

    @Test
    fun `unregistered caller gets user_not_registered`() = runTest {
        val f = ControllerFixture()
        assertEquals("user_not_registered", assertFailsWith<ConflictException> { f.entitlementsController.current("ghost") }.code)
    }

    @Test
    fun `test purchases are refused when disabled`() = runTest {
        val f = ControllerFixture(); f.user("alice")
        val strict = EntitlementsController(
            EntitlementService(f.play, f.entitlements, f.userService, f.clock, ControllerFixture.PACKAGE, allowTestPurchases = false), f.clock,
        )
        f.play.purchases["t"] = f.sub(test = true)
        assertFalse(strict.verify("alice", VerifyPurchaseRequest("t", "pro_monthly")).pro)
        assertTrue(f.entitlementsController.verify("alice", VerifyPurchaseRequest("t", "pro_monthly")).pro)
    }

    @Test
    fun `lifetime purchase is acknowledged as a product and never replaced by a subscription or garbage`() = runTest {
        val f = ControllerFixture(); f.user("alice")
        f.play.purchases["life"] = lifetime
        val r = f.entitlementsController.verify("alice", VerifyPurchaseRequest("life", "pro_lifetime"))
        assertTrue(r.pro)
        assertNull(r.expiresAt)
        assertEquals(listOf(ProductKind.ONE_TIME to "life"), f.play.acknowledged)

        f.play.purchases["sub"] = f.sub()
        assertEquals("pro_lifetime", f.entitlementsController.verify("alice", VerifyPurchaseRequest("sub", "pro_monthly")).productId)
        assertEquals("pro_lifetime", f.entitlementsController.verify("alice", VerifyPurchaseRequest("junk", "pro_monthly")).productId)
        assertTrue(f.entitlementsController.current("alice").pro)
    }

    @Test
    fun `a valid subscription is not overwritten by an invalid token, but an expired one is replaced`() = runTest {
        val f = ControllerFixture(); f.user("alice")
        f.play.purchases["good"] = f.sub()
        f.entitlementsController.verify("alice", VerifyPurchaseRequest("good", "pro_monthly"))
        assertTrue(f.entitlementsController.verify("alice", VerifyPurchaseRequest("junk", "pro_monthly")).pro)
        assertEquals("good", f.entitlements.entitlements.getValue("alice").purchaseToken)

        f.play.purchases["new"] = f.sub(productId = "pro_yearly", days = 365)
        assertEquals("pro_yearly", f.entitlementsController.verify("alice", VerifyPurchaseRequest("new", "pro_yearly")).productId)
    }

    @Test
    fun `upgrade invalidates the linked old token held by another account`() = runTest {
        val f = ControllerFixture(); f.user("alice"); f.user("bob")
        f.play.purchases["old-monthly"] = f.sub()
        f.entitlementsController.verify("alice", VerifyPurchaseRequest("old-monthly", "pro_monthly"))

        f.play.purchases["new-yearly"] = f.sub(productId = "pro_yearly", days = 365, linked = "old-monthly")
        assertTrue(f.entitlementsController.verify("bob", VerifyPurchaseRequest("new-yearly", "pro_yearly")).pro)

        val alice = f.entitlementsController.current("alice")
        assertFalse(alice.pro)
        assertEquals("REPLACED", alice.state)
    }

    @Test
    fun `upgrade by the same account replaces its row`() = runTest {
        val f = ControllerFixture(); f.user("alice")
        f.play.purchases["old-monthly"] = f.sub()
        f.entitlementsController.verify("alice", VerifyPurchaseRequest("old-monthly", "pro_monthly"))
        f.play.purchases["new-yearly"] = f.sub(productId = "pro_yearly", days = 365, linked = "old-monthly")
        val r = f.entitlementsController.verify("alice", VerifyPurchaseRequest("new-yearly", "pro_yearly"))
        assertEquals("pro_yearly", r.productId)
        assertEquals("ACTIVE", r.state)
    }

    @Test
    fun `verify surfaces Play outages as UpstreamUnavailable`() = runTest {
        val f = ControllerFixture(); f.user("alice")
        f.play.unavailable = true
        assertFailsWith<UpstreamUnavailableException> { f.entitlementsController.verify("alice", VerifyPurchaseRequest("t", "pro_monthly")) }
        assertTrue(f.entitlements.entitlements.isEmpty())
    }

    @Test
    fun `GET returns the stored entitlement without calling Play while it is not expired`() = runTest {
        val f = ControllerFixture(); f.user("alice")
        assertFalse(f.entitlementsController.current("alice").pro)
        assertNull(f.entitlementsController.current("alice").state)

        f.play.purchases["tok"] = f.sub(days = 30)
        f.entitlementsController.verify("alice", VerifyPurchaseRequest("tok", "pro_monthly"))
        f.play.verified.clear()
        f.clock.advance(Duration.ofDays(10))
        assertTrue(f.entitlementsController.current("alice").pro)
        assertTrue(f.play.verified.isEmpty())
    }

    @Test
    fun `GET re-verifies once after expiry (renewal picked up) and then at most every 24h`() = runTest {
        val f = ControllerFixture(); f.user("alice")
        f.play.purchases["tok"] = f.sub(days = 1)
        f.entitlementsController.verify("alice", VerifyPurchaseRequest("tok", "pro_monthly"))
        f.play.verified.clear()

        f.clock.advance(Duration.ofDays(2))
        f.play.purchases["tok"] = f.sub(days = 29) // renewed, RTDN missed
        val renewed = f.entitlementsController.current("alice")
        assertTrue(renewed.pro)
        assertEquals(1, f.play.verified.size)

        f.clock.advance(Duration.ofDays(30))
        f.play.purchases["tok"] = f.sub(state = EntitlementState.ON_HOLD, days = -2)
        assertFalse(f.entitlementsController.current("alice").pro)
        assertEquals(2, f.play.verified.size)
        f.entitlementsController.current("alice")
        assertEquals(2, f.play.verified.size, "checked recently after expiry: no new Play call")
        f.clock.advance(Duration.ofHours(25))
        f.entitlementsController.current("alice")
        assertEquals(3, f.play.verified.size)
    }

    @Test
    fun `GET falls back to the stored state when Play is down`() = runTest {
        val f = ControllerFixture(); f.user("alice")
        f.play.purchases["tok"] = f.sub(days = 1)
        f.entitlementsController.verify("alice", VerifyPurchaseRequest("tok", "pro_monthly"))
        f.clock.advance(Duration.ofDays(2))
        f.play.unavailable = true
        val r = f.entitlementsController.current("alice")
        assertFalse(r.pro)
        assertEquals("ACTIVE", r.state)
    }

    @Test
    fun `revoked entitlements are sticky`() = runTest {
        val f = ControllerFixture(); f.user("alice")
        f.play.purchases["life"] = lifetime
        f.entitlementsController.verify("alice", VerifyPurchaseRequest("life", "pro_lifetime"))
        f.entitlements.upsert(f.entitlements.entitlements.getValue("alice").copy(state = EntitlementState.REVOKED))
        val r = f.entitlementsController.verify("alice", VerifyPurchaseRequest("life", "pro_lifetime"))
        assertFalse(r.pro)
        assertEquals("REVOKED", r.state)
    }

    @Test
    fun `dev stub grants pro only for test-valid tokens when allowed`() = runTest {
        val clock = ControllerFixture().clock
        assertEquals(EntitlementState.INVALID, StubPlayPurchaseVerifier(clock, false).verify(ProductKind.SUBSCRIPTION, "pro_monthly", "test-valid").state)
        val dev = StubPlayPurchaseVerifier(clock, true)
        assertEquals(EntitlementState.INVALID, dev.verify(ProductKind.SUBSCRIPTION, "pro_monthly", "real-token").state)
        assertEquals(EntitlementState.ACTIVE, dev.verify(ProductKind.SUBSCRIPTION, "pro_monthly", "test-valid-1").state)
        assertNull(dev.verify(ProductKind.ONE_TIME, "pro_lifetime", "test-valid-2").expiresAt)
    }
}
