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
}
