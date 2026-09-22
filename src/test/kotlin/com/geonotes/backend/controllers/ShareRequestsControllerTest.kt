package com.geonotes.backend.controllers

import com.geonotes.backend.api.model.CreateShareRequestRequest
import com.geonotes.backend.api.model.DeviceSealedKey
import com.geonotes.backend.domain.ConflictException
import com.geonotes.backend.domain.ForbiddenException
import com.geonotes.backend.domain.LimitExceededException
import com.geonotes.backend.domain.NotFoundException
import com.geonotes.backend.domain.ValidationException
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareRequestsControllerTest {
    companion object {
        fun requestTo(
            target: String,
            from: String,
            targetDevice: String = "$target-device-1",
            fromDevice: String = "$from-device-1",
            transitions: List<String> = listOf("ENTER"),
        ) = CreateShareRequestRequest(
            toUserId = target,
            encryptedPlace = ControllerFixture.b64("ciphertext-of-place".toByteArray()),
            ownerKeys = listOf(DeviceSealedKey(targetDevice, ControllerFixture.b64("owner-key-$targetDevice".toByteArray()))),
            recipientKeys = listOf(DeviceSealedKey(fromDevice, ControllerFixture.b64("recipient-key-$fromDevice".toByteArray()))),
            transitions = transitions,
        )
    }

    private suspend fun friends(): ControllerFixture = ControllerFixture().apply {
        user("alice"); user("bob")
        befriend("alice", "bob")
    }

    @Test
    fun `create stores a pending request, pushes share_request to the target and lists both directions`() = runTest {
        val f = friends()
        val created = f.shareRequestsController.create("alice", requestTo("bob", "alice", transitions = listOf("exit", "ENTER")))
        assertEquals("PENDING", created.status)
        assertEquals("OUTGOING", created.direction)
        assertEquals("Alice", created.fromDisplayName)
        assertEquals("Bob", created.toDisplayName)
        assertEquals(listOf("ENTER", "EXIT"), created.transitions)
        assertTrue(created.ownerKeys.isEmpty(), "requester never gets the friend's keys back")
        assertEquals("2026-06-08T10:00:00Z", created.expiresAt) // 7-day TTL

        val push = f.push.sent.single()
        assertEquals("fcm-bob-device-1", push.token)
        assertEquals(mapOf("type" to "share_request", "requestId" to created.id, "fromUserId" to "alice"), push.data)

        val bob = f.shareRequestsController.list("bob")
        assertTrue(bob.outgoing.isEmpty())
        val incoming = bob.incoming.single()
        assertEquals("INCOMING", incoming.direction)
        assertEquals(listOf("bob-device-1"), incoming.ownerKeys.map { it.deviceId })
        assertEquals(created.id, f.shareRequestsController.list("alice").outgoing.single().id)
    }

    @Test
    fun `create requires friendship, own devices and valid input`() = runTest {
        val f = friends()
        f.user("stranger")
        assertFailsWithCode<ForbiddenException>("not_a_friend") { f.shareRequestsController.create("alice", requestTo("stranger", "alice")) }
        assertFailsWithCode<ValidationException>("share_request_self") { f.shareRequestsController.create("alice", requestTo("alice", "alice")) }
        assertFailsWithCode<ValidationException>("share_request_device_invalid") {
            f.shareRequestsController.create("alice", requestTo("bob", "alice", targetDevice = "stranger-device-1"))
        }
        assertFailsWithCode<ValidationException>("share_request_device_invalid") {
            f.shareRequestsController.create("alice", requestTo("bob", "alice", fromDevice = "bob-device-1"))
        }
        assertFailsWithCode<ValidationException>("validation_failed") {
            f.shareRequestsController.create("alice", requestTo("bob", "alice").copy(note = "x".repeat(141)))
        }
        assertFailsWithCode<ValidationException>("validation_failed") {
            f.shareRequestsController.create("alice", requestTo("bob", "alice").copy(encryptedPlace = "%%%"))
        }
        assertTrue(f.shareRequests.requests.isEmpty())
    }

    @Test
    fun `accept creates an active share owned by the target with the requester as recipient`() = runTest {
        val f = friends()
        val req = f.shareRequestsController.create("alice", requestTo("bob", "alice"))
        f.push.sent.clear()

        val accepted = f.shareRequestsController.accept("bob", req.id)
        assertEquals("ACCEPTED", accepted.request.status)
        assertEquals(accepted.share.id, accepted.request.shareId)
        assertEquals("bob", accepted.share.ownerId)
        assertEquals(listOf("alice" to "alice-device-1"), accepted.share.recipients.map { it.userId to it.deviceId })
        assertEquals(listOf("bob-device-1"), accepted.share.ownerKeys.map { it.deviceId })
        assertEquals(ControllerFixture.b64("ciphertext-of-place".toByteArray()), accepted.share.encryptedPlace)

        val push = f.push.sent.single()
        assertEquals("fcm-alice-device-1", push.token)
        assertEquals("share_request_accepted", push.data["type"])
        assertEquals(accepted.share.id, push.data["shareId"])

        // Owner sees their keys; the recipient view never exposes them.
        assertEquals(1, f.sharesController.list("bob").owned.single().ownerKeys.size)
        assertTrue(f.sharesController.list("alice").received.single().ownerKeys.isEmpty())

        assertFailsWithCode<ConflictException>("share_request_not_pending") { f.shareRequestsController.accept("bob", req.id) }
    }

    @Test
    fun `only the target may answer, strangers get not found`() = runTest {
        val f = friends()
        f.user("eve")
        val req = f.shareRequestsController.create("alice", requestTo("bob", "alice"))
        assertFailsWithCode<ForbiddenException>("not_share_request_target") { f.shareRequestsController.accept("alice", req.id) }
        assertFailsWithCode<NotFoundException>("share_request_not_found") { f.shareRequestsController.accept("eve", req.id) }
        assertFailsWithCode<NotFoundException>("share_request_not_found") { f.shareRequestsController.decline("eve", req.id) }
        assertFailsWithCode<ForbiddenException>("not_share_requester") { f.shareRequestsController.cancel("bob", req.id) }
        assertFailsWithCode<NotFoundException>("share_request_not_found") { f.shareRequestsController.cancel("eve", req.id) }
    }

    @Test
    fun `decline and cancel`() = runTest {
        val f = friends()
        val first = f.shareRequestsController.create("alice", requestTo("bob", "alice"))
        val declined = f.shareRequestsController.decline("bob", first.id)
        assertEquals("DECLINED", declined.status)
        assertNull(declined.shareId)
        assertFailsWithCode<ConflictException>("share_request_not_pending") { f.shareRequestsController.accept("bob", first.id) }
        assertTrue(f.shares.shares.isEmpty())

        val second = f.shareRequestsController.create("alice", requestTo("bob", "alice"))
        f.shareRequestsController.cancel("alice", second.id)
        assertTrue(f.shareRequestsController.list("bob").incoming.none { it.id == second.id })
    }

    @Test
    fun `expired requests cannot be accepted and are cleaned up`() = runTest {
        val f = friends()
        val req = f.shareRequestsController.create("alice", requestTo("bob", "alice"))
        f.clock.advance(Duration.ofDays(7))
        assertFailsWithCode<ConflictException>("share_request_expired") { f.shareRequestsController.accept("bob", req.id) }
        assertTrue(f.shareRequestsController.list("bob").incoming.isEmpty())
        assertEquals(1, f.shareRequestService.cleanup())
        assertTrue(f.shareRequests.requests.isEmpty())
    }

    @Test
    fun `accept counts towards the owner's active share limit`() = runTest {
        val f = ControllerFixture(maxActiveShares = 1)
        f.user("alice"); f.user("bob")
        f.befriend("alice", "bob")
        f.sharesController.create("bob", SharesControllerTest.shareTo("alice"))
        val req = f.shareRequestsController.create("alice", requestTo("bob", "alice"))
        assertFailsWithCode<LimitExceededException>("share_limit_reached") { f.shareRequestsController.accept("bob", req.id) }
        assertEquals("PENDING", f.shareRequestsController.list("bob").incoming.single().status)
    }

    @Test
    fun `unfriending drops pending requests and accept after unfriend is forbidden`() = runTest {
        val f = friends()
        val req = f.shareRequestsController.create("alice", requestTo("bob", "alice"))
        f.friendsController.unfriend("bob", "alice")
        assertTrue(f.shareRequestsController.list("bob").incoming.isEmpty())
        assertFailsWithCode<NotFoundException>("share_request_not_found") { f.shareRequestsController.accept("bob", req.id) }
    }

    private suspend inline fun <reified T : com.geonotes.backend.domain.DomainException> assertFailsWithCode(code: String, block: () -> Unit) {
        val e = kotlin.test.assertFailsWith<T> { block() }
        assertEquals(code, e.code)
    }
}
