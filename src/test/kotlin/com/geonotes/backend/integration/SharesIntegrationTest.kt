package com.geonotes.backend.integration

import com.geonotes.backend.api.model.ShareResponse
import com.geonotes.backend.api.model.SharesResponse
import com.geonotes.backend.support.Api
import com.geonotes.backend.support.IntegrationTest
import com.geonotes.backend.support.errorCode
import com.geonotes.backend.support.expect
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SharesIntegrationTest : IntegrationTest() {
    @Test
    fun `recipients must be accepted friends and own the referenced device`() = apiTest {
        user("alice"); user("bob"); user("stranger")
        befriend("alice", "bob")
        assertEquals("recipient_not_friend", share("alice", "stranger" to "stranger-device-1").expect(HttpStatusCode.Forbidden).errorCode())
        assertEquals("recipient_device_invalid", share("alice", "bob" to "stranger-device-1").expect(HttpStatusCode.BadRequest).errorCode())
        assertEquals("recipient_is_owner", share("alice", "alice" to "alice-device-1").expect(HttpStatusCode.BadRequest).errorCode())
        share("alice", "bob" to "bob-device-1").expect(HttpStatusCode.Created)
    }

    @Test
    fun `max 20 active shares per owner`() = apiTest {
        user("alice"); user("bob")
        befriend("alice", "bob")
        val shares = (1..20).map { createShare("alice", "bob" to "bob-device-1") }
        assertEquals("share_limit_reached", share("alice", "bob" to "bob-device-1").expect(HttpStatusCode.UnprocessableEntity).errorCode())

        // Deactivating one frees a geofence slot; re-activating while full is refused.
        patch("/v1/shares/${shares[0].id}", "alice", buildJsonObject { put("active", false) }).expect(HttpStatusCode.OK)
        createShare("alice", "bob" to "bob-device-1")
        assertEquals(
            "share_limit_reached",
            patch("/v1/shares/${shares[0].id}", "alice", buildJsonObject { put("active", true) }).expect(HttpStatusCode.UnprocessableEntity).errorCode(),
        )
    }

    @Test
    fun `list returns owned and received shares with per-caller sealed keys`() = apiTest {
        user("alice"); user("bob", "bob-device-1", "bob-device-2"); user("carol")
        befriend("alice", "bob"); befriend("alice", "carol")
        val created = createShare(
            "alice", "bob" to "bob-device-1", "bob" to "bob-device-2", "carol" to "carol-device-1", transitions = listOf("ENTER", "EXIT"),
        )
        assertEquals(Api.b64("opaque-ciphertext".toByteArray()), created.encryptedPlace)

        val alice = get("/v1/shares", "alice").body<SharesResponse>()
        assertEquals(3, alice.owned.single().recipients.size)

        val bob = get("/v1/shares", "bob").body<SharesResponse>().received.single()
        assertEquals("Alice", bob.ownerDisplayName)
        assertEquals(setOf("bob-device-1", "bob-device-2"), bob.recipients.map { it.deviceId }.toSet())
        assertEquals(listOf("ENTER", "EXIT"), bob.transitions)
        assertEquals(listOf("carol-device-1"), get("/v1/shares", "carol").body<SharesResponse>().received.single().recipients.map { it.deviceId })
    }

    @Test
    fun `patch pauses and clears pause, only owner may modify or delete`() = apiTest {
        user("alice"); user("bob"); user("eve")
        befriend("alice", "bob")
        val s = createShare("alice", "bob" to "bob-device-1")

        val paused = patch("/v1/shares/${s.id}", "alice", buildJsonObject { put("pausedUntil", "2026-06-02T08:00:00Z") })
            .expect(HttpStatusCode.OK).body<ShareResponse>()
        assertEquals("2026-06-02T08:00:00Z", paused.pausedUntil)
        val cleared = patch("/v1/shares/${s.id}", "alice", buildJsonObject { put("pausedUntil", JsonNull) }).body<ShareResponse>()
        assertNull(cleared.pausedUntil)
        assertEquals("bad_request", patch("/v1/shares/${s.id}", "alice", buildJsonObject { put("active", JsonPrimitive("yes")) }).expect(HttpStatusCode.BadRequest).errorCode())

        assertEquals("not_share_owner", patch("/v1/shares/${s.id}", "bob", buildJsonObject { put("active", false) }).expect(HttpStatusCode.Forbidden).errorCode())
        assertEquals("share_not_found", delete("/v1/shares/${s.id}", "eve").expect(HttpStatusCode.NotFound).errorCode())
        delete("/v1/shares/${s.id}", "alice").expect(HttpStatusCode.NoContent)
        assertFalse(get("/v1/shares", "bob").body<SharesResponse>().received.any { it.id == s.id })
    }
}
