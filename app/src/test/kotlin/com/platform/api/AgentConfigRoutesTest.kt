package com.platform.api

import com.platform.database.AppDatabaseTestBase
import com.platform.database.OrganizationRepository
import com.platform.database.ServiceRepository
import com.platform.models.Organization
import com.platform.models.Service
import com.platform.module
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentConfigRoutesTest : AppDatabaseTestBase() {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun createOrgAndService(serviceName: String): Service {
        val org =
            OrganizationRepository.create(
                Organization(
                    id = UUID.randomUUID().toString(),
                    name = "Test Org",
                    createdAt = Instant.now(),
                ),
            )
        val now = Instant.now()
        return ServiceRepository.create(
            Service(
                id = UUID.randomUUID().toString(),
                organizationId = org.id,
                cluster = "prod",
                namespace = "production",
                name = serviceName,
                discoveredAt = now,
                lastSeenAt = now,
            ),
        )
    }

    @Test
    fun `GET agent config returns 200 with empty targetServices when no services`() =
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
    fun `GET agent config returns registered services in targetServices map`() =
        testApplication {
            application { module(initDatabase = false) }

            val svc1 = createOrgAndService("order-service")
            val svc2 = createOrgAndService("api-gateway")

            val response = client.get("/api/agent/config")

            assertEquals(HttpStatusCode.OK, response.status)
            val config = json.decodeFromString<AgentConfigResponse>(response.bodyAsText())
            assertEquals(2, config.targetServices.size)
            assertEquals(svc1.id, config.targetServices["order-service"])
            assertEquals(svc2.id, config.targetServices["api-gateway"])
        }
}
