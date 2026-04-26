package com.platform.collector.api

import com.platform.collector.module
import com.platform.shared.testing.TestJwtKeys
import com.platform.shared.testing.authedTestApplication
import com.platform.shared.testing.defaultTestToken
import io.ktor.client.HttpClient
import io.ktor.server.testing.ApplicationTestBuilder

/**
 * Per-module thin wrapper around the shared [authedTestApplication]: bakes in
 * the collector's `Application.module()` setup. See `PlatformTestApplication`
 * for the platform-side mirror; same usage pattern.
 *
 * For tests that explicitly verify auth-failure paths, use bare
 * `testApplication { }`.
 */
fun collectorTestApplication(
    token: String = defaultTestToken,
    block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
) = authedTestApplication(
    token = token,
    setupApplication = {
        application { module(initDatabase = false, privateKeyPem = TestJwtKeys.privateKeyPem) }
    },
    block = block,
)
