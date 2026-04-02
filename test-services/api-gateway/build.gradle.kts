plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.ktor.plugin")
    id("com.google.cloud.tools.jib")
}

group = "com.platform.testservices"
version = "1.0.0"

application {
    mainClass.set("com.platform.testservices.ApiGatewayKt")
}

repositories {
    mavenCentral()
}

val ktor_version = "3.1.3"

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor_version")

    // Ktor client (for proxying to backend services)
    implementation("io.ktor:ktor-client-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-client-cio-jvm:$ktor_version")

    // Cache
    implementation("redis.clients:jedis:5.1.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")
}

ktor {
    fatJar {
        archiveFileName.set("api-gateway.jar")
    }
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
        image = "test-api-gateway"
        tags = setOf("latest")
    }
    container {
        mainClass = "com.platform.testservices.ApiGatewayKt"
        ports = listOf("8080")
        jvmFlags = listOf("-Xms64m", "-Xmx128m")
    }
}