package com.platform.collector.api

import com.platform.collector.database.CollectorDatabaseTestBase
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

class HealthRoutesTest : CollectorDatabaseTestBase() {
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
    fun `GET health should return OK`() =
        testApplication {
            application { configureTestApplication() }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("OK", response.bodyAsText())
        }
}
