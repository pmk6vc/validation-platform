package com.platform.collector.database

import com.platform.models.capture.InputType
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb

object CapturedInputs : Table("captured_inputs") {
    val id = uuid("id")
    val serviceId = uuid("service_id")
    val inputType = enumerationByName<InputType>("input_type", 50)
    val method = varchar("method", 20)
    val url = text("url")
    val requestHeaders = jsonb<Map<String, String>>("request_headers", Json.Default).nullable()
    val requestBody = text("request_body").nullable()
    val responseStatus = integer("response_status")
    val responseHeaders = jsonb<Map<String, String>>("response_headers", Json.Default).nullable()
    val responseBody = text("response_body").nullable()
    val latencyMs = long("latency_ms").nullable()
    val sourceIp = varchar("source_ip", 45).nullable()
    val destinationIp = varchar("destination_ip", 45).nullable()
    val capturedAt = timestamp("captured_at")

    override val primaryKey = PrimaryKey(id)
}
