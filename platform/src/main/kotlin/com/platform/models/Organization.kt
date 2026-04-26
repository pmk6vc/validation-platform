package com.platform.models

import com.platform.shared.models.InstantSerializer
import com.platform.shared.models.OrganizationId
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * An organization (tenant) in the platform.
 */
@Serializable
data class Organization(
    val id: OrganizationId,
    val name: String,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
)
