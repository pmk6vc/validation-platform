package com.platform.models

import com.platform.shared.models.InstantSerializer
import com.platform.shared.models.OrganizationId
import com.platform.shared.models.ServiceId
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * A deployable unit (container, function, etc.) discovered in an environment.
 *
 * Uniquely identified by: organizationId + cluster + namespace + name
 */
@Serializable
data class Service(
    val id: ServiceId,
    val organizationId: OrganizationId,
    val cluster: String,
    val namespace: String,
    val name: String,
    val provider: Provider = Provider.UNKNOWN,
    @Serializable(with = InstantSerializer::class)
    val discoveredAt: Instant,
    @Serializable(with = InstantSerializer::class)
    val lastSeenAt: Instant,
    val metadata: Map<String, String>? = null,
)
