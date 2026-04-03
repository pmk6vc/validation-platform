package com.platform.models.capture

import kotlinx.serialization.Serializable

/**
 * The protocol type of a captured input.
 *
 * Designed to be extended as the platform adds support for additional transports.
 */
@Serializable
enum class InputType {
    HTTP,
    KAFKA,
    PUBSUB,
}

/**
 * Read/write classification for a captured input.
 *
 * Used to control safe replay behavior — READ inputs can be replayed freely;
 * WRITE inputs require explicit opt-in to avoid side effects.
 */
@Serializable
enum class TrafficClassification {
    READ,
    WRITE,
    UNKNOWN,
}
