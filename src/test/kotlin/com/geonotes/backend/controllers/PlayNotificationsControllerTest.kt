package com.geonotes.backend.controllers

import com.geonotes.backend.api.model.PubSubMessage
import com.geonotes.backend.api.model.PubSubPushRequest
import com.geonotes.backend.api.model.VerifyPurchaseRequest
import com.geonotes.backend.billing.PlayPurchase
import com.geonotes.backend.domain.UpstreamUnavailableException
import com.geonotes.backend.domain.model.EntitlementState
import com.geonotes.backend.domain.model.PlayNotification
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlayNotificationsControllerTest {
    private val pkg = ControllerFixture.PACKAGE

    private fun push(json: String) = PubSubPushRequest(PubSubMessage(Base64.getEncoder().encodeToString(json.toByteArray()), "msg-1"))

    private fun subNotification(token: String, type: Int, packageName: String = pkg) =
        """{"version":"1.0","packageName":"$packageName","eventTimeMillis":"1780308000000",
           "subscriptionNotification":{"version":"1.0","notificationType":$type,"purchaseToken":"$token"}}"""

    private fun voided(token: String, productType: Int = 1, refundType: Int = 1) =
        """{"version":"1.0","packageName":"$pkg","eventTimeMillis":"1780308000000",
           "voidedPurchaseNotification":{"purchaseToken":"$token","orderId":"GPA.1","productType":$productType,"refundType":$refundType}}"""

    private fun ControllerFixture.active(productId: String = "pro_monthly", days: Long = 30, linked: String? = null) =
        PlayPurchase(productId, EntitlementState.ACTIVE, clock.instant().plus(Duration.ofDays(days)), autoRenewing = true, linkedPurchaseToken = linked)

    @Test
    fun `decodes every DeveloperNotification kind`() {
        val c = ControllerFixture().playNotificationsController
        fun dec(json: String) = c.decode(Base64.getEncoder().encodeToString(json.toByteArray()))
        assertEquals(PlayNotification.Subscription(pkg, "t1", 2), dec(subNotification("t1", 2)))
        assertEquals(
            PlayNotification.OneTimeProduct(pkg, "t2", "pro_lifetime", 1),
            dec("""{"packageName":"$pkg","oneTimeProductNotification":{"version":"1.0","notificationType":1,"purchaseToken":"t2","sku":"pro_lifetime"}}"""),
        )
        assertEquals(PlayNotification.VoidedPurchase(pkg, "t3", 1, 1), dec(voided("t3")))
        assertEquals(PlayNotification.Test(pkg), dec("""{"version":"1.0","packageName":"$pkg","eventTimeMillis":"1","testNotification":{"version":"1.0"}}"""))
        assertIs<PlayNotification.Other>(dec("""{"packageName":"$pkg","pendingRefundReviewNotification":{"orderId":"x"}}"""))
        assertIs<PlayNotification.Other>(c.decode("%%%not-base64%%%"))
        assertIs<PlayNotification.Other>(dec("[1,2,3]"))
        assertIs<PlayNotification.Other>(c.decode(null))
    }

    @Test
    fun `renewal notification re-verifies the bound token and extends the entitlement`() = runTest {
        val f = ControllerFixture(); f.user("alice")
        f.play.purchases["tok"] = f.active(days = 1)
        f.entitlementsController.verify("alice", VerifyPurchaseRequest("tok", "pro_monthly"))

        f.play.purchases["tok"] = f.active(days = 31)
        assertEquals("UPDATED", f.playNotificationsController.handle(push(subNotification("tok", 2))).outcome)
        assertEquals(f.clock.instant().plus(Duration.ofDays(31)), f.entitlements.entitlements.getValue("alice").expiresAt)
    }

    @Test
    fun `cancel, hold and expiry notifications update the state`() = runTest {
        val f = ControllerFixture(); f.user("alice")
        f.play.purchases["tok"] = f.active()
        f.entitlementsController.verify("alice", VerifyPurchaseRequest("tok", "pro_monthly"))

        f.play.purchases["tok"] = f.active().copy(state = EntitlementState.ON_HOLD, expiresAt = f.clock.instant().minusSeconds(1))
        f.playNotificationsController.handle(push(subNotification("tok", 5)))
        assertFalse(f.entitlementsController.current("alice").pro)
        assertEquals("ON_HOLD", f.entitlementsController.current("alice").state)
    }

    @Test
    fun `voided purchase revokes the entitlement and it stays revoked`() = runTest {
        val f = ControllerFixture(); f.user("alice")
        f.play.purchases["life"] = PlayPurchase("pro_lifetime", EntitlementState.ACTIVE, null)
        f.entitlementsController.verify("alice", VerifyPurchaseRequest("life", "pro_lifetime"))
        assertTrue(f.entitlementsController.current("alice").pro)

        assertEquals("REVOKED", f.playNotificationsController.handle(push(voided("life", productType = 2))).outcome)
        assertFalse(f.entitlementsController.current("alice").pro)

        // Even if Play still answered PURCHASED, a later product notification cannot resurrect it.
        f.playNotificationsController.handle(
            push("""{"packageName":"$pkg","oneTimeProductNotification":{"notificationType":1,"purchaseToken":"life","sku":"pro_lifetime"}}"""),
        )
        assertEquals("REVOKED", f.entitlementsController.current("alice").state)
    }

    @Test
    fun `partial refunds, test notifications and foreign packages are ignored`() = runTest {
        val f = ControllerFixture(); f.user("alice")
        f.play.purchases["tok"] = f.active()
        f.entitlementsController.verify("alice", VerifyPurchaseRequest("tok", "pro_monthly"))
        f.play.verified.clear()

        assertEquals("IGNORED", f.playNotificationsController.handle(push(voided("tok", refundType = 2))).outcome)
        assertEquals("IGNORED", f.playNotificationsController.handle(push("""{"packageName":"$pkg","testNotification":{"version":"1.0"}}""")).outcome)
        assertEquals("IGNORED", f.playNotificationsController.handle(push(subNotification("tok", 3, packageName = "com.evil.app"))).outcome)
        assertTrue(f.entitlementsController.current("alice").pro)
        assertTrue(f.play.verified.isEmpty())
    }

    @Test
    fun `notification for an unbound token changes nothing`() = runTest {
        val f = ControllerFixture(); f.user("alice")
        assertEquals("UNKNOWN_TOKEN", f.playNotificationsController.handle(push(subNotification("never-seen", 2))).outcome)
        assertEquals("UNKNOWN_TOKEN", f.playNotificationsController.handle(push(voided("never-seen"))).outcome)
        assertTrue(f.entitlements.entitlements.isEmpty())
    }

    @Test
    fun `SUBSCRIPTION_PURCHASED for an upgrade moves the owner of the linked token onto the new token`() = runTest {
        val f = ControllerFixture(); f.user("alice")
        f.play.purchases["old"] = f.active()
        f.entitlementsController.verify("alice", VerifyPurchaseRequest("old", "pro_monthly"))

        f.play.purchases["new"] = f.active(productId = "pro_yearly", days = 365, linked = "old").copy(acknowledged = false)
        assertEquals("UPDATED", f.playNotificationsController.handle(push(subNotification("new", 4))).outcome)

        val e = f.entitlements.entitlements.getValue("alice")
        assertEquals("new", e.purchaseToken)
        assertEquals("pro_yearly", e.productId)
        assertTrue(e.acknowledged)
        // The old token is no longer bound: a stale RTDN for it is a no-op.
        assertEquals("UNKNOWN_TOKEN", f.playNotificationsController.handle(push(subNotification("old", 13))).outcome)
    }

    @Test
    fun `Play outage during RTDN propagates so Pub-Sub redelivers`() = runTest {
        val f = ControllerFixture(); f.user("alice")
        f.play.purchases["tok"] = f.active()
        f.entitlementsController.verify("alice", VerifyPurchaseRequest("tok", "pro_monthly"))
        f.play.unavailable = true
        assertFailsWith<UpstreamUnavailableException> { f.playNotificationsController.handle(push(subNotification("tok", 2))) }
    }
}
