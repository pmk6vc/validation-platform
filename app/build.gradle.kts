plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.ktor.plugin")
    jacoco
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
    implementation(project(":shared"))

    // Ktor server
    implementation(libs.bundles.ktor.server)

    // Database (Exposed + PostgreSQL) - needed for app-owned tables and repositories
    implementation(libs.bundles.exposed)
    implementation(libs.postgresql)

    // Kubernetes
    implementation(libs.fabric8.kubernetes.client)

    // Logging
    implementation(libs.logback)

    // Testing
    testImplementation(testFixtures(project(":shared")))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.mockk)
    // BouncyCastle is required for K3s EC keys in fabric8 kubernetes-client
    testImplementation(libs.bouncycastle)

    // Ktor client for integration tests
    testImplementation(libs.bundles.ktor.client)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
    useJUnitPlatform()

    // Run tests from root project directory so paths to k8s/ manifests resolve correctly
    workingDir = rootProject.projectDir

    // Run tests sequentially to avoid conflicts with shared database
    maxParallelForks = 1

    // Testcontainers configuration for Colima on macOS
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
