package com.geonotes.backend.integration

import com.geonotes.backend.api.model.EntitlementResponse
import com.geonotes.backend.api.model.FriendsResponse
import com.geonotes.backend.api.model.MeResponse
import com.geonotes.backend.api.model.PostEventRequest
import com.geonotes.backend.api.model.SharesResponse
import com.geonotes.backend.api.model.VerifyPurchaseRequest
import com.geonotes.backend.support.IntegrationTest
import com.geonotes.backend.support.TestDatabase
import com.geonotes.backend.support.errorCode
import com.geonotes.backend.support.expect
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountDeletionIntegrationTest : IntegrationTest() {
    @Test
    fun `deleting the account cascades every row belonging to the user`() = apiTest {
        user("alice"); user("bob")
        befriend("alice", "bob")
        post("/v1/invites", "alice").expect(HttpStatusCode.Created) // pending invite
        val owned = createShare("alice", "bob" to "bob-device-1")
        createShare("bob", "alice" to "alice-device-1")
        post("/v1/events", "alice", PostEventRequest(owned.id, "ENTER", clock.instant().toString())).expect(HttpStatusCode.Accepted)
        post("/v1/entitlements/verify", "alice", VerifyPurchaseRequest("test-valid-alice", "pro_yearly")).expect(HttpStatusCode.OK)

        delete("/v1/me", "alice").expect(HttpStatusCode.NoContent)

        assertEquals(0, TestDatabase.count("users", "id = 'alice'"))
        assertEquals(0, TestDatabase.count("devices", "user_id = 'alice'"))
        assertEquals(0, TestDatabase.count("invites", "inviter_id = 'alice' OR accepted_by = 'alice'"))
        assertEquals(0, TestDatabase.count("friendships", "user_a = 'alice' OR user_b = 'alice'"))
        assertEquals(0, TestDatabase.count("shares", "owner_id = 'alice'"))
        assertEquals(0, TestDatabase.count("share_recipients", "user_id = 'alice'"))
        assertEquals(0, TestDatabase.count("events", "owner_id = 'alice'"))
        assertEquals(0, TestDatabase.count("entitlements", "user_id = 'alice'"))

        // Bob's side is consistent and his own data survives.
        assertTrue(get("/v1/friends", "bob").body<FriendsResponse>().friends.isEmpty())
        val bobShares = get("/v1/shares", "bob").body<SharesResponse>()
        assertTrue(bobShares.received.isEmpty())
        assertTrue(bobShares.owned.single().recipients.isEmpty())
        assertEquals(1, TestDatabase.count("devices", "user_id = 'bob'"))

        assertEquals("user_not_registered", get("/v1/me", "alice").expect(HttpStatusCode.NotFound).errorCode())
    }

    @Test
    fun `entitlement verification is stored per user and token cannot be reused`() = apiTest {
        user("alice"); user("bob")
        val ent = post("/v1/entitlements/verify", "alice", VerifyPurchaseRequest("test-valid-123", "pro_monthly"))
            .expect(HttpStatusCode.OK).body<EntitlementResponse>()
        assertTrue(ent.pro)
        assertTrue(get("/v1/me", "alice").body<MeResponse>().entitlement.pro)

        val bogus = post("/v1/entitlements/verify", "bob", VerifyPurchaseRequest("garbage", "pro_monthly")).body<EntitlementResponse>()
        assertFalse(bogus.pro)
        assertEquals(
            "purchase_token_in_use",
            post("/v1/entitlements/verify", "bob", VerifyPurchaseRequest("test-valid-123", "pro_monthly")).expect(HttpStatusCode.Conflict).errorCode(),
        )
    }
}
