package com.platform.api

import com.platform.database.PlatformDatabaseTestBase
import com.platform.module
import com.platform.shared.testing.TestJwtKeys
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Regression test: every `/api/...` route on the platform server must require
 * a JWT bearer token. Hits each route without auth and asserts 401.
 *
 * Adding a new `/api/...` route outside the `authenticate(JWT_AUTH) { }` block
 * is the kind of subtle mistake CI should catch — if a new route is added,
 * extend [authenticatedPaths] below.
 *
 * `/health` and `/.well-known/jwks.json` are intentionally unauthenticated
 * and excluded from this test (covered by [HealthRoutesTest] / [JwksRouteTest]).
 */
class ApiAuthEnforcementTest : PlatformDatabaseTestBase() {
    private val authenticatedPaths =
        listOf(
            "/api/organizations",
            "/api/organizations/00000000-0000-0000-0000-000000000001",
            "/api/services",
            "/api/services/00000000-0000-0000-0000-000000000001",
            "/api/agent/config",
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
