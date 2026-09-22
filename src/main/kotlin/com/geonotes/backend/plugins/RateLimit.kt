package com.geonotes.backend.plugins

import com.geonotes.backend.config.RateLimitConfig
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import kotlin.time.Duration.Companion.minutes

val RATE_LIMIT_EVENTS = RateLimitName("events")
val RATE_LIMIT_INVITES = RateLimitName("invites")

fun Application.configureRateLimit(config: RateLimitConfig) {
    install(RateLimit) {
        register(RATE_LIMIT_EVENTS) {
            rateLimiter(limit = config.eventsPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.principal<UserPrincipal>()?.uid ?: call.request.origin.remoteHost }
        }
        register(RATE_LIMIT_INVITES) {
            rateLimiter(limit = config.invitesPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.principal<UserPrincipal>()?.uid ?: call.request.origin.remoteHost }
        }
    }
}
