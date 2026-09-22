package com.geonotes.backend.support

import com.geonotes.backend.auth.PubSubTokenVerifier
import com.geonotes.backend.billing.PlayPurchase
import com.geonotes.backend.billing.PlayPurchaseVerifier
import com.geonotes.backend.domain.UpstreamUnavailableException
import com.geonotes.backend.domain.model.ProductKind
import com.geonotes.backend.push.PushMessage
import com.geonotes.backend.push.PushResult
import com.geonotes.backend.push.PushSender
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

class FakePushSender : PushSender {
    val sent = CopyOnWriteArrayList<PushMessage>()
    /** Tokens this fake reports as UNREGISTERED. */
    val unregistered = mutableSetOf<String>()

    override suspend fun send(messages: List<PushMessage>): PushResult {
        val (bad, good) = messages.partition { it.token in unregistered }
        sent += good
        return PushResult(good.size, bad.size, bad.map { it.token }.toSet())
    }
}

class MutableClock(private var now: Instant = Instant.parse("2026-06-01T10:00:00Z")) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    fun advance(d: Duration) {
        now = now.plus(d)
    }
}

/** Scriptable Play verifier: answers from [purchases] (unknown tokens → INVALID) and records calls. */
class FakePlayPurchaseVerifier : PlayPurchaseVerifier {
    val purchases = mutableMapOf<String, PlayPurchase>()
    val verified = CopyOnWriteArrayList<String>()
    val acknowledged = CopyOnWriteArrayList<Pair<ProductKind, String>>()
    /** When set, every call fails like an unreachable Play API. */
    var unavailable = false
    var failAcknowledge = false

    override suspend fun verify(kind: ProductKind, productId: String, purchaseToken: String): PlayPurchase {
        if (unavailable) throw UpstreamUnavailableException("Google Play is unreachable; retry later", retryAfterSeconds = 30)
        verified += purchaseToken
        return purchases[purchaseToken] ?: PlayPurchase.INVALID
    }

    override suspend fun acknowledge(kind: ProductKind, productId: String, purchaseToken: String) {
        if (unavailable || failAcknowledge) throw UpstreamUnavailableException("Google Play is unreachable; retry later")
        acknowledged += kind to purchaseToken
        purchases[purchaseToken]?.let { purchases[purchaseToken] = it.copy(acknowledged = true) }
    }
}

/** Accepts exactly [validToken] as an authentic Pub/Sub push token. */
class FakePubSubTokenVerifier(private val validToken: String = VALID) : PubSubTokenVerifier {
    override suspend fun verify(token: String): Boolean = token == validToken

    companion object {
        const val VALID = "pubsub-oidc-ok"
    }
}
