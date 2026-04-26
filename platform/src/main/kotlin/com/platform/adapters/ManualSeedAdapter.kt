package com.platform.adapters

import com.platform.models.Provider
import com.platform.models.Service
import com.platform.shared.models.OrganizationId
import com.platform.shared.models.ServiceId
import java.time.Instant

/**
 * Adapter that provides hardcoded seed data for testing and development.
 *
 * This adapter simulates service discovery by returning a predefined set of services
 * representing a typical microservices architecture with frontend, backend, messaging, and data layers.
 *
 * Use cases:
 * - Development environment setup without requiring Kubernetes/Kubeshark
 * - Testing adapter integration patterns
 * - Demos and examples
 */
class ManualSeedAdapter : ServiceAdapter {
    /**
     * Returns a predefined set of seed services representing a typical microservices setup.
     *
     * The seed data includes:
     * - Frontend services (web UI, mobile API gateway)
     * - Backend services (order service, payment service, user service)
     * - Messaging layer (Kafka)
     * - Data layer services (PostgreSQL, Redis)
     *
     * All services are deployed in the "prod-us-east" cluster across different namespaces.
     */
    override suspend fun discoverServices(organizationId: OrganizationId): List<Service> {
        val now = Instant.now()

        return listOf(
            // Frontend layer
            Service(
                id = ServiceId.generate(),
                organizationId = organizationId,
                cluster = "prod-us-east",
                namespace = "frontend",
                name = "web-ui",
                provider = Provider.MANUAL_SEED,
                discoveredAt = now,
                lastSeenAt = now,
                metadata =
                    mapOf(
                        "version" to "2.1.0",
                        "language" to "react",
                        "team" to "frontend",
                    ),
            ),
            Service(
                id = ServiceId.generate(),
                organizationId = organizationId,
                cluster = "prod-us-east",
                namespace = "frontend",
                name = "mobile-api-gateway",
                provider = Provider.MANUAL_SEED,
                discoveredAt = now,
                lastSeenAt = now,
                metadata =
                    mapOf(
                        "version" to "1.5.2",
                        "language" to "kotlin",
                        "team" to "mobile",
                    ),
            ),
            // Backend services
            Service(
                id = ServiceId.generate(),
                organizationId = organizationId,
                cluster = "prod-us-east",
                namespace = "backend",
                name = "order-service",
                provider = Provider.MANUAL_SEED,
                discoveredAt = now,
                lastSeenAt = now,
                metadata =
                    mapOf(
                        "version" to "3.2.1",
                        "language" to "kotlin",
                        "team" to "orders",
                        "database" to "postgresql",
                    ),
            ),
            Service(
                id = ServiceId.generate(),
                organizationId = organizationId,
                cluster = "prod-us-east",
                namespace = "backend",
                name = "payment-service",
                provider = Provider.MANUAL_SEED,
                discoveredAt = now,
                lastSeenAt = now,
                metadata =
                    mapOf(
                        "version" to "2.0.3",
                        "language" to "java",
                        "team" to "payments",
                        "pci-compliant" to "true",
                    ),
            ),
            Service(
                id = ServiceId.generate(),
                organizationId = organizationId,
                cluster = "prod-us-east",
                namespace = "backend",
                name = "user-service",
                provider = Provider.MANUAL_SEED,
                discoveredAt = now,
                lastSeenAt = now,
                metadata =
                    mapOf(
                        "version" to "4.1.0",
                        "language" to "kotlin",
                        "team" to "identity",
                    ),
            ),
            // Messaging layer
            Service(
                id = ServiceId.generate(),
                organizationId = organizationId,
                cluster = "prod-us-east",
                namespace = "messaging",
                name = "kafka",
                provider = Provider.MANUAL_SEED,
                discoveredAt = now,
                lastSeenAt = now,
                metadata =
                    mapOf(
                        "type" to "kafka",
                        "version" to "3.6.0",
                        "brokers" to "3",
                        "topics" to "order-events,payment-events,user-events",
                    ),
            ),
            // Data layer
            Service(
                id = ServiceId.generate(),
                organizationId = organizationId,
                cluster = "prod-us-east",
                namespace = "data",
                name = "orders-db",
                provider = Provider.MANUAL_SEED,
                discoveredAt = now,
                lastSeenAt = now,
                metadata =
                    mapOf(
                        "type" to "postgresql",
                        "version" to "16.1",
                        "storage" to "1TB",
                    ),
            ),
            Service(
                id = ServiceId.generate(),
                organizationId = organizationId,
                cluster = "prod-us-east",
                namespace = "data",
                name = "session-cache",
                provider = Provider.MANUAL_SEED,
                discoveredAt = now,
                lastSeenAt = now,
                metadata =
                    mapOf(
                        "type" to "redis",
                        "version" to "7.2",
                        "memory" to "16GB",
                    ),
            ),
        )
    }
}
