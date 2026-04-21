package com.platform.models

import kotlinx.serialization.Serializable
import java.util.UUID

@JvmInline
@Serializable
value class OrganizationId(
    val value: String,
) {
    init {
        try {
            UUID.fromString(value)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid organization ID (must be UUID): $value")
        }
    }

    companion object {
        fun generate(): OrganizationId = OrganizationId(UUID.randomUUID().toString())
    }
}

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

    companion object {
        fun generate(): ServiceId = ServiceId(UUID.randomUUID().toString())
    }
}
