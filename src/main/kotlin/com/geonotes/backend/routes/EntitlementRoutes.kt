package com.geonotes.backend.routes

import com.geonotes.backend.api.model.VerifyPurchaseRequest
import com.geonotes.backend.controllers.EntitlementsController
import com.geonotes.backend.plugins.callerId
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.entitlementRoutes(controller: EntitlementsController) {
    post("/entitlements/verify") { call.respond(controller.verify(call.callerId(), call.receive<VerifyPurchaseRequest>())) }
}
