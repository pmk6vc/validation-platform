package com.platform.agent

import com.platform.agent.models.KubesharkEntry

/**
 * Abstraction over the Kubeshark data source.
 *
 * Allows the capture loop to be tested with an in-memory implementation
 * without depending on WebSocket transport details.
 */
interface TrafficSource {
    /**
     * Fetch HTTP API calls.
     *
     * @param startMs Unix timestamp in milliseconds — only return entries after this time
     * @param limit Maximum number of entries to return
     * @return List of entries (unfiltered — caller handles service filtering)
     */
    suspend fun listHttpCalls(
        startMs: Long? = null,
        limit: Int = 100,
    ): List<KubesharkEntry>
}
