package com.geonotes.backend.config

import java.time.Duration

enum class AuthMode { FIREBASE, DEV }

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int = 10,
)

data class RateLimitConfig(
    /** POST /v1/events per user. */
    val eventsPerMinute: Int = 60,
    /** Invite create/accept per user. */
    val invitesPerMinute: Int = 10,
    /** POST /v1/share-requests per user. */
    val shareRequestsPerMinute: Int = 20,
)

data class PlayConfig(
    /** Android application id the purchases belong to. */
    val packageName: String = DEFAULT_PACKAGE_NAME,
    /**
     * Service-account JSON with access to the Play Developer API. Falls back to GOOGLE_APPLICATION_CREDENTIALS /
     * Application Default Credentials when null.
     */
    val serviceAccountJsonPath: String? = null,
    /** Whether license-tester (test) purchases grant Pro. */
    val allowTestPurchases: Boolean = true,
    /** GET /v1/entitlements re-verifies with Play when the last check is older than this and the expiry has passed. */
    val reverifyAfter: Duration = Duration.ofHours(24),
) {
    companion object {
        const val DEFAULT_PACKAGE_NAME = "com.ars899.geonotes"
    }
}

/** Real-time developer notifications (Pub/Sub push to POST /v1/play/rtdn). Disabled unless both are set. */
data class RtdnConfig(
    /** Expected `aud` of the push OIDC token (the audience configured on the push subscription). */
    val audience: String? = null,
    /** Expected `email` of the push OIDC token (the service account set on the push subscription). */
    val pushServiceAccount: String? = null,
) {
    val enabled: Boolean get() = !audience.isNullOrBlank() && !pushServiceAccount.isNullOrBlank()
}

data class AppConfig(
    val port: Int = 8080,
    val database: DatabaseConfig,
    val authMode: AuthMode = AuthMode.FIREBASE,
    /** Path to a service-account JSON; if null, Application Default Credentials are tried. */
    val googleCredentialsPath: String? = null,
    val rateLimits: RateLimitConfig = RateLimitConfig(),
    val play: PlayConfig = PlayConfig(),
    val rtdn: RtdnConfig = RtdnConfig(),
    val maxActiveSharesPerOwner: Int = 20,
    val inviteTtl: Duration = Duration.ofHours(48),
    val eventTtl: Duration = Duration.ofDays(7),
    val shareRequestTtl: Duration = Duration.ofDays(7),
    /** Null disables the background cleanup job (tests). */
    val cleanupInterval: Duration? = Duration.ofHours(1),
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun opt(key: String) = env[key]?.takeIf { it.isNotBlank() }
            fun req(key: String) = opt(key) ?: error("Missing required environment variable $key")
            val authMode = when (opt("AUTH_MODE")?.lowercase()) {
                null, "firebase" -> AuthMode.FIREBASE
                "dev" -> AuthMode.DEV
                else -> error("AUTH_MODE must be 'firebase' or 'dev'")
            }
            // Safety net: dev auth trusts any `dev:<uid>` token, so it must never run in production.
            if (authMode == AuthMode.DEV && opt("APP_ENV")?.lowercase() == "production") {
                error("AUTH_MODE=dev is not allowed when APP_ENV=production")
            }
            return AppConfig(
                port = opt("PORT")?.toInt() ?: 8080,
                database = DatabaseConfig(
                    url = opt("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/geonotes",
                    user = opt("DATABASE_USER") ?: "geonotes",
                    password = req("DATABASE_PASSWORD"),
                    maxPoolSize = opt("DATABASE_POOL_SIZE")?.toInt() ?: 10,
                ),
                authMode = authMode,
                googleCredentialsPath = opt("GOOGLE_APPLICATION_CREDENTIALS"),
                rateLimits = RateLimitConfig(
                    eventsPerMinute = opt("RATE_LIMIT_EVENTS_PER_MINUTE")?.toInt() ?: 60,
                    invitesPerMinute = opt("RATE_LIMIT_INVITES_PER_MINUTE")?.toInt() ?: 10,
                    shareRequestsPerMinute = opt("RATE_LIMIT_SHARE_REQUESTS_PER_MINUTE")?.toInt() ?: 20,
                ),
                play = PlayConfig(
                    packageName = opt("PLAY_PACKAGE_NAME") ?: PlayConfig.DEFAULT_PACKAGE_NAME,
                    serviceAccountJsonPath = opt("PLAY_SERVICE_ACCOUNT_JSON") ?: opt("GOOGLE_APPLICATION_CREDENTIALS"),
                    allowTestPurchases = opt("PLAY_ALLOW_TEST_PURCHASES")?.toBooleanStrict() ?: true,
                ),
                rtdn = RtdnConfig(
                    audience = opt("RTDN_AUDIENCE"),
                    pushServiceAccount = opt("RTDN_PUSH_SERVICE_ACCOUNT"),
                ),
            )
        }
    }
}
