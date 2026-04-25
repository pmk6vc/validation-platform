package com.platform.api

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.platform.auth.installJwtAuth
import com.platform.database.AppDatabaseTestBase
import com.platform.database.OrganizationRepository
import com.platform.database.ServiceRepository
import com.platform.models.Organization
import com.platform.models.OrganizationId
import com.platform.models.Service
import com.platform.models.ServiceId
import com.platform.module
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date
import kotlin.test.assertEquals

class AgentConfigRoutesTest : AppDatabaseTestBase() {
    private val json = Json { ignoreUnknownKeys = true }

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val privateKey = keyPair.private as RSAPrivateKey
    private val publicKey = keyPair.public as RSAPublicKey

    /** PEM-formatted private key, used by [installJwtAuth] inside the test application. */
    private val privateKeyPem: String by lazy {
        val encoded = Base64.getEncoder().encodeToString(privateKey.encoded)
        "-----BEGIN PRIVATE KEY-----\n" +
            encoded.chunked(64).joinToString("\n") +
            "\n-----END PRIVATE KEY-----"
    }

    private lateinit var org: Organization

    @BeforeEach
    fun setup() {
        kotlinx.coroutines.runBlocking {
            org =
                OrganizationRepository.create(
                    Organization(
                        id = OrganizationId.generate(),
                        name = "Test Org",
                        createdAt = Instant.now(),
                    ),
                )
        }
    }

    private fun generateJwt(
        organizationId: String = org.id.value,
        cluster: String = "prod",
        expiresAt: Date = Date.from(Instant.now().plusSeconds(3600)),
    ): String =
        JWT
            .create()
            .withClaim("organizationId", organizationId)
            .withClaim("cluster", cluster)
            .withIssuedAt(Date())
            .withExpiresAt(expiresAt)
            .sign(Algorithm.RSA256(publicKey, privateKey))

    private suspend fun createService(
        name: String,
        cluster: String = "prod",
        namespace: String = "production",
        organizationId: OrganizationId = org.id,
    ): Service {
        val now = Instant.now()
        return ServiceRepository.create(
            Service(
                id = ServiceId.generate(),
                organizationId = organizationId,
                cluster = cluster,
                namespace = namespace,
                name = name,
                discoveredAt = now,
                lastSeenAt = now,
            ),
        )
    }

    @Test
    fun `GET agent config returns 401 without authorization header`() =
        testApplication {
            application { module(initDatabase = false, privateKeyPem = privateKeyPem) }

            val response = client.get("/api/agent/config")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `GET agent config returns 401 with invalid token`() =
        testApplication {
            application { module(initDatabase = false, privateKeyPem = privateKeyPem) }

            val response =
                client.get("/api/agent/config") {
                    bearerAuth("not-a-valid-jwt")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `GET agent config returns scoped services with valid JWT`() =
        testApplication {
            application { module(initDatabase = false, privateKeyPem = privateKeyPem) }

            val svc1 = createService("order-service")
            val svc2 = createService("api-gateway")

            val response =
                client.get("/api/agent/config") {
                    bearerAuth(generateJwt())
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val config = json.decodeFromString<AgentConfigResponse>(response.bodyAsText())
            assertEquals(2, config.targetServices.size)
            assertEquals(svc1.id.value, config.targetServices["order-service"])
            assertEquals(svc2.id.value, config.targetServices["api-gateway"])
        }

    @Test
    fun `GET agent config filters by organizationId from JWT`() =
        testApplication {
            application { module(initDatabase = false, privateKeyPem = privateKeyPem) }

            val otherOrg =
                OrganizationRepository.create(
                    Organization(
                        id = OrganizationId.generate(),
                        name = "Other Org",
                        createdAt = Instant.now(),
                    ),
                )
            createService("order-service", organizationId = org.id)
            createService("other-service", organizationId = otherOrg.id)

            val response =
                client.get("/api/agent/config") {
                    bearerAuth(generateJwt())
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val config = json.decodeFromString<AgentConfigResponse>(response.bodyAsText())
            assertEquals(1, config.targetServices.size)
            assertEquals("order-service", config.targetServices.keys.single())
        }

    @Test
    fun `GET agent config filters by cluster from JWT`() =
        testApplication {
            application { module(initDatabase = false, privateKeyPem = privateKeyPem) }

            createService("prod-service", cluster = "prod")
            createService("staging-service", cluster = "staging")

            val response =
                client.get("/api/agent/config") {
                    bearerAuth(generateJwt(cluster = "prod"))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val config = json.decodeFromString<AgentConfigResponse>(response.bodyAsText())
            assertEquals(1, config.targetServices.size)
            assertEquals("prod-service", config.targetServices.keys.single())
        }
}
