package com.geonotes.backend.integration

import com.geonotes.backend.api.model.DeviceKeysResponse
import com.geonotes.backend.api.model.FriendsResponse
import com.geonotes.backend.api.model.InviteResponse
import com.geonotes.backend.api.model.SharesResponse
import com.geonotes.backend.config.RateLimitConfig
import com.geonotes.backend.support.IntegrationTest
import com.geonotes.backend.support.errorCode
import com.geonotes.backend.support.expect
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FriendsIntegrationTest : IntegrationTest() {
    @Test
    fun `invite accept creates mutual friendship`() = apiTest {
        user("alice"); user("bob")
        val invite = post("/v1/invites", "alice").expect(HttpStatusCode.Created).body<InviteResponse>()
        assertEquals(Instant.parse(invite.expiresAt), clock.instant().plus(Duration.ofHours(48)))

        val friend = post("/v1/invites/${invite.code}/accept", "bob").expect(HttpStatusCode.OK).body<com.geonotes.backend.api.model.FriendResponse>()
        assertEquals("alice", friend.userId)
        assertEquals("Alice", friend.displayName)

        assertEquals(listOf("bob"), get("/v1/friends", "alice").body<FriendsResponse>().friends.map { it.userId })
        assertEquals(listOf("alice"), get("/v1/friends", "bob").body<FriendsResponse>().friends.map { it.userId })
    }

    @Test
    fun `invites are single use, not self-acceptable and expire after 48h`() = apiTest {
        user("alice"); user("bob"); user("carol")
        val invite = post("/v1/invites", "alice").body<InviteResponse>()
        assertEquals("invite_self", post("/v1/invites/${invite.code}/accept", "alice").expect(HttpStatusCode.BadRequest).errorCode())
        post("/v1/invites/${invite.code}/accept", "bob").expect(HttpStatusCode.OK)
        assertEquals("invite_used", post("/v1/invites/${invite.code}/accept", "carol").expect(HttpStatusCode.Conflict).errorCode())
        assertEquals("invite_not_found", post("/v1/invites/ZZZZZZZZ/accept", "carol").expect(HttpStatusCode.NotFound).errorCode())

        val expiring = post("/v1/invites", "alice").body<InviteResponse>()
        clock.advance(Duration.ofHours(48).plusSeconds(1))
        assertEquals("invite_expired", post("/v1/invites/${expiring.code}/accept", "carol").expect(HttpStatusCode.Conflict).errorCode())
    }

    @Test
    fun `friend device public keys are only exposed to accepted friends`() = apiTest {
        user("alice"); user("bob", "bob-phone-01", "bob-tablet-1"); user("mallory")
        befriend("alice", "bob")
        val keys = get("/v1/friends/bob/devices", "alice").expect(HttpStatusCode.OK).body<DeviceKeysResponse>()
        assertEquals(setOf("bob-phone-01", "bob-tablet-1"), keys.devices.map { it.deviceId }.toSet())
        assertTrue(keys.devices.all { it.publicKey.isNotBlank() })
        assertEquals("not_a_friend", get("/v1/friends/bob/devices", "mallory").expect(HttpStatusCode.Forbidden).errorCode())
    }

    @Test
    fun `either side can unfriend, which revokes shares between them`() = apiTest {
        user("alice"); user("bob")
        befriend("alice", "bob")
        createShare("alice", "bob" to "bob-device-1")
        createShare("bob", "alice" to "alice-device-1")

        delete("/v1/friends/alice", "bob").expect(HttpStatusCode.NoContent)
        assertTrue(get("/v1/friends", "alice").body<FriendsResponse>().friends.isEmpty())
        assertTrue(get("/v1/shares", "alice").body<SharesResponse>().received.isEmpty())
        assertTrue(get("/v1/shares", "bob").body<SharesResponse>().received.isEmpty())
        assertTrue(get("/v1/shares", "alice").body<SharesResponse>().owned.single().recipients.isEmpty())
        assertEquals("friend_not_found", delete("/v1/friends/alice", "bob").expect(HttpStatusCode.NotFound).errorCode())
    }

    @Test
    fun `invite endpoints are rate limited per user`() = apiTest(rateLimits = RateLimitConfig(eventsPerMinute = 100, invitesPerMinute = 2)) {
        user("alice"); user("bob")
        post("/v1/invites", "alice").expect(HttpStatusCode.Created)
        post("/v1/invites", "alice").expect(HttpStatusCode.Created)
        val limited = post("/v1/invites", "alice").expect(HttpStatusCode.TooManyRequests)
        assertEquals("rate_limited", limited.errorCode())
        // A different user has an independent bucket.
        post("/v1/invites", "bob").expect(HttpStatusCode.Created)
    }
}
