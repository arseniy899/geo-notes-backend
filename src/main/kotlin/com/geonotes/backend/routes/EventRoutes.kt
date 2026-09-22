package com.geonotes.backend.routes

import com.geonotes.backend.api.model.PostEventRequest
import com.geonotes.backend.controllers.EventsController
import com.geonotes.backend.plugins.RATE_LIMIT_EVENTS
import com.geonotes.backend.plugins.callerId
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.eventRoutes(controller: EventsController) {
    rateLimit(RATE_LIMIT_EVENTS) {
        post("/events") { call.respond(HttpStatusCode.Accepted, controller.post(call.callerId(), call.receive<PostEventRequest>())) }
    }
}
