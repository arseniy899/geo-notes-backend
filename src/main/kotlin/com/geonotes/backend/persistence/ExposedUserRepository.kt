package com.geonotes.backend.persistence

import com.geonotes.backend.domain.model.User
import com.geonotes.backend.domain.model.UserId
import com.geonotes.backend.domain.repository.UserRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

class ExposedUserRepository(database: Database) : ExposedRepository(database), UserRepository {
    override suspend fun find(id: UserId): User? = dbQuery {
        UsersTable.selectAll().where { UsersTable.id eq id }.singleOrNull()?.toUser()
    }

    override suspend fun upsert(id: UserId, displayName: String, now: Instant): User = dbQuery {
        UsersTable.upsert(UsersTable.id, onUpdateExclude = listOf(UsersTable.createdAt)) {
            it[UsersTable.id] = id
            it[UsersTable.displayName] = displayName
            it[createdAt] = now.toOdt()
            it[updatedAt] = now.toOdt()
        }
        UsersTable.selectAll().where { UsersTable.id eq id }.single().toUser()
    }

    override suspend fun delete(id: UserId): Boolean = dbQuery {
        UsersTable.deleteWhere { UsersTable.id eq id } > 0
    }

    private fun ResultRow.toUser() = User(
        id = this[UsersTable.id],
        displayName = this[UsersTable.displayName],
        createdAt = this[UsersTable.createdAt].toInstant(),
        updatedAt = this[UsersTable.updatedAt].toInstant(),
    )
}
