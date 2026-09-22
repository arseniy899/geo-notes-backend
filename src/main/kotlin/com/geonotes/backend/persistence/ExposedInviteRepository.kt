package com.geonotes.backend.persistence

import com.geonotes.backend.domain.model.Invite
import com.geonotes.backend.domain.model.UserId
import com.geonotes.backend.domain.repository.InviteRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

class ExposedInviteRepository(database: Database) : ExposedRepository(database), InviteRepository {
    override suspend fun create(invite: Invite): Invite = dbQuery {
        InvitesTable.insert {
            it[code] = invite.code
            it[inviterId] = invite.inviterId
            it[createdAt] = invite.createdAt.toOdt()
            it[expiresAt] = invite.expiresAt.toOdt()
        }
        invite
    }

    override suspend fun find(code: String): Invite? = dbQuery {
        InvitesTable.selectAll().where { InvitesTable.code eq code }.singleOrNull()?.toInvite()
    }

    override suspend fun markAccepted(code: String, acceptedBy: UserId, at: Instant): Boolean = dbQuery {
        InvitesTable.update({ (InvitesTable.code eq code) and InvitesTable.acceptedBy.isNull() }) {
            it[InvitesTable.acceptedBy] = acceptedBy
            it[acceptedAt] = at.toOdt()
        } > 0
    }

    override suspend fun deleteExpired(now: Instant): Int = dbQuery {
        InvitesTable.deleteWhere { InvitesTable.expiresAt lessEq now.toOdt() }
    }

    private fun ResultRow.toInvite() = Invite(
        code = this[InvitesTable.code],
        inviterId = this[InvitesTable.inviterId],
        createdAt = this[InvitesTable.createdAt].toInstant(),
        expiresAt = this[InvitesTable.expiresAt].toInstant(),
        acceptedBy = this[InvitesTable.acceptedBy],
        acceptedAt = this[InvitesTable.acceptedAt]?.toInstant(),
    )
}
