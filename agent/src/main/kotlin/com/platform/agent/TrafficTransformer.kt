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
     * 2. Attribute by `dst.pod.metadata.labels.app`. The `app=<service-name>`
     *    invariant is enforced at discovery in [K8sServiceDiscovery] — services
     *    whose pod selector lacks `app=<name>` are skipped before registration,
     *    so a label match here cleanly resolves to a registered serviceId.
     *    Kubeshark's `dst.name` is the destination pod name (unstable across
     *    rollouts), so we don't use it for attribution.
     * 3. Only keep entries with required fields (method, url, status)
     * 4. Apply sampling rate
     *
     * The same `app` label filter is also applied server-side via
     * [KubesharkClient.buildKflQuery] — Kubeshark only sends entries that pass
     * it. The check here is a safety net for the brief window between a
     * targetServices change (which cancels the WebSocket session) and the new
     * session connecting.
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
            .mapNotNull { entry ->
                val appLabel =
                    entry.dst
                        ?.pod
                        ?.metadata
                        ?.labels
                        ?.get("app") ?: return@mapNotNull null
                val serviceId = targetServices[appLabel] ?: return@mapNotNull null
                if (entry.request?.method == null ||
                    entry.request.url == null ||
                    entry.response?.status == null
                ) {
                    return@mapNotNull null
                }
                if (random.nextDouble() >= samplingRate) return@mapNotNull null
                entry to serviceId
            }.map { (entry, serviceId) ->
                CapturedInputRequest(
                    serviceId = serviceId,
                    inputType = "HTTP",
                    method = entry.request!!.method!!,
                    url = entry.request.url!!,
                    requestHeaders = entry.request.headers,
                    requestBody = entry.request.postData?.text,
                    responseStatus = entry.response!!.status!!,
                    responseHeaders = entry.response.headers,
                    responseBody = decodeContent(entry.response.content),
                    latencyMs = entry.elapsedTime,
                    sourceIp = entry.src?.ip,
                    destinationIp = entry.dst?.ip,
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
