package com.geonotes.backend.plugins

import com.geonotes.backend.auth.PubSubTokenVerifier
import com.geonotes.backend.auth.TokenVerifier
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.principal
import io.ktor.server.response.header

const val AUTH_BEARER = "bearer"
const val AUTH_PUBSUB = "pubsub"
private const val REALM = "whenhere"

data class UserPrincipal(val uid: String)

/**
 * `Authorization: Bearer <token>` provider backed by a [TokenVerifier] (Firebase ID token in prod,
 * `dev:<uid>` in AUTH_MODE=dev).
 *
 * A small custom provider instead of Ktor's built-in `bearer {}`: that one rejects tokens that are not
 * RFC 7235 token68 (e.g. `dev:alice`, because of ':') with a 400, while we want any bad/malformed
 * credential to be a uniform JSON 401.
 */
class BearerTokenProvider(config: Config) : AuthenticationProvider(config) {
    private val verifier = config.verifier

    class Config(name: String?) : AuthenticationProvider.Config(name) {
        lateinit var verifier: TokenVerifier
    }

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val token = context.call.bearerToken()
        val uid = token?.let { verifier.verify(it) }
        if (uid != null) {
            context.principal(name, UserPrincipal(uid))
            return
        }
        val cause = if (token == null) AuthenticationFailedCause.NoCredentials else AuthenticationFailedCause.InvalidCredentials
        context.challenge("Bearer", cause) { challenge, call ->
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"$REALM\"")
            call.respondError(HttpStatusCode.Unauthorized, "unauthorized", "Missing or invalid bearer token")
            challenge.complete()
        }
    }

}

/** Principal of an authentic Pub/Sub push request (Google Play RTDN). */
data object PubSubPrincipal

/**
 * `Authorization: Bearer <OIDC JWT>` sent by a Pub/Sub push subscription, checked by a [PubSubTokenVerifier]
 * (signature via Google JWKS, issuer, audience, service-account email). Anything else → JSON 401.
 */
class PubSubTokenProvider(config: Config) : AuthenticationProvider(config) {
    private val verifier = config.verifier

    class Config(name: String?) : AuthenticationProvider.Config(name) {
        lateinit var verifier: PubSubTokenVerifier
    }

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val token = context.call.bearerToken()
        if (token != null && verifier.verify(token)) {
            context.principal(name, PubSubPrincipal)
            return
        }
        val cause = if (token == null) AuthenticationFailedCause.NoCredentials else AuthenticationFailedCause.InvalidCredentials
        context.challenge("PubSub", cause) { challenge, call ->
            call.respondError(HttpStatusCode.Unauthorized, "unauthorized", "Missing or invalid Pub/Sub push token")
            challenge.complete()
        }
    }
}

private fun ApplicationCall.bearerToken(): String? {
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    val parts = header.trim().split(' ', limit = 2)
    if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return null
    return parts[1].trim().takeIf { it.isNotEmpty() && it.length <= 8192 }
}

fun Application.configureAuth(verifier: TokenVerifier, pubSubVerifier: PubSubTokenVerifier) {
    install(Authentication) {
        register(BearerTokenProvider(BearerTokenProvider.Config(AUTH_BEARER).apply { this.verifier = verifier }))
        register(PubSubTokenProvider(PubSubTokenProvider.Config(AUTH_PUBSUB).apply { this.verifier = pubSubVerifier }))
    }
}

/** The authenticated caller's user id. Only valid inside `authenticate(AUTH_BEARER) { }` routes. */
fun ApplicationCall.callerId(): String =
    principal<UserPrincipal>()?.uid ?: error("callerId() used on an unauthenticated route")
