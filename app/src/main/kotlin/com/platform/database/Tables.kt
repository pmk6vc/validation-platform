package com.platform.database

import com.platform.models.Provider
import com.platform.models.capture.InputType
import com.platform.models.capture.TrafficClassification
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
    val provider = enumerationByName<Provider>("provider", 50).default(Provider.UNKNOWN)
    val discoveredAt = timestamp("discovered_at")
    val lastSeenAt = timestamp("last_seen_at")
    val metadata = jsonb<Map<String, String>>("metadata", Json.Default).nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_service_identity", organizationId, cluster, namespace, name)
    }
}

/**
 * Exposed table definition for captured inputs.
 *
 * Stores protocol-agnostic captured inputs (HTTP requests, Kafka messages, etc.)
 * observed from production traffic for later replay during validation.
 */
object CapturedInputs : Table("captured_inputs") {
    val id = uuid("id")
    val serviceId = uuid("service_id").references(Services.id)
    val inputType = enumerationByName<InputType>("input_type", 50)
    val classification = enumerationByName<TrafficClassification>("classification", 50)
    val method = varchar("method", 20).nullable()
    val url = text("url").nullable()
    val requestHeaders = jsonb<Map<String, String>>("request_headers", Json.Default).nullable()
    val requestBody = text("request_body").nullable()
    val responseStatus = integer("response_status").nullable()
    val responseHeaders = jsonb<Map<String, String>>("response_headers", Json.Default).nullable()
    val responseBody = text("response_body").nullable()
    val latencyMs = long("latency_ms").nullable()
    val sourceIp = varchar("source_ip", 45).nullable()
    val destinationIp = varchar("destination_ip", 45).nullable()
    val capturedAt = timestamp("captured_at")

    override val primaryKey = PrimaryKey(id)
}
