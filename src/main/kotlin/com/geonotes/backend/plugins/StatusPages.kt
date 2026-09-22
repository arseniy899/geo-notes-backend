package com.geonotes.backend.plugins

import com.geonotes.backend.api.model.ErrorBody
import com.geonotes.backend.api.model.ErrorResponse
import com.geonotes.backend.domain.ConflictException
import com.geonotes.backend.domain.DomainException
import com.geonotes.backend.domain.ForbiddenException
import com.geonotes.backend.domain.LimitExceededException
import com.geonotes.backend.domain.NotFoundException
import com.geonotes.backend.domain.ValidationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.requestvalidation.RequestValidationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond

suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String, details: List<String> = emptyList()) =
    respond(status, ErrorResponse(ErrorBody(code, message, details)))

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<DomainException> { call, e ->
            val status = when (e) {
                is ValidationException -> HttpStatusCode.BadRequest
                is NotFoundException -> HttpStatusCode.NotFound
                is ForbiddenException -> HttpStatusCode.Forbidden
                is ConflictException -> HttpStatusCode.Conflict
                is LimitExceededException -> HttpStatusCode.UnprocessableEntity
            }
            call.respondError(status, e.code, e.message ?: e.code)
        }
        exception<RequestValidationException> { call, e ->
            call.respondError(HttpStatusCode.BadRequest, "validation_failed", "Request validation failed", e.reasons)
        }
        exception<BadRequestException> { call, e ->
            call.respondError(HttpStatusCode.BadRequest, "bad_request", e.cause?.message ?: e.message ?: "Malformed request")
        }
        exception<IllegalArgumentException> { call, e ->
            call.respondError(HttpStatusCode.BadRequest, "bad_request", e.message ?: "Malformed request")
        }
        exception<Throwable> { call, e ->
            call.application.log.error("Unhandled error", e)
            call.respondError(HttpStatusCode.InternalServerError, "internal_error", "Internal server error")
        }
        status(HttpStatusCode.Unauthorized) { call, status ->
            call.respondError(status, "unauthorized", "Missing or invalid bearer token")
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respondError(status, "not_found", "Route not found")
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respondError(status, "method_not_allowed", "Method not allowed")
        }
        status(HttpStatusCode.UnsupportedMediaType) { call, status ->
            call.respondError(status, "unsupported_media_type", "Use Content-Type: application/json")
        }
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respondError(status, "rate_limited", "Too many requests; retry later")
        }
    }
}
