package com.platform.collector.models

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Typed ID for captured inputs. Validates UUID format at construction.
 * Local to the collector module — not shared with app or agent.
 */
@JvmInline
@Serializable
value class CapturedInputId(
    val value: String,
) {
    init {
        try {
            UUID.fromString(value)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid captured input ID (must be UUID): $value")
        }
    }

    companion object {
        fun generate(): CapturedInputId = CapturedInputId(UUID.randomUUID().toString())
    }
}

/**
 * Typed reference to a service. Validates UUID format at construction.
 * The collector doesn't own services (app does), but it stores and queries
 * by serviceId — this type ensures invalid UUIDs are caught at the boundary.
 */
@JvmInline
@Serializable
value class ServiceId(
    val value: String,
) {
    init {
        try {
            UUID.fromString(value)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid service ID (must be UUID): $value")
        }
    }
}
