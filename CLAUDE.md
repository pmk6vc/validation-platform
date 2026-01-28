# CLAUDE.md - Validation & Release Platform

## Project Overview

This is a **validation and release platform** that helps engineering teams validate code changes against real production traffic before deployment. The platform captures live traffic, replays it against candidate versions, and statistically compares behavior to detect regressions—including performance degradation, memory leaks, and behavioral changes.

### Core Value Proposition

> **"Validate every change against real production traffic before it hits production."**

1. **"What services do I have and how do they connect?"** - Automatic topology discovery
2. **"If I change service X, what's affected?"** - Blast radius analysis
3. **"Does this change break anything?"** - Traffic replay with statistical comparison
4. **"Is something leaking or degrading?"** - Resource monitoring during validation
5. **"What does normal look like?"** - Baseline learning
6. **"Is something wrong right now?"** - Anomaly detection

### Key Differentiators

| What We Do | Why It's Different |
|------------|-------------------|
| **Replay real production traffic** | Synthetic load tests miss edge cases; we use actual requests |
| **Statistical comparison (control vs candidate)** | Not threshold-based; compare against current version simultaneously |
| **Resource trend detection** | Detect memory leaks and CPU growth under realistic load |
| **Blast radius at PR time** | CI tools don't know your service topology |
| **Zero instrumentation** | eBPF-based capture (Pixie) requires no code changes |

### What This Catches That Unit Tests Don't

| Issue Type | Unit Tests | Synthetic Load | Our Approach |
|------------|------------|----------------|--------------|
| Logic bugs | ✓ | ✓ | ✓ |
| Memory leaks | ✗ | Sometimes | ✓ |
| N+1 queries with real data | ✗ | ✗ | ✓ |
| Connection pool exhaustion | ✗ | Sometimes | ✓ |
| Cache miss storms | ✗ | ✗ | ✓ |
| Hot key/partition issues | ✗ | ✗ | ✓ |
| Payload-specific edge cases | ✗ | ✗ | ✓ |

---

## Architecture

### High-Level Design

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              Platform                                    │
│                                                                         │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐   │
│  │  Topology   │  │   Blast     │  │   Replay    │  │  Anomaly    │   │
│  │  Service    │  │   Radius    │  │   Engine    │  │  Detection  │   │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘   │
│         │                │                │                │           │
│         └────────────────┴────────────────┴────────────────┘           │
│                                   │                                     │
│                     ┌─────────────▼─────────────┐                      │
│                     │     Unified Data Model    │                      │
│                     │         + Storage         │                      │
│                     └─────────────┬─────────────┘                      │
│                                   │                                     │
│         ┌─────────────────────────┼─────────────────────────┐          │
│         │                         │                         │          │
│    ┌────▼────┐              ┌────▼────┐              ┌────▼────┐      │
│    │  Pixie  │              │   K8s   │              │   AWS   │      │
│    │ Adapter │              │ Adapter │              │ Adapter │      │
│    │         │              │         │              │         │      │
│    │• Traffic│              │• Service│              │• X-Ray  │      │
│    │• Deps   │              │• Metrics│              │• CW     │      │
│    └─────────┘              └─────────┘              └─────────┘      │
└─────────────────────────────────────────────────────────────────────────┘
```

### Validation Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Validation Environment                            │
│                                                                         │
│  1. CAPTURE (continuous)                                                │
│     Pixie captures HTTP traffic with request/response bodies            │
│                           │                                             │
│                           ▼                                             │
│  2. DEPLOY (on validation request)                                      │
│     ┌─────────────────┐          ┌─────────────────┐                   │
│     │    Control      │          │   Candidate     │                   │
│     │  (current ver)  │          │  (PR branch)    │                   │
│     └────────┬────────┘          └────────┬────────┘                   │
│              │                            │                             │
│              └────────────┬───────────────┘                             │
│                           │                                             │
│  3. REPLAY                ▼                                             │
│     Send captured traffic to both versions simultaneously               │
│                           │                                             │
│         ┌─────────────────┼─────────────────┐                          │
│         ▼                 ▼                 ▼                          │
│    ┌─────────┐      ┌──────────┐      ┌──────────┐                    │
│    │Response │      │ Latency  │      │ Resource │                    │
│    │  Diff   │      │Comparison│      │ Monitor  │ ◄── K8s Metrics    │
│    └─────────┘      └──────────┘      └──────────┘     API            │
│                           │                                             │
│                           ▼                                             │
│  4. VERDICT                                                             │
│     Statistical analysis → PASS / FAIL / INCONCLUSIVE                  │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### Design Principles

1. **Adapters normalize data**: Each adapter converts source-specific data into the unified model
2. **Features depend only on the unified model**: Business logic is decoupled from data sources
3. **Multiple adapters can coexist**: Data from different sources is merged/deduplicated
4. **Graceful degradation**: Topology/baselines work even without Pixie; replay requires Pixie
5. **Statistical rigor**: Use proper statistical tests, not arbitrary thresholds

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
    
    // Ktor client (for replay engine and adapters)
    implementation("io.ktor:ktor-client-core-jvm:2.3.7")
    implementation("io.ktor:ktor-client-cio-jvm:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:2.3.7")
    
    // Database
    implementation("org.jetbrains.exposed:exposed-core:0.45.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.45.0")
    implementation("org.xerial:sqlite-jdbc:3.44.1.0")
    
    // Kubernetes (services, metrics, deployments)
    implementation("io.fabric8:kubernetes-client:6.10.0")
    
    // AWS (optional, for AWS adapter)
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
    val discoveredVia: String,        // "kubernetes", "pixie", "manual"
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
```

### Traffic Capture Entities

```kotlin
// CapturedRequest - a captured HTTP request with body (from Pixie)
data class CapturedRequest(
    val id: String,
    val serviceId: String,
    val endpointId: String?,
    val capturedAt: Instant,
    
    // Request
    val method: String,
    val path: String,                  // Full path with query params
    val headers: Map<String, String>,  // Filtered (no auth tokens)
    val requestBody: ByteArray?,
    
    // Response (as observed in production)
    val responseStatus: Int,
    val responseHeaders: Map<String, String>,
    val responseBody: ByteArray?,
    val responseLatencyMs: Long,
    
    // Correlation
    val traceId: String?
)

// ReplayRun - a traffic replay validation experiment
data class ReplayRun(
    val id: String,
    val serviceId: String,
    val createdAt: Instant,
    val status: ReplayStatus,
    
    // What triggered this
    val triggerType: String,           // "cli", "api", "pr"
    val triggerRef: String?,           // PR number, etc.
    
    // Configuration
    val requestCount: Int,             // How many requests to replay
    val trafficSourceStart: Instant,   // Sample requests from this range
    val trafficSourceEnd: Instant,
    val replayDuration: Duration,      // How long to run the test
    
    // Targets
    val controlImage: String,          // Current version image
    val candidateImage: String,        // PR/candidate image
    
    // Results (populated after completion)
    val completedAt: Instant?,
    val verdict: ValidationVerdict?,
    val results: ReplayResults?
)

enum class ReplayStatus {
    PENDING,
    DEPLOYING,
    RUNNING,
    ANALYZING,
    COMPLETED,
    FAILED
}

// ReplayResults - aggregate results of a replay run
data class ReplayResults(
    val totalRequests: Int,
    val successfulReplays: Int,
    
    // Latency comparison
    val controlLatencyP50: Double,
    val controlLatencyP99: Double,
    val candidateLatencyP50: Double,
    val candidateLatencyP99: Double,
    val latencyPValue: Double,         // Statistical significance
    
    // Error comparison
    val controlErrorRate: Double,
    val candidateErrorRate: Double,
    val errorRatePValue: Double,
    
    // Response comparison
    val responseMismatchRate: Double,  // % of responses that differ
    
    // Resource metrics
    val controlMemoryStart: Long,
    val controlMemoryEnd: Long,
    val candidateMemoryStart: Long,
    val candidateMemoryEnd: Long,
    val candidateMemoryGrowthPercent: Double,
    val memoryLeakDetected: Boolean,
    
    val controlCpuAvg: Double,
    val candidateCpuAvg: Double,
    
    // Regressions detected
    val regressions: List<Regression>
)

data class Regression(
    val metric: String,                // "latency_p99", "memory", "error_rate"
    val controlValue: Double,
    val candidateValue: Double,
    val changePercent: Double,
    val pValue: Double,
    val severity: RegressionSeverity
)

enum class RegressionSeverity { LOW, MEDIUM, HIGH, CRITICAL }

data class ValidationVerdict(
    val passed: Boolean,
    val confidence: Double,            // 0.0 to 1.0
    val summary: String,
    val regressionCount: Int,
    val recommendation: String         // "safe to merge", "review recommended", "do not merge"
)
```

### Resource Monitoring Entities

```kotlin
// ResourceSample - point-in-time resource usage (from K8s Metrics API)
data class ResourceSample(
    val id: String,
    val replayRunId: String,
    val pod: String,                   // "control" or "candidate"
    val timestamp: Instant,
    val cpuCores: Double,              // e.g., 0.25 = 250m
    val memoryBytes: Long
)

// ResourceAnalysis - computed from ResourceSamples
data class ResourceAnalysis(
    val startMemoryBytes: Long,
    val endMemoryBytes: Long,
    val peakMemoryBytes: Long,
    val memoryGrowthPercent: Double,
    val memoryGrowthBytesPerMinute: Double,
    val isLeaking: Boolean,            // Heuristic: >20% growth with positive trend
    
    val avgCpuCores: Double,
    val peakCpuCores: Double
)
```

### Metrics & Baseline Entities

```kotlin
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

---

## Project Structure

```
platform/
├── build.gradle.kts
├── settings.gradle.kts
├── README.md
├── CLAUDE.md                          # This file
│
├── src/
│   ├── main/
│   │   ├── kotlin/
│   │   │   └── com/platform/
│   │   │       ├── Application.kt     # Ktor entry point
│   │   │       ├── Cli.kt             # CLI entry point
│   │   │       │
│   │   │       ├── models/            # Data classes
│   │   │       │   ├── Service.kt
│   │   │       │   ├── Dependency.kt
│   │   │       │   ├── Endpoint.kt
│   │   │       │   ├── CapturedRequest.kt
│   │   │       │   ├── ReplayRun.kt
│   │   │       │   ├── ResourceSample.kt
│   │   │       │   ├── MetricSample.kt
│   │   │       │   ├── Baseline.kt
│   │   │       │   ├── Anomaly.kt
│   │   │       │   └── BlastRadius.kt
│   │   │       │
│   │   │       ├── database/          # Persistence layer
│   │   │       │   ├── Tables.kt
│   │   │       │   └── Database.kt
│   │   │       │
│   │   │       ├── adapters/          # Data source integrations
│   │   │       │   ├── Adapter.kt
│   │   │       │   ├── AdapterRunner.kt
│   │   │       │   ├── PixieAdapter.kt       # Traffic capture + topology
│   │   │       │   ├── KubernetesAdapter.kt  # Services + resource metrics
│   │   │       │   ├── AwsAdapter.kt         # Optional
│   │   │       │   └── ManualSeedAdapter.kt  # Testing
│   │   │       │
│   │   │       ├── features/          # Business logic
│   │   │       │   ├── TopologyService.kt
│   │   │       │   ├── BlastRadiusService.kt
│   │   │       │   ├── TrafficCaptureService.kt
│   │   │       │   ├── ReplayEngine.kt
│   │   │       │   ├── ResourceMonitor.kt
│   │   │       │   ├── ValidationService.kt
│   │   │       │   ├── BaselineService.kt
│   │   │       │   └── AnomalyService.kt
│   │   │       │
│   │   │       ├── api/               # HTTP endpoints
│   │   │       │   ├── Routes.kt
│   │   │       │   └── Responses.kt
│   │   │       │
│   │   │       └── stats/             # Statistical utilities
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
│               │   ├── BlastRadiusServiceTest.kt
│               │   ├── ReplayEngineTest.kt
│               │   └── ResourceMonitorTest.kt
│               └── stats/
│                   └── StatisticsTest.kt
│
├── deploy/
│   ├── Dockerfile
│   ├── docker-compose.yml
│   └── k8s/
│       ├── platform.yaml
│       └── validation-namespace.yaml
│
└── test-app/
    ├── order-service/
    │   └── Dockerfile
    ├── inventory-service/
    │   └── Dockerfile
    ├── payment-worker/
    │   └── Dockerfile
    └── k8s-manifests.yaml
```

---

## Feature Specifications

### Feature 1: Traffic Capture (via Pixie)

**Purpose:** Continuously capture HTTP traffic with request/response bodies for later replay.

**How It Works:**
- Pixie runs as a DaemonSet, uses eBPF to observe traffic
- PixieAdapter queries Pixie for recent HTTP events
- Requests are sampled and stored (we don't need every request)
- Sensitive headers (Authorization, etc.) are filtered

**Implementation:**

```kotlin
class PixieAdapter(
    private val clusterId: String,
    private val apiKey: String
) : Adapter {
    
    override val name = "pixie"
    
    suspend fun captureTraffic(
        namespace: String,
        since: Duration = Duration.ofMinutes(5),
        sampleRate: Double = 0.1  // 10% sampling
    ): List<CapturedRequest> {
        val query = """
            import px
            
            df = px.DataFrame(table='http_events', start_time='-${since.toMinutes()}m')
            df = df[df.namespace == '$namespace']
            df = df[['time_', 'source', 'destination', 'req_method', 'req_path', 
                     'req_headers', 'req_body', 'resp_status', 'resp_headers', 
                     'resp_body', 'latency']]
            px.display(df)
        """.trimIndent()
        
        return executeQuery(query)
            .filter { random.nextDouble() < sampleRate }
            .map { row -> row.toCapturedRequest() }
    }
    
    override fun capabilities() = setOf(
        AdapterCapability.SERVICES,
        AdapterCapability.DEPENDENCIES,
        AdapterCapability.ENDPOINTS,
        AdapterCapability.TRAFFIC_BODIES  // Key capability
    )
}
```

---

### Feature 2: Traffic Replay & Validation

**Purpose:** Replay captured traffic against control and candidate versions, compare results.

**User Stories:**
- As a developer, I want to test my changes against real traffic before merging
- As a developer, I want to know if my changes cause latency regressions
- As a developer, I want to detect memory leaks before they hit production

**CLI Commands:**
```bash
# Start a validation run
./platform validate \
  --service order-service \
  --candidate order-service:pr-1234 \
  --duration 10m \
  --traffic-source "1h"

# Check status
./platform validate status {runId}

# View results
./platform validate results {runId}
```

**API Endpoints:**
```
POST /api/validate              # Start validation run
GET  /api/validate/{runId}      # Get run status and results
GET  /api/validate              # List recent runs
```

**Implementation:**

```kotlin
class ReplayEngine(
    private val kubernetesClient: KubernetesClient,
    private val resourceMonitor: ResourceMonitor,
    private val httpClient: HttpClient,
    private val database: Database
) {
    suspend fun runValidation(config: ValidationConfig): ReplayRun {
        val run = createReplayRun(config)
        
        try {
            // 1. Deploy control and candidate
            updateStatus(run, ReplayStatus.DEPLOYING)
            val controlPod = deployVersion(config.serviceId, config.controlImage, "control")
            val candidatePod = deployVersion(config.serviceId, config.candidateImage, "candidate")
            waitForPodsReady(controlPod, candidatePod)
            
            // 2. Get captured traffic
            val requests = database.getCapturedRequests(
                serviceId = config.serviceId,
                since = config.trafficSourceStart,
                until = config.trafficSourceEnd,
                limit = config.requestCount
            )
            
            // 3. Start resource monitoring
            updateStatus(run, ReplayStatus.RUNNING)
            val monitorJob = launch {
                resourceMonitor.monitor(run.id, listOf(controlPod, candidatePod), config.duration)
            }
            
            // 4. Replay traffic
            val replayResults = replayTraffic(requests, controlPod, candidatePod)
            
            // 5. Wait for monitoring to complete
            monitorJob.join()
            
            // 6. Analyze results
            updateStatus(run, ReplayStatus.ANALYZING)
            val analysis = analyzeResults(run.id, replayResults)
            
            // 7. Generate verdict
            val verdict = generateVerdict(analysis)
            
            return completeRun(run, analysis, verdict)
            
        } finally {
            // Cleanup validation environment
            cleanupPods(run.id)
        }
    }
    
    private suspend fun replayTraffic(
        requests: List<CapturedRequest>,
        controlPod: String,
        candidatePod: String
    ): List<ReplayedRequest> {
        return requests.map { captured ->
            // Prepare request (strip sensitive headers, etc.)
            val prepared = prepareRequest(captured)
            
            // Send to both versions in parallel
            val (controlResponse, candidateResponse) = coroutineScope {
                val control = async { sendRequest(controlPod, prepared) }
                val candidate = async { sendRequest(candidatePod, prepared) }
                control.await() to candidate.await()
            }
            
            ReplayedRequest(
                capturedRequestId = captured.id,
                controlStatus = controlResponse.status,
                controlLatencyMs = controlResponse.latencyMs,
                controlBodyHash = hash(controlResponse.body),
                candidateStatus = candidateResponse.status,
                candidateLatencyMs = candidateResponse.latencyMs,
                candidateBodyHash = hash(candidateResponse.body),
                statusMatch = controlResponse.status == candidateResponse.status,
                bodyMatch = controlResponse.body.contentEquals(candidateResponse.body)
            )
        }
    }
}
```

---

### Feature 3: Resource Monitoring

**Purpose:** Track CPU and memory usage during replay to detect leaks and growth.

**Data Source:** Kubernetes Metrics API (requires metrics-server)

**Implementation:**

```kotlin
class ResourceMonitor(
    private val kubernetesClient: KubernetesClient,
    private val database: Database,
    private val pollInterval: Duration = Duration.ofSeconds(5)
) {
    suspend fun monitor(
        replayRunId: String,
        pods: List<String>,
        duration: Duration
    ) {
        val endTime = Instant.now() + duration
        
        while (Instant.now() < endTime) {
            for (podName in pods) {
                try {
                    val metrics = getPodMetrics(podName)
                    database.saveResourceSample(ResourceSample(
                        id = UUID.randomUUID().toString(),
                        replayRunId = replayRunId,
                        pod = podName,
                        timestamp = Instant.now(),
                        cpuCores = metrics.cpuCores,
                        memoryBytes = metrics.memoryBytes
                    ))
                } catch (e: Exception) {
                    logger.warn("Failed to get metrics for $podName: ${e.message}")
                }
            }
            delay(pollInterval)
        }
    }
    
    private fun getPodMetrics(podName: String): PodMetrics {
        val metrics = kubernetesClient.top()
            .pods()
            .inNamespace(VALIDATION_NAMESPACE)
            .withName(podName)
            .metric()
        
        val container = metrics.containers.first()
        return PodMetrics(
            cpuCores = parseCpuQuantity(container.usage["cpu"]),
            memoryBytes = parseMemoryQuantity(container.usage["memory"])
        )
    }
    
    fun analyzeResources(replayRunId: String, pod: String): ResourceAnalysis {
        val samples = database.getResourceSamples(replayRunId, pod)
        
        val memoryValues = samples.map { it.memoryBytes.toDouble() }
        val startMemory = memoryValues.first()
        val endMemory = memoryValues.last()
        val growthPercent = (endMemory - startMemory) / startMemory * 100
        
        // Linear regression to detect trend
        val slope = Statistics.linearRegressionSlope(
            samples.mapIndexed { i, _ -> i.toDouble() },
            memoryValues
        )
        
        // Heuristic: leak if >20% growth with positive slope
        val isLeaking = growthPercent > 20 && slope > 0
        
        return ResourceAnalysis(
            startMemoryBytes = startMemory.toLong(),
            endMemoryBytes = endMemory.toLong(),
            peakMemoryBytes = memoryValues.max().toLong(),
            memoryGrowthPercent = growthPercent,
            memoryGrowthBytesPerMinute = slope * (60.0 / pollInterval.seconds),
            isLeaking = isLeaking,
            avgCpuCores = samples.map { it.cpuCores }.average(),
            peakCpuCores = samples.maxOf { it.cpuCores }
        )
    }
}
```

---

### Feature 4: Topology & Blast Radius

**Purpose:** Discover services and compute impact of changes.

**API Endpoints:**
```
GET /api/services
GET /api/services/{serviceId}
GET /api/services/{serviceId}/dependencies
GET /api/services/{serviceId}/blast-radius?depth=3
GET /api/topology/{environment}
```

**CLI Commands:**
```bash
./platform topology {environment}
./platform blast-radius {serviceId} --depth 3
```

---

### Feature 5: Baselines & Anomaly Detection

**Purpose:** Learn normal behavior, detect anomalies in production.

**API Endpoints:**
```
GET  /api/services/{serviceId}/baselines
POST /api/baselines/compute
GET  /api/anomalies
GET  /api/services/{serviceId}/health
```

**CLI Commands:**
```bash
./platform compute-baselines
./platform check-health
./platform anomalies list
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
    TRAFFIC_BODIES    // Only Pixie provides this
}
```

### Capability Matrix

| Adapter | Services | Dependencies | Endpoints | Metrics | Traffic Bodies |
|---------|----------|--------------|-----------|---------|----------------|
| Pixie | ✓ | ✓ | ✓ | ✓ | ✓ |
| Kubernetes | ✓ | | | ✓ (resources) | |
| AWS (X-Ray) | ✓ | ✓ | | ✓ | |
| Manual Seed | ✓ | ✓ | ✓ | ✓ | ✓ (fake) |

---

## Statistics Module

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
        // Combine and rank
        data class RankedValue(val value: Double, val group: Int, var rank: Double = 0.0)
        
        val combined = sample1.map { RankedValue(it, 1) } + 
                       sample2.map { RankedValue(it, 2) }
        val sorted = combined.sortedBy { it.value }
        
        // Assign ranks (handle ties)
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j < sorted.size && sorted[j].value == sorted[i].value) j++
            val avgRank = (i + 1 + j) / 2.0
            for (k in i until j) sorted[k].rank = avgRank
            i = j
        }
        
        // Calculate U
        val r1 = sorted.filter { it.group == 1 }.sumOf { it.rank }
        val n1 = sample1.size
        val n2 = sample2.size
        val u1 = r1 - (n1 * (n1 + 1)) / 2.0
        val u2 = (n1 * n2).toDouble() - u1
        val u = minOf(u1, u2)
        
        // Normal approximation
        val meanU = (n1 * n2) / 2.0
        val stdU = sqrt((n1 * n2 * (n1 + n2 + 1)) / 12.0)
        val z = (u - meanU) / stdU
        val pValue = 2 * (1 - normalCDF(abs(z)))
        
        return TestResult(u, pValue, pValue < 0.05)
    }
    
    /**
     * Linear regression slope - used for detecting trends (memory leaks).
     */
    fun linearRegressionSlope(x: List<Double>, y: List<Double>): Double {
        require(x.size == y.size)
        val n = x.size
        val sumX = x.sum()
        val sumY = y.sum()
        val sumXY = x.zip(y).sumOf { it.first * it.second }
        val sumX2 = x.sumOf { it * it }
        
        return (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)
    }
    
    private fun normalCDF(z: Double): Double {
        val a1 = 0.254829592
        val a2 = -0.284496736
        val a3 = 1.421413741
        val a4 = -1.453152027
        val a5 = 1.061405429
        val p = 0.3275911
        
        val sign = if (z < 0) -1 else 1
        val x = abs(z) / sqrt(2.0)
        val t = 1.0 / (1.0 + p * x)
        val y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * exp(-x * x)
        
        return 0.5 * (1.0 + sign * y)
    }
    
    data class TestResult(
        val uStatistic: Double,
        val pValue: Double,
        val isSignificant: Boolean
    )
}
```

---

## Delivery Plan

### Phase 1: Foundation (Week 1-2)

**Week 1: Project Setup + Data Model**
- [ ] Initialize Gradle project with dependencies
- [ ] Create package structure
- [ ] Define all data models (including CapturedRequest, ReplayRun, ResourceSample)
- [ ] Create Exposed table definitions
- [ ] Implement Database class with CRUD operations
- [ ] Write database tests

**Week 2: Kubernetes Integration + Manual Seed**
- [ ] Implement KubernetesAdapter (services + resource metrics)
- [ ] Create ManualSeedAdapter with fake traffic data
- [ ] Implement basic CLI structure
- [ ] Create CLI commands: `seed`, `services`
- [ ] Deploy test workloads to kind cluster

**Milestone:** `./platform seed && ./platform services list` works

---

### Phase 2: Pixie Integration (Week 3-4)

**Week 3: Pixie Setup + Traffic Capture**
- [ ] Deploy Pixie to kind cluster
- [ ] Implement PixieAdapter
- [ ] Query services and dependencies from Pixie
- [ ] Query HTTP events with bodies
- [ ] Store captured requests in database

**Week 4: Traffic Storage + Topology**
- [ ] Implement TrafficCaptureService
- [ ] Sampling strategy (don't store everything)
- [ ] Sensitive header filtering
- [ ] Implement TopologyService
- [ ] CLI commands: `discover`, `topology`

**Milestone:** `./platform discover` captures real traffic

---

### Phase 3: Replay Engine (Week 5-6)

**Week 5: Validation Environment + Replay**
- [ ] Create validation namespace in K8s
- [ ] Implement pod deployment for control/candidate
- [ ] Implement ReplayEngine - send traffic to both
- [ ] Record response timing and status

**Week 6: Resource Monitoring**
- [ ] Implement ResourceMonitor (poll K8s Metrics API)
- [ ] Store resource samples during replay
- [ ] Implement resource analysis (leak detection)
- [ ] CLI command: `validate`

**Milestone:** `./platform validate --service X --candidate X:pr-123` runs

---

### Phase 4: Analysis & Verdicts (Week 7-8)

**Week 7: Statistical Analysis**
- [ ] Implement Statistics module (Mann-Whitney U, etc.)
- [ ] Latency comparison (control vs candidate)
- [ ] Error rate comparison
- [ ] Memory trend analysis
- [ ] Generate regressions list

**Week 8: Verdicts + API**
- [ ] Implement ValidationService (orchestrates everything)
- [ ] Generate verdict (pass/fail/inconclusive)
- [ ] Create API endpoints for validation
- [ ] Polish CLI output
- [ ] Documentation

**Milestone:** Full validation with verdict: "FAIL - memory leak detected"

---

### Phase 5: Hardening (Week 9-10)

**Week 9: Blast Radius + Baselines**
- [ ] Implement BlastRadiusService
- [ ] Implement BaselineService
- [ ] Implement AnomalyService
- [ ] CLI commands: `blast-radius`, `check-health`

**Week 10: Stabilization**
- [ ] Error handling and retry logic
- [ ] Configuration management
- [ ] Logging and observability
- [ ] End-to-end tests
- [ ] Documentation

**Milestone:** Production-ready V1

---

## Example Output

```bash
$ ./platform validate \
    --service order-service \
    --candidate order-service:pr-1234 \
    --duration 10m \
    --traffic-source "1h"

Validation: order-service (PR #1234)
════════════════════════════════════════════════════════════════

Status: COMPLETED
Duration: 10m 23s
Requests Replayed: 2,847 (sampled from last hour)

LATENCY
────────────────────────────────────────────────────────────────
                  Control      Candidate     Change
p50               45ms         48ms          +6.7%      ✓
p99               180ms        312ms         +73.3%     ⚠ REGRESSION
Statistical significance: p < 0.001

ERRORS
────────────────────────────────────────────────────────────────
                  Control      Candidate     Change
Error rate        0.3%         0.4%          +33.3%     ✓
Not statistically significant (p = 0.23)

RESOURCES (10 min observation)
────────────────────────────────────────────────────────────────
                  Control      Candidate     Change
Memory start      512 MB       510 MB
Memory end        520 MB       783 MB        +53.5%     ⚠ LEAK DETECTED
Memory growth     +1.6 MB/min  +27.3 MB/min
CPU avg           12%          18%           +50%       ✓

RESPONSE CONSISTENCY
────────────────────────────────────────────────────────────────
Status mismatches: 2 / 2847 (0.07%)        ✓
Body mismatches:   12 / 2847 (0.42%)       ✓

════════════════════════════════════════════════════════════════
VERDICT: FAIL

Regressions detected:
  • p99 latency increased by 73% (180ms → 312ms)
  • Memory leak: +27 MB/min growth rate

Recommendation: DO NOT MERGE - investigate memory leak

[View full report: http://localhost:8080/validate/run-abc123]
```

---

## Environment Setup

### Prerequisites

```bash
# Install kind
brew install kind

# Install kubectl
brew install kubectl

# Install Pixie CLI
bash -c "$(curl -fsSL https://work.withpixie.ai/install.sh)"
```

### Local Development

```bash
# Create cluster
kind create cluster --name platform-dev

# Install metrics-server (for resource monitoring)
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml

# Patch for kind (metrics-server needs this)
kubectl patch deployment metrics-server -n kube-system \
  --type='json' \
  -p='[{"op": "add", "path": "/spec/template/spec/containers/0/args/-", "value": "--kubelet-insecure-tls"}]'

# Install Pixie
px deploy

# Deploy test workloads
kubectl apply -f test-app/k8s-manifests.yaml

# Verify
kubectl get pods -n test-app
px get pods
```

---

## Future Features (V2+)

### PR Integration
- GitHub/GitLab webhook integration
- Automatic validation on PR
- Status checks with results summary

### Deployment Correlation
- Ingest deployment events
- Correlate anomalies with recent deploys
- "This deploy likely caused this anomaly"

### Automatic Rollback
- Integration with Argo/Flux
- Anomaly-triggered rollback

### Multi-Cluster Support
- Federated topology across clusters
- Cross-cluster dependency tracking

---

## Notes for Claude

1. **Pixie is required for V1**: Traffic replay is the core differentiator
2. **Start with manual seed**: Build replay engine with fake data first
3. **Test incrementally**: Each phase should produce working functionality
4. **Resource monitoring uses K8s Metrics API**: Not Pixie
5. **Statistics are simple**: Mean, stddev, percentiles, Mann-Whitney U, linear regression
6. **Type safety**: Leverage Kotlin's type system
7. **Coroutines everywhere**: All I/O operations should be suspend functions

### Implementation Order

For each feature:
1. Data model (models/)
2. Database operations (database/)
3. Business logic (features/)
4. API endpoint (api/)
5. CLI command (Cli.kt)
6. Tests

### Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Traffic capture | Pixie (eBPF) | Zero instrumentation, captures bodies |
| Resource metrics | K8s Metrics API | Simple, always available |
| Statistical tests | Mann-Whitney U | Non-parametric, handles skewed latency distributions |
| Leak detection | Linear regression | Detect growth trend over time |
| Comparison approach | Control vs candidate simultaneously | Eliminates infrastructure noise |
