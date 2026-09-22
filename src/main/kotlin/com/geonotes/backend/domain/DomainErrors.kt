package com.geonotes.backend.domain

/**
 * Business-rule failures. Mapped to HTTP status codes + a stable error `code` in StatusPages.
 */
sealed class DomainException(val code: String, message: String) : RuntimeException(message)

class ValidationException(message: String, code: String = "validation_failed") : DomainException(code, message)

class NotFoundException(message: String, code: String = "not_found") : DomainException(code, message)

class ForbiddenException(message: String, code: String = "forbidden") : DomainException(code, message)

class ConflictException(message: String, code: String = "conflict") : DomainException(code, message)

/** A quota/budget limit was hit (e.g. max active shares). */
class LimitExceededException(message: String, code: String = "limit_exceeded") : DomainException(code, message)

/**
 * A required upstream (e.g. the Google Play Developer API) is unavailable or misconfigured.
 * Mapped to 503 with `Retry-After: [retryAfterSeconds]`.
 */
class UpstreamUnavailableException(
    message: String,
    code: String = "billing_unavailable",
    val retryAfterSeconds: Long = 30,
    cause: Throwable? = null,
) : DomainException(code, message) {
    init {
        cause?.let { initCause(it) }
    }
}
