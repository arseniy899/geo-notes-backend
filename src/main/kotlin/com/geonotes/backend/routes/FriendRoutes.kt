package com.geonotes.backend.routes

import com.geonotes.backend.controllers.FriendsController
import com.geonotes.backend.plugins.RATE_LIMIT_INVITES
import com.geonotes.backend.plugins.callerId
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.friendRoutes(controller: FriendsController) {
    rateLimit(RATE_LIMIT_INVITES) {
        route("/invites") {
            post { call.respond(HttpStatusCode.Created, controller.createInvite(call.callerId())) }
            post("/{code}/accept") { call.respond(controller.acceptInvite(call.callerId(), call.parameters["code"]!!)) }
        }
    }
    route("/friends") {
        get { call.respond(controller.list(call.callerId())) }
        delete("/{userId}") {
            controller.unfriend(call.callerId(), call.parameters["userId"]!!)
            call.respond(HttpStatusCode.NoContent)
        }
        get("/{userId}/devices") { call.respond(controller.friendDevices(call.callerId(), call.parameters["userId"]!!)) }
    }
}
