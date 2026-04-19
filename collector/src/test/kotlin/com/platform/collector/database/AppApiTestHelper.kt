package com.platform.collector.database

import com.platform.api.CreateOrganizationRequest
import com.platform.api.CreateServiceRequest
import com.platform.module
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

@Serializable
data class CreatedOrganization(
    val id: String,
    val name: String,
)

@Serializable
data class CreatedService(
    val id: String,
    val organizationId: String,
    val name: String,
)

private val lenientJson = Json { ignoreUnknownKeys = true }

object AppApiTestHelper {
    /**
     * Create an organization and a service in a single testApplication session,
     * avoiding the overhead of two separate Ktor startups per test.
     */
    suspend fun createOrganizationAndService(
        orgName: String,
        serviceName: String,
        cluster: String = "prod",
        namespace: String = "default",
    ): Pair<CreatedOrganization, CreatedService> {
        var org: CreatedOrganization? = null
        var svc: CreatedService? = null
        testApplication {
            application { module(initDatabase = false) }
            val client = createJsonClient()

            val orgResponse =
                client.post("/api/organizations") {
                    contentType(ContentType.Application.Json)
                    setBody(CreateOrganizationRequest(name = orgName))
                }
            org = lenientJson.decodeFromString<CreatedOrganization>(orgResponse.bodyAsText())

            val svcResponse =
                client.post("/api/services") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        CreateServiceRequest(
                            organizationId = org!!.id,
                            cluster = cluster,
                            namespace = namespace,
                            name = serviceName,
                        ),
                    )
                }
            svc = lenientJson.decodeFromString<CreatedService>(svcResponse.bodyAsText())
        }
        return Pair(org!!, svc!!)
    }

    suspend fun createOrganization(name: String): CreatedOrganization {
        var result: CreatedOrganization? = null
        testApplication {
            application { module(initDatabase = false) }
            val client = createJsonClient()
            val response =
                client.post("/api/organizations") {
                    contentType(ContentType.Application.Json)
                    setBody(CreateOrganizationRequest(name = name))
                }
            result = lenientJson.decodeFromString<CreatedOrganization>(response.bodyAsText())
        }
        return result!!
    }

    suspend fun createService(
        organizationId: String,
        name: String,
        cluster: String = "prod",
        namespace: String = "default",
    ): CreatedService {
        var result: CreatedService? = null
        testApplication {
            application { module(initDatabase = false) }
            val client = createJsonClient()
            val response =
                client.post("/api/services") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        CreateServiceRequest(
                            organizationId = organizationId,
                            cluster = cluster,
                            namespace = namespace,
                            name = name,
                        ),
                    )
                }
            result = lenientJson.decodeFromString<CreatedService>(response.bodyAsText())
        }
        return result!!
    }

    private fun ApplicationTestBuilder.createJsonClient(): HttpClient =
        createClient {
            install(ClientContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
}
