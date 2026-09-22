package com.geonotes.backend.persistence

import com.geonotes.backend.domain.model.ReceivedShare
import com.geonotes.backend.domain.model.Share
import com.geonotes.backend.domain.model.ShareRecipient
import com.geonotes.backend.domain.model.Transition
import com.geonotes.backend.domain.model.UserId
import com.geonotes.backend.domain.repository.ShareRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

class ExposedShareRepository(database: Database) : ExposedRepository(database), ShareRepository {
    override suspend fun create(share: Share): Share = dbQuery {
        SharesTable.insert {
            it[id] = share.id
            it[ownerId] = share.ownerId
            it[encryptedPlace] = share.encryptedPlace
            it[transitions] = share.transitions.encode()
            it[active] = share.active
            it[pausedUntil] = share.pausedUntil?.toOdt()
            it[createdAt] = share.createdAt.toOdt()
            it[updatedAt] = share.updatedAt.toOdt()
        }
        ShareRecipientsTable.batchInsert(share.recipients) { r ->
            this[ShareRecipientsTable.shareId] = share.id
            this[ShareRecipientsTable.deviceId] = r.deviceId
            this[ShareRecipientsTable.userId] = r.userId
            this[ShareRecipientsTable.sealedKey] = r.sealedKey
        }
        if (share.ownerKeys.isNotEmpty()) {
            ShareOwnerKeysTable.batchInsert(share.ownerKeys) { k ->
                this[ShareOwnerKeysTable.shareId] = share.id
                this[ShareOwnerKeysTable.deviceId] = k.deviceId
                this[ShareOwnerKeysTable.sealedKey] = k.sealedKey
            }
        }
        share
    }

    override suspend fun find(id: UUID): Share? = dbQuery {
        val row = SharesTable.selectAll().where { SharesTable.id eq id }.singleOrNull() ?: return@dbQuery null
        row.toShare(recipientsFor(listOf(id))[id].orEmpty(), ownerKeysFor(listOf(id))[id].orEmpty())
    }

    override suspend fun listOwned(ownerId: UserId): List<Share> = dbQuery {
        val rows = SharesTable.selectAll().where { SharesTable.ownerId eq ownerId }.orderBy(SharesTable.createdAt).toList()
        val ids = rows.map { it[SharesTable.id] }
        val recipients = recipientsFor(ids)
        val ownerKeys = ownerKeysFor(ids)
        rows.map { it.toShare(recipients[it[SharesTable.id]].orEmpty(), ownerKeys[it[SharesTable.id]].orEmpty()) }
    }

    override suspend fun listReceived(userId: UserId): List<ReceivedShare> = dbQuery {
        val myRecipientRows = ShareRecipientsTable.selectAll().where { ShareRecipientsTable.userId eq userId }.toList()
        if (myRecipientRows.isEmpty()) return@dbQuery emptyList()
        val mine = myRecipientRows.groupBy({ it[ShareRecipientsTable.shareId] }, { it.toRecipient() })
        (SharesTable innerJoin UsersTable)
            .selectAll()
            .where { SharesTable.id inList mine.keys }
            .orderBy(SharesTable.createdAt)
            .map { ReceivedShare(it.toShare(mine[it[SharesTable.id]].orEmpty()), it[UsersTable.displayName]) }
    }

    override suspend fun countActive(ownerId: UserId): Int = dbQuery {
        SharesTable.selectAll().where { (SharesTable.ownerId eq ownerId) and (SharesTable.active eq true) }.count().toInt()
    }

    override suspend fun update(id: UUID, active: Boolean, pausedUntil: Instant?, now: Instant): Share? = dbQuery {
        val updated = SharesTable.update({ SharesTable.id eq id }) {
            it[SharesTable.active] = active
            it[SharesTable.pausedUntil] = pausedUntil?.toOdt()
            it[updatedAt] = now.toOdt()
        }
        if (updated == 0) return@dbQuery null
        val row = SharesTable.selectAll().where { SharesTable.id eq id }.single()
        row.toShare(recipientsFor(listOf(id))[id].orEmpty(), ownerKeysFor(listOf(id))[id].orEmpty())
    }

    override suspend fun delete(id: UUID): Boolean = dbQuery {
        SharesTable.deleteWhere { SharesTable.id eq id } > 0
    }

    override suspend fun removeRecipientsBetween(userA: UserId, userB: UserId): Int = dbQuery {
        val sharesAtoB = SharesTable.select(SharesTable.id).where { SharesTable.ownerId eq userA }.map { it[SharesTable.id] }
        val sharesBtoA = SharesTable.select(SharesTable.id).where { SharesTable.ownerId eq userB }.map { it[SharesTable.id] }
        var removed = 0
        if (sharesAtoB.isNotEmpty() || sharesBtoA.isNotEmpty()) {
            removed = ShareRecipientsTable.deleteWhere {
                ((ShareRecipientsTable.shareId inList sharesAtoB) and (ShareRecipientsTable.userId eq userB)) or
                    ((ShareRecipientsTable.shareId inList sharesBtoA) and (ShareRecipientsTable.userId eq userA))
            }
        }
        removed
    }

    private fun recipientsFor(shareIds: List<UUID>): Map<UUID, List<ShareRecipient>> =
        if (shareIds.isEmpty()) emptyMap() else ShareRecipientsTable.selectAll()
            .where { ShareRecipientsTable.shareId inList shareIds }
            .orderBy(ShareRecipientsTable.userId to org.jetbrains.exposed.v1.core.SortOrder.ASC, ShareRecipientsTable.deviceId to org.jetbrains.exposed.v1.core.SortOrder.ASC)
            .groupBy({ it[ShareRecipientsTable.shareId] }, { it.toRecipient() })

    private fun ownerKeysFor(shareIds: List<UUID>): Map<UUID, List<ShareRecipient>> =
        if (shareIds.isEmpty()) emptyMap() else (ShareOwnerKeysTable innerJoin SharesTable)
            .selectAll()
            .where { ShareOwnerKeysTable.shareId inList shareIds }
            .orderBy(ShareOwnerKeysTable.deviceId to org.jetbrains.exposed.v1.core.SortOrder.ASC)
            .groupBy(
                { it[ShareOwnerKeysTable.shareId] },
                { ShareRecipient(userId = it[SharesTable.ownerId], deviceId = it[ShareOwnerKeysTable.deviceId], sealedKey = it[ShareOwnerKeysTable.sealedKey]) },
            )

    private fun ResultRow.toRecipient() = ShareRecipient(
        userId = this[ShareRecipientsTable.userId],
        deviceId = this[ShareRecipientsTable.deviceId],
        sealedKey = this[ShareRecipientsTable.sealedKey],
    )

    private fun ResultRow.toShare(recipients: List<ShareRecipient>, ownerKeys: List<ShareRecipient> = emptyList()) = Share(
        id = this[SharesTable.id],
        ownerId = this[SharesTable.ownerId],
        encryptedPlace = this[SharesTable.encryptedPlace],
        transitions = decode(this[SharesTable.transitions]),
        active = this[SharesTable.active],
        pausedUntil = this[SharesTable.pausedUntil]?.toInstant(),
        createdAt = this[SharesTable.createdAt].toInstant(),
        updatedAt = this[SharesTable.updatedAt].toInstant(),
        recipients = recipients,
        ownerKeys = ownerKeys,
    )

    private fun Set<Transition>.encode(): String = map { it.name }.sorted().joinToString(",")

    private fun decode(value: String): Set<Transition> =
        value.split(',').filter { it.isNotBlank() }.map { Transition.valueOf(it) }.toSet()
}
