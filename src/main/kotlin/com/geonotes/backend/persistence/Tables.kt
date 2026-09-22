package com.geonotes.backend.persistence

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/** Exposed mappings of the Flyway-managed schema (see db/migration/V*.sql). Schema is never auto-created. */
object UsersTable : Table("users") {
    val id = varchar("id", 128)
    val displayName = varchar("display_name", 64)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object DevicesTable : Table("devices") {
    val id = varchar("id", 64)
    val userId = reference("user_id", UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val fcmToken = text("fcm_token").nullable()
    val publicKey = binary("public_key")
    val platform = varchar("platform", 16)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object InvitesTable : Table("invites") {
    val code = varchar("code", 16)
    val inviterId = reference("inviter_id", UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestampWithTimeZone("created_at")
    val expiresAt = timestampWithTimeZone("expires_at")
    val acceptedBy = optReference("accepted_by", UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val acceptedAt = timestampWithTimeZone("accepted_at").nullable()
    override val primaryKey = PrimaryKey(code)
}

object FriendshipsTable : Table("friendships") {
    val userA = reference("user_a", UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val userB = reference("user_b", UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(userA, userB)
}

object SharesTable : Table("shares") {
    val id = javaUUID("id")
    val ownerId = reference("owner_id", UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val encryptedPlace = binary("encrypted_place")
    val transitions = varchar("transitions", 32)
    val active = bool("active")
    val pausedUntil = timestampWithTimeZone("paused_until").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object ShareRecipientsTable : Table("share_recipients") {
    val shareId = reference("share_id", SharesTable.id, onDelete = ReferenceOption.CASCADE)
    val deviceId = reference("device_id", DevicesTable.id, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val sealedKey = binary("sealed_key")
    override val primaryKey = PrimaryKey(shareId, deviceId)
}

object ShareOwnerKeysTable : Table("share_owner_keys") {
    val shareId = reference("share_id", SharesTable.id, onDelete = ReferenceOption.CASCADE)
    val deviceId = reference("device_id", DevicesTable.id, onDelete = ReferenceOption.CASCADE)
    val sealedKey = binary("sealed_key")
    override val primaryKey = PrimaryKey(shareId, deviceId)
}

object ShareRequestsTable : Table("share_requests") {
    val id = javaUUID("id")
    val requesterId = reference("requester_id", UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val targetId = reference("target_id", UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val encryptedPlace = binary("encrypted_place")
    val transitions = varchar("transitions", 32)
    val note = varchar("note", 140).nullable()
    val status = varchar("status", 16)
    val shareId = optReference("share_id", SharesTable.id, onDelete = ReferenceOption.SET_NULL)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val expiresAt = timestampWithTimeZone("expires_at")
    override val primaryKey = PrimaryKey(id)
}

object ShareRequestKeysTable : Table("share_request_keys") {
    val requestId = reference("request_id", ShareRequestsTable.id, onDelete = ReferenceOption.CASCADE)
    val deviceId = reference("device_id", DevicesTable.id, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val role = varchar("role", 16)
    val sealedKey = binary("sealed_key")
    override val primaryKey = PrimaryKey(requestId, deviceId)
}

object EventsTable : Table("events") {
    val id = javaUUID("id")
    val shareId = reference("share_id", SharesTable.id, onDelete = ReferenceOption.CASCADE)
    val ownerId = reference("owner_id", UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val transition = varchar("transition", 8)
    val occurredAt = timestampWithTimeZone("occurred_at")
    val receivedAt = timestampWithTimeZone("received_at")
    val expiresAt = timestampWithTimeZone("expires_at")
    override val primaryKey = PrimaryKey(id)
}

object EntitlementsTable : Table("entitlements") {
    val userId = reference("user_id", UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val productId = varchar("product_id", 64)
    val purchaseToken = text("purchase_token")
    val pro = bool("pro")
    val expiresAt = timestampWithTimeZone("expires_at").nullable()
    val verifiedAt = timestampWithTimeZone("verified_at")
    override val primaryKey = PrimaryKey(userId)
}
