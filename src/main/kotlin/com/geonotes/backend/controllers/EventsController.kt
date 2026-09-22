package com.geonotes.backend.controllers

import com.geonotes.backend.api.model.EventResponse
import com.geonotes.backend.api.model.PostEventRequest
import com.geonotes.backend.domain.model.UserId
import com.geonotes.backend.domain.service.EventService

class EventsController(private val events: EventService) {
    suspend fun post(caller: UserId, request: PostEventRequest): EventResponse {
        val outcome = events.post(
            callerId = caller,
            shareId = Parse.uuid("shareId", request.shareId),
            transition = Parse.transition("transition", request.transition),
            occurredAt = Parse.instant("occurredAt", request.occurredAt),
        )
        return when (outcome) {
            is EventService.Outcome.Delivered -> EventResponse(
                status = "DELIVERED",
                eventId = outcome.event.id.toString(),
                recipientDevices = outcome.recipientDevices,
                pushed = outcome.pushed,
            )
            is EventService.Outcome.Ignored -> EventResponse(status = "IGNORED", reason = outcome.reason)
        }
    }
}
