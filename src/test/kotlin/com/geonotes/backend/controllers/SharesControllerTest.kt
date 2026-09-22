package com.geonotes.backend.controllers

import com.geonotes.backend.api.model.CreateShareRequest
import com.geonotes.backend.api.model.ShareRecipientRequest
import com.geonotes.backend.api.model.UpdateShareRequest
import com.geonotes.backend.domain.ForbiddenException
import com.geonotes.backend.domain.LimitExceededException
import com.geonotes.backend.domain.ValidationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SharesControllerTest {
    companion object {
        fun shareTo(userId: String, deviceId: String = "$userId-device-1", transitions: List<String> = listOf("ENTER")) = CreateShareRequest(
            encryptedPlace = ControllerFixture.b64("ciphertext-of-place".toByteArray()),
            recipients = listOf(ShareRecipientRequest(userId, deviceId, ControllerFixture.b64(ByteArray(48) { 1 }))),
            transitions = transitions,
        )
    }

    @Test
    fun `create maps domain share into response view state`() = runTest {
        val f = ControllerFixture()
        f.user("alice"); f.user("bob")
        f.befriend("alice", "bob")

        val created = f.sharesController.create("alice", shareTo("bob", transitions = listOf("exit", "ENTER")))
        assertEquals("alice", created.ownerId)
        assertEquals(listOf("ENTER", "EXIT"), created.transitions)
        assertEquals(ControllerFixture.b64("ciphertext-of-place".toByteArray()), created.encryptedPlace)
        assertEquals("bob-device-1", created.recipients.single().deviceId)

        val bobView = f.sharesController.list("bob")
        assertEquals("Alice", bobView.received.single().ownerDisplayName)
    }

    @Test
    fun `recipient must be an accepted friend and own the device`() = runTest {
        val f = ControllerFixture()
        f.user("alice"); f.user("bob"); f.user("stranger")
        f.befriend("alice", "bob")
        assertFailsWith<ForbiddenException> { f.sharesController.create("alice", shareTo("stranger")) }
        assertFailsWith<ValidationException> { f.sharesController.create("alice", shareTo("bob", deviceId = "stranger-device-1")) }
        assertFailsWith<ValidationException> { f.sharesController.create("alice", shareTo("bob").copy(encryptedPlace = "%%%")) }
    }

    @Test
    fun `active share budget is enforced on create and re-activate`() = runTest {
        val f = ControllerFixture(maxActiveShares = 2)
        f.user("alice"); f.user("bob")
        f.befriend("alice", "bob")
        val first = f.sharesController.create("alice", shareTo("bob"))
        f.sharesController.create("alice", shareTo("bob"))
        assertFailsWith<LimitExceededException> { f.sharesController.create("alice", shareTo("bob")) }

        val paused = f.sharesController.update("alice", first.id, UpdateShareRequest(active = false, pausedUntil = null, pausedUntilSet = false))
        assertFalse(paused.active)
        f.sharesController.create("alice", shareTo("bob"))
        assertFailsWith<LimitExceededException> {
            f.sharesController.update("alice", first.id, UpdateShareRequest(active = true, pausedUntil = null, pausedUntilSet = false))
        }
    }

    @Test
    fun `only the owner may modify a share`() = runTest {
        val f = ControllerFixture()
        f.user("alice"); f.user("bob")
        f.befriend("alice", "bob")
        val share = f.sharesController.create("alice", shareTo("bob"))
        assertFailsWith<ForbiddenException> { f.sharesController.delete("bob", share.id) }

        val updated = f.sharesController.update(
            "alice", share.id, UpdateShareRequest(active = null, pausedUntil = "2026-06-02T00:00:00Z", pausedUntilSet = true),
        )
        assertEquals("2026-06-02T00:00:00Z", updated.pausedUntil)
        val cleared = f.sharesController.update("alice", share.id, UpdateShareRequest(active = null, pausedUntil = null, pausedUntilSet = true))
        assertNull(cleared.pausedUntil)
    }
}
