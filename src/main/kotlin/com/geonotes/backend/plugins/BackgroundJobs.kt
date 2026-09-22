package com.geonotes.backend.plugins

import com.geonotes.backend.domain.service.EventService
import com.geonotes.backend.domain.service.ShareRequestService
import io.ktor.server.application.Application
import io.ktor.server.application.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration

/** Hourly cleanup of expired events (7-day TTL), invites and share requests. Cancelled with the application scope. */
fun Application.configureBackgroundJobs(eventService: EventService, shareRequestService: ShareRequestService, interval: Duration?) {
    if (interval == null) return
    launch {
        while (isActive) {
            try {
                eventService.cleanup()
                shareRequestService.cleanup()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Cleanup job failed", e)
            }
            delay(interval.toMillis())
        }
    }
}
