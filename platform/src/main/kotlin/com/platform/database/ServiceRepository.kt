package com.platform.database

import com.platform.models.Service
import com.platform.shared.models.OrganizationId
import com.platform.shared.models.Page
import com.platform.shared.models.ServiceId
import com.platform.shared.models.decodeCursor
import com.platform.shared.models.encodeCursor
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
                it[id] = UUID.fromString(service.id.value)
                it[organizationId] = UUID.fromString(service.organizationId.value)
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

    suspend fun findById(id: ServiceId): Service? =
        newSuspendedTransaction {
            Services
                .selectAll()
                .where { Services.id eq UUID.fromString(id.value) }
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
        organizationId: OrganizationId? = null,
        cluster: String? = null,
        namespace: String? = null,
        limit: Int = DEFAULT_PAGE_SIZE,
        cursor: String? = null,
    ): Page<Service> =
        newSuspendedTransaction {
            val pageLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
            val conditions = mutableListOf<Op<Boolean>>()

            organizationId?.let {
                conditions.add(Services.organizationId eq UUID.fromString(it.value))
            }
            cluster?.let {
                conditions.add(Services.cluster eq it)
            }
            namespace?.let {
                conditions.add(Services.namespace eq it)
            }
            cursor?.let {
                val (cursorTimestamp, cursorId) =
                    decodeCursor(it) ?: throw IllegalArgumentException("Invalid cursor")
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
                    encodeCursor(items.last().discoveredAt, items.last().id.value)
                } else {
                    null
                }

            Page(items = items, nextCursor = nextCursor)
        }

    suspend fun upsert(service: Service): Service =
        newSuspendedTransaction {
            Services.upsert(
                keys = arrayOf(Services.organizationId, Services.cluster, Services.namespace, Services.name),
                onUpdateExclude =
                    listOf(
                        Services.id,
                        Services.organizationId,
                        Services.cluster,
                        Services.namespace,
                        Services.name,
                        Services.discoveredAt,
                    ),
            ) {
                it[id] = UUID.fromString(service.id.value)
                it[organizationId] = UUID.fromString(service.organizationId.value)
                it[cluster] = service.cluster
                it[namespace] = service.namespace
                it[name] = service.name
                it[provider] = service.provider
                it[discoveredAt] = service.discoveredAt
                it[lastSeenAt] = service.lastSeenAt
                it[metadata] = service.metadata
            }

            // Post-select is required because on conflict the caller's service.id is
            // ignored and the existing row's id is kept. Exposed's upsert() doesn't
            // support RETURNING; upsertReturning() requires Exposed 0.58+.
            Services
                .selectAll()
                .where {
                    (Services.organizationId eq UUID.fromString(service.organizationId.value)) and
                        (Services.cluster eq service.cluster) and
                        (Services.namespace eq service.namespace) and
                        (Services.name eq service.name)
                }.map { it.toService() }
                .single()
        }

    suspend fun delete(id: ServiceId): Boolean =
        newSuspendedTransaction {
            Services.deleteWhere { Services.id eq UUID.fromString(id.value) } > 0
        }

    private fun ResultRow.toService(): Service =
        Service(
            id = ServiceId(this[Services.id].toString()),
            organizationId = OrganizationId(this[Services.organizationId].toString()),
            cluster = this[Services.cluster],
            namespace = this[Services.namespace],
            name = this[Services.name],
            provider = this[Services.provider],
            discoveredAt = this[Services.discoveredAt],
            lastSeenAt = this[Services.lastSeenAt],
            metadata = this[Services.metadata],
        )
}
