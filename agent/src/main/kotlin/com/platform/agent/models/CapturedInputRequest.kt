package com.platform.agent.models

import kotlinx.serialization.Serializable

/**
 * POST payload for the collector's /api/captured-inputs endpoint.
 * This is the agent's client-side DTO — it matches the collector's expected format
 * but is defined independently so the agent has no compile-time dependency on the collector.
 */
@Serializable
data class CapturedInputRequest(
    val serviceId: String,
    val inputType: String = "HTTP",
    val method: String,
    val url: String,
    val requestHeaders: Map<String, String>? = null,
    val requestBody: String? = null,
    val responseStatus: Int,
    val responseHeaders: Map<String, String>? = null,
    val responseBody: String? = null,
    val latencyMs: Long? = null,
    val sourceIp: String? = null,
    val destinationIp: String? = null,
    val capturedAt: String,
)

@Serializable
data class BatchCapturedInputRequest(
    val items: List<CapturedInputRequest>,
)
