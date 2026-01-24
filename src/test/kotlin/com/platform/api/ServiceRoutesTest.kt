package com.platform.api

import com.platform.database.DatabaseTestBase
import com.platform.database.OrganizationRepository
import com.platform.database.ServiceRepository
import com.platform.models.Organization
import com.platform.models.Service
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceRoutesTest : DatabaseTestBase() {
    private lateinit var testOrg: Organization

    @BeforeEach
    fun setupOrg() {
        testOrg = Organization(UUID.randomUUID().toString(), "Test Org", Instant.now())
        OrganizationRepository.create(testOrg)
    }

    private fun Application.configureTestApplication() {
        install(ContentNegotiation) {
            json(
                Json {
                    prettyPrint = true
                    ignoreUnknownKeys = true
                },
            )
        }
        configureRouting()
    }

    private fun createService(
        name: String = "test-service",
        cluster: String = "prod",
        namespace: String = "default",
        provider: String? = "AWS",
        metadata: Map<String, String>? = null,
    ) = Service(
        id = UUID.randomUUID().toString(),
        organizationId = testOrg.id,
        cluster = cluster,
        namespace = namespace,
        name = name,
        provider = provider,
        discoveredAt = Instant.now(),
        metadata = metadata,
    )

    @Test
    fun `GET services should return empty list when no services`() =
        testApplication {
            application { configureTestApplication() }

            val response = client.get("/api/services")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("[]", response.bodyAsText().trim())
        }

    @Test
    fun `GET services should return all services`() =
        testApplication {
            application { configureTestApplication() }

            val svc1 = createService(name = "order-service")
            val svc2 = createService(name = "payment-service")
            ServiceRepository.create(svc1)
            ServiceRepository.create(svc2)

            val response = client.get("/api/services")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("order-service"))
            assertTrue(body.contains("payment-service"))
        }

    @Test
    fun `GET services with organizationId filter should return filtered services`() =
        testApplication {
            application { configureTestApplication() }

            val otherOrg = Organization(UUID.randomUUID().toString(), "Other Org", Instant.now())
            OrganizationRepository.create(otherOrg)

            val svc1 = createService(name = "my-service")
            val svc2 =
                Service(
                    id = UUID.randomUUID().toString(),
                    organizationId = otherOrg.id,
                    cluster = "prod",
                    namespace = "default",
                    name = "other-service",
                    provider = "AWS",
                    discoveredAt = Instant.now(),
                    metadata = null,
                )
            ServiceRepository.create(svc1)
            ServiceRepository.create(svc2)

            val response = client.get("/api/services?organizationId=${testOrg.id}")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("my-service"))
            assertTrue(!body.contains("other-service"))
        }

    @Test
    fun `GET services with cluster filter should return filtered services`() =
        testApplication {
            application { configureTestApplication() }

            val svc1 = createService(name = "prod-service", cluster = "prod-us-east")
            val svc2 = createService(name = "staging-service", cluster = "staging")
            ServiceRepository.create(svc1)
            ServiceRepository.create(svc2)

            val response = client.get("/api/services?organizationId=${testOrg.id}&cluster=prod-us-east")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("prod-service"))
            assertTrue(!body.contains("staging-service"))
        }

    @Test
    fun `GET services with namespace filter should return filtered services`() =
        testApplication {
            application { configureTestApplication() }

            val svc1 = createService(name = "payments-api", cluster = "prod", namespace = "payments")
            val svc2 = createService(name = "orders-api", cluster = "prod", namespace = "orders")
            ServiceRepository.create(svc1)
            ServiceRepository.create(svc2)

            val response = client.get("/api/services?organizationId=${testOrg.id}&cluster=prod&namespace=payments")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("payments-api"))
            assertTrue(!body.contains("orders-api"))
        }

    @Test
    fun `GET services with only namespace filter should return filtered services`() =
        testApplication {
            application { configureTestApplication() }

            val svc1 = createService(name = "payments-api", cluster = "prod", namespace = "payments")
            val svc2 = createService(name = "payments-worker", cluster = "staging", namespace = "payments")
            val svc3 = createService(name = "orders-api", cluster = "prod", namespace = "orders")
            ServiceRepository.create(svc1)
            ServiceRepository.create(svc2)
            ServiceRepository.create(svc3)

            val response = client.get("/api/services?namespace=payments")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("payments-api"))
            assertTrue(body.contains("payments-worker"))
            assertTrue(!body.contains("orders-api"))
        }

    @Test
    fun `GET services with only cluster filter should return filtered services`() =
        testApplication {
            application { configureTestApplication() }

            val svc1 = createService(name = "prod-service-1", cluster = "prod")
            val svc2 = createService(name = "prod-service-2", cluster = "prod", namespace = "other")
            val svc3 = createService(name = "staging-service", cluster = "staging")
            ServiceRepository.create(svc1)
            ServiceRepository.create(svc2)
            ServiceRepository.create(svc3)

            val response = client.get("/api/services?cluster=prod")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("prod-service-1"))
            assertTrue(body.contains("prod-service-2"))
            assertTrue(!body.contains("staging-service"))
        }

    @Test
    fun `GET service by id should return service when exists`() =
        testApplication {
            application { configureTestApplication() }

            val svc = createService(name = "my-service", metadata = mapOf("version" to "1.0"))
            ServiceRepository.create(svc)

            val response = client.get("/api/services/${svc.id}")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("my-service"))
            assertTrue(body.contains(svc.id))
            assertTrue(body.contains("version"))
            assertTrue(body.contains("1.0"))
        }

    @Test
    fun `GET service by id should return 404 when not exists`() =
        testApplication {
            application { configureTestApplication() }

            val response = client.get("/api/services/${UUID.randomUUID()}")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `GET service should include all fields in response`() =
        testApplication {
            application { configureTestApplication() }

            val svc =
                createService(
                    name = "full-service",
                    cluster = "production",
                    namespace = "backend",
                    provider = "GCP",
                    metadata = mapOf("team" to "platform", "tier" to "critical"),
                )
            ServiceRepository.create(svc)

            val response = client.get("/api/services/${svc.id}")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("full-service"))
            assertTrue(body.contains("production"))
            assertTrue(body.contains("backend"))
            assertTrue(body.contains("GCP"))
            assertTrue(body.contains("platform"))
            assertTrue(body.contains("critical"))
            assertTrue(body.contains(testOrg.id))
        }
}
