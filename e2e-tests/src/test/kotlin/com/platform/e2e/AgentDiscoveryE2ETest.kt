package com.platform.e2e

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.platform.agent.DynamicConfig
import com.platform.agent.K8sServiceDiscovery
import com.platform.agent.PlatformClient
import com.platform.agent.buildAgentPlatformHttpClient
import com.platform.agent.discoverServices
import com.platform.api.CreateOrganizationRequest
import com.platform.kubernetes.KubernetesWorkloadTestBase
import com.platform.models.Organization
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real K8s → agent → platform e2e flow.
 *
 * Wires the agent's [discoverServices] function against:
 *  - A real k3s cluster running the test microservices (provided by
 *    [KubernetesWorkloadTestBase] — 7 Services across `infrastructure`,
 *    `production`, and `external` namespaces)
 *  - A real platform stack (Postgres + Platform via TestContainers,
 *    started inline in this test class)
 *
 * Validates the contract that the unit tests can't:
 *  - The agent's `POST /api/services` payload deserializes against the
 *    platform's `CreateServiceRequest`
 *  - JWT auth works end-to-end (real RS256 sign/verify, real org claim)
 *  - Discovered services round-trip via `GET /api/services`
 *  - 409 Conflict from re-registering an existing service is treated as
 *    idempotent success (proves the restart-recovery story)
 *
 * Inline platform stack startup duplicates a chunk of [PlatformStackTestBase]
 * because Kotlin doesn't allow multiple base classes and this test also
 * extends [KubernetesWorkloadTestBase]. The duplication is acceptable for
 * one test class; if a second test ends up needing both, refactor
 * [PlatformStackTestBase] into a composable utility.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentDiscoveryE2ETest : KubernetesWorkloadTestBase() {
    private val json = Json { ignoreUnknownKeys = true }
    private val network: Network = Network.newNetwork()

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val privateKey = keyPair.private as RSAPrivateKey
    private val publicKey = keyPair.public as RSAPublicKey
    private val privateKeyPem = buildPrivateKeyPem(privateKey)

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var platform: GenericContainer<*>
    private lateinit var platformUrl: String
    private lateinit var httpClient: HttpClient

    @BeforeAll
    fun setupPlatformStack() {
        postgres =
            PostgreSQLContainer("postgres:16-alpine")
                .withNetwork(network)
                .withNetworkAliases("db")
                .withDatabaseName("platform_test")
                .withUsername("test")
                .withPassword("test")
        postgres.start()

        platform =
            GenericContainer("validation-platform:test")
                .withNetwork(network)
                .withNetworkAliases("platform")
                .withExposedPorts(8080)
                .withEnv("DATABASE_URL", "jdbc:postgresql://db:5432/platform_test")
                .withEnv("DATABASE_USER", "test")
                .withEnv("DATABASE_PASSWORD", "test")
                .withEnv("JWT_PRIVATE_KEY", privateKeyPem)
                .waitingFor(Wait.forHttp("/health").forPort(8080).forStatusCode(200))
        platform.start()

        platformUrl = "http://${platform.host}:${platform.getMappedPort(8080)}"
        httpClient = buildAgentPlatformHttpClient()
    }

    @AfterAll
    fun teardownPlatformStack() {
        httpClient.close()
        platform.stop()
        postgres.stop()
        network.close()
    }

    private fun generateJwt(
        organizationId: String,
        cluster: String,
    ): String =
        JWT
            .create()
            .withClaim("organizationId", organizationId)
            .withClaim("cluster", cluster)
            .withClaim("role", "admin")
            .withIssuedAt(Date())
            .withExpiresAt(Date.from(Instant.now().plusSeconds(3600)))
            .sign(Algorithm.RSA256(publicKey, privateKey))

    private fun buildPrivateKeyPem(key: RSAPrivateKey): String {
        val encoded = Base64.getEncoder().encodeToString(key.encoded)
        return "-----BEGIN PRIVATE KEY-----\n" +
            encoded.chunked(64).joinToString("\n") +
            "\n-----END PRIVATE KEY-----"
    }

    @Test
    fun `agent discovers k3s services and registers them with the platform`() =
        runBlocking {
            // 1. Bootstrap an org via the platform's admin API.
            val adminToken =
                generateJwt(
                    organizationId = "00000000-0000-0000-0000-000000000000",
                    cluster = "admin",
                )
            val orgResponse =
                httpClient.post("$platformUrl/api/organizations") {
                    bearerAuth(adminToken)
                    contentType(ContentType.Application.Json)
                    setBody(CreateOrganizationRequest(name = "k8s-discovery-test"))
                }
            assertEquals(HttpStatusCode.Created, orgResponse.status)
            val org = json.decodeFromString<Organization>(orgResponse.bodyAsText())
            val orgToken = generateJwt(organizationId = org.id.value, cluster = "test-cluster")

            // 2. Wire the agent's actual K8sServiceDiscovery against the k3s cluster
            //    AND the actual PlatformClient against the platform under test.
            //    No mocks — this is the real call path the agent runs in production.
            val k8sClient = createKubernetesClient()
            try {
                K8sServiceDiscovery(k8sClient).use { discovery ->
                    val platformClient = PlatformClient(httpClient, platformUrl, orgToken)
                    val dynamicConfig =
                        MutableStateFlow(
                            DynamicConfig.default().copy(namespaceFilters = listOf("production")),
                        )
                    val registered = mutableSetOf<Pair<String, String>>()

                    // 3. Run one discovery iteration.
                    discoverServices(discovery, platformClient, dynamicConfig, registered)

                    // 4. The production namespace's K8s Services in the test fixture are:
                    //    api-gateway, order-service, notification-service.
                    //    (traffic-generator is a Deployment with no Service resource.)
                    val expected =
                        setOf(
                            "production" to "api-gateway",
                            "production" to "order-service",
                            "production" to "notification-service",
                        )
                    assertEquals(
                        expected,
                        registered,
                        "discoverServices should have populated registered with the production-namespace test services",
                    )

                    // 5. Verify services round-trip via the platform's GET /api/services.
                    //    This is what the platform's config endpoint reads from to build
                    //    the agent's targetServices map — the actual production wiring.
                    val listResponse =
                        httpClient.get("$platformUrl/api/services") {
                            bearerAuth(orgToken)
                        }
                    assertEquals(HttpStatusCode.OK, listResponse.status)
                    val responseBody = listResponse.bodyAsText()
                    for ((_, name) in expected) {
                        assertTrue(
                            responseBody.contains("\"name\":\"$name\""),
                            "Expected service $name in GET /api/services response: $responseBody",
                        )
                    }
                }
            } finally {
                k8sClient.close()
            }
        }

    @Test
    fun `re-registration after restart returns 409 and is treated as idempotent success`() =
        runBlocking {
            // Different org so this test is independent of the first.
            val adminToken =
                generateJwt(
                    organizationId = "00000000-0000-0000-0000-000000000000",
                    cluster = "admin",
                )
            val orgResponse =
                httpClient.post("$platformUrl/api/organizations") {
                    bearerAuth(adminToken)
                    contentType(ContentType.Application.Json)
                    setBody(CreateOrganizationRequest(name = "idempotency-test"))
                }
            assertEquals(HttpStatusCode.Created, orgResponse.status)
            val org = json.decodeFromString<Organization>(orgResponse.bodyAsText())
            val orgToken = generateJwt(organizationId = org.id.value, cluster = "test-cluster-2")

            val k8sClient = createKubernetesClient()
            try {
                K8sServiceDiscovery(k8sClient).use { discovery ->
                    val platformClient = PlatformClient(httpClient, platformUrl, orgToken)
                    val dynamicConfig =
                        MutableStateFlow(
                            DynamicConfig.default().copy(namespaceFilters = listOf("production")),
                        )

                    // First "agent lifetime": registers services normally (200/201).
                    val firstRegistered = mutableSetOf<Pair<String, String>>()
                    discoverServices(discovery, platformClient, dynamicConfig, firstRegistered)
                    val initialCount = firstRegistered.size
                    assertTrue(initialCount > 0, "first run must register at least one service")

                    // Simulate an agent restart — fresh in-memory set, but the platform
                    // already has these services. Every POST /api/services should now
                    // return 409, which PlatformClient classifies as Success.
                    val secondRegistered = mutableSetOf<Pair<String, String>>()
                    discoverServices(discovery, platformClient, dynamicConfig, secondRegistered)

                    assertEquals(
                        firstRegistered,
                        secondRegistered,
                        "second run (post-restart) must register the same set via 409-as-Success",
                    )
                }
            } finally {
                k8sClient.close()
            }
        }
}
