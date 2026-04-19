package com.platform.collector.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.response.respond

/**
 * Simple bearer token auth for the collector.
 * Validates `Authorization: Bearer <token>` on all `/api/` routes.
 * When `apiKey` is null, auth is disabled (dev/test mode).
 */
val BearerAuthPlugin =
    createRouteScopedPlugin("BearerAuth", ::BearerAuthConfig) {
        val apiKey = pluginConfig.apiKey ?: return@createRouteScopedPlugin

        onCall { call ->
            if (call.skipAuth()) return@onCall

            val token = call.bearerToken()
            if (token == null || token != apiKey) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid or missing API key")
                return@onCall
            }
        }
    }

class BearerAuthConfig {
    var apiKey: String? = null
}

private fun ApplicationCall.skipAuth(): Boolean {
    val path = request.local.uri
    return path == "/" || path == "/health"
}

private fun ApplicationCall.bearerToken(): String? =
    request.headers["Authorization"]
        ?.removePrefix("Bearer ")
        ?.takeIf { it != request.headers["Authorization"] }

fun Application.installAuth(apiKey: String? = System.getenv("API_KEY")) {
    install(BearerAuthPlugin) {
        this.apiKey = apiKey
    }
}
