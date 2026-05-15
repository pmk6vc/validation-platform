package com.platform.api

import com.platform.database.OrganizationRepository
import com.platform.database.PlatformDatabaseTestBase
import com.platform.database.ServiceRepository
import com.platform.models.Organization
import com.platform.models.Service
import com.platform.module
import com.platform.shared.models.OrganizationId
import com.platform.shared.models.ServiceId
import com.platform.shared.testing.TestJwtKeys
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class AgentConfigRoutesTest : PlatformDatabaseTestBase() {
    private val json = Json { ignoreUnknownKeys = true }

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
            application { module(initDatabase = false, privateKeyPem = TestJwtKeys.privateKeyPem) }

            val response = client.get("/api/agent/config")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `GET agent config returns 401 with invalid token`() =
        testApplication {
            application { module(initDatabase = false, privateKeyPem = TestJwtKeys.privateKeyPem) }

            val response =
                client.get("/api/agent/config") {
                    bearerAuth("not-a-valid-jwt")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `GET agent config returns scoped services with valid JWT`() =
        testApplication {
            application { module(initDatabase = false, privateKeyPem = TestJwtKeys.privateKeyPem) }

            val svc1 = createService("order-service")
            val svc2 = createService("api-gateway")

            val response =
                client.get("/api/agent/config") {
                    bearerAuth(TestJwtKeys.generateTestJwt(organizationId = org.id.value))
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
            application { module(initDatabase = false, privateKeyPem = TestJwtKeys.privateKeyPem) }

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
                    bearerAuth(TestJwtKeys.generateTestJwt(organizationId = org.id.value))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val config = json.decodeFromString<AgentConfigResponse>(response.bodyAsText())
            assertEquals(1, config.targetServices.size)
            assertEquals("order-service", config.targetServices.keys.single())
        }

    @Test
    fun `GET agent config filters by cluster from JWT`() =
        testApplication {
            application { module(initDatabase = false, privateKeyPem = TestJwtKeys.privateKeyPem) }

            createService("prod-service", cluster = "prod")
            createService("staging-service", cluster = "staging")

            val response =
                client.get("/api/agent/config") {
                    bearerAuth(TestJwtKeys.generateTestJwt(organizationId = org.id.value, cluster = "prod"))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val config = json.decodeFromString<AgentConfigResponse>(response.bodyAsText())
            assertEquals(1, config.targetServices.size)
            assertEquals("prod-service", config.targetServices.keys.single())
        }

    @Test
    fun `GET agent config returns 64 char hex redactionSalt for caller org`() =
        testApplication {
            application { module(initDatabase = false, privateKeyPem = TestJwtKeys.privateKeyPem) }

            val response =
                client.get("/api/agent/config") {
                    bearerAuth(TestJwtKeys.generateTestJwt(organizationId = org.id.value))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val config = json.decodeFromString<AgentConfigResponse>(response.bodyAsText())
            kotlin.test.assertTrue(
                config.redactionSalt.matches(Regex("[0-9a-f]{64}")),
                "redactionSalt expected 64-char hex, got: '${'$'}{config.redactionSalt}'",
            )
            // SEC-09 hook fields land empty in Phase 1; Phase 3 populates.
            assertEquals(emptyList(), config.extraRedactedHeaders)
            assertEquals(emptyList(), config.extraBodyRedactionPatterns)
        }

    @Test
    fun `GET agent config redactionSalt is stable per org across calls`() =
        testApplication {
            application { module(initDatabase = false, privateKeyPem = TestJwtKeys.privateKeyPem) }

            val token = TestJwtKeys.generateTestJwt(organizationId = org.id.value)
            val first =
                json.decodeFromString<AgentConfigResponse>(
                    client.get("/api/agent/config") { bearerAuth(token) }.bodyAsText(),
                )
            val second =
                json.decodeFromString<AgentConfigResponse>(
                    client.get("/api/agent/config") { bearerAuth(token) }.bodyAsText(),
                )
            assertEquals(first.redactionSalt, second.redactionSalt)
        }
}
