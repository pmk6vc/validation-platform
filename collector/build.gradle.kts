plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.ktor.plugin")
}

group = "com.platform"
version = "0.0.1"

application {
    mainClass.set("com.platform.collector.CollectorApplicationKt")
}

ktor {
    fatJar {
        archiveFileName.set("collector.jar")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":shared"))

    // Ktor server
    implementation(libs.bundles.ktor.server)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)

    // Database (Exposed + PostgreSQL) - needed for collector-owned tables and repositories
    implementation(libs.bundles.exposed)
    implementation(libs.postgresql)

    // Logging
    implementation(libs.logback)
    implementation(libs.logstash.logback.encoder)

    // Testing
    testImplementation(testFixtures(project(":shared")))
    testImplementation(libs.java.jwt)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.bundles.ktor.client)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.test {
    useJUnitPlatform()

    maxParallelForks = 1

    val colimaSocket = file("${System.getProperty("user.home")}/.colima/docker.sock")
    if (colimaSocket.exists()) {
        environment("DOCKER_HOST", "unix://${colimaSocket.absolutePath}")
        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
    }
}
