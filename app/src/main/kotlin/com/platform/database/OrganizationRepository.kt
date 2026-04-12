package com.platform.database

import com.platform.models.Organization
import com.platform.models.Page
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
import java.util.UUID

/**
 * Repository for Organization CRUD operations.
 */
class OrganizationRepository {
    companion object {
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 100
    }

    suspend fun create(organization: Organization): Organization =
        newSuspendedTransaction {
            Organizations.insert {
                it[id] = UUID.fromString(organization.id)
                it[name] = organization.name
                it[createdAt] = organization.createdAt
            }
            organization
        }

    suspend fun findById(id: String): Organization? =
        newSuspendedTransaction {
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
    suspend fun find(
        limit: Int = DEFAULT_PAGE_SIZE,
        cursor: String? = null,
    ): Page<Organization> =
        newSuspendedTransaction {
            val pageLimit = limit.coerceIn(1, MAX_PAGE_SIZE)

            val query =
                if (cursor != null) {
                    val (cursorTimestamp, cursorId) =
                        decodeCursor(cursor) ?: throw IllegalArgumentException("Invalid cursor")
                    val cursorCondition: Op<Boolean> =
                        (Organizations.createdAt greater cursorTimestamp) or
                            ((Organizations.createdAt eq cursorTimestamp) and (Organizations.id greater cursorId))
                    Organizations.selectAll().where { cursorCondition }
                } else {
                    Organizations.selectAll()
                }

            val results =
                query
                    .orderBy(Organizations.createdAt to SortOrder.ASC, Organizations.id to SortOrder.ASC)
                    .limit(pageLimit + 1)
                    .map { it.toOrganization() }

            val hasMore = results.size > pageLimit
            val items = if (hasMore) results.dropLast(1) else results
            val nextCursor =
                if (hasMore) {
                    encodeCursor(items.last().createdAt, items.last().id)
                } else {
                    null
                }

            Page(items = items, nextCursor = nextCursor)
        }

    suspend fun delete(id: String): Boolean =
        newSuspendedTransaction {
            Organizations.deleteWhere { Organizations.id eq UUID.fromString(id) } > 0
        }

    private fun ResultRow.toOrganization(): Organization =
        Organization(
            id = this[Organizations.id].toString(),
            name = this[Organizations.name],
            createdAt = this[Organizations.createdAt],
        )
}
