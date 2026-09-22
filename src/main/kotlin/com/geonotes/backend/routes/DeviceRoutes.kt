package com.geonotes.backend.routes

import com.geonotes.backend.api.model.RegisterDeviceRequest
import com.geonotes.backend.controllers.DevicesController
import com.geonotes.backend.plugins.callerId
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.deviceRoutes(controller: DevicesController) = route("/devices") {
    post { call.respond(controller.register(call.callerId(), call.receive<RegisterDeviceRequest>())) }
    delete("/{id}") {
        controller.unregister(call.callerId(), call.parameters["id"]!!)
        call.respond(HttpStatusCode.NoContent)
    }
}
