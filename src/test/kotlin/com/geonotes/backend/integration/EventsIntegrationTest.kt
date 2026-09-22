package com.geonotes.backend.integration

import com.geonotes.backend.api.model.EventResponse
import com.geonotes.backend.api.model.PostEventRequest
import com.geonotes.backend.config.RateLimitConfig
import com.geonotes.backend.support.Api
import com.geonotes.backend.support.IntegrationTest
import com.geonotes.backend.support.TestDatabase
import com.geonotes.backend.support.errorCode
import com.geonotes.backend.support.expect
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EventsIntegrationTest : IntegrationTest() {
    private fun Api.event(shareId: String, transition: String = "ENTER") = PostEventRequest(shareId, transition, clock.instant().toString())

    @Test
    fun `owner transition is stored and fanned out to all recipient devices via push`() = apiTest {
        user("alice"); user("bob", "bob-device-1", "bob-device-2"); user("carol"); user("dave")
        befriend("alice", "bob"); befriend("alice", "carol"); befriend("alice", "dave")
        val share = createShare("alice", "bob" to "bob-device-1", "bob" to "bob-device-2", "carol" to "carol-device-1")

        val r = post("/v1/events", "alice", event(share.id)).expect(HttpStatusCode.Accepted).body<EventResponse>()
        assertEquals("DELIVERED", r.status)
        assertNotNull(r.eventId)
        assertEquals(3, r.recipientDevices)
        assertEquals(3, r.pushed)

        // dave is a friend but not a recipient → no push.
        assertEquals(setOf("fcm-bob-device-1", "fcm-bob-device-2", "fcm-carol-device-1"), push.sent.map { it.token }.toSet())
        push.sent.forEach { msg ->
            assertEquals(
                mapOf(
                    "type" to "friend_transition",
                    "shareId" to share.id,
                    "fromUserId" to "alice",
                    "transition" to "ENTER",
                    "occurredAt" to clock.instant().toString(),
                ),
                msg.data,
            )
        }
        assertEquals(1, TestDatabase.count("events", "share_id = '${share.id}'"))
    }

    @Test
    fun `only the share owner may post events`() = apiTest {
        user("alice"); user("bob"); user("eve")
        befriend("alice", "bob")
        val share = createShare("alice", "bob" to "bob-device-1")
        assertEquals("not_share_owner", post("/v1/events", "bob", event(share.id)).expect(HttpStatusCode.Forbidden).errorCode())
        assertEquals("share_not_found", post("/v1/events", "eve", event(share.id)).expect(HttpStatusCode.NotFound).errorCode())
        assertTrue(push.sent.isEmpty())
    }

    @Test
    fun `events for paused or inactive shares are ignored`() = apiTest {
        user("alice"); user("bob")
        befriend("alice", "bob")
        val share = createShare("alice", "bob" to "bob-device-1")

        patch("/v1/shares/${share.id}", "alice", buildJsonObject { put("pausedUntil", clock.instant().plus(Duration.ofHours(2)).toString()) })
        assertEquals("paused", post("/v1/events", "alice", event(share.id)).body<EventResponse>().reason)

        clock.advance(Duration.ofHours(3)) // pause elapsed
        assertEquals("DELIVERED", post("/v1/events", "alice", event(share.id)).body<EventResponse>().status)

        patch("/v1/shares/${share.id}", "alice", buildJsonObject { put("active", false) })
        val ignored = post("/v1/events", "alice", event(share.id)).expect(HttpStatusCode.Accepted).body<EventResponse>()
        assertEquals("IGNORED", ignored.status)
        assertEquals("inactive", ignored.reason)
        assertEquals(1, push.sent.size)
        assertEquals(1, TestDatabase.count("events"))
    }

    @Test
    fun `expired events are purged by the cleanup job after 7 days`() = apiTest {
        user("alice"); user("bob")
        befriend("alice", "bob")
        val share = createShare("alice", "bob" to "bob-device-1")
        post("/v1/events", "alice", event(share.id)).expect(HttpStatusCode.Accepted)
        clock.advance(Duration.ofDays(7).plusMinutes(1))
        module.eventService.cleanup()
        assertEquals(0, TestDatabase.count("events"))
    }

    @Test
    fun `events endpoint is rate limited`() = apiTest(rateLimits = RateLimitConfig(eventsPerMinute = 3, invitesPerMinute = 100)) {
        user("alice"); user("bob")
        befriend("alice", "bob")
        val share = createShare("alice", "bob" to "bob-device-1")
        repeat(3) { post("/v1/events", "alice", event(share.id)).expect(HttpStatusCode.Accepted) }
        assertEquals("rate_limited", post("/v1/events", "alice", event(share.id)).expect(HttpStatusCode.TooManyRequests).errorCode())
    }
}
