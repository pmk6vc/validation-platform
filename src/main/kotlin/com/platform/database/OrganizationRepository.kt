package com.platform.database

import com.platform.models.Organization
import com.platform.models.Page
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * Repository for Organization CRUD operations.
 */
object OrganizationRepository {
    const val DEFAULT_PAGE_SIZE = 20
    const val MAX_PAGE_SIZE = 100

    fun create(organization: Organization): Organization =
        transaction {
            Organizations.insert {
                it[id] = UUID.fromString(organization.id)
                it[name] = organization.name
                it[createdAt] = organization.createdAt
            }
            organization
        }

    fun findById(id: String): Organization? =
        transaction {
            Organizations
                .selectAll()
                .where { Organizations.id eq UUID.fromString(id) }
                .map { it.toOrganization() }
                .singleOrNull()
        }

    /**
     * Find organizations with cursor-based pagination.
     *
     * @param limit Maximum number of items to return (default 20, max 100)
     * @param cursor Cursor from previous page's nextCursor, null for first page
     * @return Page containing organizations and nextCursor for pagination
     */
    fun find(
        limit: Int = DEFAULT_PAGE_SIZE,
        cursor: String? = null,
    ): Page<Organization> =
        transaction {
            val pageLimit = limit.coerceIn(1, MAX_PAGE_SIZE)

            val query =
                if (cursor != null) {
                    Organizations.selectAll().where { Organizations.id greater UUID.fromString(cursor) }
                } else {
                    Organizations.selectAll()
                }

            val results =
                query
                    .orderBy(Organizations.id, SortOrder.ASC)
                    .limit(pageLimit + 1)
                    .map { it.toOrganization() }

            val hasMore = results.size > pageLimit
            val items = if (hasMore) results.dropLast(1) else results
            val nextCursor = if (hasMore) items.last().id else null

            Page(items = items, nextCursor = nextCursor)
        }

    /**
     * Find all organizations without pagination.
     * Prefer using find() with pagination for large datasets.
     */
    fun findAll(): List<Organization> =
        transaction {
            Organizations
                .selectAll()
                .map { it.toOrganization() }
        }

    fun delete(id: String): Boolean =
        transaction {
            Organizations.deleteWhere { Organizations.id eq UUID.fromString(id) } > 0
        }

    private fun ResultRow.toOrganization(): Organization =
        Organization(
            id = this[Organizations.id].toString(),
            name = this[Organizations.name],
            createdAt = this[Organizations.createdAt],
        )
}
