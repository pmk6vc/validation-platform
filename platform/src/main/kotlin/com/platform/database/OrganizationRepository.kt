package com.platform.database

import com.platform.models.Organization
import com.platform.shared.models.OrganizationId
import com.platform.shared.models.Page
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
import java.security.SecureRandom
import java.util.UUID

/**
 * Repository for Organization CRUD operations.
 */
object OrganizationRepository {
    const val DEFAULT_PAGE_SIZE = 20
    const val MAX_PAGE_SIZE = 100

    /**
     * Length of the per-org redaction salt in bytes (V0008). 32 bytes →
     * 64 hex chars. Matches gen_random_bytes(32) used in the migration.
     */
    private const val REDACTION_SALT_BYTES = 32
    private val secureRandom = SecureRandom()

    /**
     * Create a new organization. If [Organization.redactionSalt] is blank,
     * a fresh 32-byte hex-encoded salt is generated for it (so callers can
     * construct from REST input without minting a salt themselves).
     */
    suspend fun create(organization: Organization): Organization =
        newSuspendedTransaction {
            val salt =
                organization.redactionSalt.ifBlank { generateHexSalt() }
            val effective = if (salt == organization.redactionSalt) organization else organization.copy(redactionSalt = salt)
            Organizations.insert {
                it[id] = UUID.fromString(effective.id.value)
                it[name] = effective.name
                it[createdAt] = effective.createdAt
                it[redactionSalt] = effective.redactionSalt
            }
            effective
        }

    private fun generateHexSalt(): String {
        val bytes = ByteArray(REDACTION_SALT_BYTES)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    suspend fun findById(id: OrganizationId): Organization? =
        newSuspendedTransaction {
            Organizations
                .selectAll()
                .where { Organizations.id eq UUID.fromString(id.value) }
                .map { it.toOrganization() }
                .singleOrNull()
        }

    /**
     * Find organizations with cursor-based pagination.
     *
     * @param organizationId Filter to a single org; pass the JWT principal's org to enforce tenant scoping.
     * @param limit Maximum number of items to return (default 20, max 100)
     * @param cursor Cursor from previous page's nextCursor, null for first page
     * @return Page containing organizations and nextCursor for pagination
     */
    suspend fun find(
        organizationId: OrganizationId? = null,
        limit: Int = DEFAULT_PAGE_SIZE,
        cursor: String? = null,
    ): Page<Organization> =
        newSuspendedTransaction {
            val pageLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
            val conditions = mutableListOf<Op<Boolean>>()

            organizationId?.let {
                conditions.add(Organizations.id eq UUID.fromString(it.value))
            }
            cursor?.let {
                val (cursorTimestamp, cursorId) =
                    decodeCursor(it) ?: throw IllegalArgumentException("Invalid cursor")
                conditions.add(
                    (Organizations.createdAt greater cursorTimestamp) or
                        ((Organizations.createdAt eq cursorTimestamp) and (Organizations.id greater cursorId)),
                )
            }

            val query =
                if (conditions.isEmpty()) {
                    Organizations.selectAll()
                } else {
                    Organizations.selectAll().where { conditions.reduce { acc, op -> acc and op } }
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
                    encodeCursor(items.last().createdAt, items.last().id.value)
                } else {
                    null
                }

            Page(items = items, nextCursor = nextCursor)
        }

    suspend fun delete(id: OrganizationId): Boolean =
        newSuspendedTransaction {
            Organizations.deleteWhere { Organizations.id eq UUID.fromString(id.value) } > 0
        }

    private fun ResultRow.toOrganization(): Organization =
        Organization(
            id = OrganizationId(this[Organizations.id].toString()),
            name = this[Organizations.name],
            createdAt = this[Organizations.createdAt],
            redactionSalt = this[Organizations.redactionSalt],
        )
}
