plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.ktor.plugin")
    id("com.google.cloud.tools.jib")
}

group = "com.platform.testservices"
version = "1.0.0"

application {
    mainClass.set("com.platform.testservices.WebhookStubKt")
}

repositories {
    mavenCentral()
}

val ktor_version = "3.1.3"

dependencies {
    // Ktor server (minimal)
    implementation("io.ktor:ktor-server-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor_version")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")
}

ktor {
    fatJar {
        archiveFileName.set("webhook-stub.jar")
    }
}

// Detect current architecture for Jib
val jibArch = when (System.getProperty("os.arch")) {
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
        image = "test-webhook-stub"
        tags = setOf("latest")
    }
    container {
        mainClass = "com.platform.testservices.WebhookStubKt"
        ports = listOf("8080")
        jvmFlags = listOf("-Xms32m", "-Xmx64m")
    }
}