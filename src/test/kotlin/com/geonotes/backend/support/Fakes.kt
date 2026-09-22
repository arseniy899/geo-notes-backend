package com.geonotes.backend.support

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
