package com.platform.collector.api

import com.platform.collector.database.CollectorDatabaseTestBase
import com.platform.collector.module
import com.platform.shared.testing.TestJwtKeys
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Regression test: every `/api/...` route on the collector server must require
 * a JWT bearer token. Hits each route without auth and asserts 401.
 *
 * If a new `/api/...` route is added, extend [authenticatedPaths] below.
 *
 * `/health` is intentionally unauthenticated and excluded.
 */
class ApiAuthEnforcementTest : CollectorDatabaseTestBase() {
    private val authenticatedPaths =
        listOf(
            "/api/captured-inputs",
            "/api/captured-inputs/00000000-0000-0000-0000-000000000001",
        )

    @Test
    fun `every authenticated route returns 401 without a bearer token`() =
        testApplication {
            application { module(initDatabase = false, privateKeyPem = TestJwtKeys.privateKeyPem) }

            for (path in authenticatedPaths) {
                val response = client.get(path)
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    response.status,
                    "Expected 401 for $path without bearer token (route accidentally outside authenticate {} block?)",
                )
            }
        }
}
