package com.geonotes.backend.routes

import com.geonotes.backend.api.model.UpsertMeRequest
import com.geonotes.backend.controllers.MeController
import com.geonotes.backend.plugins.callerId
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route

fun Route.meRoutes(controller: MeController) = route("/me") {
    put { call.respond(controller.upsert(call.callerId(), call.receive<UpsertMeRequest>())) }
    get { call.respond(controller.get(call.callerId())) }
    delete {
        controller.delete(call.callerId())
        call.respond(HttpStatusCode.NoContent)
    }
}
