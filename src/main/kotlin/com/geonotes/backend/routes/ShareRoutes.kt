package com.geonotes.backend.routes

import com.geonotes.backend.api.model.CreateShareRequest
import com.geonotes.backend.api.model.UpdateShareRequest
import com.geonotes.backend.controllers.SharesController
import com.geonotes.backend.plugins.callerId
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonObject

fun Route.shareRoutes(controller: SharesController) = route("/shares") {
    post { call.respond(HttpStatusCode.Created, controller.create(call.callerId(), call.receive<CreateShareRequest>())) }
    get { call.respond(controller.list(call.callerId())) }
    patch("/{id}") {
        val request = UpdateShareRequest.fromJson(call.receive<JsonObject>())
        call.respond(controller.update(call.callerId(), call.parameters["id"]!!, request))
    }
    delete("/{id}") {
        controller.delete(call.callerId(), call.parameters["id"]!!)
        call.respond(HttpStatusCode.NoContent)
    }
}
