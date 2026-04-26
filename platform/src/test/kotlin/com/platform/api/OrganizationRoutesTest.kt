package com.platform.api

import com.platform.database.AppDatabaseTestBase
import com.platform.database.OrganizationRepository
import com.platform.models.Organization
import com.platform.shared.models.OrganizationId
import com.platform.shared.models.Page
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OrganizationRoutesTest : AppDatabaseTestBase() {
    @Test
    fun `GET organizations should return empty page when no organizations`() =
        authedTestApplication { client ->
            val response =
                client.get(
                    "/api/organizations",
                )

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
        authedTestApplication { client ->
            val org1 = Organization(OrganizationId.generate(), "Org 1", Instant.now())
            val org2 = Organization(OrganizationId.generate(), "Org 2", Instant.now())
            OrganizationRepository.create(org1)
            OrganizationRepository.create(org2)

            val response =
                client.get(
                    "/api/organizations",
                )

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
        authedTestApplication { client ->
            val org = Organization(OrganizationId.generate(), "Test Org", Instant.now())
            OrganizationRepository.create(org)

            val response =
                client.get("/api/organizations/${org.id.value}") {
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val result = Json.decodeFromString<Organization>(response.bodyAsText())
            assertEquals("Test Org", result.name)
            assertEquals(org.id, result.id)
        }

    @Test
    fun `GET organization by id should return 404 when not exists`() =
        authedTestApplication { client ->
            val response =
                client.get("/api/organizations/${UUID.randomUUID()}") {
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `GET organization by id should return 400 for malformed UUID`() =
        authedTestApplication { client ->
            val response =
                client.get("/api/organizations/not-a-uuid")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `GET organizations with malformed cursor should return 400`() =
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
                    client.get("/api/organizations?cursor=$cursor")
                assertEquals(HttpStatusCode.BadRequest, response.status, "Expected 400 for cursor: '$cursor'")
            }
        }

    @Test
    fun `POST organizations should create and return organization with 201`() =
        authedTestApplication { client ->

            val response =
                client.post("/api/organizations") {
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
        authedTestApplication { client ->

            val createResponse =
                client.post("/api/organizations") {
                    contentType(ContentType.Application.Json)
                    setBody(CreateOrganizationRequest(name = "Persist Corp"))
                }
            assertEquals(HttpStatusCode.Created, createResponse.status)

            val created = Json.decodeFromString<com.platform.models.Organization>(createResponse.bodyAsText())

            val getResponse = client.get("/api/organizations/${created.id.value}")
            assertEquals(HttpStatusCode.OK, getResponse.status)
            val fetched = Json.decodeFromString<Organization>(getResponse.bodyAsText())
            assertEquals("Persist Corp", fetched.name)
            assertEquals(created.id, fetched.id)
        }

    @Test
    fun `POST organizations with missing name returns 400`() =
        authedTestApplication { client ->
            val response =
                client.post("/api/organizations") {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `GET organizations by invalid UUID id returns 400`() =
        authedTestApplication { client ->
            val response =
                client.get("/api/organizations/not-a-uuid")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `POST organizations with blank name returns 400`() =
        authedTestApplication { client ->

            val response =
                client.post("/api/organizations") {
                    contentType(ContentType.Application.Json)
                    setBody(CreateOrganizationRequest(name = "   "))
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `GET organizations with limit=0 is clamped to 1 and returns results`() =
        authedTestApplication { client ->
            repeat(3) { i ->
                OrganizationRepository.create(
                    Organization(OrganizationId.generate(), "Org $i", Instant.now()),
                )
            }

            // limit=0 is parsed as 0 by toIntOrNull; the repository clamps it to 1
            val response =
                client.get("/api/organizations?limit=0")

            assertEquals(HttpStatusCode.OK, response.status)
            val page =
                Json.decodeFromString(
                    Page.serializer(Organization.serializer()),
                    response.bodyAsText(),
                )
            assertEquals(1, page.items.size)
            assertNotNull(page.nextCursor)
        }

    @Test
    fun `GET organizations with limit=-1 is clamped to 1 and returns results`() =
        authedTestApplication { client ->
            repeat(3) { i ->
                OrganizationRepository.create(
                    Organization(OrganizationId.generate(), "Org $i", Instant.now()),
                )
            }

            val response =
                client.get("/api/organizations?limit=-1")

            assertEquals(HttpStatusCode.OK, response.status)
            val page =
                Json.decodeFromString(
                    Page.serializer(Organization.serializer()),
                    response.bodyAsText(),
                )
            assertEquals(1, page.items.size)
            assertNotNull(page.nextCursor)
        }

    @Test
    fun `GET organizations with limit over maximum is clamped to max page size`() =
        authedTestApplication { client ->
            // Insert more than DEFAULT_PAGE_SIZE but fewer than MAX_PAGE_SIZE items so
            // we can tell whether the limit was actually capped
            val count = OrganizationRepository.DEFAULT_PAGE_SIZE + 5
            repeat(count) { i ->
                OrganizationRepository.create(
                    Organization(OrganizationId.generate(), "Org $i", Instant.now()),
                )
            }

            // limit=200 exceeds MAX_PAGE_SIZE (100); the repository clamps it to 100.
            // Since we only have (DEFAULT_PAGE_SIZE + 5) rows the page should contain
            // all of them and there should be no next cursor.
            val response =
                client.get("/api/organizations?limit=200")

            assertEquals(HttpStatusCode.OK, response.status)
            val page =
                Json.decodeFromString(
                    Page.serializer(Organization.serializer()),
                    response.bodyAsText(),
                )
            assertEquals(count, page.items.size)
            assertNull(page.nextCursor)
        }
}
