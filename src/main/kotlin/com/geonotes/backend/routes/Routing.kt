package com.geonotes.backend.routes

import com.geonotes.backend.AppModule
import com.geonotes.backend.api.model.HealthResponse
import com.geonotes.backend.plugins.AUTH_BEARER
import com.geonotes.backend.plugins.AUTH_PUBSUB
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/** View layer: thin HTTP bindings. Parse → call controller → respond. No business logic here. */
fun Application.configureRouting(module: AppModule) {
    routing {
        get("/health") {
            val dbUp = module.databaseHealth.isUp()
            call.respond(
                if (dbUp) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                HealthResponse(status = if (dbUp) "ok" else "degraded", database = if (dbUp) "up" else "down"),
            )
        }
        authenticate(AUTH_BEARER) {
            route("/v1") {
                meRoutes(module.meController)
                deviceRoutes(module.devicesController)
                friendRoutes(module.friendsController)
                shareRoutes(module.sharesController)
                eventRoutes(module.eventsController)
                entitlementRoutes(module.entitlementsController)
            }
        }
        // Google Play RTDN via Pub/Sub push: authenticated by the push subscription's OIDC token, not a user token.
        authenticate(AUTH_PUBSUB) {
            route("/v1") {
                playNotificationRoutes(module.playNotificationsController)
            }
        }
    }
}
