package com.geonotes.backend.domain.service

import com.geonotes.backend.domain.ConflictException
import com.geonotes.backend.domain.NotFoundException
import com.geonotes.backend.domain.ValidationException
import com.geonotes.backend.domain.model.Entitlement
import com.geonotes.backend.domain.model.User
import com.geonotes.backend.domain.model.UserId
import com.geonotes.backend.domain.repository.EntitlementRepository
import com.geonotes.backend.domain.repository.UserRepository
import java.time.Clock

class UserService(
    private val users: UserRepository,
    private val entitlements: EntitlementRepository,
    private val clock: Clock,
) {
    data class Profile(val user: User, val entitlement: Entitlement?)

    suspend fun upsert(userId: UserId, displayName: String): Profile {
        val name = displayName.trim()
        if (name.isEmpty() || name.length > MAX_DISPLAY_NAME) {
            throw ValidationException("displayName must be 1..$MAX_DISPLAY_NAME characters")
        }
        val user = users.upsert(userId, name, clock.instant())
        return Profile(user, entitlements.find(userId))
    }

    suspend fun get(userId: UserId): Profile {
        val user = users.find(userId) ?: throw NotFoundException("User not registered; call PUT /v1/me first", "user_not_registered")
        return Profile(user, entitlements.find(userId))
    }

    /** Account deletion (Google Play requirement). Cascades devices, invites, friendships, shares, events, entitlements. */
    suspend fun delete(userId: UserId) {
        if (!users.delete(userId)) throw NotFoundException("User not registered", "user_not_registered")
    }

    /** Most endpoints need a registered profile (FK target). */
    suspend fun requireRegistered(userId: UserId): User =
        users.find(userId) ?: throw ConflictException("User not registered; call PUT /v1/me first", "user_not_registered")

    companion object {
        const val MAX_DISPLAY_NAME = 64
    }
}
