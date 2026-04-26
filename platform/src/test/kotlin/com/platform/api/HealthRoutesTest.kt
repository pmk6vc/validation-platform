package com.platform.api

import com.platform.database.PlatformDatabaseTestBase
import com.platform.module
import com.platform.shared.testing.TestJwtKeys
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class HealthRoutesTest : PlatformDatabaseTestBase() {
    @Test
    fun `GET root should return welcome message`() =
        testApplication {
            application { module(initDatabase = false, privateKeyPem = TestJwtKeys.privateKeyPem) }

            val response = client.get("/")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("Validation Platform API", response.bodyAsText())
        }

    @Test
    fun `GET health should return OK`() =
        testApplication {
            application { module(initDatabase = false, privateKeyPem = TestJwtKeys.privateKeyPem) }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("OK", response.bodyAsText())
        }
}
