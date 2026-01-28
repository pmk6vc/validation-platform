package com.platform.database

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb

/**
 * Exposed table definition for organizations.
 */
object Organizations : Table("organizations") {
    val id = uuid("id")
    val name = varchar("name", 255)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Exposed table definition for services.
 *
 * Unique constraint on (organization_id, cluster, namespace, name).
 */
object Services : Table("services") {
    val id = uuid("id")
    val organizationId = uuid("organization_id").references(Organizations.id)
    val cluster = varchar("cluster", 255)
    val namespace = varchar("namespace", 255)
    val name = varchar("name", 255)
    val provider = varchar("provider", 50).nullable()
    val discoveredAt = timestamp("discovered_at")
    val lastSeenAt = timestamp("last_seen_at")
    val metadata = jsonb<Map<String, String>>("metadata", Json.Default).nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_service_identity", organizationId, cluster, namespace, name)
    }
}
