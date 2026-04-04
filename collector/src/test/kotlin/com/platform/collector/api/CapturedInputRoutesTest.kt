package com.platform.collector.api

import com.platform.collector.database.AppApiTestHelper
import com.platform.collector.database.CapturedInputRepository
import com.platform.collector.database.CollectorDatabaseTestBase
import com.platform.collector.models.CapturedInput
import com.platform.collector.models.InputType
import com.platform.models.Page
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CapturedInputRoutesTest : CollectorDatabaseTestBase() {
    private lateinit var testServiceId: String

    @BeforeEach
    fun setupServiceFixture() {
        runBlocking {
            val org = AppApiTestHelper.createOrganization("Test Organization")
            val service =
                AppApiTestHelper.createService(
                    organizationId = org.id,
                    name = "order-service",
                )
            testServiceId = service.id
        }
    }

    private fun Application.configureTestApplication() {
        install(StatusPages) {
            exception<IllegalArgumentException> { call, cause ->
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "Bad request")))
            }
            exception<ExposedSQLException> { call, cause ->
                when (cause.sqlState) {
                    "23505" -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "Resource already exists"))
                    "23503" ->
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Referenced resource not found"),
                        )
                    else -> throw cause
                }
            }
        }
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

    private fun createTestInput(
        id: String = UUID.randomUUID().toString(),
        serviceId: String = testServiceId,
        inputType: InputType = InputType.HTTP,
        method: String = "GET",
        url: String = "/api/orders",
        responseStatus: Int = 200,
        responseBody: String? = """{"orders":[]}""",
    ) = CapturedInput(
        id = id,
        serviceId = serviceId,
        inputType = inputType,
        method = method,
        url = url,
        responseStatus = responseStatus,
        responseBody = responseBody,
        capturedAt = Instant.now(),
    )

    @Test
    fun `GET captured-inputs should return empty page when no inputs`() =
        testApplication {
            application { configureTestApplication() }

            val response = client.get("/api/captured-inputs")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"items\""))
            assertTrue(body.contains("[]"))
            assertTrue(body.contains("\"nextCursor\""))
        }

    @Test
    fun `GET captured-inputs should return all inputs`() =
        testApplication {
            application { configureTestApplication() }

            val input1 = createTestInput(url = "/api/orders/1")
            val input2 = createTestInput(url = "/api/orders/2")
            CapturedInputRepository.create(input1)
            CapturedInputRepository.create(input2)

            val response = client.get("/api/captured-inputs")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("/api/orders/1"))
            assertTrue(body.contains("/api/orders/2"))
        }

    @Test
    fun `GET captured-inputs with serviceId filter should return filtered inputs`() =
        testApplication {
            application { configureTestApplication() }

            val org = runBlocking { AppApiTestHelper.createOrganization("Other Org") }
            val otherService =
                runBlocking {
                    AppApiTestHelper.createService(
                        organizationId = org.id,
                        name = "other-service",
                    )
                }

            val input1 = createTestInput(serviceId = testServiceId, url = "/api/mine")
            val input2 = createTestInput(serviceId = otherService.id, url = "/api/other")
            CapturedInputRepository.create(input1)
            CapturedInputRepository.create(input2)

            val response = client.get("/api/captured-inputs?serviceId=$testServiceId")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("/api/mine"))
            assertTrue(!body.contains("/api/other"))
        }

    @Test
    fun `GET captured-inputs with inputType filter should return filtered inputs`() =
        testApplication {
            application { configureTestApplication() }

            val httpInput = createTestInput(inputType = InputType.HTTP, url = "/api/http")
            CapturedInputRepository.create(httpInput)

            val response = client.get("/api/captured-inputs?inputType=HTTP")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("/api/http"))
        }

    @Test
    fun `GET captured-inputs with invalid inputType should return 400`() =
        testApplication {
            application { configureTestApplication() }

            val response = client.get("/api/captured-inputs?inputType=INVALID")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `GET captured-inputs with limit should return paginated response`() =
        testApplication {
            application { configureTestApplication() }

            repeat(5) { i ->
                CapturedInputRepository.create(createTestInput(url = "/api/orders/$i"))
            }

            val response = client.get("/api/captured-inputs?limit=3")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"nextCursor\""))
            // Should not contain null nextCursor since there are more items
            assertTrue(!body.contains("\"nextCursor\" : null"))
        }

    @Test
    fun `GET captured-inputs with cursor should return next page`() =
        testApplication {
            application { configureTestApplication() }

            repeat(5) { i ->
                CapturedInputRepository.create(createTestInput(url = "/api/orders/$i"))
            }

            val firstResponse = client.get("/api/captured-inputs?limit=2")
            assertEquals(HttpStatusCode.OK, firstResponse.status)
            val firstBody = firstResponse.bodyAsText()
            val lenientJson = Json { ignoreUnknownKeys = true }
            val firstPage =
                lenientJson.decodeFromString(
                    Page.serializer(CapturedInput.serializer()),
                    firstBody,
                )
            assertEquals(2, firstPage.items.size)
            assertNotNull(firstPage.nextCursor)

            val secondResponse = client.get("/api/captured-inputs?limit=2&cursor=${firstPage.nextCursor}")
            assertEquals(HttpStatusCode.OK, secondResponse.status)
            val secondPage =
                lenientJson.decodeFromString(
                    Page.serializer(CapturedInput.serializer()),
                    secondResponse.bodyAsText(),
                )
            assertEquals(2, secondPage.items.size)

            val firstIds = firstPage.items.map { it.id }.toSet()
            val secondIds = secondPage.items.map { it.id }.toSet()
            assertTrue(firstIds.intersect(secondIds).isEmpty())
        }

    @Test
    fun `GET captured-input by id should return input when exists`() =
        testApplication {
            application { configureTestApplication() }

            val input =
                createTestInput(
                    method = "POST",
                    url = "/api/orders",
                    responseStatus = 201,
                    responseBody = """{"id":"123"}""",
                )
            CapturedInputRepository.create(input)

            val response = client.get("/api/captured-inputs/${input.id}")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains(input.id))
            assertTrue(body.contains("POST"))
            assertTrue(body.contains("/api/orders"))
            assertTrue(body.contains("201"))
        }

    @Test
    fun `GET captured-input by id should return 404 when not exists`() =
        testApplication {
            application { configureTestApplication() }

            val response = client.get("/api/captured-inputs/${UUID.randomUUID()}")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `GET captured-input by id should return 400 for malformed UUID`() =
        testApplication {
            application { configureTestApplication() }

            val response = client.get("/api/captured-inputs/not-a-uuid")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `DELETE captured-inputs with serviceId should delete and return count`() =
        testApplication {
            application { configureTestApplication() }

            repeat(3) { CapturedInputRepository.create(createTestInput()) }

            val response = client.delete("/api/captured-inputs?serviceId=$testServiceId")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"deleted\""))
            assertTrue(body.contains("3"))
        }

    @Test
    fun `DELETE captured-inputs without serviceId should return 400`() =
        testApplication {
            application { configureTestApplication() }

            val response = client.delete("/api/captured-inputs")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `DELETE captured-inputs with no matching inputs should return zero`() =
        testApplication {
            application { configureTestApplication() }

            val response = client.delete("/api/captured-inputs?serviceId=${UUID.randomUUID()}")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"deleted\""))
            assertTrue(body.contains("0"))
        }
}
