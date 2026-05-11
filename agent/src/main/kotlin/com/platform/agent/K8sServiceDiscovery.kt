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
            .filter { svc -> svc.metadata?.namespace?.let { !isSystemNamespace(it) } == true }
            .mapNotNull { svc ->
                val ns = svc.metadata?.namespace ?: return@mapNotNull null
                val name = svc.metadata?.name ?: return@mapNotNull null
                // Invariant: pods backing this Service must carry `app=<name>` label.
                // The agent's traffic capture matches on `dst.pod.metadata.labels.app`
                // both server-side (KFL) and client-side (attribution); without this
                // label there is no stable identifier to capture against. Skip rather
                // than register a service we can't actually capture for.
                val appLabel = svc.spec?.selector?.get("app")
                if (appLabel != name) {
                    logger.warn(
                        "Skipping service {}/{} — pod selector lacks 'app={}' label (selector: {})",
                        ns,
                        name,
                        name,
                        svc.spec?.selector,
                    )
                    return@mapNotNull null
                }
                DiscoveredService(ns, name)
            }
    }

    override fun close() = client.close()

    companion object {
        private val SYSTEM_NAMESPACE_NAMES =
            setOf("default", "kubeshark", "validation")
        private val SYSTEM_NAMESPACE_PREFIXES =
            listOf("kube-", "gke-managed-", "gmp-")

        private fun isSystemNamespace(ns: String): Boolean =
            ns in SYSTEM_NAMESPACE_NAMES || SYSTEM_NAMESPACE_PREFIXES.any { ns.startsWith(it) }
    }
}
