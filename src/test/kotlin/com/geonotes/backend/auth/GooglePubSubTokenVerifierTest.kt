package com.geonotes.backend.auth

import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.json.webtoken.JsonWebSignature
import com.google.api.client.json.webtoken.JsonWebToken
import kotlinx.coroutines.test.runTest
import java.security.KeyPair
import java.security.KeyPairGenerator
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises the real JWT checks with a locally generated RSA key standing in for Google's JWKS. */
class GooglePubSubTokenVerifierTest {
    private val audience = "https://api.example.com/v1/play/rtdn"
    private val pushSa = "play-rtdn-push@whenhere.iam.gserviceaccount.com"
    private val googleKey = rsa()
    private val verifier = GooglePubSubTokenVerifier(audience, pushSa, publicKey = googleKey.public)

    private fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun jwt(
        key: KeyPair = googleKey,
        iss: String = "https://accounts.google.com",
        aud: String = audience,
        email: String = pushSa,
        emailVerified: Boolean = true,
        expiresInSeconds: Long = 3600,
    ): String {
        val now = System.currentTimeMillis() / 1000
        val header = JsonWebSignature.Header().setAlgorithm("RS256").setType("JWT").setKeyId("test-kid")
        val payload = JsonWebToken.Payload()
            .setIssuer(iss)
            .setAudience(aud)
            .setSubject("112233445566778899000")
            .setIssuedAtTimeSeconds(now - 10)
            .setExpirationTimeSeconds(now + expiresInSeconds)
        payload["email"] = email
        payload["email_verified"] = emailVerified
        return JsonWebSignature.signUsingRsaSha256(key.private, GsonFactory.getDefaultInstance(), header, payload)
    }

    @Test
    fun `accepts a correctly signed push token for our audience and service account`() = runTest {
        assertTrue(verifier.verify(jwt()))
        assertTrue(verifier.verify(jwt(iss = "accounts.google.com")))
    }

    @Test
    fun `rejects wrong audience, issuer, email, unverified email and expired tokens`() = runTest {
        assertFalse(verifier.verify(jwt(aud = "https://evil.example.com/rtdn")), "audience")
        assertFalse(verifier.verify(jwt(iss = "https://evil.example.com")), "issuer")
        assertFalse(verifier.verify(jwt(email = "attacker@evil.iam.gserviceaccount.com")), "email")
        assertFalse(verifier.verify(jwt(emailVerified = false)), "email_verified")
        assertFalse(verifier.verify(jwt(expiresInSeconds = -3600)), "expired")
    }

    @Test
    fun `rejects tokens signed by another key and garbage`() = runTest {
        assertFalse(verifier.verify(jwt(key = rsa())))
        assertFalse(verifier.verify("not-a-jwt"))
        assertFalse(verifier.verify("a.b.c"))
        assertFalse(DisabledPubSubTokenVerifier.verify(jwt()))
    }
}
