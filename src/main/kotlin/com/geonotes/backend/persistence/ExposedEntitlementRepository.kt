package com.geonotes.backend.persistence

import com.geonotes.backend.domain.ConflictException
import com.geonotes.backend.domain.model.Entitlement
import com.geonotes.backend.domain.model.EntitlementState
import com.geonotes.backend.domain.model.UserId
import com.geonotes.backend.domain.repository.EntitlementRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.sql.SQLException

class ExposedEntitlementRepository(database: Database) : ExposedRepository(database), EntitlementRepository {
    override suspend fun find(userId: UserId): Entitlement? = dbQuery {
        EntitlementsTable.selectAll().where { EntitlementsTable.userId eq userId }.singleOrNull()?.toEntitlement()
    }

    override suspend fun findByTokenHash(tokenHash: String): Entitlement? = dbQuery {
        EntitlementsTable.selectAll().where { EntitlementsTable.tokenHash eq tokenHash }.singleOrNull()?.toEntitlement()
    }

    override suspend fun upsert(entitlement: Entitlement): Entitlement = try {
        write(entitlement)
    } catch (e: Exception) {
        // Two accounts racing to bind the same token: the UNIQUE(token_hash) constraint decides.
        if (e.isUniqueViolation()) throw ConflictException("Purchase token already bound to another account", "purchase_token_in_use")
        throw e
    }

    private suspend fun write(entitlement: Entitlement): Entitlement = dbQuery {
        EntitlementsTable.upsert(EntitlementsTable.userId) {
            it[userId] = entitlement.userId
            it[productId] = entitlement.productId
            it[purchaseToken] = entitlement.purchaseToken
            it[tokenHash] = entitlement.tokenHash
            it[state] = entitlement.state.name
            it[expiresAt] = entitlement.expiresAt?.toOdt()
            it[autoRenewing] = entitlement.autoRenewing
            it[acknowledged] = entitlement.acknowledged
            it[testPurchase] = entitlement.testPurchase
            it[lastVerifiedAt] = entitlement.lastVerifiedAt.toOdt()
        }
        entitlement
    }

    private fun Throwable.isUniqueViolation(): Boolean =
        generateSequence(this) { it.cause }.any { it is SQLException && it.sqlState == "23505" }

    private fun ResultRow.toEntitlement() = Entitlement(
        userId = this[EntitlementsTable.userId],
        productId = this[EntitlementsTable.productId],
        purchaseToken = this[EntitlementsTable.purchaseToken],
        state = EntitlementState.valueOf(this[EntitlementsTable.state]),
        expiresAt = this[EntitlementsTable.expiresAt]?.toInstant(),
        autoRenewing = this[EntitlementsTable.autoRenewing],
        acknowledged = this[EntitlementsTable.acknowledged],
        testPurchase = this[EntitlementsTable.testPurchase],
        lastVerifiedAt = this[EntitlementsTable.lastVerifiedAt].toInstant(),
    )
}
