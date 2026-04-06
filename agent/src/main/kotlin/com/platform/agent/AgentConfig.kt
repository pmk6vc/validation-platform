package com.platform.agent

import kotlinx.serialization.Serializable

/**
 * Static configuration loaded once from environment variables at startup.
 * These are deployment-time facts that never change while the agent is running.
 */
data class StaticConfig(
    val kubesharkUrl: String,
    val collectorUrl: String,
    val apiKey: String,
) {
    companion object {
        fun fromEnvironment(env: (String) -> String? = System::getenv): StaticConfig =
            StaticConfig(
                kubesharkUrl =
                    env("KUBESHARK_URL")
                        ?: "http://kubeshark-front.kubeshark:80",
                collectorUrl = requireEnv("COLLECTOR_URL", env),
                apiKey = requireEnv("API_KEY", env),
            )

        private fun requireEnv(
            name: String,
            env: (String) -> String?,
        ): String =
            env(name)
                ?: throw IllegalStateException(
                    "Required environment variable $name is not set",
                )
    }
}

/**
 * Dynamic configuration polled from the platform at runtime.
 * All fields have safe defaults so the agent can operate before the first successful poll.
 *
 * targetServices maps K8s service name → platform service ID.
 * This replaces the static TARGET_SERVICES env var from the previous design.
 */
@Serializable
data class DynamicConfig(
    val targetServices: Map<String, String> = emptyMap(),
    val samplingRate: Double = 1.0,
    val batchSize: Int = 100,
    val captureIntervalMs: Long = 5000,
    val configPollIntervalMs: Long = 30000,
    val discoveryIntervalMs: Long = 60000,
    val namespaceFilters: List<String> = emptyList(),
) {
    companion object {
        fun default(): DynamicConfig = DynamicConfig()
    }
}
