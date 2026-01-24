# CLAUDE.md - Validation & Release Platform

## Project Overview

This is a **validation and release platform** that helps engineering teams understand their service topology, assess the impact of changes, and detect anomalies in production. The platform ingests telemetry from multiple sources (Kubernetes, AWS, Pixie, etc.) and provides actionable insights about service dependencies and system health.

### Core Value Proposition

1. **"What services do I have and how do they connect?"** - Automatic topology discovery
2. **"If I change service X, what's affected?"** - Blast radius analysis
3. **"What does normal look like?"** - Baseline learning
4. **"Is something wrong right now?"** - Anomaly detection

### Key Differentiators

- **Adapter-based architecture**: Ingest data from multiple sources (K8s, AWS, Pixie, OTel) without lock-in
- **Unified data model**: Normalize telemetry from different sources into a consistent schema
- **Zero-instrumentation option**: Use eBPF-based tools (Pixie) for observation without code changes
- **Focus on validation**: Build toward traffic replay and regression detection (V2)

---

## Architecture

### High-Level Design

```
┌─────────────────────────────────────────────────────────────────┐
│                         Platform                                │
│                                                                 │
│   ┌─────────────┐ ┌─────────────┐ ┌─────────────┐              │
│   │  Topology   │ │   Blast     │ │  Baseline   │              │
│   │  Service    │ │   Radius    │ │  Service    │              │
│   └──────┬──────┘ └──────┬──────┘ └──────┬──────┘              │
│          │               │               │                      │
│          └───────────────┴───────────────┘                      │
│                          │                                      │
│                ┌─────────▼─────────┐                           │
│                │  Unified Data     │                           │
│                │  Model + Storage  │                           │
│                └─────────┬─────────┘                           │
│                          │                                      │
│         ┌────────────────┼────────────────┐                    │
│         │                │                │                    │
│    ┌────▼────┐     ┌────▼────┐     ┌────▼────┐               │
│    │   K8s   │     │   AWS   │     │  Pixie  │               │
│    │ Adapter │     │ Adapter │     │ Adapter │               │
│    └─────────┘     └─────────┘     └─────────┘               │
└─────────────────────────────────────────────────────────────────┘
```

### Design Principles

1. **Adapters normalize data**: Each adapter converts source-specific data into the unified model
2. **Features depend only on the unified model**: Business logic is decoupled from data sources
3. **Multiple adapters can coexist**: Data from different sources is merged/deduplicated
4. **Graceful degradation**: If one adapter fails, others continue working

---

## Tech Stack

### Language: Kotlin

**Rationale:**
- Strong typing catches errors early, aids refactoring
- Coroutines provide clean async handling for I/O-heavy workload
- Excellent Kubernetes client (fabric8)
- Data classes reduce boilerplate
- JetBrains support ensures good tooling

### Framework: Ktor

**Rationale:**
- Kotlin-native, built by JetBrains
- Coroutines are first-class
- Lightweight, only include what you need
- Simple mental model, no magic annotations
- Fast startup time

### Database: SQLite → PostgreSQL

**Rationale:**
- SQLite for development (zero config, single file)
- PostgreSQL when scale requires it
- Exposed ORM for type-safe queries

### CLI: Clikt

**Rationale:**
- Kotlin-native CLI framework
- Type hints become CLI arguments
- Clean, readable command definitions

### Key Dependencies

```kotlin
// build.gradle.kts
dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core-jvm:2.3.7")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.7")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:2.3.7")
    
    // Ktor client (for adapters)
    implementation("io.ktor:ktor-client-core-jvm:2.3.7")
    implementation("io.ktor:ktor-client-cio-jvm:2.3.7")
    
    // Database
    implementation("org.jetbrains.exposed:exposed-core:0.45.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.45.0")
    implementation("org.xerial:sqlite-jdbc:3.44.1.0")
    
    // Kubernetes
    implementation("io.fabric8:kubernetes-client:6.10.0")
    
    // AWS (when needed)
    implementation("aws.sdk.kotlin:cloudwatch:1.0.30")
    implementation("aws.sdk.kotlin:xray:1.0.30")
    
    // CLI
    implementation("com.github.ajalt.clikt:clikt:4.2.1")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    
    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")
    
    // Testing
    testImplementation("io.ktor:ktor-server-tests-jvm:2.3.7")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.mockk:mockk:1.13.8")
}
```

---

## Data Model

### Core Entities

```kotlin
// Service - a deployable unit (container, function, etc.)
data class Service(
    val id: String,
    val name: String,
    val environmentId: String,
    val discoveredVia: String,        // "kubernetes", "aws_xray", "pixie", "manual"
    val discoveredAt: Instant,
    val lastSeenAt: Instant,
    val metadata: Map<String, String>? = null
)

// Dependency - a connection between services
data class Dependency(
    val id: String,
    val sourceServiceId: String,
    val targetServiceId: String?,      // null if external
    val targetExternal: String?,       // "api.stripe.com", etc.
    val dependencyType: DependencyType,
    val topicOrQueue: String?,         // for async dependencies
    val observedRequestCount: Int,
    val firstObservedAt: Instant,
    val lastObservedAt: Instant,
    val discoveredVia: List<String>    // can have multiple sources
)

enum class DependencyType {
    SYNC_HTTP,
    SYNC_GRPC,
    ASYNC_KAFKA,
    ASYNC_SQS,
    ASYNC_RABBITMQ,
    DATABASE_POSTGRES,
    DATABASE_MYSQL,
    CACHE_REDIS,
    EXTERNAL_API
}

// Endpoint - a specific API endpoint on a service
data class Endpoint(
    val id: String,
    val serviceId: String,
    val method: String,               // GET, POST, etc.
    val pathPattern: String,          // /api/orders/{id}
    val firstSeenAt: Instant,
    val lastSeenAt: Instant
)

// MetricSample - time-series metrics for an endpoint
data class MetricSample(
    val id: String,
    val endpointId: String,
    val timestamp: Instant,
    val latencyP50Ms: Double,
    val latencyP99Ms: Double,
    val errorRate: Double,            // 0.0 to 1.0
    val requestCount: Int,
    val periodSeconds: Int            // aggregation period
)

// Baseline - learned normal behavior for an endpoint
data class Baseline(
    val id: String,
    val endpointId: String,
    val dayOfWeek: Int?,              // 0=Monday, null=all days
    val hourStart: Int?,              // 0-23, null=all hours
    val hourEnd: Int?,
    val latencyP50Mean: Double,
    val latencyP50StdDev: Double,
    val latencyP99Mean: Double,
    val latencyP99StdDev: Double,
    val errorRateMean: Double,
    val errorRateStdDev: Double,
    val throughputMean: Double,
    val throughputStdDev: Double,
    val sampleCount: Int,
    val computedAt: Instant,
    val windowDays: Int
)

// Anomaly - detected deviation from baseline
data class Anomaly(
    val id: String,
    val endpointId: String,
    val startedAt: Instant,
    val endedAt: Instant?,            // null if ongoing
    val metric: String,               // "latency_p99", "error_rate", etc.
    val baselineValue: Double,
    val anomalousValue: Double,
    val deviationSigma: Double,
    val severity: AnomalySeverity
)

enum class AnomalySeverity { LOW, MEDIUM, HIGH, CRITICAL }

// BlastRadius - computed impact of a change
data class BlastRadius(
    val changedService: String,
    val upstream: List<Service>,
    val downstreamSync: List<Service>,
    val downstreamAsync: List<Service>,
    val topicsAffected: List<String>,
    val riskLevel: RiskLevel
)

enum class RiskLevel { LOW, MEDIUM, HIGH }
```

### Database Schema (Exposed)

```kotlin
object Services : Table("services") {
    val id = varchar("id", 255)
    val name = varchar("name", 255)
    val environmentId = varchar("environment_id", 255)
    val discoveredVia = varchar("discovered_via", 50)
    val discoveredAt = varchar("discovered_at", 50)
    val lastSeenAt = varchar("last_seen_at", 50)
    val metadata = text("metadata").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Dependencies : Table("dependencies") {
    val id = varchar("id", 255)
    val sourceServiceId = varchar("source_service_id", 255)
    val targetServiceId = varchar("target_service_id", 255).nullable()
    val targetExternal = varchar("target_external", 255).nullable()
    val dependencyType = varchar("dependency_type", 50)
    val topicOrQueue = varchar("topic_or_queue", 255).nullable()
    val observedRequestCount = integer("observed_request_count").default(0)
    val firstObservedAt = varchar("first_observed_at", 50)
    val lastObservedAt = varchar("last_observed_at", 50)
    val discoveredVia = text("discovered_via")
    override val primaryKey = PrimaryKey(id)
}

object Endpoints : Table("endpoints") {
    val id = varchar("id", 255)
    val serviceId = varchar("service_id", 255)
    val method = varchar("method", 10)
    val pathPattern = varchar("path_pattern", 500)
    val firstSeenAt = varchar("first_seen_at", 50)
    val lastSeenAt = varchar("last_seen_at", 50)
    override val primaryKey = PrimaryKey(id)
}

object MetricSamples : Table("metric_samples") {
    val id = varchar("id", 255)
    val endpointId = varchar("endpoint_id", 255)
    val timestamp = varchar("timestamp", 50)
    val latencyP50Ms = double("latency_p50_ms")
    val latencyP99Ms = double("latency_p99_ms")
    val errorRate = double("error_rate")
    val requestCount = integer("request_count")
    val periodSeconds = integer("period_seconds")
    override val primaryKey = PrimaryKey(id)
}

object Baselines : Table("baselines") {
    val id = varchar("id", 255)
    val endpointId = varchar("endpoint_id", 255)
    val dayOfWeek = integer("day_of_week").nullable()
    val hourStart = integer("hour_start").nullable()
    val hourEnd = integer("hour_end").nullable()
    val latencyP50Mean = double("latency_p50_mean")
    val latencyP50StdDev = double("latency_p50_stddev")
    val latencyP99Mean = double("latency_p99_mean")
    val latencyP99StdDev = double("latency_p99_stddev")
    val errorRateMean = double("error_rate_mean")
    val errorRateStdDev = double("error_rate_stddev")
    val throughputMean = double("throughput_mean")
    val throughputStdDev = double("throughput_stddev")
    val sampleCount = integer("sample_count")
    val computedAt = varchar("computed_at", 50)
    val windowDays = integer("window_days")
    override val primaryKey = PrimaryKey(id)
}

object Anomalies : Table("anomalies") {
    val id = varchar("id", 255)
    val endpointId = varchar("endpoint_id", 255)
    val startedAt = varchar("started_at", 50)
    val endedAt = varchar("ended_at", 50).nullable()
    val metric = varchar("metric", 50)
    val baselineValue = double("baseline_value")
    val anomalousValue = double("anomalous_value")
    val deviationSigma = double("deviation_sigma")
    val severity = varchar("severity", 20)
    override val primaryKey = PrimaryKey(id)
}
```

---

## Project Structure

```
platform/
├── build.gradle.kts
├── settings.gradle.kts
├── README.md
├── CLAUDE.md                        # This file
│
├── src/
│   ├── main/
│   │   ├── kotlin/
│   │   │   └── com/platform/
│   │   │       ├── Application.kt   # Ktor entry point
│   │   │       ├── Cli.kt           # CLI entry point
│   │   │       │
│   │   │       ├── models/          # Data classes
│   │   │       │   ├── Service.kt
│   │   │       │   ├── Dependency.kt
│   │   │       │   ├── Endpoint.kt
│   │   │       │   ├── MetricSample.kt
│   │   │       │   ├── Baseline.kt
│   │   │       │   ├── Anomaly.kt
│   │   │       │   └── BlastRadius.kt
│   │   │       │
│   │   │       ├── database/        # Persistence layer
│   │   │       │   ├── Tables.kt    # Exposed table definitions
│   │   │       │   └── Database.kt  # Repository/DAO
│   │   │       │
│   │   │       ├── adapters/        # Data source integrations
│   │   │       │   ├── Adapter.kt   # Interface
│   │   │       │   ├── AdapterRunner.kt
│   │   │       │   ├── KubernetesAdapter.kt
│   │   │       │   ├── AwsAdapter.kt
│   │   │       │   ├── PixieAdapter.kt
│   │   │       │   └── ManualSeedAdapter.kt
│   │   │       │
│   │   │       ├── features/        # Business logic
│   │   │       │   ├── TopologyService.kt
│   │   │       │   ├── BlastRadiusService.kt
│   │   │       │   ├── BaselineService.kt
│   │   │       │   └── AnomalyService.kt
│   │   │       │
│   │   │       ├── api/             # HTTP endpoints
│   │   │       │   ├── Routes.kt
│   │   │       │   └── Responses.kt
│   │   │       │
│   │   │       └── stats/           # Statistical utilities
│   │   │           └── Statistics.kt
│   │   │
│   │   └── resources/
│   │       ├── application.conf
│   │       └── logback.xml
│   │
│   └── test/
│       └── kotlin/
│           └── com/platform/
│               ├── database/
│               │   └── DatabaseTest.kt
│               ├── features/
│               │   ├── TopologyServiceTest.kt
│               │   ├── BlastRadiusServiceTest.kt
│               │   └── BaselineServiceTest.kt
│               └── stats/
│                   └── StatisticsTest.kt
│
├── deploy/
│   ├── Dockerfile
│   └── docker-compose.yml
│
└── test-app/                        # Sample microservices for testing
    ├── order-service/
    ├── inventory-service/
    ├── payment-worker/
    └── k8s-manifests.yaml
```

---

## Feature Specifications

### Feature 1: Topology Mapping

**Purpose:** Discover services and their dependencies automatically.

**User Stories:**
- As a developer, I want to see all services in my environment
- As a developer, I want to see what service A calls
- As a developer, I want to see what calls service A

**API Endpoints:**
```
GET /api/services
GET /api/services?environment={env}
GET /api/services/{serviceId}
GET /api/services/{serviceId}/dependencies
GET /api/topology/{environment}
```

**CLI Commands:**
```bash
./platform discover --namespace default
./platform topology {environment}
./platform services list
```

**Implementation Notes:**
- Adapters populate Services and Dependencies tables
- TopologyService provides query methods
- Support transitive dependency traversal with depth limit

---

### Feature 2: Blast Radius Analysis

**Purpose:** Compute the impact of changing a service.

**User Stories:**
- As a developer, I want to know what services are affected if I change service X
- As a developer, I want to assess risk before deploying

**API Endpoints:**
```
GET /api/services/{serviceId}/blast-radius?depth=3
```

**CLI Commands:**
```bash
./platform blast-radius {serviceId} --depth 3
```

**Algorithm:**
```kotlin
fun computeBlastRadius(serviceId: String, depth: Int = 3): BlastRadius {
    val visited = mutableSetOf<String>()
    
    // Upstream: who calls this service?
    val upstream = traverseUpstream(serviceId, visited, depth)
    
    // Downstream sync: what does this service call synchronously?
    val downstreamSync = traverseDownstreamSync(serviceId, visited, depth)
    
    // Downstream async: what consumes topics this service publishes to?
    val producedTopics = getProducedTopics(serviceId)
    val downstreamAsync = producedTopics.flatMap { getTopicConsumers(it) }
    
    val riskLevel = assessRisk(upstream, downstreamSync, downstreamAsync)
    
    return BlastRadius(
        changedService = serviceId,
        upstream = upstream,
        downstreamSync = downstreamSync,
        downstreamAsync = downstreamAsync,
        topicsAffected = producedTopics,
        riskLevel = riskLevel
    )
}
```

---

### Feature 3: Baseline Learning

**Purpose:** Learn what "normal" looks like for each endpoint.

**User Stories:**
- As a developer, I want the system to learn normal latency/error patterns
- As a developer, I want time-aware baselines (business hours vs nights)

**API Endpoints:**
```
GET /api/services/{serviceId}/baselines
POST /api/baselines/compute  # Trigger recomputation
```

**CLI Commands:**
```bash
./platform compute-baselines
./platform baselines show {serviceId}
```

**Algorithm:**
```kotlin
fun computeBaseline(endpointId: String, windowDays: Int = 14): List<Baseline> {
    val samples = getMetricSamples(endpointId, since = now() - windowDays.days)
    
    // Group by time windows
    val businessHours = samples.filter { isBusinessHours(it) && isWeekday(it) }
    val offHours = samples.filter { !isBusinessHours(it) && isWeekday(it) }
    val weekends = samples.filter { isWeekend(it) }
    
    return listOfNotNull(
        computeBaselineForGroup(businessHours, "weekday_business"),
        computeBaselineForGroup(offHours, "weekday_other"),
        computeBaselineForGroup(weekends, "weekend")
    )
}

fun computeBaselineForGroup(samples: List<MetricSample>, label: String): Baseline? {
    if (samples.size < 10) return null  // Minimum sample size
    
    return Baseline(
        latencyP50Mean = samples.map { it.latencyP50Ms }.average(),
        latencyP50StdDev = samples.map { it.latencyP50Ms }.stdDev(),
        latencyP99Mean = samples.map { it.latencyP99Ms }.average(),
        latencyP99StdDev = samples.map { it.latencyP99Ms }.stdDev(),
        errorRateMean = samples.map { it.errorRate }.average(),
        errorRateStdDev = samples.map { it.errorRate }.stdDev(),
        // ... etc
    )
}
```

---

### Feature 4: Anomaly Detection

**Purpose:** Detect when current metrics deviate from baseline.

**User Stories:**
- As a developer, I want to be alerted when latency is abnormally high
- As a developer, I want to see all current anomalies

**API Endpoints:**
```
GET /api/anomalies
GET /api/anomalies?environment={env}
GET /api/services/{serviceId}/health
```

**CLI Commands:**
```bash
./platform check-health
./platform anomalies list
```

**Algorithm:**
```kotlin
fun detectAnomaly(endpointId: String): List<Anomaly> {
    val current = getCurrentMetrics(endpointId, windowMinutes = 5)
    val baseline = getBaselineForCurrentTime(endpointId)
    
    if (baseline == null) return emptyList()
    
    val anomalies = mutableListOf<Anomaly>()
    
    // Check each metric
    for (metric in listOf("latency_p50", "latency_p99", "error_rate", "throughput")) {
        val currentVal = current.getValue(metric)
        val baselineMean = baseline.getMean(metric)
        val baselineStdDev = baseline.getStdDev(metric)
        
        val zScore = (currentVal - baselineMean) / baselineStdDev
        
        if (abs(zScore) > 3.0) {  // 3 sigma threshold
            anomalies.add(Anomaly(
                metric = metric,
                baselineValue = baselineMean,
                anomalousValue = currentVal,
                deviationSigma = zScore,
                severity = when {
                    abs(zScore) > 4.0 -> CRITICAL
                    abs(zScore) > 3.5 -> HIGH
                    else -> MEDIUM
                }
            ))
        }
    }
    
    return anomalies
}
```

---

## Adapter Interface

```kotlin
interface Adapter {
    val name: String
    
    suspend fun discoverServices(): List<Service>
    suspend fun discoverDependencies(): List<Dependency>
    suspend fun discoverEndpoints(): List<Endpoint>
    suspend fun collectMetrics(): List<MetricSample>
    
    fun capabilities(): Set<AdapterCapability>
}

enum class AdapterCapability {
    SERVICES,
    DEPENDENCIES,
    ENDPOINTS,
    METRICS,
    REQUEST_BODIES  // Only Pixie/mesh provide this
}
```

### Kubernetes Adapter

```kotlin
class KubernetesAdapter(
    private val context: String? = null
) : Adapter {
    
    override val name = "kubernetes"
    
    private val client by lazy {
        if (context != null) {
            Config.fromKubeconfig(context).let { KubernetesClientBuilder().withConfig(it).build() }
        } else {
            KubernetesClientBuilder().build()
        }
    }
    
    override suspend fun discoverServices(): List<Service> {
        return client.services()
            .inAnyNamespace()
            .list()
            .items
            .filter { it.metadata.name != "kubernetes" }
            .map { svc ->
                Service(
                    id = "${svc.metadata.namespace}/${svc.metadata.name}",
                    name = svc.metadata.name,
                    environmentId = svc.metadata.namespace,
                    discoveredVia = name,
                    discoveredAt = Instant.now(),
                    lastSeenAt = Instant.now(),
                    metadata = mapOf(
                        "clusterIp" to svc.spec.clusterIP,
                        "ports" to svc.spec.ports.joinToString(",") { "${it.port}/${it.protocol}" }
                    )
                )
            }
    }
    
    override suspend fun discoverDependencies(): List<Dependency> {
        // K8s API doesn't know about runtime dependencies
        // Return empty - dependencies come from Pixie/AWS/OTel
        return emptyList()
    }
    
    override fun capabilities() = setOf(AdapterCapability.SERVICES)
}
```

### Manual Seed Adapter (for testing)

```kotlin
class ManualSeedAdapter : Adapter {
    
    override val name = "manual_seed"
    
    override suspend fun discoverServices(): List<Service> {
        return listOf(
            Service("order-service", "order-service", "test-app", name, Instant.now(), Instant.now()),
            Service("inventory-service", "inventory-service", "test-app", name, Instant.now(), Instant.now()),
            Service("payment-worker", "payment-worker", "test-app", name, Instant.now(), Instant.now())
        )
    }
    
    override suspend fun discoverDependencies(): List<Dependency> {
        return listOf(
            Dependency(
                id = "order-to-inventory",
                sourceServiceId = "order-service",
                targetServiceId = "inventory-service",
                targetExternal = null,
                dependencyType = DependencyType.SYNC_HTTP,
                topicOrQueue = null,
                observedRequestCount = 1000,
                firstObservedAt = Instant.now().minus(7, ChronoUnit.DAYS),
                lastObservedAt = Instant.now(),
                discoveredVia = listOf(name)
            ),
            Dependency(
                id = "order-to-kafka",
                sourceServiceId = "order-service",
                targetServiceId = null,
                targetExternal = "kafka:9092",
                dependencyType = DependencyType.ASYNC_KAFKA,
                topicOrQueue = "order-created",
                observedRequestCount = 1000,
                firstObservedAt = Instant.now().minus(7, ChronoUnit.DAYS),
                lastObservedAt = Instant.now(),
                discoveredVia = listOf(name)
            ),
            Dependency(
                id = "payment-from-kafka",
                sourceServiceId = "payment-worker",
                targetServiceId = null,
                targetExternal = "kafka:9092",
                dependencyType = DependencyType.ASYNC_KAFKA,
                topicOrQueue = "order-created",
                observedRequestCount = 1000,
                firstObservedAt = Instant.now().minus(7, ChronoUnit.DAYS),
                lastObservedAt = Instant.now(),
                discoveredVia = listOf(name)
            )
        )
    }
    
    override fun capabilities() = setOf(
        AdapterCapability.SERVICES,
        AdapterCapability.DEPENDENCIES,
        AdapterCapability.ENDPOINTS,
        AdapterCapability.METRICS
    )
}
```

---

## Statistics Module

Implement these statistical functions (no external library needed):

```kotlin
object Statistics {
    
    fun mean(values: List<Double>): Double = values.average()
    
    fun stdDev(values: List<Double>): Double {
        val mean = mean(values)
        val variance = values.map { (it - mean).pow(2) }.average()
        return sqrt(variance)
    }
    
    fun percentile(values: List<Double>, p: Double): Double {
        require(p in 0.0..100.0)
        val sorted = values.sorted()
        val index = (p / 100.0 * (sorted.size - 1)).toInt()
        return sorted[index]
    }
    
    fun zScore(value: Double, mean: Double, stdDev: Double): Double {
        return if (stdDev > 0) (value - mean) / stdDev else 0.0
    }
    
    /**
     * Mann-Whitney U test for comparing two distributions.
     * Used for replay comparison (control vs candidate).
     */
    fun mannWhitneyU(sample1: List<Double>, sample2: List<Double>): TestResult {
        // Implementation details in codebase
        // Returns p-value and significance assessment
    }
    
    data class TestResult(
        val uStatistic: Double,
        val pValue: Double,
        val isSignificant: Boolean  // p < 0.05
    )
}
```

---

## Delivery Plan

### Phase 1: Foundation (Week 1-2)

**Week 1: Project Setup**
- [ ] Initialize Gradle project with dependencies
- [ ] Create package structure
- [ ] Define data models (Service, Dependency, Endpoint, MetricSample)
- [ ] Create Exposed table definitions
- [ ] Implement Database class with CRUD operations
- [ ] Write database tests

**Week 2: Kubernetes Adapter**
- [ ] Implement KubernetesAdapter
- [ ] Create ManualSeedAdapter for testing
- [ ] Implement AdapterRunner for periodic polling
- [ ] Create CLI commands: `seed`, `discover`, `topology`
- [ ] Deploy test workloads to kind cluster

**Milestone:** `./platform seed && ./platform topology test-app` works

---

### Phase 2: Topology & Blast Radius (Week 3-4)

**Week 3: Topology Service + API**
- [ ] Implement TopologyService with query methods
- [ ] Set up Ktor application
- [ ] Create API routes for services and topology
- [ ] Add error handling and validation
- [ ] Write API tests

**Week 4: Blast Radius**
- [ ] Implement BlastRadiusService
- [ ] Add graph traversal (upstream, downstream, async)
- [ ] Implement risk assessment heuristic
- [ ] Add API endpoint and CLI command
- [ ] Test with complex topology

**Milestone:** `./platform blast-radius order-service` returns accurate results

---

### Phase 3: Metrics & Baselines (Week 5-6)

**Week 5: Metrics Collection**
- [ ] Implement MetricSample storage
- [ ] Create fake metrics generator for testing
- [ ] Add metrics API endpoints
- [ ] Implement metric retention/cleanup

**Week 6: Baselines & Anomaly Detection**
- [ ] Implement BaselineService
- [ ] Add time-aware baseline computation
- [ ] Implement AnomalyService
- [ ] Add health check API and CLI
- [ ] Test anomaly detection with injected anomalies

**Milestone:** `./platform check-health` detects anomalies

---

### Phase 4: Real Data Sources (Week 7-8)

**Week 7: AWS or Pixie Adapter**
- [ ] Implement AwsAdapter (X-Ray, CloudWatch) OR PixieAdapter
- [ ] Test with real infrastructure
- [ ] Handle adapter-specific edge cases

**Week 8: Stabilization**
- [ ] Error handling and retry logic
- [ ] Configuration management
- [ ] Logging and observability
- [ ] Documentation

**Milestone:** Platform running against real infrastructure

---

## Future Features (V2+)

### Deployment Correlation
- Ingest deployment events (CloudTrail, Argo, GitHub)
- Correlate anomalies with recent deployments
- "This deployment likely caused this anomaly"

### Traffic Replay
- Requires body capture (Pixie adapter)
- Store CapturedRequest with request/response bodies
- Replay against control vs candidate versions
- Statistical comparison of results

### PR Integration
- GitHub/GitLab webhook integration
- Automatic blast radius on PR
- Validation status checks

### Automatic Rollback
- Integration with deployment tools (Argo, Flux)
- Anomaly-triggered rollback decisions

---

## Testing Strategy

### Unit Tests
- Statistics functions
- Blast radius computation
- Baseline learning logic

### Integration Tests
- Database operations
- Adapter → Database pipeline
- API endpoints

### End-to-End Tests
- Full workflow with kind cluster
- Deploy test app → discover → analyze

### Test Commands
```bash
./gradlew test                    # All tests
./gradlew test --tests "*Unit*"   # Unit tests only
./gradlew test --tests "*Api*"    # API tests only
```

---

## Development Commands

```bash
# Build
./gradlew build

# Run API server
./gradlew run

# Run CLI
./gradlew installDist
./build/install/platform/bin/platform --help

# Or during development
./gradlew run --args="seed"
./gradlew run --args="topology test-app"

# Test
./gradlew test

# Docker
docker build -t platform .
docker run -p 8080:8080 platform
```

---

## Environment Setup

### Local Development

```bash
# Install kind (Kubernetes in Docker)
brew install kind

# Create cluster
kind create cluster --name platform-dev

# Verify
kubectl cluster-info
```

### Test Workloads

Deploy sample services for testing:

```bash
kubectl apply -f test-app/k8s-manifests.yaml
kubectl get pods -n test-app
```

---

## Notes for Claude

1. **Start small**: Begin with models and database before adapters
2. **Test first**: Write tests as you implement features
3. **Manual seed first**: Use ManualSeedAdapter before real adapters
4. **Iterate**: Get basic version working, then enhance
5. **Keep it simple**: Avoid over-engineering; this is a solo project
6. **Type safety**: Leverage Kotlin's type system to catch errors early
7. **Coroutines**: Use suspend functions for I/O operations

When implementing features, follow this order:
1. Data model (models/)
2. Database operations (database/)
3. Business logic (features/)
4. API endpoint (api/)
5. CLI command (Cli.kt)
6. Tests