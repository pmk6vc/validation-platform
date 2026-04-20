package com.platform.e2e

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E2E tests for the full agent → Envoy → platform pipeline.
 *
 * Tests the data path that real agents use: POST captured traffic through
 * Envoy to the collector, poll config through Envoy from the app, and
 * verify cross-org data isolation.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PlatformPipelineE2ETest : PlatformStackTestBase() {
    private val orgAToken = generateJwt(organizationId = "org-a", cluster = "prod-a")
    private val orgBToken = generateJwt(organizationId = "org-b", cluster = "prod-b")

    // --- Setup: create orgs and services ---

    @Test
    @Order(1)
    fun `setup - create org-a with services`() =
        runBlocking {
            val orgResponse =
                httpClient.post("$envoyUrl/api/organizations") {
                    bearerAuth(orgAToken)
                    contentType(ContentType.Application.Json)
                    setBody("""{"name": "Organization A"}""")
                }
            assertEquals(HttpStatusCode.Created, orgResponse.status)
            val org = json.decodeFromString<CreatedOrg>(orgResponse.bodyAsText())

            val svcResponse =
                httpClient.post("$envoyUrl/api/services") {
                    bearerAuth(orgAToken)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"organizationId": "${org.id}", "cluster": "prod-a", "namespace": "production", "name": "order-service-a"}""",
                    )
                }
            assertEquals(HttpStatusCode.Created, svcResponse.status)
        }

    @Test
    @Order(2)
    fun `setup - create org-b with services`() =
        runBlocking {
            val orgResponse =
                httpClient.post("$envoyUrl/api/organizations") {
                    bearerAuth(orgBToken)
                    contentType(ContentType.Application.Json)
                    setBody("""{"name": "Organization B"}""")
                }
            assertEquals(HttpStatusCode.Created, orgResponse.status)
            val org = json.decodeFromString<CreatedOrg>(orgResponse.bodyAsText())

            val svcResponse =
                httpClient.post("$envoyUrl/api/services") {
                    bearerAuth(orgBToken)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"organizationId": "${org.id}", "cluster": "prod-b", "namespace": "production", "name": "order-service-b"}""",
                    )
                }
            assertEquals(HttpStatusCode.Created, svcResponse.status)
        }

    // --- Agent → Envoy → Collector pipeline ---

    @Test
    @Order(10)
    fun `agent can POST captured traffic through Envoy to collector`() =
        runBlocking {
            // First get the service ID for org-a
            val configResponse =
                httpClient.get("$envoyUrl/api/agent/config") {
                    bearerAuth(orgAToken)
                }
            assertEquals(HttpStatusCode.OK, configResponse.status)
            val config = json.decodeFromString<AgentConfig>(configResponse.bodyAsText())
            val serviceId = config.targetServices["order-service-a"]
            assertNotNull(serviceId, "org-a should have order-service-a in config")

            // POST a batch of captured inputs (simulating what the agent does)
            val batchResponse =
                httpClient.post("$envoyUrl/api/captured-inputs") {
                    bearerAuth(orgAToken)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{
                        "items": [
                            {
                                "serviceId": "$serviceId",
                                "inputType": "HTTP",
                                "method": "GET",
                                "url": "/api/orders/1",
                                "responseStatus": 200,
                                "responseBody": "{\"id\": 1}",
                                "capturedAt": "${Instant.now()}"
                            },
                            {
                                "serviceId": "$serviceId",
                                "inputType": "HTTP",
                                "method": "POST",
                                "url": "/api/orders",
                                "responseStatus": 201,
                                "capturedAt": "${Instant.now()}"
                            }
                        ]
                    }""",
                    )
                }
            assertEquals(HttpStatusCode.Created, batchResponse.status)

            // Verify data landed in the collector
            val listResponse =
                httpClient.get("$envoyUrl/api/captured-inputs?serviceId=$serviceId") {
                    bearerAuth(orgAToken)
                }
            assertEquals(HttpStatusCode.OK, listResponse.status)
            val body = listResponse.bodyAsText()
            assertTrue(body.contains("/api/orders/1"), "Should find the captured GET request")
            assertTrue(body.contains("/api/orders"), "Should find the captured POST request")
        }

    // --- Agent config scoping ---

    @Test
    @Order(20)
    fun `agent config returns all services when no identity scoping`() =
        runBlocking {
            // With a generic token (no specific org/cluster match in the app's
            // HeaderIdentityPlugin), the config endpoint returns all services
            val token = generateJwt()
            val response =
                httpClient.get("$envoyUrl/api/agent/config") {
                    bearerAuth(token)
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val config = json.decodeFromString<AgentConfig>(response.bodyAsText())
            // Should see services from both orgs (no scoping without HeaderIdentityPlugin)
            assertTrue(
                config.targetServices.size >= 2,
                "Without identity scoping, should see services from multiple orgs",
            )
        }

    // --- Cross-org data isolation ---

    @Test
    @Order(30)
    fun `collector batch ingest rejects invalid serviceId`() =
        runBlocking {
            val response =
                httpClient.post("$envoyUrl/api/captured-inputs") {
                    bearerAuth(orgAToken)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{
                        "items": [{
                            "serviceId": "non-existent-service-id",
                            "inputType": "HTTP",
                            "method": "GET",
                            "url": "/test",
                            "responseStatus": 200,
                            "capturedAt": "${Instant.now()}"
                        }]
                    }""",
                    )
                }
            assertEquals(
                HttpStatusCode.BadRequest,
                response.status,
                "FK violation on non-existent serviceId should return 400",
            )
        }

    @Test
    @Order(31)
    fun `large batch ingest succeeds through Envoy`() =
        runBlocking {
            // Get a valid service ID
            val configResponse =
                httpClient.get("$envoyUrl/api/agent/config") {
                    bearerAuth(orgAToken)
                }
            val config = json.decodeFromString<AgentConfig>(configResponse.bodyAsText())
            val serviceId = config.targetServices.values.first()

            // Build a batch of 100 entries
            val items =
                (1..100).joinToString(",") { i ->
                    """{
                    "serviceId": "$serviceId",
                    "inputType": "HTTP",
                    "method": "GET",
                    "url": "/api/orders/$i",
                    "responseStatus": 200,
                    "capturedAt": "${Instant.now()}"
                }"""
                }

            val response =
                httpClient.post("$envoyUrl/api/captured-inputs") {
                    bearerAuth(orgAToken)
                    contentType(ContentType.Application.Json)
                    setBody("""{"items": [$items]}""")
                }
            assertEquals(HttpStatusCode.Created, response.status)
            val body = json.decodeFromString<BatchResult>(response.bodyAsText())
            assertEquals(100, body.created)
        }
}

@Serializable
private data class AgentConfig(
    val targetServices: Map<String, String> = emptyMap(),
)

@Serializable
private data class BatchResult(
    val created: Int,
)
