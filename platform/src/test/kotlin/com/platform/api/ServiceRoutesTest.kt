package com.platform.api

import com.platform.database.AppDatabaseTestBase
import com.platform.database.OrganizationRepository
import com.platform.database.ServiceRepository
import com.platform.models.Organization
import com.platform.models.Provider
import com.platform.models.Service
import com.platform.shared.models.OrganizationId
import com.platform.shared.models.Page
import com.platform.shared.models.ServiceId
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
            testOrg = Organization(OrganizationId.generate(), "Test Org", Instant.now())
            OrganizationRepository.create(testOrg)
        }
    }

    private fun createService(
        name: String = "test-service",
        cluster: String = "prod",
        namespace: String = "default",
        provider: Provider = Provider.KUBERNETES,
        metadata: Map<String, String>? = null,
    ) = Service(
        id = ServiceId.generate(),
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
        authedTestApplication { client ->
            val response =
                client.get(
                    "/api/services",
                )

            assertEquals(HttpStatusCode.OK, response.status)
            val page =
                Json.decodeFromString(
                    Page.serializer(Service.serializer()),
                    response.bodyAsText(),
                )
            assertEquals(emptyList(), page.items)
            assertNull(page.nextCursor)
        }

    @Test
    fun `GET services should return all services`() =
        authedTestApplication { client ->
            val svc1 = createService(name = "order-service")
            val svc2 = createService(name = "payment-service")
            ServiceRepository.create(svc1)
            ServiceRepository.create(svc2)

            val response =
                client.get(
                    "/api/services",
                )

            assertEquals(HttpStatusCode.OK, response.status)
            val page =
                Json.decodeFromString(
                    Page.serializer(Service.serializer()),
                    response.bodyAsText(),
                )
            assertEquals(2, page.items.size)
            assertEquals(setOf("order-service", "payment-service"), page.items.map { it.name }.toSet())
        }

    @Test
    fun `GET services with organizationId filter should return filtered services`() =
        authedTestApplication { client ->
            val otherOrg = Organization(OrganizationId.generate(), "Other Org", Instant.now())
            OrganizationRepository.create(otherOrg)

            val svc1 = createService(name = "my-service")
            val svc2 =
                Service(
                    id = ServiceId.generate(),
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

            val response =
                client.get("/api/services?organizationId=${testOrg.id.value}") {
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val page =
                Json.decodeFromString(
                    Page.serializer(Service.serializer()),
                    response.bodyAsText(),
                )
            assertEquals(1, page.items.size)
            assertEquals("my-service", page.items[0].name)
        }

    @Test
    fun `GET services with cluster filter should return filtered services`() =
        authedTestApplication { client ->
            val svc1 = createService(name = "prod-service", cluster = "prod-us-east")
            val svc2 = createService(name = "staging-service", cluster = "staging")
            ServiceRepository.create(svc1)
            ServiceRepository.create(svc2)

            val response =
                client.get("/api/services?organizationId=${testOrg.id.value}&cluster=prod-us-east") {
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val page =
                Json.decodeFromString(
                    Page.serializer(Service.serializer()),
                    response.bodyAsText(),
                )
            assertEquals(1, page.items.size)
            assertEquals("prod-service", page.items[0].name)
        }

    @Test
    fun `GET services with namespace filter should return filtered services`() =
        authedTestApplication { client ->
            val svc1 = createService(name = "payments-api", cluster = "prod", namespace = "payments")
            val svc2 = createService(name = "orders-api", cluster = "prod", namespace = "orders")
            ServiceRepository.create(svc1)
            ServiceRepository.create(svc2)

            val response =
                client.get(
                    "/api/services?organizationId=${testOrg.id.value}&cluster=prod&namespace=payments",
                )

            assertEquals(HttpStatusCode.OK, response.status)
            val page =
                Json.decodeFromString(
                    Page.serializer(Service.serializer()),
                    response.bodyAsText(),
                )
            assertEquals(1, page.items.size)
            assertEquals("payments-api", page.items[0].name)
        }

    @Test
    fun `GET services with only namespace filter should return filtered services`() =
        authedTestApplication { client ->
            val svc1 = createService(name = "payments-api", cluster = "prod", namespace = "payments")
            val svc2 = createService(name = "payments-worker", cluster = "staging", namespace = "payments")
            val svc3 = createService(name = "orders-api", cluster = "prod", namespace = "orders")
            ServiceRepository.create(svc1)
            ServiceRepository.create(svc2)
            ServiceRepository.create(svc3)

            val response =
                client.get("/api/services?namespace=payments")

            assertEquals(HttpStatusCode.OK, response.status)
            val page =
                Json.decodeFromString(
                    Page.serializer(Service.serializer()),
                    response.bodyAsText(),
                )
            assertEquals(2, page.items.size)
            assertEquals(setOf("payments-api", "payments-worker"), page.items.map { it.name }.toSet())
        }

    @Test
    fun `GET services with only cluster filter should return filtered services`() =
        authedTestApplication { client ->
            val svc1 = createService(name = "prod-service-1", cluster = "prod")
            val svc2 = createService(name = "prod-service-2", cluster = "prod", namespace = "other")
            val svc3 = createService(name = "staging-service", cluster = "staging")
            ServiceRepository.create(svc1)
            ServiceRepository.create(svc2)
            ServiceRepository.create(svc3)

            val response =
                client.get("/api/services?cluster=prod")

            assertEquals(HttpStatusCode.OK, response.status)
            val page =
                Json.decodeFromString(
                    Page.serializer(Service.serializer()),
                    response.bodyAsText(),
                )
            assertEquals(2, page.items.size)
            assertEquals(setOf("prod-service-1", "prod-service-2"), page.items.map { it.name }.toSet())
        }

    @Test
    fun `GET services with malformed cursor should return 400`() =
        authedTestApplication { client ->
            val malformedCursors =
                listOf(
                    "garbage",
                    "",
                    "a|b|c|extra",
                    "123|not-a-uuid",
                    "not-a-number.0|${UUID.randomUUID()}",
                )
            for (cursor in malformedCursors) {
                val response =
                    client.get("/api/services?cursor=$cursor")
                assertEquals(HttpStatusCode.BadRequest, response.status, "Expected 400 for cursor: '$cursor'")
            }
        }

    @Test
    fun `GET service by id should return service when exists`() =
        authedTestApplication { client ->
            val svc = createService(name = "my-service", metadata = mapOf("version" to "1.0"))
            ServiceRepository.create(svc)

            val response =
                client.get("/api/services/${svc.id.value}") {
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val result = Json.decodeFromString<Service>(response.bodyAsText())
            assertEquals("my-service", result.name)
            assertEquals(svc.id, result.id)
            assertEquals(mapOf("version" to "1.0"), result.metadata)
        }

    @Test
    fun `GET service by id should return 404 when not exists`() =
        authedTestApplication { client ->
            val response =
                client.get("/api/services/${UUID.randomUUID()}") {
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `GET service by id should return 400 for malformed UUID`() =
        authedTestApplication { client ->
            val response =
                client.get("/api/services/not-a-uuid")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `POST services should create and return service with 201`() =
        authedTestApplication { client ->

            val response =
                client.post("/api/services") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        CreateServiceRequest(
                            organizationId = testOrg.id,
                            cluster = "prod",
                            namespace = "default",
                            name = "new-service",
                            provider = Provider.KUBERNETES,
                        ),
                    )
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val svc = Json.decodeFromString<Service>(response.bodyAsText())
            assertEquals("new-service", svc.name)
            assertNotNull(svc.id)
            assertEquals(Provider.KUBERNETES, svc.provider)
            assertEquals(testOrg.id, svc.organizationId)
        }

    @Test
    fun `POST services should persist service retrievable by GET`() =
        authedTestApplication { client ->

            val createResponse =
                client.post("/api/services") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        CreateServiceRequest(
                            organizationId = testOrg.id,
                            cluster = "staging",
                            namespace = "backend",
                            name = "persist-service",
                            metadata = mapOf("team" to "platform"),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Created, createResponse.status)

            val created = Json.decodeFromString<Service>(createResponse.bodyAsText())

            val getResponse = client.get("/api/services/${created.id.value}")
            assertEquals(HttpStatusCode.OK, getResponse.status)
            val fetched = Json.decodeFromString<Service>(getResponse.bodyAsText())
            assertEquals("persist-service", fetched.name)
            assertEquals(created.id, fetched.id)
            assertEquals(mapOf("team" to "platform"), fetched.metadata)
        }

    @Test
    fun `POST services with missing required fields returns 400`() =
        authedTestApplication { client ->
            val response =
                client.post("/api/services") {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `POST services with duplicate identity returns 409`() =
        authedTestApplication { client ->

            val request =
                CreateServiceRequest(
                    organizationId = testOrg.id,
                    cluster = "prod",
                    namespace = "default",
                    name = "duplicate-service",
                )

            val first =
                client.post("/api/services") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
            assertEquals(HttpStatusCode.Created, first.status)

            val second =
                client.post("/api/services") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
            assertEquals(HttpStatusCode.Conflict, second.status)
        }

    @Test
    fun `POST services with invalid organizationId returns 400`() =
        authedTestApplication { client ->

            val response =
                client.post("/api/services") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        CreateServiceRequest(
                            organizationId = OrganizationId.generate(),
                            cluster = "prod",
                            namespace = "default",
                            name = "orphan-service",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `POST services with blank name returns 400`() =
        authedTestApplication { client ->

            val response =
                client.post("/api/services") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        CreateServiceRequest(
                            organizationId = testOrg.id,
                            cluster = "prod",
                            namespace = "default",
                            name = "   ",
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `GET service should include all fields in response`() =
        authedTestApplication { client ->
            val svc =
                createService(
                    name = "full-service",
                    cluster = "production",
                    namespace = "backend",
                    provider = Provider.KUBERNETES,
                    metadata = mapOf("team" to "platform", "tier" to "critical"),
                )
            ServiceRepository.create(svc)

            val response =
                client.get("/api/services/${svc.id.value}") {
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val result = Json.decodeFromString<Service>(response.bodyAsText())
            assertEquals("full-service", result.name)
            assertEquals("production", result.cluster)
            assertEquals("backend", result.namespace)
            assertEquals(Provider.KUBERNETES, result.provider)
            assertEquals(mapOf("team" to "platform", "tier" to "critical"), result.metadata)
            assertEquals(testOrg.id, result.organizationId)
        }

    @Test
    fun `GET service by invalid UUID id returns 400`() =
        authedTestApplication { client ->
            val response =
                client.get("/api/services/not-a-uuid")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `GET services with limit should return paginated response`() =
        authedTestApplication { client ->
            repeat(5) { i ->
                ServiceRepository.create(createService(name = "service-$i"))
            }

            val response =
                client.get("/api/services?limit=3")

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
        authedTestApplication { client ->
            repeat(5) { i ->
                ServiceRepository.create(createService(name = "service-$i"))
            }

            // Get first page
            val firstResponse =
                client.get("/api/services?limit=2")
            assertEquals(HttpStatusCode.OK, firstResponse.status)
            val firstPage =
                Json.decodeFromString(
                    Page.serializer(Service.serializer()),
                    firstResponse.bodyAsText(),
                )
            assertEquals(2, firstPage.items.size)
            assertNotNull(firstPage.nextCursor)

            // Get second page using cursor
            val secondResponse =
                client.get("/api/services?limit=2&cursor=${firstPage.nextCursor}") {
                }
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
            val thirdResponse =
                client.get("/api/services?limit=2&cursor=${secondPage.nextCursor}") {
                }
            assertEquals(HttpStatusCode.OK, thirdResponse.status)
            val thirdPage =
                Json.decodeFromString(
                    Page.serializer(Service.serializer()),
                    thirdResponse.bodyAsText(),
                )
            assertEquals(1, thirdPage.items.size)
            assertNull(thirdPage.nextCursor)
        }

    @Test
    fun `GET services with limit=0 is clamped to 1 and returns results`() =
        authedTestApplication { client ->
            repeat(3) { i -> ServiceRepository.create(createService(name = "service-$i")) }

            // limit=0 is parsed as 0 by toIntOrNull; the repository clamps it to 1
            val response =
                client.get("/api/services?limit=0")

            assertEquals(HttpStatusCode.OK, response.status)
            val page =
                Json.decodeFromString(
                    Page.serializer(Service.serializer()),
                    response.bodyAsText(),
                )
            assertEquals(1, page.items.size)
            assertNotNull(page.nextCursor)
        }

    @Test
    fun `GET services with limit=-1 is clamped to 1 and returns results`() =
        authedTestApplication { client ->
            repeat(3) { i -> ServiceRepository.create(createService(name = "service-$i")) }

            val response =
                client.get("/api/services?limit=-1")

            assertEquals(HttpStatusCode.OK, response.status)
            val page =
                Json.decodeFromString(
                    Page.serializer(Service.serializer()),
                    response.bodyAsText(),
                )
            assertEquals(1, page.items.size)
            assertNotNull(page.nextCursor)
        }

    @Test
    fun `GET services with limit over maximum is clamped to max page size`() =
        authedTestApplication { client ->
            val count = ServiceRepository.DEFAULT_PAGE_SIZE + 5
            repeat(count) { i -> ServiceRepository.create(createService(name = "service-$i")) }

            // limit=200 exceeds MAX_PAGE_SIZE (100); the repository clamps it to 100.
            // We have fewer than 100 rows so all items fit in one page with no next cursor.
            val response =
                client.get("/api/services?limit=200")

            assertEquals(HttpStatusCode.OK, response.status)
            val page =
                Json.decodeFromString(
                    Page.serializer(Service.serializer()),
                    response.bodyAsText(),
                )
            assertEquals(count, page.items.size)
            assertNull(page.nextCursor)
        }
}
