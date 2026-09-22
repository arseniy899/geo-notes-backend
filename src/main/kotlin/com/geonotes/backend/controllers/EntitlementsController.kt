package com.geonotes.backend.controllers

import com.geonotes.backend.api.model.EntitlementResponse
import com.geonotes.backend.api.model.VerifyPurchaseRequest
import com.geonotes.backend.domain.model.UserId
import com.geonotes.backend.domain.service.EntitlementService
import java.time.Clock

class EntitlementsController(private val entitlements: EntitlementService, private val clock: Clock) {
    suspend fun verify(caller: UserId, request: VerifyPurchaseRequest): EntitlementResponse =
        entitlements.verify(caller, request.productId.trim(), request.purchaseToken.trim()).toResponse(clock.instant())

    suspend fun current(caller: UserId): EntitlementResponse = entitlements.current(caller).toResponse(clock.instant())
}
