package com.geonotes.backend

import com.geonotes.backend.config.AppConfig
import com.geonotes.backend.config.AuthMode
import com.geonotes.backend.config.DatabaseFactory
import com.geonotes.backend.config.Firebase
import com.geonotes.backend.plugins.configureAuth
import com.geonotes.backend.plugins.configureBackgroundJobs
import com.geonotes.backend.plugins.configureMonitoring
import com.geonotes.backend.plugins.configureRateLimit
import com.geonotes.backend.plugins.configureSerialization
import com.geonotes.backend.plugins.configureStatusPages
import com.geonotes.backend.plugins.configureValidation
import com.geonotes.backend.routes.configureRouting
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val config = AppConfig.fromEnv()
    val dataSource = DatabaseFactory.dataSource(config.database)
    DatabaseFactory.migrate(dataSource)
    val database = DatabaseFactory.connect(dataSource)
    // In dev mode Firebase is optional (only when credentials are given explicitly); pushes are then logged.
    val firebaseApp = if (config.authMode == AuthMode.DEV && config.googleCredentialsPath == null) {
        null
    } else {
        Firebase.initOrNull(config.googleCredentialsPath)
    }
    val appModule = AppModule.create(config, database, firebaseApp)

    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(appModule)
        monitor.subscribe(ApplicationStopped) { dataSource.close() }
    }.start(wait = true)
}

/** Installs plugins and routes. Shared by production `main` and `testApplication`. */
fun Application.module(appModule: AppModule) {
    configureMonitoring()
    configureSerialization()
    configureStatusPages()
    configureValidation()
    configureAuth(appModule.tokenVerifier)
    configureRateLimit(appModule.config.rateLimits)
    configureRouting(appModule)
    configureBackgroundJobs(appModule.eventService, appModule.shareRequestService, appModule.config.cleanupInterval)
}
