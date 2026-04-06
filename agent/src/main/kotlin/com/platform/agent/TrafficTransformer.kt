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
     * 1. Only keep HTTP entries (proto == "http")
     * 2. Only keep entries where dst.svc matches a target service (deduplicates)
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
            .filter { it.proto == "http" }
            .filter { it.dst?.svc != null && it.dst.svc in targetServices }
            .filter { it.method != null && it.url != null && it.status != null }
            .filter { Math.random() < samplingRate }
            .map { entry ->
                CapturedInputRequest(
                    serviceId = targetServices.getValue(entry.dst!!.svc!!),
                    inputType = "HTTP",
                    method = entry.method!!,
                    url = entry.url!!,
                    requestHeaders = entry.reqHeaders,
                    requestBody = entry.reqBody,
                    responseStatus = entry.status!!,
                    responseHeaders = entry.respHeaders,
                    responseBody = entry.respBody,
                    sourceIp = entry.src?.ip,
                    destinationIp = entry.dst.ip,
                    capturedAt = Instant.ofEpochMilli(entry.ts).toString(),
                )
            }
    }
}
