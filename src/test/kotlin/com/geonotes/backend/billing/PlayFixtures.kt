package com.geonotes.backend.billing

/** Recorded Android Publisher API v3 responses under src/test/resources/play/. */
internal object PlayFixtures {
    fun load(name: String): String =
        checkNotNull(PlayFixtures::class.java.getResource("/play/$name.json")) { "missing fixture $name" }.readText()
}
