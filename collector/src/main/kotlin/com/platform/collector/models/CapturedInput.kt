package com.platform.collector.models

import com.platform.shared.models.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class CapturedInput(
    val id: CapturedInputId,
    val serviceId: ServiceId,
    val inputType: InputType,
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
    @Serializable(with = InstantSerializer::class)
    val capturedAt: Instant,
)
