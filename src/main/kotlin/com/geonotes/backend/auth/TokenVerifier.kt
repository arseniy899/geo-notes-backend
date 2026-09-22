package com.geonotes.backend.auth

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/** Verifies a bearer token and returns the authenticated user id, or null if invalid. */
interface TokenVerifier {
    suspend fun verify(token: String): String?
}

/** Production verifier: Firebase ID tokens (signature, audience, expiry, revocation not checked for latency). */
class FirebaseTokenVerifier(firebaseApp: FirebaseApp) : TokenVerifier {
    private val log = LoggerFactory.getLogger(FirebaseTokenVerifier::class.java)
    private val auth = FirebaseAuth.getInstance(firebaseApp)

    override suspend fun verify(token: String): String? = withContext(Dispatchers.IO) {
        try {
            auth.verifyIdToken(token).uid
        } catch (e: FirebaseAuthException) {
            log.debug("Rejected Firebase ID token: {}", e.authErrorCode)
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/**
 * Local/testing only (AUTH_MODE=dev): accepts `dev:<uid>` and authenticates as `<uid>`.
 * Never enable in production.
 */
class DevTokenVerifier : TokenVerifier {
    override suspend fun verify(token: String): String? {
        if (!token.startsWith(PREFIX)) return null
        val uid = token.removePrefix(PREFIX)
        return uid.takeIf { it.isNotBlank() && it.length <= 128 && it.all { c -> c.isLetterOrDigit() || c in "-_." } }
    }

    companion object {
        const val PREFIX = "dev:"
    }
}
