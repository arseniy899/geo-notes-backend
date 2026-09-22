package com.geonotes.backend.domain.service

import com.geonotes.backend.billing.PlayPurchaseVerifier
import com.geonotes.backend.domain.ConflictException
import com.geonotes.backend.domain.ValidationException
import com.geonotes.backend.domain.model.Entitlement
import com.geonotes.backend.domain.model.UserId
import com.geonotes.backend.domain.repository.EntitlementRepository
import java.time.Clock

class EntitlementService(
    private val verifier: PlayPurchaseVerifier,
    private val entitlements: EntitlementRepository,
    private val userService: UserService,
    private val clock: Clock,
    private val knownProductIds: Set<String> = setOf("pro_monthly", "pro_yearly", "pro_lifetime"),
) {
    suspend fun verify(userId: UserId, productId: String, purchaseToken: String): Entitlement {
        userService.requireRegistered(userId)
        if (productId !in knownProductIds) throw ValidationException("Unknown productId", "unknown_product")
        // A purchase token must never unlock Pro for more than one account.
        val existing = entitlements.findByPurchaseToken(purchaseToken)
        if (existing != null && existing.userId != userId) {
            throw ConflictException("Purchase token already bound to another account", "purchase_token_in_use")
        }
        val result = verifier.verify(productId, purchaseToken)
        return entitlements.upsert(
            Entitlement(
                userId = userId,
                productId = productId,
                purchaseToken = purchaseToken,
                pro = result.valid,
                expiresAt = result.expiresAt,
                verifiedAt = clock.instant(),
            ),
        )
    }
}
