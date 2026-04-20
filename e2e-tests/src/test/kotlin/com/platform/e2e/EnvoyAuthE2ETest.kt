package com.platform.e2e

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E2E tests for Envoy JWT auth, routing, and claim forwarding.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EnvoyAuthE2ETest : PlatformStackTestBase() {
    // --- Health endpoint ---

    @Test
    @Order(1)
    fun `health endpoint returns 200 without auth`() =
        runBlocking {
            val response = httpClient.get("$envoyUrl/health")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("OK", response.bodyAsText())
        }

    @Test
    @Order(2)
    fun `JWKS endpoint is accessible without auth`() =
        runBlocking {
            val response = httpClient.get("$envoyUrl/.well-known/jwks.json")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("RSA"))
            assertTrue(body.contains("RS256"))
        }

    // --- Auth enforcement ---

    @Test
    @Order(10)
    fun `api request without token returns 401`() =
        runBlocking {
            val response = httpClient.get("$envoyUrl/api/organizations")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    @Order(11)
    fun `api request with invalid token returns 401`() =
        runBlocking {
            val response =
                httpClient.get("$envoyUrl/api/organizations") {
                    bearerAuth("not-a-valid-jwt")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    @Order(12)
    fun `api request with wrong signing key returns 401`() =
        runBlocking {
            val wrongKeyPair =
                KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val wrongToken =
                com.auth0.jwt.JWT
                    .create()
                    .withClaim("organizationId", "org-test")
                    .withClaim("cluster", "test-cluster")
                    .withClaim("role", "admin")
                    .sign(
                        com.auth0.jwt.algorithms.Algorithm.RSA256(
                            wrongKeyPair.public as java.security.interfaces.RSAPublicKey,
                            wrongKeyPair.private as RSAPrivateKey,
                        ),
                    )

            val response =
                httpClient.get("$envoyUrl/api/organizations") {
                    bearerAuth(wrongToken)
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    @Order(13)
    fun `api request with expired token returns 401`() =
        runBlocking {
            val expiredToken =
                generateJwt(expiresAt = Date.from(Instant.now().minusSeconds(3600)))

            val response =
                httpClient.get("$envoyUrl/api/organizations") {
                    bearerAuth(expiredToken)
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    // --- Routing ---

    @Test
    @Order(20)
    fun `valid token routes to app - create organization`() =
        runBlocking {
            val token = generateJwt()
            val response =
                httpClient.post("$envoyUrl/api/organizations") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"name": "Auth Test Org"}""")
                }
            assertEquals(HttpStatusCode.Created, response.status)
            val body = json.decodeFromString<CreatedOrg>(response.bodyAsText())
            assertNotNull(body.id)
        }

    @Test
    @Order(21)
    fun `valid token routes to app - list organizations`() =
        runBlocking {
            val token = generateJwt()
            val response =
                httpClient.get("$envoyUrl/api/organizations") {
                    bearerAuth(token)
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    @Order(22)
    fun `valid token routes to app - agent config`() =
        runBlocking {
            val token = generateJwt()
            val response =
                httpClient.get("$envoyUrl/api/agent/config") {
                    bearerAuth(token)
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    @Order(30)
    fun `valid token routes to collector - list captured inputs`() =
        runBlocking {
            val token = generateJwt()
            val response =
                httpClient.get("$envoyUrl/api/captured-inputs") {
                    bearerAuth(token)
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    @Order(31)
    fun `valid token routes to collector - empty batch returns 400`() =
        runBlocking {
            val token = generateJwt()
            val response =
                httpClient.post("$envoyUrl/api/captured-inputs") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"items": []}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
}

@Serializable
data class CreatedOrg(
    val id: String,
    val name: String,
)
