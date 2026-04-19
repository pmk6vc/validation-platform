package com.platform.api

import com.platform.database.AppDatabaseTestBase
import com.platform.database.OrganizationRepository
import com.platform.database.ServiceRepository
import com.platform.models.Organization
import com.platform.models.Service
import com.platform.module
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentConfigRoutesTest : AppDatabaseTestBase() {
    private val json = Json { ignoreUnknownKeys = true }
    private val testApiKey = "test-secret-key"

    private lateinit var org: Organization

    @BeforeEach
    fun setup() {
        kotlinx.coroutines.runBlocking {
            org =
                OrganizationRepository.create(
                    Organization(
                        id = UUID.randomUUID().toString(),
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
        organizationId: String = org.id,
    ): Service {
        val now = Instant.now()
        return ServiceRepository.create(
            Service(
                id = UUID.randomUUID().toString(),
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
    fun `GET agent config returns 200 with defaults when no services`() =
        testApplication {
            application { module(initDatabase = false) }

            val response = client.get("/api/agent/config")

            assertEquals(HttpStatusCode.OK, response.status)
            val config = json.decodeFromString<AgentConfigResponse>(response.bodyAsText())
            assertTrue(config.targetServices.isEmpty())
            assertEquals(1.0, config.samplingRate)
            assertEquals(100, config.batchSize)
            assertEquals(5000, config.captureInterval)
            assertEquals(30000, config.configPollInterval)
            assertEquals(60000, config.discoveryInterval)
        }

    @Test
    fun `GET agent config returns all services when no auth identity`() =
        testApplication {
            application { module(initDatabase = false) }

            val svc1 = createService("order-service")
            val svc2 = createService("api-gateway")

            val response = client.get("/api/agent/config")

            assertEquals(HttpStatusCode.OK, response.status)
            val config = json.decodeFromString<AgentConfigResponse>(response.bodyAsText())
            assertEquals(2, config.targetServices.size)
            assertEquals(svc1.id, config.targetServices["order-service"])
            assertEquals(svc2.id, config.targetServices["api-gateway"])
        }

    @Test
    fun `GET agent config scopes by identity from auth token`() =
        testApplication {
            application {
                module(
                    initDatabase = false,
                    apiKey = testApiKey,
                    apiKeyOrgId = org.id,
                    apiKeyCluster = "prod",
                )
            }

            val otherOrg =
                OrganizationRepository.create(
                    Organization(
                        id = UUID.randomUUID().toString(),
                        name = "Other Org",
                        createdAt = Instant.now(),
                    ),
                )
            createService("order-service", organizationId = org.id, cluster = "prod")
            createService("other-service", organizationId = otherOrg.id, cluster = "prod")
            createService("staging-service", organizationId = org.id, cluster = "staging")

            val response =
                client.get("/api/agent/config") {
                    bearerAuth(testApiKey)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val config = json.decodeFromString<AgentConfigResponse>(response.bodyAsText())
            assertEquals(1, config.targetServices.size)
            assertEquals("order-service", config.targetServices.keys.single())
        }

    @Test
    fun `GET agent config returns 401 without valid token when auth enabled`() =
        testApplication {
            application {
                module(initDatabase = false, apiKey = testApiKey)
            }

            val response = client.get("/api/agent/config")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `GET agent config returns 401 with wrong token`() =
        testApplication {
            application {
                module(initDatabase = false, apiKey = testApiKey)
            }

            val response =
                client.get("/api/agent/config") {
                    bearerAuth("wrong-key")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `health endpoint is accessible without auth`() =
        testApplication {
            application {
                module(initDatabase = false, apiKey = testApiKey)
            }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
        }
}
