package com.platform.api

import com.platform.database.DatabaseTestBase
import com.platform.database.OrganizationRepository
import com.platform.models.Organization
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrganizationRoutesTest : DatabaseTestBase() {
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

    @Test
    fun `GET organizations should return empty page when no organizations`() =
        testApplication {
            application { configureTestApplication() }

            val response = client.get("/api/organizations")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"items\""))
            assertTrue(body.contains("[]"))
            assertTrue(body.contains("\"nextCursor\""))
        }

    @Test
    fun `GET organizations should return all organizations`() =
        testApplication {
            application { configureTestApplication() }

            val org1 = Organization(UUID.randomUUID().toString(), "Org 1", Instant.now())
            val org2 = Organization(UUID.randomUUID().toString(), "Org 2", Instant.now())
            OrganizationRepository.create(org1)
            OrganizationRepository.create(org2)

            val response = client.get("/api/organizations")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Org 1"))
            assertTrue(body.contains("Org 2"))
        }

    @Test
    fun `GET organization by id should return organization when exists`() =
        testApplication {
            application { configureTestApplication() }

            val org = Organization(UUID.randomUUID().toString(), "Test Org", Instant.now())
            OrganizationRepository.create(org)

            val response = client.get("/api/organizations/${org.id}")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Test Org"))
            assertTrue(body.contains(org.id))
        }

    @Test
    fun `GET organization by id should return 404 when not exists`() =
        testApplication {
            application { configureTestApplication() }

            val response = client.get("/api/organizations/${UUID.randomUUID()}")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
}
