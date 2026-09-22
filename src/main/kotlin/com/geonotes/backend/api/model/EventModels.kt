package com.geonotes.backend.api.model

import kotlinx.serialization.Serializable

@Serializable
data class PostEventRequest(
    val shareId: String,
    /** ENTER | EXIT */
    val transition: String,
    /** ISO-8601 instant when the geofence transition happened on the device. */
    val occurredAt: String,
)

@Serializable
data class EventResponse(
    /** DELIVERED | IGNORED */
    val status: String,
    val eventId: String? = null,
    val reason: String? = null,
    val recipientDevices: Int = 0,
    val pushed: Int = 0,
)
