package com.geonotes.backend.persistence

import com.geonotes.backend.support.TestDatabase
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TransactionRunnerTest {
    @BeforeTest
    fun reset() = TestDatabase.reset()

    @Test
    fun `repository writes inside inTransaction roll back together`() = runTest {
        val tx = ExposedTransactionRunner(TestDatabase.database)
        val users = ExposedUserRepository(TestDatabase.database)
        assertFailsWith<IllegalStateException> {
            tx.inTransaction {
                users.upsert("u1", "One", Instant.now())
                users.upsert("u2", "Two", Instant.now())
                error("boom")
            }
        }
        assertEquals(0, TestDatabase.count("users"))

        tx.inTransaction { users.upsert("u1", "One", Instant.now()) }
        assertEquals(1, TestDatabase.count("users"))
    }
}
