package com.geonotes.backend.persistence

import com.geonotes.backend.domain.model.FriendEvent
import com.geonotes.backend.domain.repository.EventRepository
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.util.UUID

class ExposedEventRepository(database: Database) : ExposedRepository(database), EventRepository {
    override suspend fun insert(event: FriendEvent): FriendEvent = dbQuery {
        EventsTable.insert {
            it[id] = event.id
            it[shareId] = event.shareId
            it[ownerId] = event.ownerId
            it[transition] = event.transition.name
            it[occurredAt] = event.occurredAt.toOdt()
            it[receivedAt] = event.receivedAt.toOdt()
            it[expiresAt] = event.expiresAt.toOdt()
        }
        event
    }

    override suspend fun deleteExpired(now: Instant): Int = dbQuery {
        EventsTable.deleteWhere { EventsTable.expiresAt lessEq now.toOdt() }
    }

    override suspend fun countForShare(shareId: UUID): Int = dbQuery {
        EventsTable.selectAll().where { EventsTable.shareId eq shareId }.count().toInt()
    }
}
