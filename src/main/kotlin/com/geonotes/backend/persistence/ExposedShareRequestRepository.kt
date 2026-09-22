package com.geonotes.backend.persistence

import com.geonotes.backend.domain.model.ShareRecipient
import com.geonotes.backend.domain.model.ShareRequest
import com.geonotes.backend.domain.model.ShareRequestStatus
import com.geonotes.backend.domain.model.ShareRequestView
import com.geonotes.backend.domain.model.Transition
import com.geonotes.backend.domain.model.UserId
import com.geonotes.backend.domain.repository.ShareRequestRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

class ExposedShareRequestRepository(database: Database) : ExposedRepository(database), ShareRequestRepository {
    override suspend fun create(request: ShareRequest): ShareRequest = dbQuery {
        ShareRequestsTable.insert {
            it[id] = request.id
            it[requesterId] = request.requesterId
            it[targetId] = request.targetId
            it[encryptedPlace] = request.encryptedPlace
            it[transitions] = request.transitions.map { t -> t.name }.sorted().joinToString(",")
            it[note] = request.note
            it[status] = request.status.name
            it[shareId] = request.shareId
            it[createdAt] = request.createdAt.toOdt()
            it[updatedAt] = request.updatedAt.toOdt()
            it[expiresAt] = request.expiresAt.toOdt()
        }
        val keys = request.ownerKeys.map { it to ROLE_OWNER } + request.recipientKeys.map { it to ROLE_RECIPIENT }
        ShareRequestKeysTable.batchInsert(keys) { (k, role) ->
            this[ShareRequestKeysTable.requestId] = request.id
            this[ShareRequestKeysTable.deviceId] = k.deviceId
            this[ShareRequestKeysTable.userId] = k.userId
            this[ShareRequestKeysTable.role] = role
            this[ShareRequestKeysTable.sealedKey] = k.sealedKey
        }
        request
    }

    override suspend fun find(id: UUID): ShareRequest? = dbQuery {
        val row = ShareRequestsTable.selectAll().where { ShareRequestsTable.id eq id }.singleOrNull() ?: return@dbQuery null
        row.toRequest(keysFor(listOf(id))[id].orEmpty())
    }

    override suspend fun listForUser(userId: UserId, now: Instant): List<ShareRequestView> = dbQuery {
        val requester = UsersTable.alias("requester")
        val target = UsersTable.alias("target")
        val rows = ShareRequestsTable
            .join(requester, org.jetbrains.exposed.v1.core.JoinType.INNER, ShareRequestsTable.requesterId, requester[UsersTable.id])
            .join(target, org.jetbrains.exposed.v1.core.JoinType.INNER, ShareRequestsTable.targetId, target[UsersTable.id])
            .selectAll()
            .where {
                ((ShareRequestsTable.requesterId eq userId) or (ShareRequestsTable.targetId eq userId)) and
                    (ShareRequestsTable.expiresAt greater now.toOdt())
            }
            .orderBy(ShareRequestsTable.createdAt to SortOrder.DESC)
            .toList()
        val keys = keysFor(rows.map { it[ShareRequestsTable.id] })
        rows.map {
            ShareRequestView(
                request = it.toRequest(keys[it[ShareRequestsTable.id]].orEmpty()),
                requesterDisplayName = it[requester[UsersTable.displayName]],
                targetDisplayName = it[target[UsersTable.displayName]],
            )
        }
    }

    override suspend fun countPendingOutgoing(requesterId: UserId, now: Instant): Int = dbQuery {
        ShareRequestsTable.selectAll().where {
            (ShareRequestsTable.requesterId eq requesterId) and (ShareRequestsTable.status eq ShareRequestStatus.PENDING.name) and
                (ShareRequestsTable.expiresAt greater now.toOdt())
        }.count().toInt()
    }

    override suspend fun resolve(id: UUID, status: ShareRequestStatus, shareId: UUID?, now: Instant): Boolean = dbQuery {
        ShareRequestsTable.update({ (ShareRequestsTable.id eq id) and (ShareRequestsTable.status eq ShareRequestStatus.PENDING.name) }) {
            it[ShareRequestsTable.status] = status.name
            it[ShareRequestsTable.shareId] = shareId
            it[updatedAt] = now.toOdt()
        } > 0
    }

    override suspend fun delete(id: UUID): Boolean = dbQuery {
        ShareRequestsTable.deleteWhere { ShareRequestsTable.id eq id } > 0
    }

    override suspend fun deletePendingBetween(userA: UserId, userB: UserId): Int = dbQuery {
        ShareRequestsTable.deleteWhere {
            (ShareRequestsTable.status eq ShareRequestStatus.PENDING.name) and (
                ((ShareRequestsTable.requesterId eq userA) and (ShareRequestsTable.targetId eq userB)) or
                    ((ShareRequestsTable.requesterId eq userB) and (ShareRequestsTable.targetId eq userA))
                )
        }
    }

    override suspend fun deleteExpired(now: Instant): Int = dbQuery {
        ShareRequestsTable.deleteWhere { ShareRequestsTable.expiresAt lessEq now.toOdt() }
    }

    private fun keysFor(ids: List<UUID>): Map<UUID, List<Pair<String, ShareRecipient>>> =
        if (ids.isEmpty()) emptyMap() else ShareRequestKeysTable.selectAll()
            .where { ShareRequestKeysTable.requestId inList ids }
            .orderBy(ShareRequestKeysTable.deviceId to SortOrder.ASC)
            .groupBy(
                { it[ShareRequestKeysTable.requestId] },
                {
                    it[ShareRequestKeysTable.role] to ShareRecipient(
                        userId = it[ShareRequestKeysTable.userId],
                        deviceId = it[ShareRequestKeysTable.deviceId],
                        sealedKey = it[ShareRequestKeysTable.sealedKey],
                    )
                },
            )

    private fun ResultRow.toRequest(keys: List<Pair<String, ShareRecipient>>) = ShareRequest(
        id = this[ShareRequestsTable.id],
        requesterId = this[ShareRequestsTable.requesterId],
        targetId = this[ShareRequestsTable.targetId],
        encryptedPlace = this[ShareRequestsTable.encryptedPlace],
        transitions = this[ShareRequestsTable.transitions].split(',').filter { it.isNotBlank() }.map { Transition.valueOf(it) }.toSet(),
        note = this[ShareRequestsTable.note],
        status = ShareRequestStatus.valueOf(this[ShareRequestsTable.status]),
        shareId = this[ShareRequestsTable.shareId],
        createdAt = this[ShareRequestsTable.createdAt].toInstant(),
        updatedAt = this[ShareRequestsTable.updatedAt].toInstant(),
        expiresAt = this[ShareRequestsTable.expiresAt].toInstant(),
        ownerKeys = keys.filter { it.first == ROLE_OWNER }.map { it.second },
        recipientKeys = keys.filter { it.first == ROLE_RECIPIENT }.map { it.second },
    )

    private companion object {
        const val ROLE_OWNER = "OWNER"
        const val ROLE_RECIPIENT = "RECIPIENT"
    }
}
