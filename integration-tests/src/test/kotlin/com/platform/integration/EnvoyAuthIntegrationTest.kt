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
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.utility.MountableFile
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for the full platform stack: Envoy + App + Collector + Postgres.
 *
 * Uses TestContainers to spin up individual containers on a shared network.
 * No docker-compose — each container's lifecycle is managed by test code.
 *
 * The test generates an RSA key pair, configures the app to serve the public
 * key via JWKS, and configures Envoy to fetch it via remote_jwks. JWTs are
 * signed in test code using the private key.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EnvoyAuthIntegrationTest {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val network = Network.newNetwork()

        // Generate RSA key pair for JWT signing
        private val keyPair =
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val privateKey = keyPair.private as RSAPrivateKey
        private val publicKey = keyPair.public as RSAPublicKey

        private val privateKeyPem = buildPrivateKeyPem(privateKey)

        private lateinit var postgres: PostgreSQLContainer<*>
        private lateinit var app: GenericContainer<*>
        private lateinit var collector: GenericContainer<*>
        private lateinit var envoy: GenericContainer<*>
        private lateinit var httpClient: HttpClient
        private lateinit var envoyUrl: String

        private fun generateJwt(
            organizationId: String = "org-test",
            cluster: String = "test-cluster",
            role: String = "admin",
            expiresAt: Date = Date.from(Instant.now().plusSeconds(3600)),
            signingKey: RSAPrivateKey = privateKey,
        ): String =
            JWT
                .create()
                .withClaim("organizationId", organizationId)
                .withClaim("cluster", cluster)
                .withClaim("role", role)
                .withIssuedAt(Date())
                .withExpiresAt(expiresAt)
                .sign(Algorithm.RSA256(publicKey, signingKey))

        private fun buildPrivateKeyPem(key: RSAPrivateKey): String {
            val encoded = Base64.getEncoder().encodeToString(key.encoded)
            return "-----BEGIN PRIVATE KEY-----\n" +
                encoded.chunked(64).joinToString("\n") +
                "\n-----END PRIVATE KEY-----"
        }

        @BeforeAll
        @JvmStatic
        fun setup() {
            val repoRoot = Path.of(".").toAbsolutePath().normalize()

            // 1. Postgres
            postgres =
                PostgreSQLContainer("postgres:16-alpine")
                    .withNetwork(network)
                    .withNetworkAliases("db")
                    .withDatabaseName("platform_test")
                    .withUsername("test")
                    .withPassword("test")
            postgres.start()

            val jdbcUrl = "jdbc:postgresql://db:5432/platform_test"

            // 2. App (built from Dockerfile.app)
            app =
                GenericContainer(
                    ImageFromDockerfile()
                        .withDockerfile(repoRoot.resolve("deploy/Dockerfile.app")),
                ).withNetwork(network)
                    .withNetworkAliases("app")
                    .withExposedPorts(8080)
                    .withEnv("DATABASE_URL", jdbcUrl)
                    .withEnv("DATABASE_USER", "test")
                    .withEnv("DATABASE_PASSWORD", "test")
                    .withEnv("JWT_PRIVATE_KEY", privateKeyPem)
                    .waitingFor(Wait.forHttp("/health").forPort(8080).forStatusCode(200))
            app.start()

            // 3. Collector (built from Dockerfile.collector)
            collector =
                GenericContainer(
                    ImageFromDockerfile()
                        .withDockerfile(repoRoot.resolve("deploy/Dockerfile.collector")),
                ).withNetwork(network)
                    .withNetworkAliases("collector")
                    .withExposedPorts(8081)
                    .withEnv("DATABASE_URL", jdbcUrl)
                    .withEnv("DATABASE_USER", "test")
                    .withEnv("DATABASE_PASSWORD", "test")
                    .waitingFor(Wait.forHttp("/health").forPort(8081).forStatusCode(200))
            collector.start()

            // 4. Envoy (stock image, config mounted)
            envoy =
                GenericContainer("envoyproxy/envoy:v1.31-latest")
                    .withNetwork(network)
                    .withNetworkAliases("envoy")
                    .withExposedPorts(8082)
                    .withCopyFileToContainer(
                        MountableFile.forHostPath(repoRoot.resolve("deploy/envoy/envoy.yaml")),
                        "/etc/envoy/envoy.yaml",
                    ).waitingFor(Wait.forHttp("/health").forPort(8082).forStatusCode(200))
            envoy.start()

            envoyUrl = "http://${envoy.host}:${envoy.getMappedPort(8082)}"

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
            envoy.stop()
            collector.stop()
            app.stop()
            postgres.stop()
            network.close()
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

    // --- JWKS endpoint ---

    @Test
    @Order(2)
    fun `JWKS endpoint is accessible without auth`() =
        runBlocking {
            val response = httpClient.get("$envoyUrl/.well-known/jwks.json")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("RSA"), "JWKS should contain RSA key")
            assertTrue(body.contains("RS256"), "JWKS should specify RS256 algorithm")
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
                generateJwt(signingKey = wrongKeyPair.private as RSAPrivateKey)

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

    // --- Routing: app routes ---

    @Test
    @Order(20)
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
    @Order(21)
    fun `valid token routes to app - list organizations`() =
        runBlocking {
            val token = generateJwt()
            val response =
                httpClient.get("$envoyUrl/api/organizations") {
                    bearerAuth(token)
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("Test Org"))
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

    // --- Routing: collector routes ---

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
            // 400 from collector (not 401) proves routing works
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
}

@Serializable
private data class CreatedOrg(
    val id: String,
    val name: String,
)
