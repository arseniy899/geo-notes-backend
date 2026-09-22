package com.geonotes.backend.config

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.slf4j.LoggerFactory
import java.io.FileInputStream

object Firebase {
    private val log = LoggerFactory.getLogger(Firebase::class.java)

    /** Initializes the Firebase Admin SDK, or returns null if no credentials are available. */
    fun initOrNull(credentialsPath: String?): FirebaseApp? = try {
        val credentials = credentialsPath
            ?.let { path -> FileInputStream(path).use { GoogleCredentials.fromStream(it) } }
            ?: GoogleCredentials.getApplicationDefault()
        FirebaseApp.getApps().firstOrNull() ?: FirebaseApp.initializeApp(FirebaseOptions.builder().setCredentials(credentials).build())
    } catch (e: Exception) {
        log.warn("Firebase not initialized ({}); FCM disabled", e.message)
        null
    }
}
