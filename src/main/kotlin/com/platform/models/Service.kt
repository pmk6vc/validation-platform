package com.platform.models

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * A deployable unit (container, function, etc.) discovered in an environment.
 *
 * Uniquely identified by: organizationId + cluster + namespace + name
 */
@Serializable
data class Service(
    val id: String,
    val organizationId: String,
    val cluster: String,
    val namespace: String,
    val name: String,
    val provider: String? = null,
    @Serializable(with = InstantSerializer::class)
    val discoveredAt: Instant,
    val metadata: Map<String, String>? = null,
)
