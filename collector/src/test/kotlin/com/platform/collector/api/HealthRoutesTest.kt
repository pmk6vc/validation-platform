package com.platform.collector.api

import com.platform.collector.database.CollectorDatabaseTestBase
import com.platform.collector.module
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class HealthRoutesTest : CollectorDatabaseTestBase() {
    @Test
    fun `GET health should return OK`() =
        testApplication {
            application { module(initDatabase = false) }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("OK", response.bodyAsText())
        }
}
