package com.platform.models

import kotlinx.serialization.Serializable

/**
 * Enumeration of all data source providers recognized by the platform.
 *
 * Each adapter implementation corresponds to one of these provider types.
 * The provider is stored with each Service to track where it was discovered.
 */
@Serializable
enum class Provider {
    /**
     * Provider is unknown or not specified.
     */
    UNKNOWN,

    /**
     * Service was manually seeded (hardcoded test data, config file, etc.).
     */
    MANUAL_SEED,

    /**
     * Service was discovered via Kubernetes API.
     */
    KUBERNETES,

    /**
     * Service was discovered via Pixie (eBPF-based observability).
     */
    PIXIE,
}
