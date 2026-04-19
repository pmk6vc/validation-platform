package com.platform.integration

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.testcontainers.containers.ComposeContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.io.File
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for the Envoy reverse proxy + JWT auth.
 *
 * Spins up the full platform stack via docker-compose (Postgres, app,
 * collector, Envoy) and tests the auth and routing behavior end-to-end.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EnvoyAuthIntegrationTest {
    companion object {
        private const val JWT_SECRET = "integration-test-secret"

        private val json = Json { ignoreUnknownKeys = true }

        private lateinit var compose: ComposeContainer
        private lateinit var httpClient: HttpClient
        private lateinit var envoyUrl: String

        private fun generateJwt(
            organizationId: String = "org-test",
            cluster: String = "test-cluster",
            role: String = "admin",
            expiresAt: Date = Date.from(Instant.now().plusSeconds(3600)),
        ): String =
            JWT
                .create()
                .withClaim("organizationId", organizationId)
                .withClaim("cluster", cluster)
                .withClaim("role", role)
                .withIssuedAt(Date())
                .withExpiresAt(expiresAt)
                .sign(Algorithm.HMAC256(JWT_SECRET))

        @BeforeAll
        @JvmStatic
        fun setup() {
            compose =
                ComposeContainer(File("integration-tests/docker-compose.yaml"))
                    .withExposedService("envoy", 8082, Wait.forHttp("/health").forStatusCode(200))
                    .withExposedService("app", 8080, Wait.forHttp("/health").forStatusCode(200))
                    .withExposedService("collector", 8081, Wait.forHttp("/health").forStatusCode(200))
                    .withBuild(true)

            compose.start()

            val envoyHost = compose.getServiceHost("envoy", 8082)
            val envoyPort = compose.getServicePort("envoy", 8082)
            envoyUrl = "http://$envoyHost:$envoyPort"

            httpClient =
                HttpClient(CIO) {
                    install(ContentNegotiation) {
                        json(json)
                    }
                }
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            httpClient.close()
            compose.stop()
        }
    }

    // --- Health endpoint (no auth required) ---

    @Test
    @Order(1)
    fun `health endpoint returns 200 without auth`() =
        runBlocking {
            val response = httpClient.get("$envoyUrl/health")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("OK", response.bodyAsText())
        }

    // --- Auth enforcement ---

    @Test
    @Order(2)
    fun `api request without token returns 401`() =
        runBlocking {
            val response = httpClient.get("$envoyUrl/api/organizations")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    @Order(3)
    fun `api request with invalid token returns 401`() =
        runBlocking {
            val response =
                httpClient.get("$envoyUrl/api/organizations") {
                    bearerAuth("not-a-valid-jwt")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    @Order(4)
    fun `api request with wrong secret returns 401`() =
        runBlocking {
            val wrongToken =
                JWT
                    .create()
                    .withClaim("organizationId", "org-test")
                    .withClaim("cluster", "test-cluster")
                    .withClaim("role", "admin")
                    .sign(Algorithm.HMAC256("wrong-secret"))

            val response =
                httpClient.get("$envoyUrl/api/organizations") {
                    bearerAuth(wrongToken)
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    @Order(5)
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

    // --- Routing: app routes ---

    @Test
    @Order(10)
    fun `valid token routes to app - create organization`() =
        runBlocking {
            val token = generateJwt()
            val response =
                httpClient.post("$envoyUrl/api/organizations") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"name": "Test Org"}""")
                }
            assertEquals(HttpStatusCode.Created, response.status)
            val body = json.decodeFromString<CreatedOrg>(response.bodyAsText())
            assertNotNull(body.id)
            assertEquals("Test Org", body.name)
        }

    @Test
    @Order(11)
    fun `valid token routes to app - list organizations`() =
        runBlocking {
            val token = generateJwt()
            val response =
                httpClient.get("$envoyUrl/api/organizations") {
                    bearerAuth(token)
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Test Org"), "Should contain the created org")
        }

    @Test
    @Order(12)
    fun `valid token routes to app - agent config`() =
        runBlocking {
            val token = generateJwt()
            val response =
                httpClient.get("$envoyUrl/api/agent/config") {
                    bearerAuth(token)
                }
            assertEquals(HttpStatusCode.OK, response.status)
            // Response is valid JSON (AgentConfigResponse with defaults)
            val body = response.bodyAsText()
            assertTrue(body.startsWith("{"), "Should return JSON object")
        }

    // --- Routing: collector routes ---

    @Test
    @Order(20)
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
    @Order(21)
    fun `valid token routes to collector - post captured inputs requires body`() =
        runBlocking {
            val token = generateJwt()
            // POST with empty batch should return 400 (reaches collector, not 401)
            val response =
                httpClient.post("$envoyUrl/api/captured-inputs") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"items": []}""")
                }
            assertEquals(
                HttpStatusCode.BadRequest,
                response.status,
                "Empty batch should be rejected by collector (not 401 — proves routing works)",
            )
        }

    // --- Claim forwarding ---

    @Test
    @Order(30)
    fun `agent config is scoped by JWT claims`() =
        runBlocking {
            val adminToken = generateJwt(role = "admin")

            // Create an org and a service
            val orgResponse =
                httpClient.post("$envoyUrl/api/organizations") {
                    bearerAuth(adminToken)
                    contentType(ContentType.Application.Json)
                    setBody("""{"name": "Scoped Org"}""")
                }
            val org = json.decodeFromString<CreatedOrg>(orgResponse.bodyAsText())

            httpClient.post("$envoyUrl/api/services") {
                bearerAuth(adminToken)
                contentType(ContentType.Application.Json)
                setBody(
                    """{"organizationId": "${org.id}", "cluster": "test-cluster", "namespace": "prod", "name": "my-service"}""",
                )
            }

            // Agent token scoped to org + cluster should see the service
            val agentToken = generateJwt(organizationId = org.id, cluster = "test-cluster", role = "agent")
            val configResponse =
                httpClient.get("$envoyUrl/api/agent/config") {
                    bearerAuth(agentToken)
                }
            assertEquals(HttpStatusCode.OK, configResponse.status)
            val configBody = configResponse.bodyAsText()
            assertTrue(configBody.contains("my-service"), "Agent should see its own service")
        }
}

@Serializable
private data class CreatedOrg(
    val id: String,
    val name: String,
)
