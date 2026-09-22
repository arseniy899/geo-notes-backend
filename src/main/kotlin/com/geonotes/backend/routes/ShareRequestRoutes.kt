package com.geonotes.backend.routes

import com.geonotes.backend.api.model.CreateShareRequestRequest
import com.geonotes.backend.controllers.ShareRequestsController
import com.geonotes.backend.plugins.RATE_LIMIT_SHARE_REQUESTS
import com.geonotes.backend.plugins.callerId
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.shareRequestRoutes(controller: ShareRequestsController) = route("/share-requests") {
    rateLimit(RATE_LIMIT_SHARE_REQUESTS) {
        post { call.respond(HttpStatusCode.Created, controller.create(call.callerId(), call.receive<CreateShareRequestRequest>())) }
    }
    get { call.respond(controller.list(call.callerId())) }
    post("/{id}/accept") { call.respond(controller.accept(call.callerId(), call.parameters["id"]!!)) }
    post("/{id}/decline") { call.respond(controller.decline(call.callerId(), call.parameters["id"]!!)) }
    delete("/{id}") {
        controller.cancel(call.callerId(), call.parameters["id"]!!)
        call.respond(HttpStatusCode.NoContent)
    }
}
