plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.google.cloud.tools.jib")
    application
}

group = "com.platform.agent"
version = "0.0.1"

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.platform.agent.AgentApplicationKt")
}

dependencies {
    // HTTP client
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Logging
    implementation(libs.logback)

    // Testing
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.mockk)
}

tasks.test {
    useJUnitPlatform()
}

// Detect current architecture for Jib
val jibArch =
    System.getProperty("jib.arch") ?: when (System.getProperty("os.arch")) {
        "aarch64", "arm64" -> "arm64"
        else -> "amd64"
    }

jib {
    from {
        image = "eclipse-temurin:21-jre-alpine"
        platforms {
            platform {
                architecture = jibArch
                os = "linux"
            }
        }
    }
    to {
        image = "validation-agent"
        tags = setOf("latest")
    }
    container {
        mainClass = "com.platform.agent.AgentApplicationKt"
        jvmFlags = listOf("-Xms32m", "-Xmx128m")
    }
}
