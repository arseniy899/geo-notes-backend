package com.geonotes.backend.persistence

import com.geonotes.backend.domain.model.Entitlement
import com.geonotes.backend.domain.model.UserId
import com.geonotes.backend.domain.repository.EntitlementRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

class ExposedEntitlementRepository(database: Database) : ExposedRepository(database), EntitlementRepository {
    override suspend fun find(userId: UserId): Entitlement? = dbQuery {
        EntitlementsTable.selectAll().where { EntitlementsTable.userId eq userId }.singleOrNull()?.toEntitlement()
    }

    override suspend fun findByPurchaseToken(purchaseToken: String): Entitlement? = dbQuery {
        EntitlementsTable.selectAll().where { EntitlementsTable.purchaseToken eq purchaseToken }.singleOrNull()?.toEntitlement()
    }

    override suspend fun upsert(entitlement: Entitlement): Entitlement = dbQuery {
        EntitlementsTable.upsert(EntitlementsTable.userId) {
            it[userId] = entitlement.userId
            it[productId] = entitlement.productId
            it[purchaseToken] = entitlement.purchaseToken
            it[pro] = entitlement.pro
            it[expiresAt] = entitlement.expiresAt?.toOdt()
            it[verifiedAt] = entitlement.verifiedAt.toOdt()
        }
        entitlement
    }

    private fun ResultRow.toEntitlement() = Entitlement(
        userId = this[EntitlementsTable.userId],
        productId = this[EntitlementsTable.productId],
        purchaseToken = this[EntitlementsTable.purchaseToken],
        pro = this[EntitlementsTable.pro],
        expiresAt = this[EntitlementsTable.expiresAt]?.toInstant(),
        verifiedAt = this[EntitlementsTable.verifiedAt].toInstant(),
    )
}
