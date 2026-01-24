package com.platform.models

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * An organization (tenant) in the platform.
 */
@Serializable
data class Organization(
    val id: String,
    val name: String,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
)
