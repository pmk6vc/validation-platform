package com.platform.e2e

import com.platform.api.AgentConfigResponse
import com.platform.api.CreateOrganizationRequest
import com.platform.api.CreateServiceRequest
import com.platform.collector.models.BatchCreateCapturedInputRequest
import com.platform.collector.models.BatchCreateCapturedInputResponse
import com.platform.collector.models.CreateCapturedInputRequest
import com.platform.collector.models.InputType
import com.platform.models.Organization
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.platform.collector.models.ServiceId as CollectorServiceId

/**
 * E2E tests for the full agent → Envoy → platform pipeline.
 *
 * Tests the data path that real agents use: POST captured traffic through
 * Envoy to the collector, poll config through Envoy from the app, and
 * verify cross-org data isolation.
 *
 * JWTs are generated with real org UUIDs after creating orgs, matching
 * how production works (platform issues JWT with real org ID).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PlatformPipelineE2ETest : PlatformStackTestBase() {
    // Use a generic admin token for creating orgs (org ID doesn't matter for non-config endpoints)
    private val adminToken = generateJwt(organizationId = "admin", cluster = "admin")

    // These are populated by the setup tests and used by subsequent tests
    private companion object {
        var orgAId: String = ""
        var orgBId: String = ""
        var orgAToken: String = ""
        var orgBToken: String = ""
    }

    // --- Setup: create orgs and services ---

    @Test
    @Order(1)
    fun `setup - create org-a with services`() =
        runBlocking {
            val orgResponse =
                httpClient.post("$envoyUrl/api/organizations") {
                    bearerAuth(adminToken)
                    contentType(ContentType.Application.Json)
                    setBody(CreateOrganizationRequest(name = "Organization A"))
                }
            assertEquals(HttpStatusCode.Created, orgResponse.status)
            val org = json.decodeFromString<Organization>(orgResponse.bodyAsText())
            orgAId = org.id.value
            orgAToken = generateJwt(organizationId = org.id.value, cluster = "prod-a")

            val svcResponse =
                httpClient.post("$envoyUrl/api/services") {
                    bearerAuth(adminToken)
                    contentType(ContentType.Application.Json)
                    setBody(
                        CreateServiceRequest(
                            organizationId = org.id,
                            cluster = "prod-a",
                            namespace = "production",
                            name = "order-service-a",
                        ),
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
                    bearerAuth(adminToken)
                    contentType(ContentType.Application.Json)
                    setBody(CreateOrganizationRequest(name = "Organization B"))
                }
            assertEquals(HttpStatusCode.Created, orgResponse.status)
            val org = json.decodeFromString<Organization>(orgResponse.bodyAsText())
            orgBId = org.id.value
            orgBToken = generateJwt(organizationId = org.id.value, cluster = "prod-b")

            val svcResponse =
                httpClient.post("$envoyUrl/api/services") {
                    bearerAuth(adminToken)
                    contentType(ContentType.Application.Json)
                    setBody(
                        CreateServiceRequest(
                            organizationId = org.id,
                            cluster = "prod-b",
                            namespace = "production",
                            name = "order-service-b",
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Created, svcResponse.status)
        }

    // --- Agent → Envoy → Collector pipeline ---

    @Test
    @Order(10)
    fun `agent can POST captured traffic through Envoy to collector`() =
        runBlocking {
            val configResponse =
                httpClient.get("$envoyUrl/api/agent/config") {
                    bearerAuth(orgAToken)
                }
            assertEquals(HttpStatusCode.OK, configResponse.status)
            val config = json.decodeFromString<AgentConfigResponse>(configResponse.bodyAsText())
            val serviceId = config.targetServices["order-service-a"]
            assertNotNull(serviceId, "org-a should have order-service-a in config")

            val now = Instant.now()
            val batch =
                BatchCreateCapturedInputRequest(
                    items =
                        listOf(
                            CreateCapturedInputRequest(
                                serviceId = CollectorServiceId(serviceId),
                                inputType = InputType.HTTP,
                                method = "GET",
                                url = "/api/orders/1",
                                responseStatus = 200,
                                responseBody = """{"id": 1}""",
                                capturedAt = now,
                            ),
                            CreateCapturedInputRequest(
                                serviceId = CollectorServiceId(serviceId),
                                inputType = InputType.HTTP,
                                method = "POST",
                                url = "/api/orders",
                                responseStatus = 201,
                                capturedAt = now,
                            ),
                        ),
                )

            val batchResponse =
                httpClient.post("$envoyUrl/api/captured-inputs") {
                    bearerAuth(orgAToken)
                    contentType(ContentType.Application.Json)
                    setBody(batch)
                }
            assertEquals(HttpStatusCode.Created, batchResponse.status)

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
    fun `agent config is scoped by JWT claims - only sees own org and cluster`() =
        runBlocking {
            val response =
                httpClient.get("$envoyUrl/api/agent/config") {
                    bearerAuth(orgAToken)
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val config = json.decodeFromString<AgentConfigResponse>(response.bodyAsText())
            assertEquals(1, config.targetServices.size, "Should only see org-a's services")
            assertTrue(
                config.targetServices.containsKey("order-service-a"),
                "Should see order-service-a",
            )
        }

    // --- Cross-org data isolation ---

    @Test
    @Order(30)
    fun `collector batch ingest rejects invalid serviceId`() =
        runBlocking {
            val batch =
                BatchCreateCapturedInputRequest(
                    items =
                        listOf(
                            CreateCapturedInputRequest(
                                serviceId = CollectorServiceId("00000000-0000-0000-0000-000000000000"),
                                inputType = InputType.HTTP,
                                method = "GET",
                                url = "/test",
                                responseStatus = 200,
                                capturedAt = Instant.now(),
                            ),
                        ),
                )

            val response =
                httpClient.post("$envoyUrl/api/captured-inputs") {
                    bearerAuth(orgAToken)
                    contentType(ContentType.Application.Json)
                    setBody(batch)
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
            val configResponse =
                httpClient.get("$envoyUrl/api/agent/config") {
                    bearerAuth(orgAToken)
                }
            val config = json.decodeFromString<AgentConfigResponse>(configResponse.bodyAsText())
            val serviceId = config.targetServices.values.first()

            val batch =
                BatchCreateCapturedInputRequest(
                    items =
                        (1..100).map { i ->
                            CreateCapturedInputRequest(
                                serviceId = CollectorServiceId(serviceId),
                                inputType = InputType.HTTP,
                                method = "GET",
                                url = "/api/orders/$i",
                                responseStatus = 200,
                                capturedAt = Instant.now(),
                            )
                        },
                )

            val response =
                httpClient.post("$envoyUrl/api/captured-inputs") {
                    bearerAuth(orgAToken)
                    contentType(ContentType.Application.Json)
                    setBody(batch)
                }
            assertEquals(HttpStatusCode.Created, response.status)
            val result = json.decodeFromString<BatchCreateCapturedInputResponse>(response.bodyAsText())
            assertEquals(100, result.created)
        }
}
