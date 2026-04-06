package com.platform.agent

import com.platform.agent.models.CapturedInputRequest
import com.platform.agent.models.KubesharkEntry
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class TrafficTransformer(
    private val configRef: AtomicReference<DynamicConfig>,
) {
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
     * TODO: Support configurable header stripping (e.g. Authorization, Cookie)
     *       via DynamicConfig so customers can redact sensitive headers before
     *       traffic leaves their cluster.
     */
    fun transform(entries: List<KubesharkEntry>): List<CapturedInputRequest> {
        val config = configRef.get()
        val targetServices = config.targetServices
        val samplingRate = config.samplingRate

        return entries
            .filter { it.protocol?.name == "http" }
            .filter { it.dst?.name != null && it.dst.name in targetServices }
            .filter {
                it.request?.method != null &&
                    it.request.url != null &&
                    it.response?.status != null
            }.filter { Math.random() < samplingRate }
            .map { entry ->
                CapturedInputRequest(
                    serviceId = targetServices.getValue(entry.dst!!.name!!),
                    inputType = "HTTP",
                    method = entry.request!!.method!!,
                    url = entry.request.url!!,
                    requestHeaders =
                        entry.request.headers
                            ?.associate { it.name to it.value },
                    requestBody = null, // Request body not in base WebSocket stream
                    responseStatus = entry.response!!.status!!,
                    responseHeaders =
                        entry.response.headers
                            ?.associate { it.name to it.value },
                    responseBody = entry.response.content?.text,
                    sourceIp = entry.src?.ip,
                    destinationIp = entry.dst.ip,
                    capturedAt = Instant.ofEpochMilli(entry.timestamp).toString(),
                )
            }
    }
}
