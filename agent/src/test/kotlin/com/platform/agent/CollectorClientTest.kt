package com.platform.agent

import com.platform.agent.models.BatchCapturedInputRequest
import com.platform.agent.models.CapturedInputRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class CollectorClientTest {
    private fun testBatch(size: Int = 1) =
        BatchCapturedInputRequest(
            items =
                (1..size).map { i ->
                    CapturedInputRequest(
                        serviceId = "svc-123",
                        method = "GET",
                        url = "/api/orders/$i",
                        responseStatus = 200,
                        capturedAt = "2026-04-05T00:00:00Z",
                    )
                },
        )

    /**
     * Build a CollectorClient whose upstream behavior is driven by
     * [responder] — called once per request with the 1-based attempt number,
     * returning the status to respond with.
     */
    private fun mockCollector(
        responder: (attempt: Int) -> HttpStatusCode = { HttpStatusCode.OK },
        body: String = "{}",
        onRequest: (MockRequestHandleScope.(HttpRequestData) -> Unit)? = null,
    ): CollectorClient {
        val requestCount = AtomicInteger(0)
        val engine =
            MockEngine { request ->
                onRequest?.invoke(this, request)
                val attempt = requestCount.incrementAndGet()
                respond(
                    content = body,
                    status = responder(attempt),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        val httpClient = buildAgentCollectorHttpClient(engine)
        return CollectorClient(
            httpClient = httpClient,
            baseUrl = "http://collector:8081",
            authToken = "test-api-key",
            // Fast backoff so retry tests don't sleep forever
            initialBackoff = 1.milliseconds,
            maxBackoff = 10.milliseconds,
        )
    }

    @Test
    fun `returns normally on successful send`() =
        runBlocking {
            val client = mockCollector()

            // Does not throw, returns Unit
            client.sendBatch(testBatch())
        }

    @Test
    fun `skips HTTP request for empty batch`() =
        runBlocking {
            var requestMade = false
            val client = mockCollector(onRequest = { requestMade = true })

            client.sendBatch(BatchCapturedInputRequest(items = emptyList()))

            assertFalse(requestMade)
        }

    @Test
    fun `retries transient 5xx until success`() =
        runBlocking {
            var attempts = 0
            val client =
                mockCollector(
                    onRequest = { attempts++ },
                    responder = { attempt ->
                        // Fail the first 2, succeed on the 3rd
                        if (attempt <= 2) HttpStatusCode.InternalServerError else HttpStatusCode.OK
                    },
                )

            client.sendBatch(testBatch())

            assertEquals(3, attempts, "should have made 3 attempts before succeeding")
        }

    @Test
    fun `retries network exceptions with exponential backoff`() =
        runBlocking {
            var attempts = 0
            val engine =
                MockEngine {
                    attempts++
                    if (attempts < 3) {
                        throw IOException("Connection refused")
                    }
                    respond(
                        content = "{}",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val httpClient = buildAgentCollectorHttpClient(engine)
            val client =
                CollectorClient(
                    httpClient = httpClient,
                    baseUrl = "http://collector:8081",
                    authToken = "key",
                    initialBackoff = 1.milliseconds,
                    maxBackoff = 10.milliseconds,
                )

            client.sendBatch(testBatch())

            assertEquals(3, attempts, "should have retried 2 network failures then succeeded")
        }

    @Test
    fun `does not retry 4xx client errors`() =
        runBlocking {
            var attempts = 0
            val client =
                mockCollector(
                    onRequest = { attempts++ },
                    responder = { HttpStatusCode.BadRequest },
                )

            // Returns (drops batch) without throwing or retrying
            client.sendBatch(testBatch())

            assertEquals(1, attempts, "4xx should not be retried")
        }

    @Test
    fun `does not retry 401 unauthorized`() =
        runBlocking {
            var attempts = 0
            val client =
                mockCollector(
                    onRequest = { attempts++ },
                    responder = { HttpStatusCode.Unauthorized },
                )

            client.sendBatch(testBatch())

            assertEquals(1, attempts)
        }

    @Test
    fun `sends POST to correct endpoint with bearer auth`() =
        runBlocking {
            var capturedMethod: HttpMethod? = null
            var capturedUrl = ""
            var capturedAuth = ""

            val client =
                mockCollector(onRequest = { request ->
                    capturedMethod = request.method
                    capturedUrl = request.url.toString()
                    capturedAuth = request.headers[HttpHeaders.Authorization] ?: ""
                })

            client.sendBatch(testBatch())

            assertEquals(HttpMethod.Post, capturedMethod)
            assertTrue(capturedUrl.endsWith("/api/captured-inputs"))
            assertEquals("Bearer test-api-key", capturedAuth)
        }

    @Test
    fun `suspends on sustained outage and resumes when collector recovers`() =
        runBlocking {
            coroutineScope {
                var attempts = 0
                var recovered = false
                val client =
                    mockCollector(
                        onRequest = { attempts++ },
                        responder = {
                            if (recovered) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
                        },
                    )

                // Start the send; it will keep retrying against the down collector
                val sendJob = async { client.sendBatch(testBatch()) }

                // Let it retry a few times
                delay(50.milliseconds)
                assertTrue(attempts >= 2, "should have retried at least twice during outage")
                assertFalse(sendJob.isCompleted, "sendBatch should still be suspending")

                // Bring the collector back
                recovered = true
                sendJob.await()

                assertTrue(attempts > 2, "should have continued retrying past the initial attempts")
            }
        }
}
