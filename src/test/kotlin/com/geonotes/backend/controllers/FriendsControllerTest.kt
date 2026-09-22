package com.geonotes.backend.controllers

import com.geonotes.backend.domain.ConflictException
import com.geonotes.backend.domain.ForbiddenException
import com.geonotes.backend.domain.ValidationException
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FriendsControllerTest {
    @Test
    fun `accepting an invite creates a mutual friendship`() = runTest {
        val f = ControllerFixture()
        f.user("alice"); f.user("bob")
        val invite = f.friendsController.createInvite("alice")
        assertEquals(8, invite.code.length)

        val friend = f.friendsController.acceptInvite("bob", invite.code.lowercase())
        assertEquals("alice", friend.userId)
        assertEquals(listOf("bob"), f.friendsController.list("alice").friends.map { it.userId })
        assertEquals(listOf("alice"), f.friendsController.list("bob").friends.map { it.userId })
    }

    @Test
    fun `invite rules - self, reuse, expiry`() = runTest {
        val f = ControllerFixture()
        f.user("alice"); f.user("bob"); f.user("carol")
        val invite = f.friendsController.createInvite("alice")
        assertFailsWith<ValidationException> { f.friendsController.acceptInvite("alice", invite.code) }
        f.friendsController.acceptInvite("bob", invite.code)
        assertFailsWith<ConflictException> { f.friendsController.acceptInvite("carol", invite.code) }

        val late = f.friendsController.createInvite("alice")
        f.clock.advance(Duration.ofHours(49))
        val e = assertFailsWith<ConflictException> { f.friendsController.acceptInvite("carol", late.code) }
        assertEquals("invite_expired", e.code)
    }

    @Test
    fun `friend device keys are only visible to friends and unfriend revokes shares`() = runTest {
        val f = ControllerFixture()
        f.user("alice"); f.user("bob"); f.user("mallory")
        f.befriend("alice", "bob")

        assertEquals(listOf("bob-device-1"), f.friendsController.friendDevices("alice", "bob").devices.map { it.deviceId })
        assertFailsWith<ForbiddenException> { f.friendsController.friendDevices("mallory", "bob") }

        f.sharesController.create("bob", SharesControllerTest.shareTo("alice"))
        f.friendsController.unfriend("alice", "bob") // either side may unfriend
        assertTrue(f.friendsController.list("bob").friends.isEmpty())
        assertTrue(f.sharesController.list("alice").received.isEmpty())
        assertTrue(f.sharesController.list("bob").owned.single().recipients.isEmpty())
    }
}
