package com.platform.database

import com.platform.models.Page
import com.platform.models.Service
import com.platform.models.decodeCursor
import com.platform.models.encodeCursor
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import java.util.UUID

/**
 * Repository for Service CRUD operations.
 */
object ServiceRepository {
    const val DEFAULT_PAGE_SIZE = 20
    const val MAX_PAGE_SIZE = 100

    suspend fun create(service: Service): Service =
        newSuspendedTransaction {
            Services.insert {
                it[id] = UUID.fromString(service.id)
                it[organizationId] = UUID.fromString(service.organizationId)
                it[cluster] = service.cluster
                it[namespace] = service.namespace
                it[name] = service.name
                it[provider] = service.provider
                it[discoveredAt] = service.discoveredAt
                it[lastSeenAt] = service.lastSeenAt
                it[metadata] = service.metadata
            }
            service
        }

    suspend fun findById(id: String): Service? =
        newSuspendedTransaction {
            Services
                .selectAll()
                .where { Services.id eq UUID.fromString(id) }
                .map { it.toService() }
                .singleOrNull()
        }

    /**
     * Find services with optional filters and cursor-based pagination.
     *
     * @param organizationId Filter by organization
     * @param cluster Filter by cluster
     * @param namespace Filter by namespace
     * @param limit Maximum number of items to return (default 20, max 100)
     * @param cursor Cursor from previous page's nextCursor, null for first page
     * @return Page containing services and nextCursor for pagination
     */
    suspend fun find(
        organizationId: String? = null,
        cluster: String? = null,
        namespace: String? = null,
        limit: Int = DEFAULT_PAGE_SIZE,
        cursor: String? = null,
    ): Page<Service> =
        newSuspendedTransaction {
            val pageLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
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
            cursor?.let {
                val (cursorTimestamp, cursorId) = decodeCursor(it)
                conditions.add(
                    (Services.discoveredAt greater cursorTimestamp) or
                        ((Services.discoveredAt eq cursorTimestamp) and (Services.id greater cursorId)),
                )
            }

            val query =
                if (conditions.isEmpty()) {
                    Services.selectAll()
                } else {
                    Services.selectAll().where { conditions.reduce { acc, op -> acc and op } }
                }

            // Fetch one extra to determine if there's a next page
            val results =
                query
                    .orderBy(Services.discoveredAt to SortOrder.ASC, Services.id to SortOrder.ASC)
                    .limit(pageLimit + 1)
                    .map { it.toService() }

            val hasMore = results.size > pageLimit
            val items = if (hasMore) results.dropLast(1) else results
            val nextCursor =
                if (hasMore) {
                    encodeCursor(items.last().discoveredAt, items.last().id)
                } else {
                    null
                }

            Page(items = items, nextCursor = nextCursor)
        }

    suspend fun upsert(service: Service): Service =
        newSuspendedTransaction {
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
                    it[lastSeenAt] = service.lastSeenAt
                    it[metadata] = service.metadata
                }
                service.copy(id = existingId.toString())
            } else {
                create(service)
            }
        }

    suspend fun delete(id: String): Boolean =
        newSuspendedTransaction {
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
            lastSeenAt = this[Services.lastSeenAt],
            metadata = this[Services.metadata],
        )
}
