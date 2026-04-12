package com.platform.agent

import com.platform.agent.models.BatchCapturedInputRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Posts batches of captured inputs to the collector API.
 *
 * **Retry model:** The collector is treated as an infrastructure dependency
 * — transient failures (network blips, collector restarts, 5xx responses) are
 * retried with exponential backoff until the POST succeeds. A failure does
 * NOT cause the agent to drop data. Instead, [sendBatch] suspends while
 * retrying, which naturally propagates backpressure:
 *
 * ```
 * Collector outage
 *     ↓
 * sendBatch suspends on retry delay
 *     ↓
 * captureOneBatch suspends
 *     ↓
 * capture loop stops draining the Kubeshark channel
 *     ↓
 * Kubeshark channel fills
 *     ↓
 * KubesharkClient streamer suspends on channel.send
 *     ↓
 * Ktor WebSocket receive buffer fills
 *     ↓
 * TCP window closes → Kubeshark slows emission
 * ```
 *
 * The whole pipeline tail-wags on the slowest downstream consumer. When the
 * collector comes back, every stage unwinds and resumes. No retries queue up
 * in memory, no data is dropped except whatever Kubeshark itself rolls out of
 * its in-cluster buffer during the outage.
 *
 * On cancellation (the enclosing scope is cancelled, e.g. the agent is
 * shutting down), the retry loop unsuspends via Kotlin's cooperative
 * cancellation and the in-flight batch is discarded — the normal shutdown
 * path.
 *
 * **4xx responses** are NOT retried — they indicate a client bug or contract
 * mismatch that retries won't fix. The batch is dropped with an error log.
 * We do not want to hammer the collector with malformed requests.
 */
class CollectorClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val authToken: String,
    private val initialBackoff: Duration = DEFAULT_INITIAL_BACKOFF,
    private val maxBackoff: Duration = DEFAULT_MAX_BACKOFF,
) {
    private val logger = LoggerFactory.getLogger(CollectorClient::class.java)

    /**
     * Post a batch of captured inputs to the collector API, retrying
     * transient failures (network errors, 5xx) with exponential backoff
     * until success.
     *
     * Suspends indefinitely on a sustained collector outage — the caller's
     * structured concurrency scope is responsible for propagating cancellation.
     * Empty batches return immediately without making any HTTP request.
     *
     * A 4xx response is treated as a permanent failure: the batch is dropped
     * and the method returns without retrying.
     */
    suspend fun sendBatch(batch: BatchCapturedInputRequest) {
        if (batch.items.isEmpty()) return

        var backoff = initialBackoff
        var attempt = 0
        while (true) {
            attempt++
            val outcome = tryPost(batch, attempt)
            when (outcome) {
                SendOutcome.Success -> return
                SendOutcome.PermanentFailure -> return
                SendOutcome.TransientFailure -> {
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(maxBackoff)
                }
            }
        }
    }

    /**
     * Run one HTTP POST attempt and classify the outcome.
     *
     * Success: 2xx → [SendOutcome.Success]
     * Client error: 4xx → [SendOutcome.PermanentFailure] (don't retry)
     * Server error / network / timeout → [SendOutcome.TransientFailure] (retry)
     */
    private suspend fun tryPost(
        batch: BatchCapturedInputRequest,
        attempt: Int,
    ): SendOutcome =
        try {
            val response =
                httpClient.post("$baseUrl/api/captured-inputs") {
                    contentType(ContentType.Application.Json)
                    bearerAuth(authToken)
                    setBody(batch)
                }

            when {
                response.status.isSuccess() -> {
                    if (attempt > 1) {
                        logger.info(
                            "Sent batch of {} captured inputs after {} attempts",
                            batch.items.size,
                            attempt,
                        )
                    } else {
                        logger.debug("Sent batch of {} captured inputs", batch.items.size)
                    }
                    SendOutcome.Success
                }
                response.status.value in 400..499 -> {
                    logger.error(
                        "Collector rejected batch of {} with {} (permanent, dropping): {}",
                        batch.items.size,
                        response.status,
                        response.bodyAsText(),
                    )
                    SendOutcome.PermanentFailure
                }
                else -> {
                    logger.warn(
                        "Collector returned {} for batch of {} (attempt {}), will retry: {}",
                        response.status,
                        batch.items.size,
                        attempt,
                        response.bodyAsText(),
                    )
                    SendOutcome.TransientFailure
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Failed to POST batch of {} to collector (attempt {}), will retry: {}",
                batch.items.size,
                attempt,
                e.message,
            )
            SendOutcome.TransientFailure
        }

    private enum class SendOutcome { Success, PermanentFailure, TransientFailure }

    companion object {
        /** Starting backoff — doubles on each consecutive failure. */
        val DEFAULT_INITIAL_BACKOFF: Duration = 100.milliseconds

        /** Cap on the backoff so we still retry periodically during long outages. */
        val DEFAULT_MAX_BACKOFF: Duration = 30.seconds
    }
}
