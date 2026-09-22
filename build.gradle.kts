plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "com.geonotes"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.geonotes.backend.ApplicationKt")
}

dependencies {
    implementation(libs.bundles.ktor.server)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.exposed)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.logback.classic)
    implementation(libs.firebase.admin)
    implementation(libs.google.auth.library.oauth2.http)
    implementation(libs.bundles.ktor.client)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.test {
    useJUnitPlatform()
    // Pass-through so CI / local runs can point tests at an existing PostgreSQL instead of Testcontainers.
    listOf("TEST_DATABASE_URL", "TEST_DATABASE_USER", "TEST_DATABASE_PASSWORD").forEach { key ->
        System.getenv(key)?.let { environment(key, it) }
    }
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
