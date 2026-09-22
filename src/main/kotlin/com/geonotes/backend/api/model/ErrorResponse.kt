package com.geonotes.backend.api.model

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(val error: ErrorBody)

@Serializable
data class ErrorBody(
    val code: String,
    val message: String,
    val details: List<String> = emptyList(),
)

@Serializable
data class HealthResponse(val status: String, val database: String)
