package com.platform.shared.auth

import com.platform.shared.models.OrganizationId

/**
 * Authenticated agent identity resolved from a validated JWT.
 * Populated by [installJwtAuth] after the JWT signature and claims are verified.
 *
 * In Ktor 3 the `Principal` marker interface is deprecated — `validate { ... }`
 * returns `Any?` and `call.principal<T>()` uses reified generics. So this is
 * just a plain data class.
 */
data class AgentIdentity(
    val organizationId: OrganizationId,
    val cluster: String,
    val role: String? = null,
)
