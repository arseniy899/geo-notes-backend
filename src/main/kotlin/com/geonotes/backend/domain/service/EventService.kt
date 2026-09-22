package com.geonotes.backend.domain.service

import com.geonotes.backend.domain.ValidationException
import com.geonotes.backend.domain.model.FriendEvent
import com.geonotes.backend.domain.model.Transition
import com.geonotes.backend.domain.model.UserId
import com.geonotes.backend.domain.repository.DeviceRepository
import com.geonotes.backend.domain.repository.EventRepository
import com.geonotes.backend.domain.repository.InviteRepository
import com.geonotes.backend.push.PushMessage
import com.geonotes.backend.push.PushSender
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

class EventService(
    private val shareService: ShareService,
    private val events: EventRepository,
    private val devices: DeviceRepository,
    private val invites: InviteRepository,
    private val pushSender: PushSender,
    private val clock: Clock,
    private val eventTtl: Duration = Duration.ofDays(7),
    /** Tolerated clock skew / offline delay for `occurredAt`. */
    private val maxEventAge: Duration = Duration.ofHours(24),
    private val maxFutureSkew: Duration = Duration.ofMinutes(5),
) {
    private val log = LoggerFactory.getLogger(EventService::class.java)

    sealed interface Outcome {
        data class Delivered(val event: FriendEvent, val recipientDevices: Int, val pushed: Int) : Outcome
        data class Ignored(val reason: String) : Outcome
    }

    /**
     * Called by the share owner's device when it crosses the shared geofence.
     * The payload contains no location data — only which share fired and how.
     */
    suspend fun post(callerId: UserId, shareId: UUID, transition: Transition, occurredAt: Instant): Outcome {
        val share = shareService.requireOwned(callerId, shareId)
        val now = clock.instant()
        if (occurredAt.isAfter(now.plus(maxFutureSkew))) throw ValidationException("occurredAt is in the future")
        if (occurredAt.isBefore(now.minus(maxEventAge))) return Outcome.Ignored("stale")
        if (!share.active) return Outcome.Ignored("inactive")
        if (share.isPausedAt(now)) return Outcome.Ignored("paused")
        if (transition !in share.transitions) return Outcome.Ignored("transition_not_subscribed")

        val event = events.insert(
            FriendEvent(
                id = UUID.randomUUID(),
                shareId = share.id,
                ownerId = callerId,
                transition = transition,
                occurredAt = occurredAt,
                receivedAt = now,
                expiresAt = now.plus(eventTtl),
            ),
        )
        // Only the devices that hold a sealed key for this share can decrypt it → notify exactly those.
        val targets = devices.findByIds(share.recipients.map { it.deviceId }).mapNotNull { it.fcmToken }
        val data = mapOf(
            "type" to "friend_transition",
            "shareId" to share.id.toString(),
            "fromUserId" to callerId,
            "transition" to transition.name,
            "occurredAt" to occurredAt.toString(),
        )
        val result = pushSender.send(targets.map { PushMessage(it, data) })
        if (result.unregisteredTokens.isNotEmpty()) devices.clearFcmTokens(result.unregisteredTokens)
        return Outcome.Delivered(event, share.recipients.size, result.successCount)
    }

    /** Hourly housekeeping: drop expired events (7-day TTL) and expired invites. */
    suspend fun cleanup(): Pair<Int, Int> {
        val now = clock.instant()
        val e = events.deleteExpired(now)
        val i = invites.deleteExpired(now)
        if (e + i > 0) log.info("Cleanup removed {} expired events, {} expired invites", e, i)
        return e to i
    }
}
