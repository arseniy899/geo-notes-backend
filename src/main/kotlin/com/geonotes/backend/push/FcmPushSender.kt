package com.geonotes.backend.push

import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Sends high-priority FCM *data* messages (no notification payload): the receiving app decides
 * how to render the reminder after decrypting the share locally.
 */
class FcmPushSender(
    firebaseApp: FirebaseApp,
    private val ttl: Duration = Duration.ofHours(1),
) : PushSender {
    private val log = LoggerFactory.getLogger(FcmPushSender::class.java)
    private val messaging = FirebaseMessaging.getInstance(firebaseApp)

    override suspend fun send(messages: List<PushMessage>): PushResult {
        if (messages.isEmpty()) return PushResult(0, 0)
        var success = 0
        var failure = 0
        val unregistered = mutableSetOf<String>()
        // FCM allows at most 500 messages per sendEach call.
        for (chunk in messages.chunked(500)) {
            val fcmMessages = chunk.map { msg ->
                Message.builder()
                    .setToken(msg.token)
                    .putAllData(msg.data)
                    .setAndroidConfig(
                        AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.HIGH)
                            .setTtl(ttl.toMillis())
                            .build(),
                    )
                    .build()
            }
            val response = withContext(Dispatchers.IO) { messaging.sendEach(fcmMessages) }
            success += response.successCount
            failure += response.failureCount
            response.responses.forEachIndexed { i, r ->
                if (!r.isSuccessful) {
                    val code = r.exception?.messagingErrorCode
                    if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                        unregistered += chunk[i].token
                    } else {
                        log.warn("FCM send failed: code={} msg={}", code, r.exception?.message)
                    }
                }
            }
        }
        return PushResult(success, failure, unregistered)
    }
}
