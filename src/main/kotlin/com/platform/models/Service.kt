package com.platform.models

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * A deployable unit (container, function, etc.) discovered in an environment.
 *
 * Uniquely identified by: organizationId + cluster + namespace + name
 */
@Serializable
data class Service(
    val id: String,
    val organizationId: String,
    val cluster: String,
    val namespace: String,
    val name: String,
    val provider: String? = null,
    @Serializable(with = InstantSerializer::class)
    val discoveredAt: Instant,
    val metadata: Map<String, String>? = null
)

/**
 * Custom serializer for java.time.Instant to work with kotlinx.serialization.
 */
object InstantSerializer : kotlinx.serialization.KSerializer<Instant> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
        "Instant",
        kotlinx.serialization.descriptors.PrimitiveKind.STRING
    )

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Instant {
        return Instant.parse(decoder.decodeString())
    }
}
