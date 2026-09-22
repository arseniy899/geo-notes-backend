package com.geonotes.backend.persistence

import com.geonotes.backend.domain.model.Device
import com.geonotes.backend.domain.model.Platform
import com.geonotes.backend.domain.model.UserId
import com.geonotes.backend.domain.repository.DeviceRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert

class ExposedDeviceRepository(database: Database) : ExposedRepository(database), DeviceRepository {
    override suspend fun upsert(device: Device): Device = dbQuery {
        DevicesTable.upsert(DevicesTable.id, onUpdateExclude = listOf(DevicesTable.createdAt)) {
            it[id] = device.id
            it[userId] = device.userId
            it[fcmToken] = device.fcmToken
            it[publicKey] = device.publicKey
            it[platform] = device.platform.name
            it[createdAt] = device.createdAt.toOdt()
            it[updatedAt] = device.updatedAt.toOdt()
        }
        DevicesTable.selectAll().where { DevicesTable.id eq device.id }.single().toDevice()
    }

    override suspend fun find(id: String): Device? = dbQuery {
        DevicesTable.selectAll().where { DevicesTable.id eq id }.singleOrNull()?.toDevice()
    }

    override suspend fun findByIds(ids: Collection<String>): List<Device> =
        if (ids.isEmpty()) emptyList() else dbQuery {
            DevicesTable.selectAll().where { DevicesTable.id inList ids }.map { it.toDevice() }
        }

    override suspend fun listByUser(userId: UserId): List<Device> = dbQuery {
        DevicesTable.selectAll().where { DevicesTable.userId eq userId }.orderBy(DevicesTable.createdAt).map { it.toDevice() }
    }

    override suspend fun delete(userId: UserId, deviceId: String): Boolean = dbQuery {
        DevicesTable.deleteWhere { (DevicesTable.id eq deviceId) and (DevicesTable.userId eq userId) } > 0
    }

    override suspend fun clearFcmTokens(tokens: Collection<String>) {
        if (tokens.isEmpty()) return
        dbQuery { DevicesTable.update({ DevicesTable.fcmToken inList tokens }) { it[fcmToken] = null } }
    }

    private fun ResultRow.toDevice() = Device(
        id = this[DevicesTable.id],
        userId = this[DevicesTable.userId],
        fcmToken = this[DevicesTable.fcmToken],
        publicKey = this[DevicesTable.publicKey],
        platform = Platform.valueOf(this[DevicesTable.platform]),
        createdAt = this[DevicesTable.createdAt].toInstant(),
        updatedAt = this[DevicesTable.updatedAt].toInstant(),
    )
}
