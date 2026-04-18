package com.platform.agent

import com.platform.agent.models.CapturedInputRequest
import com.platform.agent.models.KubesharkContent
import com.platform.agent.models.KubesharkEntry
import kotlinx.coroutines.flow.StateFlow
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Base64
import kotlin.random.Random

class TrafficTransformer(
    private val configFlow: StateFlow<DynamicConfig>,
    private val random: Random = Random.Default,
) {
    private val logger = LoggerFactory.getLogger(TrafficTransformer::class.java)
    private val base64Decoder = Base64.getDecoder()

    /**
     * Filter and transform Kubeshark entries into collector POST payloads.
     *
     * Reads targetServices and samplingRate from the current dynamic config
     * snapshot, so changes take effect on the next call without restart.
     *
     * Filtering logic:
     * 1. Only keep HTTP entries (protocol.name == "http")
     * 2. Only keep entries where dst.name matches a target service name
     *    (Kubeshark shows each call from both pod-IP and service-IP perspectives;
     *    filtering on dst.name == service name deduplicates naturally)
     * 3. Only keep entries with required fields (method, url, status)
     * 4. Apply sampling rate
     *
     * Filters 1 and 2 are also pushed to Kubeshark as a KFL query via
     * [KubesharkClient.buildKflQuery], so the server only streams entries that
     * would pass these filters. The checks here are kept as a safety net for
     * two cases:
     *   - The brief window between a query change (which cancels the active
     *     session) and the new session connecting, during which entries buffered
     *     in the channel may still reflect the old filter.
     *   - Any Kubeshark version or configuration where the KFL query is not
     *     honoured as expected.
     *
     * TODO: Support configurable header stripping (e.g. Authorization, Cookie)
     *       via DynamicConfig so customers can redact sensitive headers before
     *       traffic leaves their cluster.
     */
    fun transform(entries: List<KubesharkEntry>): List<CapturedInputRequest> {
        val config = configFlow.value
        val targetServices = config.targetServices
        val samplingRate = config.samplingRate

        return entries
            .filter { it.protocol?.name == "http" }
            .filter { it.dst?.name != null && it.dst.name in targetServices }
            .filter {
                it.request?.method != null &&
                    it.request.url != null &&
                    it.response?.status != null
            }.filter { random.nextDouble() < samplingRate }
            .map { entry ->
                CapturedInputRequest(
                    serviceId = targetServices.getValue(entry.dst!!.name!!),
                    inputType = "HTTP",
                    method = entry.request!!.method!!,
                    url = entry.request.url!!,
                    requestHeaders =
                        entry.request.headers
                            ?.associate { it.name to it.value },
                    requestBody = entry.request.postData?.text,
                    responseStatus = entry.response!!.status!!,
                    responseHeaders =
                        entry.response.headers
                            ?.associate { it.name to it.value },
                    responseBody = decodeContent(entry.response.content),
                    sourceIp = entry.src?.ip,
                    destinationIp = entry.dst.ip,
                    capturedAt = Instant.ofEpochMilli(entry.timestamp).toString(),
                )
            }
    }

    /**
     * Decode a [KubesharkContent] body into a plain string.
     *
     * Kubeshark base64-encodes response bodies (binary-safe, handles non-UTF-8
     * payloads like images or protobuf). If `encoding == "base64"`, decode first.
     * Otherwise treat `text` as already-plaintext.
     *
     * Base64 decoding is essentially free (microseconds per 10KB), so this is safe
     * at the agent's CPU budget. If a response body is malformed base64, log and
     * drop the body rather than failing the whole batch.
     *
     * TODO: Once load-tested, enable HTTP gzip on the collector POST
     *       (CollectorClient) to reduce agent→platform bandwidth for large bodies.
     *       Ktor's ContentEncoding plugin handles this transparently on both ends.
     */
    private fun decodeContent(content: KubesharkContent?): String? {
        if (content?.text == null) return null
        if (content.encoding != "base64") return content.text
        return try {
            String(base64Decoder.decode(content.text), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            logger.debug("Failed to base64-decode response body: {}", e.message)
            null
        }
    }
}
