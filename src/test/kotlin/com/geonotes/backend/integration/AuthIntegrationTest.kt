package com.geonotes.backend.integration

import com.geonotes.backend.api.model.HealthResponse
import com.geonotes.backend.support.IntegrationTest
import com.geonotes.backend.support.errorCode
import com.geonotes.backend.support.expect
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthIntegrationTest : IntegrationTest() {
    @Test
    fun `health is public and reports database status`() = apiTest {
        val health = get("/health", uid = null).expect(HttpStatusCode.OK).body<HealthResponse>()
        assertEquals("ok", health.status)
        assertEquals("up", health.database)
    }

    @Test
    fun `requests without or with invalid bearer tokens are rejected with JSON 401`() = apiTest {
        val missing = get("/v1/me", uid = null).expect(HttpStatusCode.Unauthorized)
        assertEquals("unauthorized", missing.errorCode())

        val wrongScheme = client.get("/v1/friends") { bearerAuth("not-a-dev-token") }.expect(HttpStatusCode.Unauthorized)
        assertEquals("unauthorized", wrongScheme.errorCode())

        val emptyUid = client.post("/v1/invites") { bearerAuth("dev:") }.expect(HttpStatusCode.Unauthorized)
        assertEquals("unauthorized", emptyUid.errorCode())
    }

    @Test
    fun `authenticated but unregistered users must create a profile first`() = apiTest {
        assertEquals("user_not_registered", get("/v1/me", "ghost").expect(HttpStatusCode.NotFound).errorCode())
        assertEquals("user_not_registered", post("/v1/invites", "ghost").expect(HttpStatusCode.Conflict).errorCode())
    }

    @Test
    fun `malformed bodies and unknown routes produce consistent error JSON`() = apiTest {
        user("alice")
        assertEquals("validation_failed", put("/v1/me", "alice", mapOf("displayName" to " ")).expect(HttpStatusCode.BadRequest).errorCode())
        assertEquals("not_found", get("/v1/nope", "alice").expect(HttpStatusCode.NotFound).errorCode())
    }
}
