package com.platform.collector.database

import com.platform.api.CreateOrganizationRequest
import com.platform.api.CreateServiceRequest
import com.platform.api.configureRouting
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
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
    suspend fun createOrganization(name: String): CreatedOrganization {
        var result: CreatedOrganization? = null
        testApplication {
            configureApp()
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
            configureApp()
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

    private fun ApplicationTestBuilder.configureApp() {
        application {
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
    }

    private fun ApplicationTestBuilder.createJsonClient(): HttpClient =
        createClient {
            install(ClientContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
}
