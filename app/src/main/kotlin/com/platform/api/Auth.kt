package com.platform.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.response.respond

/**
 * Authenticated agent identity resolved from headers forwarded by Envoy.
 * Envoy validates the JWT and extracts claims into X-Organization-Id
 * and X-Cluster headers before forwarding to the app.
 */
data class AgentIdentity(
    val organizationId: String,
    val cluster: String,
)

const val ENVOY_IDENTITY_AUTH = "envoy-identity"

/**
 * Custom authentication provider that reads identity from Envoy-forwarded headers.
 * Returns 401 if required headers are missing (every request through Envoy has them;
 * missing headers means the request bypassed Envoy, which is invalid in production).
 */
class EnvoyIdentityProvider(
    private val config: Config,
) : AuthenticationProvider(config) {
    class Config(
        name: String,
    ) : AuthenticationProvider.Config(name)

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val call = context.call
        val orgId = call.request.headers["X-Organization-Id"]
        val cluster = call.request.headers["X-Cluster"]

        if (orgId == null || cluster == null) {
            context.challenge("EnvoyIdentity", AuthenticationFailedCause.NoCredentials) {
                challenge,
                call,
                ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    "Missing required identity headers (X-Organization-Id, X-Cluster)",
                )
                challenge.complete()
            }
            return
        }

        context.principal(AgentIdentity(orgId, cluster))
    }
}

fun AuthenticationConfig.envoyIdentity(name: String = ENVOY_IDENTITY_AUTH) {
    register(EnvoyIdentityProvider(EnvoyIdentityProvider.Config(name)))
}

fun Application.installAuth() {
    install(Authentication) {
        envoyIdentity()
    }
}
