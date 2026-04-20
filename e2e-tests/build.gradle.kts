plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "com.platform"
version = "0.0.1"

repositories {
    mavenCentral()
}

dependencies {
    // JWT generation for test tokens
    testImplementation(libs.java.jwt)

    // HTTP client for hitting Envoy
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.serialization.json)
    testImplementation(libs.kotlinx.serialization.json)

    // TestContainers
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.k3s)

    // K8s client (for WorkloadTrafficIntegrationTest)
    testImplementation(libs.fabric8.kubernetes.client)
    testImplementation(libs.bouncycastle)

    // Shared test fixtures (KubernetesWorkloadTestBase)
    testImplementation(testFixtures(project(":shared")))

    // Platform modules (for request/response DTOs — compile-time type safety)
    testImplementation(project(":app"))
    testImplementation(project(":collector"))

    // Testing
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.logback)
}

// Build Docker images before running e2e tests
tasks.register<Exec>("buildAppImage") {
    group = "docker"
    description = "Build the app Docker image for e2e tests"
    workingDir = rootProject.projectDir
    commandLine("docker", "build", "-t", "validation-app:test", "-f", "deploy/Dockerfile.app", ".")
}

tasks.register<Exec>("buildCollectorImage") {
    group = "docker"
    description = "Build the collector Docker image for e2e tests"
    workingDir = rootProject.projectDir
    commandLine("docker", "build", "-t", "validation-collector:test", "-f", "deploy/Dockerfile.collector", ".")
}

tasks.test {
    useJUnitPlatform()
    workingDir = rootProject.projectDir

    dependsOn("buildAppImage", "buildCollectorImage")

    // Build test service images for k3s workload tests
    dependsOn(":test-services:api-gateway:jibDockerBuild")
    dependsOn(":test-services:order-service:jibDockerBuild")
    dependsOn(":test-services:notification-service:jibDockerBuild")
    dependsOn(":test-services:webhook-stub:jibDockerBuild")
    dependsOn(":test-services:traffic-generator:jibDockerBuild")

    // Colima socket for TestContainers on macOS
    val colimaSocket = file("${System.getProperty("user.home")}/.colima/docker.sock")
    if (colimaSocket.exists()) {
        environment("DOCKER_HOST", "unix://${colimaSocket.absolutePath}")
        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
    }
}
