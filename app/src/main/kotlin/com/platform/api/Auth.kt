package com.platform.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey

/**
 * Authenticated agent identity resolved from the bearer token.
 * Set as a call attribute by [BearerAuthPlugin] for downstream route handlers.
 */
data class AgentIdentity(
    val organizationId: String,
    val cluster: String,
)

val AgentIdentityKey = AttributeKey<AgentIdentity>("AgentIdentity")

/**
 * Simple bearer token auth plugin.
 *
 * Reads `API_KEY`, `API_KEY_ORG_ID`, and `API_KEY_CLUSTER` from the environment.
 * Validates `Authorization: Bearer <token>` on all `/api/` routes. Skips auth for
 * `/health` and `/`. When `API_KEY` is unset, auth is disabled (dev/test mode).
 *
 * On success, sets [AgentIdentity] as a call attribute so route handlers can
 * read the authenticated org/cluster without query params.
 *
 * This is a single-key implementation for the sandbox. Multi-tenant support
 * would replace this with a database lookup (token → org/cluster).
 */
val BearerAuthPlugin =
    createRouteScopedPlugin("BearerAuth", ::BearerAuthConfig) {
        val apiKey = pluginConfig.apiKey
        val identity = pluginConfig.identity

        if (apiKey == null) return@createRouteScopedPlugin

        onCall { call ->
            if (call.skipAuth()) return@onCall

            val token = call.bearerToken()
            if (token == null || token != apiKey) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid or missing API key")
                return@onCall
            }

            if (identity != null) {
                call.attributes.put(AgentIdentityKey, identity)
            }
        }
    }

class BearerAuthConfig {
    var apiKey: String? = null
    var identity: AgentIdentity? = null
}

private fun ApplicationCall.skipAuth(): Boolean {
    val path = request.local.uri
    return path == "/" || path == "/health"
}

private fun ApplicationCall.bearerToken(): String? =
    request.headers["Authorization"]
        ?.removePrefix("Bearer ")
        ?.takeIf { it != request.headers["Authorization"] }

fun Application.installAuth(
    apiKey: String? = System.getenv("API_KEY"),
    organizationId: String? = System.getenv("API_KEY_ORG_ID"),
    cluster: String? = System.getenv("API_KEY_CLUSTER"),
) {
    install(BearerAuthPlugin) {
        this.apiKey = apiKey
        this.identity =
            if (organizationId != null && cluster != null) {
                AgentIdentity(organizationId, cluster)
            } else {
                null
            }
    }
}
