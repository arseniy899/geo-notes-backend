package com.geonotes.backend.persistence

import org.jetbrains.exposed.v1.jdbc.Database

class DatabaseHealth(database: Database) : ExposedRepository(database) {
    suspend fun isUp(): Boolean = try {
        dbQuery { exec("SELECT 1") { rs -> rs.next() } } == true
    } catch (_: Exception) {
        false
    }
}
