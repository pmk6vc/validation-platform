package com.platform.kubernetes

import io.fabric8.kubernetes.client.KubernetesClient
import org.junit.jupiter.api.BeforeAll
import org.slf4j.LoggerFactory
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.io.File
import java.nio.file.Paths
import java.time.Duration

/**
 * Base class for Kubernetes integration tests with actual running workloads.
 *
 * ## Overview
 * This class deploys actual pods that communicate with each other, enabling testing
 * with realistic traffic patterns suitable for Pixie integration.
 *
 * ## Deployed Workloads
 *
 * ### Infrastructure Namespace (2 services)
 * - **PostgreSQL**: Database with seeded sample data (users, orders tables)
 * - **Redis**: Cache server for API Gateway
 *
 * ### Production Namespace (1 service + 1 deployment)
 * - **API Gateway**: Custom Ktor service that queries DB and cache (has Service resource)
 * - **Traffic Generator**: Continuous HTTP traffic to API Gateway (NO Service resource)
 *
 * **Note on Traffic Generator**: The traffic generator is intentionally deployed without
 * a Kubernetes Service resource. It's a client application that makes outbound requests
 * to the API Gateway but doesn't serve any inbound traffic. This is a realistic pattern
 * for jobs, workers, and client applications in Kubernetes. It also tests the platform's
 * ability to distinguish between "things running in the cluster" (Deployments) and
 * "things discoverable as network services" (Services).
 *
 * ## Traffic Patterns
 * ```
 * traffic-generator --> api-gateway --> postgresql
 *                                   --> redis
 * ```
 *
 * ## Service Discovery
 * The KubernetesAdapter discovers **3 services** (not 4):
 * - api-gateway (production namespace)
 * - postgresql (infrastructure namespace)
 * - redis (infrastructure namespace)
 *
 * ## Manifests
 * All Kubernetes resources are defined in YAML files under `k8s/test-services/`.
 * These same manifests can be used for local development with kind/minikube:
 * ```
 * kubectl apply -R -f k8s/test-services/
 * ```
 *
 * ## Usage
 * ```kotlin
 * class MyPixieTest : KubernetesWorkloadTestBase() {
 *     @Test
 *     fun `test with real traffic`() {
 *         // Workloads are already running and generating traffic
 *         // Query Pixie for captured HTTP/TCP data
 *     }
 * }
 * ```
 *
 * ## Prerequisites
 * Before running tests, build the test service images:
 * ```
 * ./gradlew :test-services:api-gateway:jibDockerBuild
 * ./gradlew :test-services:traffic-generator:jibDockerBuild
 * ```
 *
 * ## Resource Requirements
 * - Docker with ~2GB available memory
 * - ~60 seconds for initial cluster + workload startup
 */
abstract class KubernetesWorkloadTestBase {
    companion object {
        private val logger = LoggerFactory.getLogger(KubernetesWorkloadTestBase::class.java)

        private var k3s: K3sContainer? = null
        private var initialized = false

        // Image names for test services
        private const val API_GATEWAY_IMAGE = "test-api-gateway:latest"
        private const val TRAFFIC_GENERATOR_IMAGE = "test-traffic-generator:latest"

        // NodePort for API Gateway (must match k8s/test-services/02-production/api-gateway.yaml)
        private const val API_GATEWAY_NODE_PORT = 30080

        // Path to K8s manifests (relative to project root)
        private const val MANIFESTS_PATH = "k8s/test-services"

        @BeforeAll
        @JvmStatic
        fun setupClusterWithWorkloads() {
            if (initialized) return
            initialized = true

            logger.info("Starting k3s cluster with workloads...")

            // Start k3s cluster with API Gateway NodePort exposed
            // Note: K3sContainer requires port 6443 for K8s API, so we add our NodePort alongside it
            k3s =
                K3sContainer(DockerImageName.parse("rancher/k3s:v1.27.9-k3s1"))
                    .withLogConsumer(Slf4jLogConsumer(logger).withPrefix("k3s"))
                    .withExposedPorts(6443, API_GATEWAY_NODE_PORT)
                    .withCommand(
                        "server",
                        "--disable=traefik", // We don't need ingress for tests
                        "--disable=metrics-server", // Reduce resource usage
                    )
            k3s!!.start()

            // Load test service images into k3s (must happen before applying manifests)
            loadDockerImage(API_GATEWAY_IMAGE, "api-gateway")
            loadDockerImage(TRAFFIC_GENERATOR_IMAGE, "traffic-generator")

            // Copy manifests into k3s container and apply them
            applyManifests()

            // Wait for all deployments to be ready
            val client = createKubernetesClient()
            try {
                waitForDeployment(client, "infrastructure", "postgresql", Duration.ofMinutes(2))
                waitForDeployment(client, "infrastructure", "redis", Duration.ofMinutes(1))
                waitForDeployment(client, "production", "api-gateway", Duration.ofMinutes(2))
                waitForDeployment(client, "production", "traffic-generator", Duration.ofMinutes(1))

                logger.info("All workloads deployed and running")

                // Validate infrastructure is healthy before tests run
                validatePostgreSql()
                validateRedis()
            } finally {
                client.close()
            }
        }

        /**
         * Validate PostgreSQL is healthy and has seeded data.
         */
        private fun validatePostgreSql() {
            val result =
                k3s!!.execInContainer(
                    "kubectl",
                    "exec",
                    "-n",
                    "infrastructure",
                    "deploy/postgresql",
                    "--",
                    "psql",
                    "-U",
                    "postgres",
                    "-d",
                    "testdb",
                    "-t",
                    "-c",
                    "SELECT COUNT(*) FROM users;",
                )
            if (result.exitCode != 0) {
                throw RuntimeException("PostgreSQL validation failed: ${result.stderr}")
            }
            val userCount = result.stdout.trim().toIntOrNull() ?: 0
            if (userCount < 5) {
                throw RuntimeException("PostgreSQL seed data missing: expected >= 5 users, found $userCount")
            }
            logger.info("PostgreSQL validated: $userCount users in database")
        }

        /**
         * Validate Redis is healthy and responding.
         */
        private fun validateRedis() {
            val result =
                k3s!!.execInContainer(
                    "kubectl",
                    "exec",
                    "-n",
                    "infrastructure",
                    "deploy/redis",
                    "--",
                    "redis-cli",
                    "PING",
                )
            if (result.exitCode != 0 || result.stdout.trim() != "PONG") {
                throw RuntimeException("Redis validation failed: expected PONG, got ${result.stdout}")
            }
            logger.info("Redis validated: responding to PING")
        }

        /**
         * Creates a Kubernetes client connected to the test k3s cluster.
         * Automatically initializes the cluster if not already running.
         */
        fun createKubernetesClient(): KubernetesClient {
            ensureClusterInitialized()
            val config =
                io.fabric8.kubernetes.client.Config
                    .fromKubeconfig(k3s!!.kubeConfigYaml)
            return io.fabric8.kubernetes.client
                .KubernetesClientBuilder()
                .withConfig(config)
                .build()
        }

        /**
         * Ensures the k3s cluster is initialized with workloads.
         * Can be called directly by tests that don't extend KubernetesWorkloadTestBase.
         */
        fun ensureClusterInitialized() {
            if (initialized) return
            setupClusterWithWorkloads()
        }

        /**
         * Get the k3s container for direct access if needed.
         * Automatically initializes the cluster if not already running.
         */
        fun getK3sContainer(): K3sContainer {
            ensureClusterInitialized()
            return k3s!!
        }

        /**
         * Get the base URL for the API Gateway service.
         * This URL is accessible from the test JVM via the exposed NodePort.
         */
        fun getApiGatewayBaseUrl(): String {
            ensureClusterInitialized()
            val host = k3s!!.host
            val port = k3s!!.getMappedPort(API_GATEWAY_NODE_PORT)
            return "http://$host:$port"
        }

        /**
         * Copy K8s manifests into the k3s container and apply them.
         * Manifests are the single source of truth, used by both tests and local development.
         */
        private fun applyManifests() {
            logger.info("Applying Kubernetes manifests from $MANIFESTS_PATH...")

            // Find the manifests directory (handle running from different working directories)
            val manifestsDir = findManifestsDirectory()
            if (!manifestsDir.exists()) {
                throw RuntimeException(
                    "Manifests directory not found at ${manifestsDir.absolutePath}. " +
                        "Make sure k8s/test-services/ exists in the project root.",
                )
            }

            // Copy manifests into k3s container
            k3s!!.copyFileToContainer(
                MountableFile.forHostPath(manifestsDir.toPath()),
                "/manifests",
            )

            // Apply all manifests recursively
            val result =
                k3s!!.execInContainer(
                    "kubectl",
                    "apply",
                    "-R",
                    "-f",
                    "/manifests",
                )
            if (result.exitCode != 0) {
                logger.error("Failed to apply manifests: ${result.stderr}")
                throw RuntimeException("Failed to apply manifests: ${result.stderr}")
            }
            logger.info("Applied all manifests")
        }

        /**
         * Find the manifests directory, handling different working directories.
         */
        private fun findManifestsDirectory(): File {
            // Try current directory first
            val direct = File(MANIFESTS_PATH)
            if (direct.exists()) return direct

            // Try from project root (when running via Gradle)
            val projectRoot = System.getProperty("user.dir")
            val fromRoot = Paths.get(projectRoot, MANIFESTS_PATH).toFile()
            if (fromRoot.exists()) return fromRoot

            // Return the direct path and let the caller handle the error
            return direct
        }

        /**
         * Load a Docker image into k3s.
         *
         * ## Why This Is Complex
         * k3s runs inside a Testcontainers Docker container, which has its own containerd
         * runtime isolated from the host's Docker daemon. Images built by Jib exist in
         * the host's Docker daemon but not in k3s's containerd. We bridge this gap by:
         * 1. Saving the image from host Docker to a tar file
         * 2. Copying the tar into the k3s container
         * 3. Importing the tar into k3s's containerd via `ctr`
         *
         * ## DOCKER_HOST Handling
         * Testcontainers may set DOCKER_HOST to point to its own socket (e.g., for Colima
         * or remote Docker setups). However, Jib builds images using the system's default
         * Docker context, not Testcontainers' context. We must remove DOCKER_HOST from
         * the environment when running `docker save` to ensure we read from the same
         * Docker daemon where Jib wrote the image.
         *
         * @param imageName The full image name (e.g., "test-api-gateway:latest")
         * @param label A short label for logging and temp file naming
         */
        private fun loadDockerImage(
            imageName: String,
            label: String,
        ) {
            logger.info("Loading $label image into k3s...")

            val tarFile = File.createTempFile(label, ".tar")
            try {
                // Use ProcessBuilder to run docker save, explicitly removing DOCKER_HOST
                // to ensure we access the same Docker daemon where Jib built the image
                val processBuilder =
                    ProcessBuilder("docker", "save", imageName, "-o", tarFile.absolutePath)
                        .redirectErrorStream(true)
                processBuilder.environment().remove("DOCKER_HOST")

                val saveProcess = processBuilder.start()
                val output = saveProcess.inputStream.bufferedReader().readText()
                val saveExitCode = saveProcess.waitFor()
                if (saveExitCode != 0) {
                    logger.error("docker save failed: $output")
                    throw RuntimeException(
                        "Failed to save Docker image $imageName. " +
                            "Make sure to run: ./gradlew :test-services:$label:jibDockerBuild\nError: $output",
                    )
                }

                // Copy the tar file into the k3s container
                k3s!!.copyFileToContainer(MountableFile.forHostPath(tarFile.toPath()), "/tmp/$label.tar")

                // Import the image using ctr
                val importResult =
                    k3s!!.execInContainer(
                        "ctr",
                        "images",
                        "import",
                        "/tmp/$label.tar",
                    )
                if (importResult.exitCode != 0) {
                    logger.error("Failed to import image: ${importResult.stderr}")
                    throw RuntimeException("Failed to import $label image into k3s: ${importResult.stderr}")
                }

                logger.info("Successfully loaded $label image into k3s")
            } finally {
                tarFile.delete()
            }
        }

        /**
         * Wait for a deployment to be ready.
         */
        private fun waitForDeployment(
            client: KubernetesClient,
            namespace: String,
            name: String,
            timeout: Duration,
        ) {
            logger.info("Waiting for deployment $namespace/$name to be ready...")

            val deadline = System.currentTimeMillis() + timeout.toMillis()
            while (System.currentTimeMillis() < deadline) {
                val deployment =
                    client
                        .apps()
                        .deployments()
                        .inNamespace(namespace)
                        .withName(name)
                        .get()

                val status = deployment?.status
                val ready = status?.readyReplicas ?: 0
                val desired = deployment?.spec?.replicas ?: 1

                if (ready >= desired) {
                    logger.info("Deployment $namespace/$name is ready ($ready/$desired replicas)")
                    return
                }

                Thread.sleep(2000)
            }

            // Get pod logs for debugging
            try {
                val pods =
                    client
                        .pods()
                        .inNamespace(namespace)
                        .withLabel("app", name)
                        .list()
                        .items
                for (pod in pods) {
                    logger.error("Pod ${pod.metadata.name} status: ${pod.status.phase}")
                    pod.status.containerStatuses?.forEach { cs ->
                        logger.error("  Container ${cs.name}: ready=${cs.ready}, restartCount=${cs.restartCount}")
                        cs.state?.waiting?.let { w ->
                            logger.error("    Waiting: ${w.reason} - ${w.message}")
                        }
                        cs.state?.terminated?.let { t ->
                            logger.error("    Terminated: ${t.reason} - ${t.message}")
                        }
                    }
                    // Try to get logs
                    try {
                        val logs =
                            client
                                .pods()
                                .inNamespace(namespace)
                                .withName(pod.metadata.name)
                                .getLog()
                        logger.error("  Logs:\n$logs")
                    } catch (e: Exception) {
                        logger.error("  Could not get logs: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                logger.error("Could not get pod details: ${e.message}")
            }

            throw RuntimeException("Timeout waiting for deployment $namespace/$name")
        }
    }
}
