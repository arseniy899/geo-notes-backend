package com.geonotes.backend.push

/** A high-priority data-only push to a single device token. */
data class PushMessage(
    val token: String,
    val data: Map<String, String>,
)

data class PushResult(
    val successCount: Int,
    val failureCount: Int,
    /** Tokens FCM reported as permanently invalid (app uninstalled / token rotated). */
    val unregisteredTokens: Set<String> = emptySet(),
)

interface PushSender {
    suspend fun send(messages: List<PushMessage>): PushResult
}
