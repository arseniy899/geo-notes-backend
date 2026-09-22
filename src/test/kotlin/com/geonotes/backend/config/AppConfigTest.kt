package com.geonotes.backend.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppConfigTest {
    private val base = mapOf("DATABASE_PASSWORD" to "x")

    @Test
    fun `defaults to firebase auth`() {
        assertEquals(AuthMode.FIREBASE, AppConfig.fromEnv(base).authMode)
    }

    @Test
    fun `dev auth is refused in production`() {
        assertEquals(AuthMode.DEV, AppConfig.fromEnv(base + ("AUTH_MODE" to "dev")).authMode)
        assertFailsWith<IllegalStateException> { AppConfig.fromEnv(base + mapOf("AUTH_MODE" to "dev", "APP_ENV" to "production")) }
    }

    @Test
    fun `database password is required`() {
        assertFailsWith<IllegalStateException> { AppConfig.fromEnv(emptyMap()) }
    }

    @Test
    fun `play and rtdn settings`() {
        val defaults = AppConfig.fromEnv(base)
        assertEquals("com.ars899.geonotes", defaults.play.packageName)
        assertEquals(null, defaults.play.serviceAccountJsonPath)
        assertEquals(true, defaults.play.allowTestPurchases)
        assertEquals(false, defaults.rtdn.enabled)

        val c = AppConfig.fromEnv(
            base + mapOf(
                "GOOGLE_APPLICATION_CREDENTIALS" to "/run/secrets/firebase.json",
                "PLAY_PACKAGE_NAME" to "com.example.app",
                "PLAY_ALLOW_TEST_PURCHASES" to "false",
                "RTDN_AUDIENCE" to "https://api.example.com/v1/play/rtdn",
                "RTDN_PUSH_SERVICE_ACCOUNT" to "push@p.iam.gserviceaccount.com",
            ),
        )
        assertEquals("com.example.app", c.play.packageName)
        assertEquals("/run/secrets/firebase.json", c.play.serviceAccountJsonPath, "falls back to GOOGLE_APPLICATION_CREDENTIALS")
        assertEquals(false, c.play.allowTestPurchases)
        assertEquals(true, c.rtdn.enabled)
        val own = AppConfig.fromEnv(base + mapOf("GOOGLE_APPLICATION_CREDENTIALS" to "/a.json", "PLAY_SERVICE_ACCOUNT_JSON" to "/play.json"))
        assertEquals("/play.json", own.play.serviceAccountJsonPath)
    }
}
