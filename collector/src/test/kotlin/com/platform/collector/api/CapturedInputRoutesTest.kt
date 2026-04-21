package com.platform.collector.api

import com.platform.collector.database.CapturedInputRepository
import com.platform.collector.database.CollectorDatabaseTestBase
import com.platform.collector.models.BatchCreateCapturedInputRequest
import com.platform.collector.models.BatchCreateCapturedInputResponse
import com.platform.collector.models.CapturedInput
import com.platform.collector.models.CapturedInputId
import com.platform.collector.models.CreateCapturedInputRequest
import com.platform.collector.models.DeleteResponse
import com.platform.collector.models.InputType
import com.platform.collector.models.ServiceId
import com.platform.collector.module
import com.platform.models.Page
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapturedInputRoutesTest : CollectorDatabaseTestBase() {
    private val lenientJson = Json { ignoreUnknownKeys = true }
    private val testServiceId: ServiceId = ServiceId(UUID.randomUUID().toString())

    private fun ApplicationTestBuilder.createJsonClient(): HttpClient =
        createClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

    private fun createTestInput(
        id: String = UUID.randomUUID().toString(),
        serviceId: ServiceId = testServiceId,
        inputType: InputType = InputType.HTTP,
        method: String = "GET",
        url: String = "/api/orders",
        responseStatus: Int = 200,
        responseBody: String? = """{"orders":[]}""",
    ) = CapturedInput(
        id = CapturedInputId(id),
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
            application { module(initDatabase = false) }

            val response = client.get("/api/captured-inputs")

            assertEquals(HttpStatusCode.OK, response.status)
            val page =
                lenientJson.decodeFromString(
                    Page.serializer(CapturedInput.serializer()),
                    response.bodyAsText(),
                )
            assertEquals(emptyList(), page.items)
            assertNull(page.nextCursor)
        }

    @Test
    fun `GET captured-inputs should return all inputs`() =
        testApplication {
            application { module(initDatabase = false) }

            val input1 = createTestInput(url = "/api/orders/1")
            val input2 = createTestInput(url = "/api/orders/2")
            CapturedInputRepository.create(input1)
            CapturedInputRepository.create(input2)

            val response = client.get("/api/captured-inputs")

            assertEquals(HttpStatusCode.OK, response.status)
            val page =
                lenientJson.decodeFromString(
                    Page.serializer(CapturedInput.serializer()),
                    response.bodyAsText(),
                )
            assertEquals(2, page.items.size)
            assertEquals(setOf("/api/orders/1", "/api/orders/2"), page.items.map { it.url }.toSet())
        }

    @Test
    fun `GET captured-inputs with serviceId filter should return filtered inputs`() =
        testApplication {
            application { module(initDatabase = false) }

            val otherServiceId = ServiceId(UUID.randomUUID().toString())

            val input1 = createTestInput(serviceId = testServiceId, url = "/api/mine")
            val input2 = createTestInput(serviceId = otherServiceId, url = "/api/other")
            CapturedInputRepository.create(input1)
            CapturedInputRepository.create(input2)

            val response = client.get("/api/captured-inputs?serviceId=${testServiceId.value}")

            assertEquals(HttpStatusCode.OK, response.status)
            val page =
                lenientJson.decodeFromString(
                    Page.serializer(CapturedInput.serializer()),
                    response.bodyAsText(),
                )
            assertEquals(1, page.items.size)
            assertEquals("/api/mine", page.items[0].url)
        }

    @Test
    fun `GET captured-inputs with inputType filter should return filtered inputs`() =
        testApplication {
            application { module(initDatabase = false) }

            val httpInput = createTestInput(inputType = InputType.HTTP, url = "/api/http")
            CapturedInputRepository.create(httpInput)

            val response = client.get("/api/captured-inputs?inputType=HTTP")

            assertEquals(HttpStatusCode.OK, response.status)
            val page =
                lenientJson.decodeFromString(
                    Page.serializer(CapturedInput.serializer()),
                    response.bodyAsText(),
                )
            assertEquals(1, page.items.size)
            assertEquals("/api/http", page.items[0].url)
            assertEquals(InputType.HTTP, page.items[0].inputType)
        }

    @Test
    fun `GET captured-inputs with invalid serviceId UUID returns 400`() =
        testApplication {
            application { module(initDatabase = false) }

            val response = client.get("/api/captured-inputs?serviceId=not-a-uuid")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid serviceId"))
        }

    @Test
    fun `GET captured-input by invalid UUID id returns 400`() =
        testApplication {
            application { module(initDatabase = false) }

            val response = client.get("/api/captured-inputs/not-a-uuid")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid id"))
        }

    @Test
    fun `DELETE captured-inputs with invalid serviceId UUID returns 400`() =
        testApplication {
            application { module(initDatabase = false) }

            val response = client.delete("/api/captured-inputs?serviceId=not-a-uuid")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid serviceId"))
        }

    @Test
    fun `GET captured-inputs with invalid inputType should return 400`() =
        testApplication {
            application { module(initDatabase = false) }

            val response = client.get("/api/captured-inputs?inputType=INVALID")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `GET captured-inputs with limit should return paginated response`() =
        testApplication {
            application { module(initDatabase = false) }

            repeat(5) { i ->
                CapturedInputRepository.create(createTestInput(url = "/api/orders/$i"))
            }

            val response = client.get("/api/captured-inputs?limit=3")

            assertEquals(HttpStatusCode.OK, response.status)
            val page =
                lenientJson.decodeFromString(
                    Page.serializer(CapturedInput.serializer()),
                    response.bodyAsText(),
                )
            assertEquals(3, page.items.size)
            assertNotNull(page.nextCursor)
        }

    @Test
    fun `GET captured-inputs with cursor should return next page`() =
        testApplication {
            application { module(initDatabase = false) }

            repeat(5) { i ->
                CapturedInputRepository.create(createTestInput(url = "/api/orders/$i"))
            }

            val firstResponse = client.get("/api/captured-inputs?limit=2")
            assertEquals(HttpStatusCode.OK, firstResponse.status)
            val firstPage =
                lenientJson.decodeFromString(
                    Page.serializer(CapturedInput.serializer()),
                    firstResponse.bodyAsText(),
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
    fun `GET captured-inputs with malformed cursor should return 400`() =
        testApplication {
            application { module(initDatabase = false) }

            val malformedCursors =
                listOf(
                    "garbage",
                    "",
                    "a|b|c|extra",
                    "123|not-a-uuid",
                    "not-a-number.0|${UUID.randomUUID()}",
                )
            for (cursor in malformedCursors) {
                val response = client.get("/api/captured-inputs?cursor=$cursor")
                assertEquals(HttpStatusCode.BadRequest, response.status, "Expected 400 for cursor: '$cursor'")
            }
        }

    @Test
    fun `GET captured-input by id should return input when exists`() =
        testApplication {
            application { module(initDatabase = false) }

            val input =
                createTestInput(
                    method = "POST",
                    url = "/api/orders",
                    responseStatus = 201,
                    responseBody = """{"id":"123"}""",
                )
            CapturedInputRepository.create(input)

            val response = client.get("/api/captured-inputs/${input.id.value}")

            assertEquals(HttpStatusCode.OK, response.status)
            val result = lenientJson.decodeFromString<CapturedInput>(response.bodyAsText())
            assertEquals(input.id, result.id)
            assertEquals("POST", result.method)
            assertEquals("/api/orders", result.url)
            assertEquals(201, result.responseStatus)
        }

    @Test
    fun `GET captured-input by id should return 404 when not exists`() =
        testApplication {
            application { module(initDatabase = false) }

            val response = client.get("/api/captured-inputs/${UUID.randomUUID()}")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `GET captured-input by id should return 400 for malformed UUID`() =
        testApplication {
            application { module(initDatabase = false) }

            val response = client.get("/api/captured-inputs/not-a-uuid")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `DELETE captured-inputs with serviceId should delete and return count`() =
        testApplication {
            application { module(initDatabase = false) }

            repeat(3) { CapturedInputRepository.create(createTestInput()) }

            val response = client.delete("/api/captured-inputs?serviceId=${testServiceId.value}")

            assertEquals(HttpStatusCode.OK, response.status)
            val result = lenientJson.decodeFromString<DeleteResponse>(response.bodyAsText())
            assertEquals(3, result.deleted)
        }

    @Test
    fun `DELETE captured-inputs without serviceId should return 400`() =
        testApplication {
            application { module(initDatabase = false) }

            val response = client.delete("/api/captured-inputs")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `DELETE captured-inputs with no matching inputs should return zero`() =
        testApplication {
            application { module(initDatabase = false) }

            val response = client.delete("/api/captured-inputs?serviceId=${UUID.randomUUID()}")

            assertEquals(HttpStatusCode.OK, response.status)
            val result = lenientJson.decodeFromString<DeleteResponse>(response.bodyAsText())
            assertEquals(0, result.deleted)
        }

    private fun createTestBatchRequest(
        serviceId: ServiceId = testServiceId,
        count: Int = 1,
    ): BatchCreateCapturedInputRequest =
        BatchCreateCapturedInputRequest(
            items =
                (1..count).map { i ->
                    CreateCapturedInputRequest(
                        serviceId = serviceId,
                        inputType = InputType.HTTP,
                        method = "GET",
                        url = "/api/orders/$i",
                        responseStatus = 200,
                        responseBody = """{"id":$i}""",
                        capturedAt = Instant.now(),
                    )
                },
        )

    @Test
    fun `POST captured-inputs with valid batch should return 201 with count`() =
        testApplication {
            application { module(initDatabase = false) }
            val jsonClient = createJsonClient()

            val response =
                jsonClient.post("/api/captured-inputs") {
                    contentType(ContentType.Application.Json)
                    setBody(createTestBatchRequest(count = 3))
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val result = lenientJson.decodeFromString<BatchCreateCapturedInputResponse>(response.bodyAsText())
            assertEquals(3, result.created)

            val listResponse = client.get("/api/captured-inputs?serviceId=${testServiceId.value}")
            assertEquals(HttpStatusCode.OK, listResponse.status)
            val page =
                lenientJson.decodeFromString(
                    Page.serializer(CapturedInput.serializer()),
                    listResponse.bodyAsText(),
                )
            assertEquals(3, page.items.size)
        }

    @Test
    fun `POST captured-inputs with empty batch should return 400`() =
        testApplication {
            application { module(initDatabase = false) }
            val jsonClient = createJsonClient()

            val response =
                jsonClient.post("/api/captured-inputs") {
                    contentType(ContentType.Application.Json)
                    setBody(BatchCreateCapturedInputRequest(items = emptyList()))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `POST captured-inputs with unknown serviceId should succeed`() =
        testApplication {
            application { module(initDatabase = false) }
            val jsonClient = createJsonClient()

            val unknownServiceId = ServiceId(UUID.randomUUID().toString())
            val response =
                jsonClient.post("/api/captured-inputs") {
                    contentType(ContentType.Application.Json)
                    setBody(createTestBatchRequest(serviceId = unknownServiceId))
                }

            assertEquals(HttpStatusCode.Created, response.status)
        }

    @Test
    fun `POST captured-inputs with malformed JSON should return 400`() =
        testApplication {
            application { module(initDatabase = false) }

            val response =
                client.post("/api/captured-inputs") {
                    contentType(ContentType.Application.Json)
                    setBody("not valid json")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `POST captured-inputs with missing required fields should return 400`() =
        testApplication {
            application { module(initDatabase = false) }

            val response =
                client.post("/api/captured-inputs") {
                    contentType(ContentType.Application.Json)
                    // missing method, url, responseStatus, capturedAt
                    setBody("""{"items":[{"serviceId":"${testServiceId.value}"}]}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `POST captured-inputs exceeding max batch size should return 400`() =
        testApplication {
            application { module(initDatabase = false) }
            val jsonClient = createJsonClient()

            val response =
                jsonClient.post("/api/captured-inputs") {
                    contentType(ContentType.Application.Json)
                    setBody(createTestBatchRequest(count = MAX_BATCH_SIZE + 1))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("exceeds maximum"))
        }

    @Test
    fun `GET captured-inputs with limit=0 is clamped to 1 and returns results`() =
        testApplication {
            application { module(initDatabase = false) }

            repeat(3) { i -> CapturedInputRepository.create(createTestInput(url = "/api/orders/$i")) }

            // limit=0 is parsed as 0 by toIntOrNull; the repository clamps it to 1
            val response = client.get("/api/captured-inputs?limit=0")

            assertEquals(HttpStatusCode.OK, response.status)
            val page =
                lenientJson.decodeFromString(
                    Page.serializer(CapturedInput.serializer()),
                    response.bodyAsText(),
                )
            assertEquals(1, page.items.size)
            assertNotNull(page.nextCursor)
        }

    @Test
    fun `GET captured-inputs with limit=-1 is clamped to 1 and returns results`() =
        testApplication {
            application { module(initDatabase = false) }

            repeat(3) { i -> CapturedInputRepository.create(createTestInput(url = "/api/orders/$i")) }

            val response = client.get("/api/captured-inputs?limit=-1")

            assertEquals(HttpStatusCode.OK, response.status)
            val page =
                lenientJson.decodeFromString(
                    Page.serializer(CapturedInput.serializer()),
                    response.bodyAsText(),
                )
            assertEquals(1, page.items.size)
            assertNotNull(page.nextCursor)
        }

    @Test
    fun `GET captured-inputs with limit over maximum is clamped to max page size`() =
        testApplication {
            application { module(initDatabase = false) }

            val count = CapturedInputRepository.DEFAULT_PAGE_SIZE + 5
            repeat(count) { i ->
                CapturedInputRepository.create(createTestInput(url = "/api/orders/$i"))
            }

            // limit=200 exceeds MAX_PAGE_SIZE (100); the repository clamps it to 100.
            // We have fewer than 100 rows so all items fit in one page with no next cursor.
            val response = client.get("/api/captured-inputs?limit=200")

            assertEquals(HttpStatusCode.OK, response.status)
            val page =
                lenientJson.decodeFromString(
                    Page.serializer(CapturedInput.serializer()),
                    response.bodyAsText(),
                )
            assertEquals(count, page.items.size)
            assertNull(page.nextCursor)
        }
}
