package com.platform.models.capture

import com.platform.models.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * A protocol-agnostic captured input observed from production traffic.
 *
 * Stores the full request/response pair so it can be replayed against a candidate
 * version during validation. For HTTP inputs, all fields are populated. For future
 * protocols (Kafka, gRPC), the null fields reflect which concepts do not apply.
 *
 * @param id Unique identifier (UUID)
 * @param serviceId References the service that received this input
 * @param inputType Protocol of the captured input
 * @param classification Whether this is a read or write operation (controls safe replay)
 * @param method HTTP method (null for non-HTTP protocols)
 * @param url HTTP URL path (null for non-HTTP protocols)
 * @param requestHeaders HTTP request headers (null for non-HTTP protocols)
 * @param requestBody Serialized request body
 * @param responseStatus HTTP response status code (null for non-HTTP protocols)
 * @param responseHeaders HTTP response headers (null for non-HTTP protocols)
 * @param responseBody Serialized response body
 * @param latencyMs Round-trip latency observed in production, in milliseconds
 * @param sourceIp IP address of the source pod
 * @param destinationIp IP address of the destination pod
 * @param capturedAt Timestamp when this input was observed
 */
@Serializable
data class CapturedInput(
    val id: String,
    val serviceId: String,
    val inputType: InputType,
    val classification: TrafficClassification,
    val method: String? = null,
    val url: String? = null,
    val requestHeaders: Map<String, String>? = null,
    val requestBody: String? = null,
    val responseStatus: Int? = null,
    val responseHeaders: Map<String, String>? = null,
    val responseBody: String? = null,
    val latencyMs: Long? = null,
    val sourceIp: String? = null,
    val destinationIp: String? = null,
    @Serializable(with = InstantSerializer::class)
    val capturedAt: Instant,
)
