package com.platform.database

import com.platform.models.Organization
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * Repository for Organization CRUD operations.
 */
object OrganizationRepository {
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
            createdAt = this[Organizations.createdAt]
        )
}
