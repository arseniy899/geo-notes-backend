package com.geonotes.backend.api.model

import kotlinx.serialization.Serializable

@Serializable
data class UpsertMeRequest(val displayName: String)

@Serializable
data class MeResponse(
    val userId: String,
    val displayName: String,
    val createdAt: String,
    val entitlement: EntitlementResponse,
)
