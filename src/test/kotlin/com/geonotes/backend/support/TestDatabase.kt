package com.geonotes.backend.support

import com.geonotes.backend.config.DatabaseConfig
import com.geonotes.backend.config.DatabaseFactory
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * One real PostgreSQL for the whole test JVM.
 *
 * - `TEST_DATABASE_URL` (+ `TEST_DATABASE_USER` / `TEST_DATABASE_PASSWORD`) → use that server
 *   (e.g. a local postgres or a CI service container). The database must be empty/disposable.
 * - otherwise → Testcontainers `postgres:17-alpine` (requires a Docker daemon).
 */
object TestDatabase {
    private val container: PostgreSQLContainer<*>? by lazy {
        if (System.getenv("TEST_DATABASE_URL") != null) {
            null
        } else {
            PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                .withDatabaseName("geonotes_test")
                .withUsername("geonotes")
                .withPassword("geonotes")
                .also { it.start() }
        }
    }

    private val config: DatabaseConfig by lazy {
        val c = container
        if (c == null) {
            DatabaseConfig(
                url = System.getenv("TEST_DATABASE_URL"),
                user = System.getenv("TEST_DATABASE_USER") ?: "postgres",
                password = System.getenv("TEST_DATABASE_PASSWORD") ?: "",
                maxPoolSize = 5,
            )
        } else {
            DatabaseConfig(url = c.jdbcUrl, user = c.username, password = c.password, maxPoolSize = 5)
        }
    }

    val dataSource: HikariDataSource by lazy {
        DatabaseFactory.dataSource(config).also { DatabaseFactory.migrate(it) }
    }

    val database: Database by lazy { DatabaseFactory.connect(dataSource) }

    private val tables = listOf(
        "events", "share_recipients", "shares", "friendships", "invites", "entitlements", "devices", "users",
    )

    /** Wipes all data between tests (schema is kept). */
    fun reset() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("TRUNCATE ${tables.joinToString()} CASCADE") }
            conn.commit()
        }
    }

    fun count(table: String, where: String = "TRUE"): Int = dataSource.connection.use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM $table WHERE $where").use { rs -> rs.next(); rs.getInt(1) }
        }.also { conn.commit() }
    }
}
