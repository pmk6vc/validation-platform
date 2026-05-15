package com.platform.models

import com.platform.shared.models.InstantSerializer
import com.platform.shared.models.OrganizationId
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * An organization (tenant) in the platform.
 *
 * [redactionSalt] is a 64-character hex string (32 random bytes) used by
 * the Go agent's redaction engine to produce per-org deterministic
 * typed placeholders. Generated at insert time (V0008 migration backfills
 * existing rows). Never serialized to clients outside the agent-config
 * response — keep it off any external Organization GET endpoint.
 */
@Serializable
data class Organization(
    val id: OrganizationId,
    val name: String,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
    val redactionSalt: String = "",
)
