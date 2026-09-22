package com.geonotes.backend.persistence

import com.geonotes.backend.domain.model.Friend
import com.geonotes.backend.domain.model.FriendPair
import com.geonotes.backend.domain.model.UserId
import com.geonotes.backend.domain.repository.FriendshipRepository
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant

class ExposedFriendshipRepository(database: Database) : ExposedRepository(database), FriendshipRepository {
    override suspend fun add(pair: FriendPair, now: Instant): Boolean = dbQuery {
        FriendshipsTable.insertIgnore {
            it[userA] = pair.userA
            it[userB] = pair.userB
            it[createdAt] = now.toOdt()
        }.insertedCount > 0
    }

    override suspend fun exists(pair: FriendPair): Boolean = dbQuery {
        FriendshipsTable.selectAll()
            .where { (FriendshipsTable.userA eq pair.userA) and (FriendshipsTable.userB eq pair.userB) }
            .empty().not()
    }

    override suspend fun delete(pair: FriendPair): Boolean = dbQuery {
        FriendshipsTable.deleteWhere { (FriendshipsTable.userA eq pair.userA) and (FriendshipsTable.userB eq pair.userB) } > 0
    }

    override suspend fun listFriends(userId: UserId): List<Friend> = dbQuery {
        FriendshipsTable
            .join(
                UsersTable, JoinType.INNER,
                additionalConstraint = {
                    ((FriendshipsTable.userA eq userId) and (UsersTable.id eq FriendshipsTable.userB)) or
                        ((FriendshipsTable.userB eq userId) and (UsersTable.id eq FriendshipsTable.userA))
                },
            )
            .selectAll()
            .orderBy(UsersTable.displayName)
            .map {
                Friend(
                    userId = it[UsersTable.id],
                    displayName = it[UsersTable.displayName],
                    since = it[FriendshipsTable.createdAt].toInstant(),
                )
            }
    }
}
