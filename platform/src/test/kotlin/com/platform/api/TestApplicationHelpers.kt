package com.platform.api

import com.platform.database.AppDatabaseTestBase
import com.platform.module
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json

/**
 * Default test JWT — valid UUID, signed with [AppDatabaseTestBase.testPrivateKey].
 * Most tests don't care about the principal's claims; the routes they exercise
 * only check that *some* valid JWT is present. Tests that need org-specific
 * behaviour pass an explicit token to [authedTestApplication].
 */
val defaultTestToken: String by lazy {
    AppDatabaseTestBase.generateTestJwt("00000000-0000-0000-0000-000000000001")
}

/**
 * Wraps Ktor's [testApplication] with an `HttpClient` that has bearer auth
 * and JSON content negotiation pre-installed. Tests get the configured client
 * as a lambda parameter, shadowing `testApplication`'s default plugin-less one:
 *
 * ```
 * authedTestApplication { client ->
 *     val response = client.get("/api/organizations")
 *     assertEquals(HttpStatusCode.OK, response.status)
 * }
 * ```
 *
 * Use plain [testApplication] for tests that explicitly verify auth-failure
 * paths (no token, invalid token, expired token).
 */
fun authedTestApplication(
    token: String = defaultTestToken,
    block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
) = testApplication {
    application { module(initDatabase = false, privateKeyPem = AppDatabaseTestBase.testPrivateKeyPem) }
    val client =
        createClient {
            defaultRequest { bearerAuth(token) }
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    block(client)
}
