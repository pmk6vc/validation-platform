package com.platform.shared.testing

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json

/**
 * Generic Ktor [testApplication] wrapper for any module that uses in-app JWT
 * auth. Pass [setupApplication] to install the module's `Application.module()`
 * (each module has its own); the helper builds an [HttpClient] with bearer
 * auth and JSON content negotiation pre-configured and hands it to [block].
 *
 * Prefer the per-module thin wrapper (`platformTestApplication`,
 * `collectorTestApplication`) so callers don't repeat the `setupApplication`
 * lambda.
 *
 * Tests that explicitly verify auth-failure paths (no token, invalid token,
 * expired token, wrong signing key) should use bare [testApplication] — this
 * helper bakes in a valid token by design.
 */
fun authedTestApplication(
    token: String = defaultTestToken,
    setupApplication: ApplicationTestBuilder.() -> Unit,
    block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
) = testApplication {
    setupApplication()
    val client =
        createClient {
            defaultRequest { bearerAuth(token) }
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    block(client)
}
