package com.geonotes.backend.auth

import com.google.auth.oauth2.TokenVerifier as GoogleTokenVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.security.PublicKey

/**
 * Verifies the OIDC JWT that a Pub/Sub **push** subscription sends as `Authorization: Bearer <jwt>`.
 * Returns true only for an authentic token meant for us.
 */
interface PubSubTokenVerifier {
    suspend fun verify(token: String): Boolean
}

/** Used when RTDN is not configured (RTDN_AUDIENCE / RTDN_PUSH_SERVICE_ACCOUNT unset): rejects everything. */
object DisabledPubSubTokenVerifier : PubSubTokenVerifier {
    override suspend fun verify(token: String): Boolean = false
}

/**
 * Production verifier for Pub/Sub push tokens, following
 * https://cloud.google.com/pubsub/docs/authenticate-push-subscriptions:
 * - RS256 signature against Google's JWKS (`https://www.googleapis.com/oauth2/v3/certs`, cached by the library),
 * - `exp`/`iat` validity, `aud` == [audience],
 * - `iss` ∈ {`https://accounts.google.com`, `accounts.google.com`},
 * - `email` == [serviceAccountEmail] and `email_verified` == true.
 *
 * [publicKey] overrides JWKS lookup (tests only).
 */
class GooglePubSubTokenVerifier(
    private val audience: String,
    private val serviceAccountEmail: String,
    publicKey: PublicKey? = null,
) : PubSubTokenVerifier {
    private val log = LoggerFactory.getLogger(GooglePubSubTokenVerifier::class.java)
    private val delegate: GoogleTokenVerifier = GoogleTokenVerifier.newBuilder()
        .setAudience(audience)
        .apply { if (publicKey != null) setPublicKey(publicKey) else setCertificatesLocation(GOOGLE_CERTS_URL) }
        .build()

    override suspend fun verify(token: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = delegate.verify(token).payload
            val issuerOk = payload.issuer in ISSUERS
            val emailOk = payload["email"] == serviceAccountEmail && payload["email_verified"] == true
            if (!issuerOk || !emailOk) log.warn("Rejected Pub/Sub push token (issuerOk={}, emailOk={})", issuerOk, emailOk)
            issuerOk && emailOk
        } catch (e: GoogleTokenVerifier.VerificationException) {
            log.warn("Rejected Pub/Sub push token: {}", e.message)
            false
        } catch (e: IllegalArgumentException) {
            // Malformed JWT (not three base64url parts, bad JSON…).
            false
        } catch (e: java.io.IOException) {
            false
        }
    }

    companion object {
        const val GOOGLE_CERTS_URL = "https://www.googleapis.com/oauth2/v3/certs"
        val ISSUERS = setOf("https://accounts.google.com", "accounts.google.com")
    }
}
