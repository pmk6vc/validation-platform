package com.platform.agent

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
                        ?: "http://kubeshark-front.default:80",
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
 * Serializes [Duration] as a plain Long number of milliseconds on the JSON wire.
 *
 * `kotlinx-serialization`'s built-in `Duration` serializer uses ISO-8601
 * strings (e.g. `"PT5S"`), but our API contract with the platform is
 * milliseconds-as-Long (e.g. `5000`). This serializer bridges the two so the
 * in-memory type can be [Duration] while the wire format stays unchanged.
 */
object DurationAsMillisSerializer : KSerializer<Duration> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("DurationMs", PrimitiveKind.LONG)

    override fun serialize(
        encoder: Encoder,
        value: Duration,
    ) {
        encoder.encodeLong(value.inWholeMilliseconds)
    }

    override fun deserialize(decoder: Decoder): Duration = decoder.decodeLong().milliseconds
}

/**
 * Dynamic configuration polled from the platform at runtime.
 * All fields have safe defaults so the agent can operate before the first successful poll.
 *
 * targetServices maps K8s service name → platform service ID.
 * This replaces the static TARGET_SERVICES env var from the previous design.
 *
 * Interval fields are [Duration]-typed in memory but serialized as Long
 * milliseconds over the wire via [DurationAsMillisSerializer].
 */
@Serializable
data class DynamicConfig(
    val targetServices: Map<String, String> = emptyMap(),
    val samplingRate: Double = 1.0,
    val batchSize: Int = 100,
    @Serializable(with = DurationAsMillisSerializer::class)
    val captureInterval: Duration = 5.seconds,
    @Serializable(with = DurationAsMillisSerializer::class)
    val configPollInterval: Duration = 30.seconds,
    @Serializable(with = DurationAsMillisSerializer::class)
    val discoveryInterval: Duration = 60.seconds,
    val namespaceFilters: List<String> = emptyList(),
) {
    companion object {
        fun default(): DynamicConfig = DynamicConfig()
    }
}
