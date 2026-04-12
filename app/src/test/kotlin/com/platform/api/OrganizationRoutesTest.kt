package com.platform.api

import com.platform.database.AppDatabaseTestBase
import com.platform.database.OrganizationRepository
import com.platform.models.Organization
import com.platform.models.Page
import com.platform.module
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
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

class OrganizationRoutesTest : AppDatabaseTestBase() {
    private fun ApplicationTestBuilder.createJsonClient() =
        createClient {
            install(ClientContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

    @Test
    fun `GET organizations should return empty page when no organizations`() =
        testApplication {
            application { module(initDatabase = false) }

            val response = client.get("/api/organizations")

            assertEquals(HttpStatusCode.OK, response.status)
            val page =
                Json.decodeFromString(
                    Page.serializer(Organization.serializer()),
                    response.bodyAsText(),
                )
            assertEquals(emptyList(), page.items)
            assertNull(page.nextCursor)
        }

    @Test
    fun `GET organizations should return all organizations`() =
        testApplication {
            application { module(initDatabase = false) }

            val org1 = Organization(UUID.randomUUID().toString(), "Org 1", Instant.now())
            val org2 = Organization(UUID.randomUUID().toString(), "Org 2", Instant.now())
            OrganizationRepository.create(org1)
            OrganizationRepository.create(org2)

            val response = client.get("/api/organizations")

            assertEquals(HttpStatusCode.OK, response.status)
            val page =
                Json.decodeFromString(
                    Page.serializer(Organization.serializer()),
                    response.bodyAsText(),
                )
            assertEquals(2, page.items.size)
            assertEquals(setOf("Org 1", "Org 2"), page.items.map { it.name }.toSet())
        }

    @Test
    fun `GET organization by id should return organization when exists`() =
        testApplication {
            application { module(initDatabase = false) }

            val org = Organization(UUID.randomUUID().toString(), "Test Org", Instant.now())
            OrganizationRepository.create(org)

            val response = client.get("/api/organizations/${org.id}")

            assertEquals(HttpStatusCode.OK, response.status)
            val result = Json.decodeFromString<Organization>(response.bodyAsText())
            assertEquals("Test Org", result.name)
            assertEquals(org.id, result.id)
        }

    @Test
    fun `GET organization by id should return 404 when not exists`() =
        testApplication {
            application { module(initDatabase = false) }

            val response = client.get("/api/organizations/${UUID.randomUUID()}")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `GET organization by id should return 400 for malformed UUID`() =
        testApplication {
            application { module(initDatabase = false) }

            val response = client.get("/api/organizations/not-a-uuid")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `GET organizations with malformed cursor should return 400`() =
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
                val response = client.get("/api/organizations?cursor=$cursor")
                assertEquals(HttpStatusCode.BadRequest, response.status, "Expected 400 for cursor: '$cursor'")
            }
        }

    @Test
    fun `POST organizations should create and return organization with 201`() =
        testApplication {
            application { module(initDatabase = false) }
            val jsonClient = createJsonClient()

            val response =
                jsonClient.post("/api/organizations") {
                    contentType(ContentType.Application.Json)
                    setBody(CreateOrganizationRequest(name = "Acme Corp"))
                }

            assertEquals(HttpStatusCode.Created, response.status)
            val org = Json.decodeFromString<Organization>(response.bodyAsText())
            assertEquals("Acme Corp", org.name)
            assertNotNull(org.id)
            assertNotNull(org.createdAt)
        }

    @Test
    fun `POST organizations should persist organization retrievable by GET`() =
        testApplication {
            application { module(initDatabase = false) }
            val jsonClient = createJsonClient()

            val createResponse =
                jsonClient.post("/api/organizations") {
                    contentType(ContentType.Application.Json)
                    setBody(CreateOrganizationRequest(name = "Persist Corp"))
                }
            assertEquals(HttpStatusCode.Created, createResponse.status)

            val created = Json.decodeFromString<com.platform.models.Organization>(createResponse.bodyAsText())

            val getResponse = jsonClient.get("/api/organizations/${created.id}")
            assertEquals(HttpStatusCode.OK, getResponse.status)
            val fetched = Json.decodeFromString<Organization>(getResponse.bodyAsText())
            assertEquals("Persist Corp", fetched.name)
            assertEquals(created.id, fetched.id)
        }

    @Test
    fun `POST organizations with missing name returns 400`() =
        testApplication {
            application { module(initDatabase = false) }

            val response =
                client.post("/api/organizations") {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
}
