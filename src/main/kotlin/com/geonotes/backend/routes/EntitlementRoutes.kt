package com.geonotes.backend.routes

import com.geonotes.backend.api.model.PubSubPushRequest
import com.geonotes.backend.api.model.VerifyPurchaseRequest
import com.geonotes.backend.controllers.EntitlementsController
import com.geonotes.backend.controllers.PlayNotificationsController
import com.geonotes.backend.plugins.callerId
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.entitlementRoutes(controller: EntitlementsController) {
    get("/entitlements") { call.respond(controller.current(call.callerId())) }
    post("/entitlements/verify") { call.respond(controller.verify(call.callerId(), call.receive<VerifyPurchaseRequest>())) }
}

/** Pub/Sub push endpoint for Google Play RTDN. Must be inside `authenticate(AUTH_PUBSUB)`. */
fun Route.playNotificationRoutes(controller: PlayNotificationsController) {
    post("/play/rtdn") { call.respond(controller.handle(call.receive<PubSubPushRequest>())) }
}
