package com.platform.api

import com.platform.database.AppDatabaseTestBase
import com.platform.database.OrganizationRepository
import com.platform.database.ServiceRepository
import com.platform.models.Organization
import com.platform.models.Page
import com.platform.models.Provider
import com.platform.models.Service
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceRoutesTest : AppDatabaseTestBase() {
    private lateinit var testOrg: Organization

    @BeforeEach
    fun setupOrg() {
        runBlocking {
            testOrg = Organization(UUID.randomUUID().toString(), "Test Org", Instant.now())
            OrganizationRepository.create(testOrg)
        }
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
        provider: Provider = Provider.KUBERNETES,
        metadata: Map<String, String>? = null,
    ) = Service(
        id = UUID.randomUUID().toString(),
        organizationId = testOrg.id,
        cluster = cluster,
        namespace = namespace,
        name = name,
        provider = provider,
        discoveredAt = Instant.now(),
        lastSeenAt = Instant.now(),
        metadata = metadata,
    )

    @Test
    fun `GET services should return empty page when no services`() =
        testApplication {
            application { configureTestApplication() }

            val response = client.get("/api/services")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"items\""))
            assertTrue(body.contains("[]"))
            assertTrue(body.contains("\"nextCursor\""))
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
                    provider = Provider.KUBERNETES,
                    discoveredAt = Instant.now(),
                    lastSeenAt = Instant.now(),
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
                    provider = Provider.KUBERNETES,
                    metadata = mapOf("team" to "platform", "tier" to "critical"),
                )
            ServiceRepository.create(svc)

            val response = client.get("/api/services/${svc.id}")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("full-service"))
            assertTrue(body.contains("production"))
            assertTrue(body.contains("backend"))
            assertTrue(body.contains("KUBERNETES"))
            assertTrue(body.contains("platform"))
            assertTrue(body.contains("critical"))
            assertTrue(body.contains(testOrg.id))
        }

    @Test
    fun `GET services with limit should return paginated response`() =
        testApplication {
            application { configureTestApplication() }

            repeat(5) { i ->
                ServiceRepository.create(createService(name = "service-$i"))
            }

            val response = client.get("/api/services?limit=3")

            assertEquals(HttpStatusCode.OK, response.status)
            val page =
                Json.decodeFromString(
                    Page.serializer(Service.serializer()),
                    response.bodyAsText(),
                )
            assertEquals(3, page.items.size)
            assertNotNull(page.nextCursor)
        }

    @Test
    fun `GET services with cursor should return next page`() =
        testApplication {
            application { configureTestApplication() }

            repeat(5) { i ->
                ServiceRepository.create(createService(name = "service-$i"))
            }

            // Get first page
            val firstResponse = client.get("/api/services?limit=2")
            assertEquals(HttpStatusCode.OK, firstResponse.status)
            val firstPage =
                Json.decodeFromString(
                    Page.serializer(Service.serializer()),
                    firstResponse.bodyAsText(),
                )
            assertEquals(2, firstPage.items.size)
            assertNotNull(firstPage.nextCursor)

            // Get second page using cursor
            val secondResponse = client.get("/api/services?limit=2&cursor=${firstPage.nextCursor}")
            assertEquals(HttpStatusCode.OK, secondResponse.status)
            val secondPage =
                Json.decodeFromString(
                    Page.serializer(Service.serializer()),
                    secondResponse.bodyAsText(),
                )
            assertEquals(2, secondPage.items.size)
            assertNotNull(secondPage.nextCursor)

            // Verify no overlap
            val firstPageIds = firstPage.items.map { it.id }.toSet()
            val secondPageIds = secondPage.items.map { it.id }.toSet()
            assertTrue(firstPageIds.intersect(secondPageIds).isEmpty())

            // Get third page
            val thirdResponse = client.get("/api/services?limit=2&cursor=${secondPage.nextCursor}")
            assertEquals(HttpStatusCode.OK, thirdResponse.status)
            val thirdPage =
                Json.decodeFromString(
                    Page.serializer(Service.serializer()),
                    thirdResponse.bodyAsText(),
                )
            assertEquals(1, thirdPage.items.size)
            assertNull(thirdPage.nextCursor)
        }
}
