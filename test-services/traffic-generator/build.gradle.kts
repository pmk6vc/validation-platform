plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.google.cloud.tools.jib")
}

group = "com.platform.testservices"
version = "1.0.0"

repositories {
    mavenCentral()
}

val ktor_version = "3.1.3"

dependencies {
    // Ktor client
    implementation("io.ktor:ktor-client-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-client-cio-jvm:$ktor_version")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor_version")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")
}

// Detect current architecture for Jib
val jibArch = System.getProperty("jib.arch") ?: when (System.getProperty("os.arch")) {
    "aarch64", "arm64" -> "arm64"
    else -> "amd64"
}

// Jib configuration to build Docker image
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
        image = "test-traffic-generator"
        tags = setOf("latest")
    }
    container {
        mainClass = "com.platform.testservices.TrafficGeneratorKt"
        jvmFlags = listOf("-Xms32m", "-Xmx64m")
    }
}