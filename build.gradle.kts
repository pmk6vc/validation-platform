val kotlin_version: String by project
val logback_version: String by project
val ktor_version: String by project
val exposed_version = "0.57.0"

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    id("io.ktor.plugin") version "3.3.3"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
}

group = "com.platform"
version = "0.0.1"

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

ktor {
    fatJar {
        archiveFileName.set("validation-platform.jar")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-config-yaml:$ktor_version")

    // Database (Exposed + PostgreSQL)
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-json:$exposed_version")
    implementation("org.postgresql:postgresql:42.7.7")

    // Database migrations
    implementation("org.flywaydb:flyway-core:9.22.3")

    // Kubernetes
    implementation("io.fabric8:kubernetes-client:6.10.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logback_version")

    // Testing
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktor_version")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:$kotlin_version")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.testcontainers:testcontainers:2.0.3")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.3")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.3")
    testImplementation("org.testcontainers:testcontainers-k3s:2.0.3")
    testImplementation("io.mockk:mockk:1.13.9")
    // BouncyCastle is required for K3s EC keys in fabric8 kubernetes-client
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.79")

    // Ktor client for integration tests
    testImplementation("io.ktor:ktor-client-cio-jvm:$ktor_version")
    testImplementation("io.ktor:ktor-client-content-negotiation-jvm:$ktor_version")
}

tasks.test {
    useJUnitPlatform()

    // Run tests sequentially to avoid conflicts with shared database
    // All tests use the same PostgreSQL instance, so parallel execution
    // could cause race conditions even with @BeforeEach cleanup
    maxParallelForks = 1

    // Testcontainers configuration for Colima on macOS
    // This tells Testcontainers to use Colima's Docker socket on the host,
    // but mount /var/run/docker.sock inside containers (the path inside Colima's VM)
    val colimaSocket = file("${System.getProperty("user.home")}/.colima/docker.sock")
    if (colimaSocket.exists()) {
        environment("DOCKER_HOST", "unix://${colimaSocket.absolutePath}")
        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
    }

    // Build test service Docker images before running integration tests
    dependsOn(":test-services:api-gateway:jibDockerBuild")
    dependsOn(":test-services:order-service:jibDockerBuild")
    dependsOn(":test-services:notification-service:jibDockerBuild")
    dependsOn(":test-services:webhook-stub:jibDockerBuild")
    dependsOn(":test-services:traffic-generator:jibDockerBuild")
}

tasks.register<Exec>("dockerUp") {
    group = "docker"
    description = "Build and start all containers"
    commandLine("sh", "-c", "docker compose -f deploy/docker-compose.yaml --env-file .env up --build -d")
}

tasks.register<Exec>("dockerDown") {
    group = "docker"
    description = "Stop and remove all containers"
    commandLine("sh", "-c", "docker compose -f deploy/docker-compose.yaml --env-file .env down")
}

// Test services deployment to local Kubernetes cluster
tasks.register("testServicesBuild") {
    group = "test-services"
    description = "Build test service Docker images"
    dependsOn(":test-services:api-gateway:jibDockerBuild")
    dependsOn(":test-services:order-service:jibDockerBuild")
    dependsOn(":test-services:notification-service:jibDockerBuild")
    dependsOn(":test-services:webhook-stub:jibDockerBuild")
    dependsOn(":test-services:traffic-generator:jibDockerBuild")
}

tasks.register<Exec>("testServicesUp") {
    group = "test-services"
    description = "Deploy test services to local Kubernetes cluster"
    dependsOn("testServicesBuild")
    commandLine("kubectl", "apply", "-k", "k8s/test-services/base/")
    doLast {
        println(
            """
            |
            |Test services deployed! To check status:
            |  ./gradlew testServicesStatus
            |
            |To access the API Gateway:
            |  kubectl port-forward -n production svc/api-gateway 8080:8080
            |  curl http://localhost:8080/api/health
            |
            |To view logs:
            |  kubectl logs -n production -l app=api-gateway -f
            |  kubectl logs -n production -l app=traffic-generator -f
            """.trimMargin(),
        )
    }
}

tasks.register<Exec>("testServicesDown") {
    group = "test-services"
    description = "Remove test services from local Kubernetes cluster"
    commandLine("kubectl", "delete", "-k", "k8s/test-services/base/", "--ignore-not-found")
}

tasks.register<Exec>("testServicesStatus") {
    group = "test-services"
    description = "Show status of test services in local Kubernetes cluster"
    commandLine(
        "sh",
        "-c",
        """
        echo "=== Pods ===" &&
        kubectl get pods -n infrastructure -n production 2>/dev/null || echo "No pods found" &&
        echo "" &&
        echo "=== Services ===" &&
        kubectl get svc -n infrastructure -n production 2>/dev/null || echo "No services found"
        """.trimIndent(),
    )
}

ktlint {
    version.set("1.5.0")
}
