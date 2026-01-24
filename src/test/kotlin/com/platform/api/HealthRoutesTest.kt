package com.platform.api

import com.platform.database.DatabaseTestBase
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
import kotlin.test.assertEquals

class HealthRoutesTest : DatabaseTestBase() {
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
    fun `GET root should return welcome message`() =
        testApplication {
            application { configureTestApplication() }

            val response = client.get("/")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("Validation Platform API", response.bodyAsText())
        }

    @Test
    fun `GET health should return OK`() =
        testApplication {
            application { configureTestApplication() }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("OK", response.bodyAsText())
        }
}
