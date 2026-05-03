package com.platform.agent

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.Closeable

/** A Kubernetes Service the agent has discovered, identified by `(namespace, name)`. */
data class DiscoveredService(
    val namespace: String,
    val name: String,
)

/**
 * Lists Kubernetes Service resources for the agent's discovery loop.
 *
 * Payload is intentionally minimal — `(namespace, name)` only. The platform
 * stamps `organizationId` and `cluster` from the JWT and assigns the service
 * ID, so labels/annotations/spec details would be additive metadata. We don't
 * collect them here until a concrete consumer demands a specific field.
 */
class K8sServiceDiscovery(
    private val client: KubernetesClient = KubernetesClientBuilder().build(),
) : Closeable {
    private val logger = LoggerFactory.getLogger(K8sServiceDiscovery::class.java)

    /**
     * List Services in the configured namespaces. Empty `namespaceFilters`
     * means list across all non-system namespaces.
     *
     * Errors (RBAC denial, API timeout, etc.) are logged and surface as an
     * empty list — the discovery loop retries on the next tick.
     */
    suspend fun discover(namespaceFilters: List<String>): List<DiscoveredService> =
        withContext(Dispatchers.IO) {
            try {
                fetch(namespaceFilters)
            } catch (e: Exception) {
                logger.warn("Failed to list Kubernetes services: {}", e.message)
                emptyList()
            }
        }

    private fun fetch(namespaceFilters: List<String>): List<DiscoveredService> {
        val raw =
            if (namespaceFilters.isEmpty()) {
                client
                    .services()
                    .inAnyNamespace()
                    .list()
                    .items
            } else {
                namespaceFilters.flatMap {
                    client
                        .services()
                        .inNamespace(it)
                        .list()
                        .items
                }
            }
        return raw
            .filter { it.metadata?.namespace !in SYSTEM_NAMESPACES }
            .mapNotNull { svc ->
                val ns = svc.metadata?.namespace ?: return@mapNotNull null
                val name = svc.metadata?.name ?: return@mapNotNull null
                DiscoveredService(ns, name)
            }
    }

    override fun close() = client.close()

    companion object {
        private val SYSTEM_NAMESPACES =
            setOf("kube-system", "kube-public", "kube-node-lease")
    }
}
