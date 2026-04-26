package com.platform.api

import com.platform.module
import com.platform.shared.testing.TestJwtKeys
import com.platform.shared.testing.authedTestApplication
import com.platform.shared.testing.defaultTestToken
import io.ktor.client.HttpClient
import io.ktor.server.testing.ApplicationTestBuilder

/**
 * Per-module thin wrapper around the shared [authedTestApplication]: bakes in
 * the platform's `Application.module()` setup. Tests just call:
 *
 * ```
 * platformTestApplication { client ->
 *     val response = client.get("/api/organizations")
 *     ...
 * }
 * ```
 *
 * The lambda's `client` parameter is an [HttpClient] with bearer auth and
 * JSON content negotiation pre-installed; it shadows `testApplication`'s
 * default plugin-less client.
 *
 * For tests that explicitly verify auth-failure paths (no token, invalid
 * token, expired token, wrong signing key), use bare `testApplication { }`.
 */
fun platformTestApplication(
    token: String = defaultTestToken,
    block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
) = authedTestApplication(
    token = token,
    setupApplication = {
        application { module(initDatabase = false, privateKeyPem = TestJwtKeys.privateKeyPem) }
    },
    block = block,
)
