package com.platform.adapters

import com.platform.models.Service
import com.platform.shared.models.OrganizationId

/**
 * Contract for adapters that discover and import services from various sources.
 *
 * Adapters normalize data from different providers (manual seed, Kubernetes, Kubeshark)
 * into the unified Service model. Each adapter is responsible for:
 * - Connecting to its data source
 * - Querying/discovering services
 * - Normalizing the data into the Service model
 *
 * The adapter does NOT persist data to the database. That responsibility belongs
 * to the caller (typically a feature/service layer) which can apply additional
 * business logic (deduplication, filtering, etc.) before persisting.
 */
interface ServiceAdapter {
    /**
     * Discovers services from the adapter's data source.
     *
     * @param organizationId The organization context for discovered services
     * @return List of services normalized into the unified Service model
     */
    suspend fun discoverServices(organizationId: OrganizationId): List<Service>
}
