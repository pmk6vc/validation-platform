package com.platform.e2e

import com.platform.api.CreateOrganizationRequest
import com.platform.collector.models.BatchCreateCapturedInputRequest
import com.platform.models.Organization
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E2E tests for in-app JWT auth across both platform (8080) and collector (8081).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class JwtAuthE2ETest : PlatformStackTestBase() {
    // --- Health endpoint ---

    @Test
    @Order(1)
    fun `health endpoint returns 200 without auth`() =
        runBlocking {
            val response = platformClient.get("$platformUrl/health")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("OK", response.bodyAsText())
        }

    @Test
    @Order(2)
    fun `JWKS endpoint is accessible without auth`() =
        runBlocking {
            val response = platformClient.get("$platformUrl/.well-known/jwks.json")
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
            val response = platformClient.get("$platformUrl/api/organizations")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    @Order(11)
    fun `api request with invalid token returns 401`() =
        runBlocking {
            val response =
                platformClient.get("$platformUrl/api/organizations") {
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
                            wrongKeyPair.public as RSAPublicKey,
                            wrongKeyPair.private as RSAPrivateKey,
                        ),
                    )

            val response =
                platformClient.get("$platformUrl/api/organizations") {
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
                platformClient.get("$platformUrl/api/organizations") {
                    bearerAuth(expiredToken)
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    // --- Routing ---

    @Test
    @Order(20)
    fun `valid token routes to platform - create organization`() =
        runBlocking {
            val token = generateJwt()
            val response =
                platformClient.post("$platformUrl/api/organizations") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(CreateOrganizationRequest(name = "Auth Test Org"))
                }
            assertEquals(HttpStatusCode.Created, response.status)
            val org = json.decodeFromString<Organization>(response.bodyAsText())
            assertNotNull(org.id)
        }

    @Test
    @Order(21)
    fun `valid token routes to platform - list organizations`() =
        runBlocking {
            val token = generateJwt()
            val response =
                platformClient.get("$platformUrl/api/organizations") {
                    bearerAuth(token)
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    @Order(22)
    fun `valid token routes to platform - agent config`() =
        runBlocking {
            val token = generateJwt()
            val response =
                platformClient.get("$platformUrl/api/agent/config") {
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
                collectorClient.get("$collectorUrl/api/captured-inputs") {
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
                collectorClient.post("$collectorUrl/api/captured-inputs") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(BatchCreateCapturedInputRequest(items = emptyList()))
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
}
