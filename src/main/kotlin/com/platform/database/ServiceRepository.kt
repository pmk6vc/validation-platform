package com.platform.database

import com.platform.models.Service
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * Repository for Service CRUD operations.
 */
object ServiceRepository {
    fun create(service: Service): Service =
        transaction {
            Services.insert {
                it[id] = UUID.fromString(service.id)
                it[organizationId] = UUID.fromString(service.organizationId)
                it[cluster] = service.cluster
                it[namespace] = service.namespace
                it[name] = service.name
                it[provider] = service.provider
                it[discoveredAt] = service.discoveredAt
                it[metadata] = service.metadata
            }
            service
        }

    fun findById(id: String): Service? =
        transaction {
            Services
                .selectAll()
                .where { Services.id eq UUID.fromString(id) }
                .map { it.toService() }
                .singleOrNull()
        }

    fun findAll(): List<Service> =
        transaction {
            Services
                .selectAll()
                .map { it.toService() }
        }

    fun find(
        organizationId: String? = null,
        cluster: String? = null,
        namespace: String? = null
    ): List<Service> =
        transaction {
            val conditions = mutableListOf<Op<Boolean>>()

            organizationId?.let {
                conditions.add(Services.organizationId eq UUID.fromString(it))
            }
            cluster?.let {
                conditions.add(Services.cluster eq it)
            }
            namespace?.let {
                conditions.add(Services.namespace eq it)
            }

            if (conditions.isEmpty()) {
                Services.selectAll()
            } else {
                Services.selectAll().where { conditions.reduce { acc, op -> acc and op } }
            }.map { it.toService() }
        }

    fun upsert(service: Service): Service =
        transaction {
            val existingId =
                Services
                    .selectAll()
                    .where {
                        (Services.organizationId eq UUID.fromString(service.organizationId)) and
                            (Services.cluster eq service.cluster) and
                            (Services.namespace eq service.namespace) and
                            (Services.name eq service.name)
                    }.map { it[Services.id] }
                    .singleOrNull()

            if (existingId != null) {
                Services.update({ Services.id eq existingId }) {
                    it[provider] = service.provider
                    it[metadata] = service.metadata
                }
                service.copy(id = existingId.toString())
            } else {
                create(service)
            }
        }

    fun delete(id: String): Boolean =
        transaction {
            Services.deleteWhere { Services.id eq UUID.fromString(id) } > 0
        }

    private fun ResultRow.toService(): Service =
        Service(
            id = this[Services.id].toString(),
            organizationId = this[Services.organizationId].toString(),
            cluster = this[Services.cluster],
            namespace = this[Services.namespace],
            name = this[Services.name],
            provider = this[Services.provider],
            discoveredAt = this[Services.discoveredAt],
            metadata = this[Services.metadata]
        )
}
