package com.platform.auth

import com.platform.models.OrganizationId
import io.ktor.server.auth.Principal

/**
 * Authenticated agent identity resolved from a validated JWT.
 * Populated by [installJwtAuth] after the JWT signature and claims are verified.
 */
@Suppress("DEPRECATION")
data class AgentIdentity(
    val organizationId: OrganizationId,
    val cluster: String,
    val role: String? = null,
) : Principal
