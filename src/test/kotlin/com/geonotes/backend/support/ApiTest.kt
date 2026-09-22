package com.geonotes.backend.support

import com.geonotes.backend.AppModule
import com.geonotes.backend.api.model.CreateShareRequest
import com.geonotes.backend.api.model.FriendResponse
import com.geonotes.backend.api.model.InviteResponse
import com.geonotes.backend.api.model.RegisterDeviceRequest
import com.geonotes.backend.api.model.ShareRecipientRequest
import com.geonotes.backend.api.model.ShareResponse
import com.geonotes.backend.api.model.UpsertMeRequest
import com.geonotes.backend.auth.DevTokenVerifier
import com.geonotes.backend.billing.StubPlayPurchaseVerifier
import com.geonotes.backend.config.AppConfig
import com.geonotes.backend.config.AuthMode
import com.geonotes.backend.config.DatabaseConfig
import com.geonotes.backend.config.RateLimitConfig
import com.geonotes.backend.module
import com.geonotes.backend.plugins.ApiJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.assertEquals

/** Base for Ktor `testApplication` integration tests against a real PostgreSQL. */
abstract class IntegrationTest {
    @BeforeTest
    fun resetDatabase() = TestDatabase.reset()

    protected fun apiTest(
        rateLimits: RateLimitConfig = RateLimitConfig(eventsPerMinute = 10_000, invitesPerMinute = 10_000),
        maxActiveShares: Int = 20,
        block: suspend Api.() -> Unit,
    ) = testApplication {
        val clock = MutableClock()
        val push = FakePushSender()
        val config = AppConfig(
            database = DatabaseConfig("unused", "unused", "unused"),
            authMode = AuthMode.DEV,
            rateLimits = rateLimits,
            maxActiveSharesPerOwner = maxActiveShares,
            cleanupInterval = null,
        )
        val appModule = AppModule(config, TestDatabase.database, DevTokenVerifier(), push, StubPlayPurchaseVerifier(clock, true), clock)
        application { module(appModule) }
        val client = createClient { install(ContentNegotiation) { json(ApiJson) } }
        Api(client, push, clock, appModule).block()
    }
}

class Api(val client: HttpClient, val push: FakePushSender, val clock: MutableClock, val module: AppModule) {
    suspend fun get(path: String, uid: String?): HttpResponse = client.get(path) { uid?.let { bearerAuth("dev:$it") } }

    suspend fun delete(path: String, uid: String): HttpResponse = client.delete(path) { bearerAuth("dev:$uid") }

    suspend fun post(path: String, uid: String, body: Any? = null): HttpResponse = client.post(path) {
        bearerAuth("dev:$uid")
        if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
    }

    suspend fun put(path: String, uid: String, body: Any): HttpResponse = client.put(path) {
        bearerAuth("dev:$uid"); contentType(ContentType.Application.Json); setBody(body)
    }

    suspend fun patch(path: String, uid: String, body: JsonObject): HttpResponse = client.patch(path) {
        bearerAuth("dev:$uid"); contentType(ContentType.Application.Json); setBody(body)
    }

    /** Registers a profile and one device (`<uid>-device-1`, FCM token `fcm-<uid>-device-1`). */
    suspend fun user(uid: String, vararg deviceIds: String = arrayOf("$uid-device-1")) {
        put("/v1/me", uid, UpsertMeRequest(uid.replaceFirstChar { it.uppercase() })).expect(HttpStatusCode.OK)
        deviceIds.forEach { device(uid, it) }
    }

    suspend fun device(uid: String, deviceId: String) =
        post("/v1/devices", uid, RegisterDeviceRequest(deviceId, "fcm-$deviceId", b64(ByteArray(32) { 3 }), "ANDROID")).expect(HttpStatusCode.OK)

    suspend fun befriend(inviter: String, accepter: String): FriendResponse {
        val invite = post("/v1/invites", inviter).expect(HttpStatusCode.Created).body<InviteResponse>()
        return post("/v1/invites/${invite.code}/accept", accepter).expect(HttpStatusCode.OK).body()
    }

    suspend fun share(owner: String, vararg recipients: Pair<String, String>, transitions: List<String> = listOf("ENTER")): HttpResponse =
        post(
            "/v1/shares", owner,
            CreateShareRequest(
                encryptedPlace = b64("opaque-ciphertext".toByteArray()),
                recipients = recipients.map { (u, d) -> ShareRecipientRequest(u, d, b64("sealed-for-$d".toByteArray())) },
                transitions = transitions,
            ),
        )

    suspend fun createShare(owner: String, vararg recipients: Pair<String, String>, transitions: List<String> = listOf("ENTER")): ShareResponse =
        share(owner, *recipients, transitions = transitions).expect(HttpStatusCode.Created).body()

    companion object {
        fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    }
}

suspend fun HttpResponse.expect(status: HttpStatusCode): HttpResponse {
    assertEquals(status, this.status, "Unexpected status; body=${bodyAsText()}")
    return this
}

/** Asserts the consistent error envelope `{"error":{"code":...}}` and returns the code. */
suspend fun HttpResponse.errorCode(): String =
    ApiJson.parseToJsonElement(bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
