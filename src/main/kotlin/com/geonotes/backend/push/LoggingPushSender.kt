package com.geonotes.backend.push

import org.slf4j.LoggerFactory

/** Used when no Firebase credentials are configured (local dev). Logs instead of sending. */
class LoggingPushSender : PushSender {
    private val log = LoggerFactory.getLogger(LoggingPushSender::class.java)

    override suspend fun send(messages: List<PushMessage>): PushResult {
        // Never log FCM tokens (privacy checklist); the data payload holds only ids + transition.
        messages.forEach { log.info("[push:dry-run] type={} shareId={} transition={}", it.data["type"], it.data["shareId"], it.data["transition"]) }
        return PushResult(successCount = messages.size, failureCount = 0)
    }
}
