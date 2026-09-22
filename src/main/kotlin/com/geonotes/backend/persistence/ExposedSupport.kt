package com.geonotes.backend.persistence

import com.geonotes.backend.domain.repository.TransactionRunner
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Base for repositories: runs a block in the current transaction, or opens a new one. */
abstract class ExposedRepository(protected val database: Database) {
    protected suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T = suspendTransaction(database) { block() }
}

class ExposedTransactionRunner(private val database: Database) : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = suspendTransaction(database) { block() }
}

internal fun Instant.toOdt(): OffsetDateTime = atOffset(ZoneOffset.UTC)
