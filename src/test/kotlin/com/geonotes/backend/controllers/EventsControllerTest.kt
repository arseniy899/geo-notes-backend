package com.geonotes.backend.controllers

import com.geonotes.backend.api.model.PostEventRequest
import com.geonotes.backend.api.model.RegisterDeviceRequest
import com.geonotes.backend.api.model.UpdateShareRequest
import com.geonotes.backend.domain.ForbiddenException
import com.geonotes.backend.domain.NotFoundException
import com.geonotes.backend.domain.ValidationException
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventsControllerTest {
    private fun ControllerFixture.event(shareId: String, transition: String = "ENTER") =
        PostEventRequest(shareId, transition, clock.instant().toString())

    @Test
    fun `owner transition fans out a data push to every recipient device`() = runTest {
        val f = ControllerFixture()
        f.user("alice"); f.user("bob"); f.user("carol")
        f.devicesController.register("bob", RegisterDeviceRequest("bob-device-2", "fcm-bob-device-2", ControllerFixture.b64(ByteArray(32)), "ANDROID"))
        f.befriend("alice", "bob"); f.befriend("alice", "carol")
        val req = SharesControllerTest.shareTo("bob").let { r ->
            r.copy(recipients = r.recipients + r.recipients[0].copy(deviceId = "bob-device-2") + r.recipients[0].copy(userId = "carol", deviceId = "carol-device-1"))
        }
        val share = f.sharesController.create("alice", req)

        val response = f.eventsController.post("alice", f.event(share.id))
        assertEquals("DELIVERED", response.status)
        assertEquals(3, response.pushed)
        assertEquals(setOf("fcm-bob-device-1", "fcm-bob-device-2", "fcm-carol-device-1"), f.push.sent.map { it.token }.toSet())
        val data = f.push.sent.first().data
        assertEquals("friend_transition", data["type"])
        assertEquals(share.id, data["shareId"])
        assertEquals("alice", data["fromUserId"])
        assertEquals("ENTER", data["transition"])
        assertTrue(data.keys.none { it.contains("lat") || it.contains("lon") })
    }

    @Test
    fun `paused, inactive and unsubscribed transitions are ignored without push`() = runTest {
        val f = ControllerFixture()
        f.user("alice"); f.user("bob")
        f.befriend("alice", "bob")
        val share = f.sharesController.create("alice", SharesControllerTest.shareTo("bob"))

        assertEquals("transition_not_subscribed", f.eventsController.post("alice", f.event(share.id, "EXIT")).reason)
        f.sharesController.update("alice", share.id, UpdateShareRequest(null, f.clock.instant().plus(Duration.ofHours(1)).toString(), true))
        assertEquals("paused", f.eventsController.post("alice", f.event(share.id)).reason)
        f.sharesController.update("alice", share.id, UpdateShareRequest(false, null, true))
        assertEquals("inactive", f.eventsController.post("alice", f.event(share.id)).reason)
        assertTrue(f.push.sent.isEmpty())
        assertTrue(f.events.events.isEmpty())
    }

    @Test
    fun `only the owner may post, and unregistered tokens are cleared`() = runTest {
        val f = ControllerFixture()
        f.user("alice"); f.user("bob"); f.user("eve")
        f.befriend("alice", "bob")
        val share = f.sharesController.create("alice", SharesControllerTest.shareTo("bob"))
        assertFailsWith<ForbiddenException> { f.eventsController.post("bob", f.event(share.id)) }
        assertFailsWith<NotFoundException> { f.eventsController.post("eve", f.event(share.id)) }
        assertFailsWith<ValidationException> { f.eventsController.post("alice", PostEventRequest(share.id, "ENTER", "yesterday")) }

        f.push.unregistered += "fcm-bob-device-1"
        val r = f.eventsController.post("alice", f.event(share.id))
        assertEquals(0, r.pushed)
        assertNull(f.devices.devices["bob-device-1"]!!.fcmToken)
    }

    @Test
    fun `cleanup drops events after the 7 day TTL`() = runTest {
        val f = ControllerFixture()
        f.user("alice"); f.user("bob")
        f.befriend("alice", "bob")
        val share = f.sharesController.create("alice", SharesControllerTest.shareTo("bob"))
        f.eventsController.post("alice", f.event(share.id))
        f.clock.advance(Duration.ofDays(6))
        assertEquals(0, f.eventService.cleanup().first)
        f.clock.advance(Duration.ofDays(1))
        assertEquals(1, f.eventService.cleanup().first)
    }
}
