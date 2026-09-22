package com.geonotes.backend.integration

import com.geonotes.backend.api.model.AcceptShareRequestResponse
import com.geonotes.backend.api.model.CreateShareRequestRequest
import com.geonotes.backend.api.model.DeviceSealedKey
import com.geonotes.backend.api.model.EventResponse
import com.geonotes.backend.api.model.PostEventRequest
import com.geonotes.backend.api.model.ShareRequestResponse
import com.geonotes.backend.api.model.ShareRequestsResponse
import com.geonotes.backend.api.model.SharesResponse
import com.geonotes.backend.support.Api
import com.geonotes.backend.support.IntegrationTest
import com.geonotes.backend.support.TestDatabase
import com.geonotes.backend.support.errorCode
import com.geonotes.backend.support.expect
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShareRequestsIntegrationTest : IntegrationTest() {
    private suspend fun Api.requestShare(from: String, to: String, ownerDevices: List<String> = listOf("$to-device-1")): HttpResponse =
        post(
            "/v1/share-requests", from,
            CreateShareRequestRequest(
                toUserId = to,
                encryptedPlace = Api.b64("opaque-ciphertext".toByteArray()),
                ownerKeys = ownerDevices.map { DeviceSealedKey(it, Api.b64("sealed-for-$it".toByteArray())) },
                recipientKeys = listOf(DeviceSealedKey("$from-device-1", Api.b64("sealed-for-$from".toByteArray()))),
                transitions = listOf("ENTER"),
                note = "Let me know when you get there",
            ),
        )

    @Test
    fun `watcher requests, friend accepts, friend's arrival is relayed to the watcher`() = apiTest {
        user("alice"); user("bob", "bob-device-1", "bob-device-2")
        befriend("alice", "bob")

        val created = requestShare("alice", "bob", listOf("bob-device-1", "bob-device-2"))
            .expect(HttpStatusCode.Created).body<ShareRequestResponse>()
        assertEquals("PENDING", created.status)
        assertEquals(setOf("fcm-bob-device-1", "fcm-bob-device-2"), push.sent.filter { it.data["type"] == "share_request" }.map { it.token }.toSet())

        val bobView = get("/v1/share-requests", "bob").expect(HttpStatusCode.OK).body<ShareRequestsResponse>()
        val incoming = bobView.incoming.single()
        assertEquals("Alice", incoming.fromDisplayName)
        assertEquals("Let me know when you get there", incoming.note)
        assertEquals(listOf("bob-device-1", "bob-device-2"), incoming.ownerKeys.map { it.deviceId })
        assertTrue(get("/v1/share-requests", "alice").body<ShareRequestsResponse>().outgoing.single().ownerKeys.isEmpty())

        push.sent.clear()
        val accepted = post("/v1/share-requests/${created.id}/accept", "bob").expect(HttpStatusCode.OK).body<AcceptShareRequestResponse>()
        assertEquals("bob", accepted.share.ownerId)
        assertEquals(listOf("alice-device-1"), accepted.share.recipients.map { it.deviceId })
        assertEquals("share_request_accepted", push.sent.single().data["type"])
        assertEquals("fcm-alice-device-1", push.sent.single().token)

        // Owner keys survive in the owner's share listing (reinstall / second device), never in the recipient's.
        val bobShares = get("/v1/shares", "bob").body<SharesResponse>()
        assertEquals(listOf("bob-device-1", "bob-device-2"), bobShares.owned.single().ownerKeys.map { it.deviceId })
        val aliceShares = get("/v1/shares", "alice").body<SharesResponse>()
        assertTrue(aliceShares.received.single().ownerKeys.isEmpty())
        assertEquals("ACCEPTED", get("/v1/share-requests", "alice").body<ShareRequestsResponse>().outgoing.single().status)

        // Bob's device evaluates the geofence locally and reports only the transition.
        push.sent.clear()
        val event = post("/v1/events", "bob", PostEventRequest(accepted.share.id, "ENTER", clock.instant().toString()))
            .expect(HttpStatusCode.Accepted).body<EventResponse>()
        assertEquals("DELIVERED", event.status)
        val relayed = push.sent.single()
        assertEquals("fcm-alice-device-1", relayed.token)
        assertEquals("friend_transition", relayed.data["type"])
        assertEquals("bob", relayed.data["fromUserId"])
    }

    @Test
    fun `authorization - no token, not a friend, not the target`() = apiTest {
        user("alice"); user("bob"); user("eve")
        befriend("alice", "bob")
        assertEquals(HttpStatusCode.Unauthorized, get("/v1/share-requests", null).status)
        assertEquals("not_a_friend", requestShare("eve", "bob").expect(HttpStatusCode.Forbidden).errorCode())

        val created = requestShare("alice", "bob").expect(HttpStatusCode.Created).body<ShareRequestResponse>()
        assertEquals("not_share_request_target", post("/v1/share-requests/${created.id}/accept", "alice").expect(HttpStatusCode.Forbidden).errorCode())
        assertEquals("share_request_not_found", post("/v1/share-requests/${created.id}/accept", "eve").expect(HttpStatusCode.NotFound).errorCode())
        assertEquals("share_request_not_found", delete("/v1/share-requests/${created.id}", "eve").expect(HttpStatusCode.NotFound).errorCode())
        assertEquals("validation_failed", post("/v1/share-requests/not-a-uuid/decline", "bob").expect(HttpStatusCode.BadRequest).errorCode())
        assertEquals(
            "share_request_device_invalid",
            requestShare("alice", "bob", listOf("eve-device-1")).expect(HttpStatusCode.BadRequest).errorCode(),
        )
    }

    @Test
    fun `decline, cancel and expiry`() = apiTest {
        user("alice"); user("bob")
        befriend("alice", "bob")
        val declined = requestShare("alice", "bob").expect(HttpStatusCode.Created).body<ShareRequestResponse>()
        assertEquals("DECLINED", post("/v1/share-requests/${declined.id}/decline", "bob").expect(HttpStatusCode.OK).body<ShareRequestResponse>().status)
        assertEquals("share_request_not_pending", post("/v1/share-requests/${declined.id}/accept", "bob").expect(HttpStatusCode.Conflict).errorCode())

        val cancelled = requestShare("alice", "bob").expect(HttpStatusCode.Created).body<ShareRequestResponse>()
        delete("/v1/share-requests/${cancelled.id}", "alice").expect(HttpStatusCode.NoContent)

        val expiring = requestShare("alice", "bob").expect(HttpStatusCode.Created).body<ShareRequestResponse>()
        clock.advance(Duration.ofDays(7).plusSeconds(1))
        assertEquals("share_request_expired", post("/v1/share-requests/${expiring.id}/accept", "bob").expect(HttpStatusCode.Conflict).errorCode())
        assertTrue(get("/v1/share-requests", "bob").body<ShareRequestsResponse>().incoming.isEmpty())
        module.shareRequestService.cleanup()
        assertEquals(0, TestDatabase.count("share_requests"))
        assertEquals(0, TestDatabase.count("share_request_keys"))
    }

    @Test
    fun `accepting counts towards the 20 active share limit`() = apiTest(maxActiveShares = 1) {
        user("alice"); user("bob")
        befriend("alice", "bob")
        createShare("bob", "alice" to "alice-device-1")
        val created = requestShare("alice", "bob").expect(HttpStatusCode.Created).body<ShareRequestResponse>()
        assertEquals("share_limit_reached", post("/v1/share-requests/${created.id}/accept", "bob").expect(HttpStatusCode.UnprocessableEntity).errorCode())
    }

    @Test
    fun `deleting the asked friend's account cascades requests, owner keys and shares`() = apiTest {
        user("alice"); user("bob")
        befriend("alice", "bob")
        val accepted = requestShare("alice", "bob").expect(HttpStatusCode.Created).body<ShareRequestResponse>()
        post("/v1/share-requests/${accepted.id}/accept", "bob").expect(HttpStatusCode.OK)
        requestShare("alice", "bob").expect(HttpStatusCode.Created)

        delete("/v1/me", "bob").expect(HttpStatusCode.NoContent)
        assertEquals(0, TestDatabase.count("share_requests"))
        assertEquals(0, TestDatabase.count("share_request_keys"))
        assertEquals(0, TestDatabase.count("share_owner_keys"))
        assertEquals(0, TestDatabase.count("shares"))
    }
}
