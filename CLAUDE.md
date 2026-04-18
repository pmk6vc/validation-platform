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
| **Zero instrumentation for capture** | eBPF-based capture (Kubeshark) requires no code changes in production |

### What This Catches That Unit Tests Don't

| Issue Type | Unit Tests | Synthetic Load | Our Approach |
|------------|------------|----------------|--------------|
| Logic bugs | Yes | Yes | Yes |
| Memory leaks | No | Sometimes | Yes |
| N+1 queries with real data | No | No | Yes |
| Connection pool exhaustion | No | Sometimes | Yes |
| Cache miss storms | No | No | Yes |
| Hot key/partition issues | No | No | Yes |
| Payload-specific edge cases | No | No | Yes |

---

## Current Implementation Status

### What's Working Now

- **Two Ktor servers**: `app` on port 8080 (organizations, services) and `collector` on port 8081 (captured inputs)
- **PostgreSQL database** with Flyway migrations (V0001–V0005), all migrations in `shared/`
- **Multi-tenant data model** with Organizations and Services (owned by `app`)
- **CapturedInput model** (owned by `collector`) — HTTP-first, non-nullable method/url/responseStatus
- **Collector batch ingest** — `POST /api/captured-inputs` accepts `BatchCreateCapturedInputRequest` from the agent
- **Pagination and filtering** on all list endpoints (cursor-based); limit clamping tested (0, -1 → 1; >100 → 100)
- **Docker deployment** — app, collector, and db all start by default; health checks on both services
- **Test infrastructure** with TestContainers (PostgreSQL + k3s Kubernetes)
- **Code quality** with ktlint
- **Adapter pattern** with ServiceAdapter interface
- **Service discovery** via ManualSeedAdapter and KubernetesAdapter (implements `Closeable`)
- **Provider tracking** (UNKNOWN, MANUAL_SEED, KUBERNETES)
- **Modular monolith** with enforced module boundaries: cross-module data access goes through REST APIs, not shared repositories
- **Validation agent** — three-loop Kotlin process deployed to customer cluster; streams traffic from Kubeshark WebSocket with server-side KFL filtering, samples, and pushes to collector; file-based liveness probe; non-root container

### Module Ownership

Each module owns its tables, models, and repositories. Cross-module communication is via HTTP API calls.

| Module | Owns | Port |
|--------|------|------|
| `shared/` | DatabaseFactory, Flyway migrations, shared models (Page, InstantSerializer) | — |
| `app/` | Organizations, Services tables; OrganizationRepository, ServiceRepository; Ktor server | 8080 |
| `collector/` | CapturedInputs table; CapturedInputRepository; Ktor server | 8081 |
| `agent/` | Kubeshark polling, K8s service discovery, traffic capture and forwarding to collector | — (standalone process) |
| `test-services/` | Standalone Kotlin microservices for k3s integration testing | — |

### App Module API Endpoints (port 8080)

```
GET    /health                             # Health check
GET    /api/organizations                  # List organizations (paginated)
POST   /api/organizations                  # Create organization
GET    /api/organizations/{id}             # Get organization by ID
GET    /api/services                       # List services (paginated, filterable by organizationId/cluster/namespace)
POST   /api/services                       # Create service
GET    /api/services/{id}                  # Get service by ID
```

### Collector Module API Endpoints (port 8081)

```
GET    /health                                    # Health check
POST   /api/captured-inputs                       # Ingest a batch of captured inputs (agent pushes here)
GET    /api/captured-inputs                       # List captured inputs (paginated, filterable by serviceId/inputType)
GET    /api/captured-inputs/{id}                  # Get captured input by ID
DELETE /api/captured-inputs?serviceId={id}        # Delete all captured inputs for a service
```

### Current Data Models

```kotlin
// --- app module ---

// Organization - a tenant/team in the platform
// app/src/main/kotlin/com/platform/models/Organization.kt
data class Organization(
    val id: String,
    val name: String,
    val createdAt: Instant,
    // Note: no updatedAt — organizations are immutable after creation
)

// Service - a deployable unit discovered from various providers
// Uniquely identified by: organizationId + cluster + namespace + name
// app/src/main/kotlin/com/platform/models/Service.kt
data class Service(
    val id: String,
    val organizationId: String,
    val cluster: String,
    val namespace: String,
    val name: String,
    val provider: Provider = Provider.UNKNOWN,
    val discoveredAt: Instant,
    val lastSeenAt: Instant,
    val metadata: Map<String, String>? = null
)

// Provider - tracks where a service was discovered
// app/src/main/kotlin/com/platform/models/Provider.kt
enum class Provider {
    UNKNOWN,        // Provider unknown or not specified
    MANUAL_SEED,    // Manually seeded test data
    KUBERNETES,     // Discovered via Kubernetes API
    // KUBESHARK - Reserved for future Kubeshark adapter
}

// --- collector module ---

// CapturedInput - an HTTP req/res pair captured from production traffic
// HTTP-only for now; method, url, responseStatus are non-nullable
// collector/src/main/kotlin/com/platform/collector/models/CapturedInput.kt
data class CapturedInput(
    val id: String,
    val serviceId: String,
    val inputType: InputType,
    val method: String,           // non-nullable: HTTP-only for now
    val url: String,              // non-nullable: HTTP-only for now
    val requestHeaders: Map<String, String>? = null,
    val requestBody: String? = null,
    val responseStatus: Int,      // non-nullable: HTTP-only for now
    val responseHeaders: Map<String, String>? = null,
    val responseBody: String? = null,
    val latencyMs: Long? = null,
    val sourceIp: String? = null,
    val destinationIp: String? = null,
    val capturedAt: Instant,
)

// InputType - HTTP-first; UNKNOWN for unrecognized protocols
// KAFKA and PUBSUB removed (YAGNI — will add when replay engine needs them)
// collector/src/main/kotlin/com/platform/collector/models/InputType.kt
enum class InputType {
    HTTP,
    UNKNOWN,
}
```

### Request DTOs (app module)

```kotlin
// app/src/main/kotlin/com/platform/api/Requests.kt
data class CreateOrganizationRequest(val name: String)

data class CreateServiceRequest(
    val organizationId: String,
    val cluster: String,
    val namespace: String,
    val name: String,
    val provider: Provider = Provider.UNKNOWN,
    val metadata: Map<String, String>? = null,
)
```

### Request/Response DTOs (collector module)

```kotlin
// collector/src/main/kotlin/com/platform/collector/models/CreateCapturedInputRequest.kt
// Used by POST /api/captured-inputs (agent → collector)
data class CreateCapturedInputRequest(
    val serviceId: String,
    val inputType: InputType = InputType.HTTP,
    val method: String,
    val url: String,
    val requestHeaders: Map<String, String>? = null,
    val requestBody: String? = null,
    val responseStatus: Int,
    val responseHeaders: Map<String, String>? = null,
    val responseBody: String? = null,
    val latencyMs: Long? = null,
    val sourceIp: String? = null,
    val destinationIp: String? = null,
    val capturedAt: Instant,
)

data class BatchCreateCapturedInputRequest(val items: List<CreateCapturedInputRequest>)
data class BatchCreateCapturedInputResponse(val created: Int)
```

### Development Setup

```bash
# Prerequisites (macOS): Install Colima for TestContainers
# Why Colima? Docker Desktop has socket compatibility issues with TestContainers.
# Colima provides a lightweight Docker runtime that works reliably with both
# TestContainers and Jib. build.gradle.kts auto-detects Colima's socket.
brew install colima docker && colima start

# Start all services (app + collector + db; all start by default)
./gradlew dockerUp

# Run application servers individually
./gradlew :app:run          # app server on port 8080
./gradlew :collector:run    # collector server on port 8081

# Run tests (includes k3s Kubernetes integration tests)
./gradlew test

# Lint code
./gradlew ktlintCheck
```

**Module structure:**
- `shared/` — DatabaseFactory, Flyway migrations (`V0001–V0005`), shared models (Page, InstantSerializer); exposes `java-test-fixtures` with `DatabaseTestBase` and `KubernetesWorkloadTestBase`
- `app/` — Ktor API server on port 8080; owns Organizations + Services tables, repositories, adapters, routes; depends on `:shared`
- `collector/` — Ktor API server on port 8081; owns CapturedInputs table, repository, routes; depends on `:shared`; uses `application.yaml` (Ktor 3 YAML config)
- `agent/` — Standalone Kotlin process deployed to customer K8s clusters; polls Kubeshark + K8s API, pushes to collector; no dependency on `shared/`, `app/`, or `collector/` (API contract only)
- `test-services/` — Standalone Kotlin microservices for k3s integration testing

**Note on collector config:** The collector uses `application.yaml` (not HOCON `.conf`). This is required by Ktor 3, which uses the YAML config parser.

**Optional:** Deploy test workloads to local Kubernetes (kind/minikube) for manual testing:
```bash
./gradlew testServicesUp              # Deploy test services
./gradlew testServicesStatus          # Check status
./gradlew testServicesDown            # Remove test services
```

---

## Architecture Vision

### High-Level Design

```
CUSTOMER'S PRODUCTION CLUSTER
┌─────────────────────────────────────────────────┐
│  Kubeshark (eBPF)      Validation Agent         │
│  captures HTTP ───────► (3 loops):              │
│  req/res pairs          1. K8s API → discover   │
│                            services → register  │
│  order-service          2. Poll platform for    │
│  api-gateway               config (sampling,    │
│  notification-svc          namespace filters)   │
│                         3. Poll Kubeshark →     │
│                            filter + sample →    │
│                            POST to collector    │
└───────────────────────────┬─────────────────────┘
                            │ HTTPS (push)
                            ▼
PLATFORM
┌────────────────────────────────────────────────────────────────────┐
│  Collector (8081)          App (8080)                              │
│  POST /api/captured-inputs POST /api/services (agent registers)   │
│  stores req/res pairs      GET /api/agent/config (agent polls)    │
│                                                                    │
│  Replay Engine (planned):                                          │
│  1. Fetch captured inputs from collector                           │
│  2. Replay against staging (read-only default)                     │
│  3. Observe via Kubeshark in staging + K8s metrics                 │
│  4. Compare baseline vs candidate → verdict                        │
└────────────────────────────────────────────────────────────────────┘

CUSTOMER'S STAGING CLUSTER
┌──────────────────────────────────────────┐
│  Kubeshark (eBPF)    target service      │
│  observes replay     (baseline or        │
│  traffic             candidate)          │
│                      ↓ real connections   │
│                      staging-db, kafka    │
└──────────────────────────────────────────┘
```

### Validation Flow

```
1. CAPTURE (production, continuous, push model)
   Kubeshark eBPF captures HTTP request/response pairs at L7
   Validation agent polls Kubeshark, filters by registered services, samples, and pushes to collector

2. BASELINE RUN (staging, current version)
   Replay captured read traffic against current version in staging
   Kubeshark in staging observes outbound connections, call patterns
   K8s Metrics API collects pod CPU/memory

3. CANDIDATE RUN (staging, PR branch version)
   Deploy candidate to staging, replay same captured traffic
   Same observation via Kubeshark + K8s Metrics

4. COMPARE
   Response diffs, latency (Mann-Whitney U), error rates,
   outbound connection counts, memory trends (linear regression)

5. VERDICT
   PASS / FAIL / INCONCLUSIVE with evidence
```

### Design Principles

1. **Staging-based validation**: Customer provides staging environments with real dependencies. Platform captures production traffic and replays it against staging — no dependency mocking needed.
2. **Read-only replay by default**: Only replay safe (read) requests to avoid mutating staging DB state between sequential runs. Full replay available when customer provides a DB reset hook.
3. **HTTP-first, protocol-extensible model**: `CapturedInput` uses an `InputType` enum (`HTTP`, `UNKNOWN`). KAFKA and PUBSUB variants are intentionally deferred (YAGNI) until the replay engine needs them. The data model can extend without breaking changes.
4. **Module ownership via APIs**: Each module owns its tables and repositories. Cross-module data access (e.g., the collector needing a service fixture in tests) goes through REST API calls, not direct repository imports.
5. **eBPF for capture and observation**: Kubeshark in production for traffic capture, Kubeshark in staging for observability during replay.
6. **Statistical rigor**: Use proper statistical tests (Mann-Whitney U, linear regression), not arbitrary thresholds.

### Staging-Based Validation (Architectural Pivot)

**Why staging instead of isolated namespaces with dependency mocking?**

The original design used PCAP-based record-replay proxies to mock all dependencies (databases, APIs, queues) in an isolated validation namespace. Testing on minikube (2026-04-02) revealed a **TLS blocker**: almost all production databases (RDS, CloudSQL) use TLS encryption. Kubeshark's eBPF hooks intercept plaintext via `SSL_read`/`SSL_write`, but a serialization bug drops binary protocol data (Postgres wire protocol). Building protocol-specific recording proxies for every database flavor adds months of complexity.

**The pivot**: require customers to provide staging environments with real dependencies already wired up. The platform focuses on what it does uniquely well — capture real traffic, replay it, compare behavior — without needing to mock every protocol.

**What was preserved from the original PCAP validation (2026-04-02):**

| Finding | Status |
|---------|--------|
| Kubeshark captures HTTP req/res pairs at L7 (even over TLS) | Works, used for traffic capture |
| PCAP contains full Postgres queries for non-TLS connections | Validated, not needed with staging approach |
| PCAP contains full Kafka messages for non-TLS connections | Validated, not needed with staging approach |
| No PCAP truncation (11,324 frames, zero data loss) | Validated |
| TLS-encrypted DB traffic invisible in PCAPs | **Blocker** that motivated the pivot |

### Validation Agent (Push Model)

The agent runs in the customer's K8s cluster as a standalone Kotlin process. It pushes data to the platform — the platform never reaches into the customer's cluster.

**Three independent coroutine loops:**

| Loop | Interval | Responsibility |
|------|----------|----------------|
| Service discovery | ~60s | Query K8s API for services → diff against in-memory map → register new services with platform via `POST /api/services` → receive service ID map |
| Config polling | ~60s | `GET /api/agent/config` → update sampling rate, namespace filters, batch size, poll interval |
| Traffic capture | continuous | Drain up to batchSize entries from `KubesharkClient`'s persistent WebSocket channel (waits up to captureIntervalMs for the first entry) → filter by target services → sample → `POST /api/captured-inputs` to collector |

**Concurrency model:** Config is stored in a `MutableStateFlow<DynamicConfig>`. `KubesharkClient` and `TrafficTransformer` observe this flow directly. The traffic capture loop reads the latest snapshot. No locks, no coordination.

**Static config (env vars, set at deploy time):**
- `COLLECTOR_URL` — platform collector endpoint
- `API_KEY` — bearer token for authentication
- `KUBESHARK_URL` — in-cluster Kubeshark front URL (default: `http://kubeshark-front.default:80`)

**Dynamic config (polled from platform):**
- Sampling rate per service
- Namespace filters
- Batch size
- Poll intervals

**Kubeshark WebSocket transport (validated 2026-04-11):**
- Kubeshark v53+ removed the REST `/api/entries` endpoint. Traffic data is served exclusively over WebSocket at `/api/wsFull` (proxied through `kubeshark-front` nginx to `kubeshark-hub`).
- The server accepts a KFL (Kubeshark Filter Language) query as the first text frame. The agent sends a KFL query built by `KubesharkClient.buildKflQuery()` that restricts the stream to HTTP entries for configured target services (e.g. `http and (dst.name == "order-service" or dst.name == "api-gateway")`). When no target services are configured, the query is `"http"` (not empty, which means no filter at all). `TrafficTransformer` keeps its client-side filters as a safety net during the brief reconnect window after a config change.
- Entries arrive as individual JSON text frames in HAR-like shape: `{id, timestamp, protocol, src, dst, request, response, ...}`. Request body lives at `request.postData.text` (plaintext, HAR's `postData` applies to any method with a body); response body at `response.content.text` is **base64-encoded** (binary-safe) when `content.encoding == "base64"` — the agent decodes before forwarding.
- **Persistent session model (2026-04-11):** `KubesharkClient` maintains a single long-lived WebSocket for the agent's lifetime. A bounded `Channel<KubesharkEntry>` (capacity 1000) buffers incoming entries between the streamer coroutine and the capture loop. Backpressure: `Channel.send` suspends when full, the streamer stops reading frames, Ktor's receive buffer fills, TCP window closes, Kubeshark slows emission. The agent never OOMs under load.
- **Reactive KFL updates (2026-04-18):** `KubesharkClient` observes the shared `StateFlow<DynamicConfig>` via a `configWatcherJob`. When `targetServices` changes, it rebuilds the KFL query and immediately cancels the active WebSocket session, forcing a reconnect with the new filter. No manual `updateKflQuery()` calls needed — the `KubesharkClient` and `AgentApplication` are fully decoupled on config propagation.
- **Why persistent not connect-per-poll:** every fresh Kubeshark WebSocket session replays ~4-10s of recent history before reaching live entries (measured on the test cluster). Connect-per-poll would re-parse ~300 entries per reconnect at 75 entries/sec.
- **Reconnect dedup:** the client tracks `lastSeenTimestamp` (max across the session lifetime) and a `DEDUP_LOOKBACK = 5s` sliding window. Entries with `ts < (lastSeen - 5s)` are dropped as reconnect-replay noise. 5s covers 100% of observed in-session out-of-order jitter (measured p50 8ms, p95 1.8s, p99 3s, max 4.8s). Trade-off: on reconnect, up to `lookback × arrival_rate` dupes slip through (~375/reconnect at 75/sec). Reconnects are rare, so this is an acceptable loss versus the alternative of dropping in-session out-of-order entries on every batch. ID-based LRU dedup was rejected because at high traffic it silently fails once the cache overflows.

**Key design decisions:**
- Push model: agent pushes to platform, not platform pulling from customer cluster. Only requires outbound network access.
- Agent has its own DTOs (`CapturedInputRequest`), no compile-time dependency on platform modules. API contract only.
- Source/destination dedup: Kubeshark shows each call from both src and dst perspective. Agent filters on `dst.name` matching a target service, which naturally deduplicates.
- Sampling: stateless per-entry random check against configured rate. Acceptable to lose some traffic.
- KFL query pushed server-side: `KubesharkClient.buildKflQuery()` builds a valid KFL string from target service names. Kubeshark filters before sending entries over the wire, reducing agent CPU for traffic filtering. `TrafficTransformer` keeps its protocol/dst checks as a safety net.

**Agent module structure:**
```
agent/
  src/main/kotlin/com/platform/agent/
    AgentApplication.kt        # main, three coroutine loops; shared MutableStateFlow<DynamicConfig>
    AgentConfig.kt             # StaticConfig (env vars), DynamicConfig (polled), DurationAsMillisSerializer
    KubesharkClient.kt         # Persistent WebSocket client; observes StateFlow for KFL query updates
    CollectorClient.kt         # HTTP client for collector POST with exponential-backoff retry
    ConfigClient.kt            # HTTP client for platform GET /api/agent/config
    TrafficTransformer.kt      # Kubeshark → CapturedInputRequest (filters + base64 decode); observes StateFlow
    models/
      KubesharkEntry.kt        # Kubeshark WebSocket wire-format DTOs (HAR-ish)
      CapturedInputRequest.kt  # Collector POST payload DTOs (BatchCapturedInputRequest)
```

**Deployment artifacts:**
- `deploy/Dockerfile.agent` — multi-stage Dockerfile for environments without a Docker daemon socket for Jib.
- `agent/build.gradle.kts` — Jib plugin config for building `validation-agent:latest` directly into a local or remote Docker daemon.
- `k8s/agent/agent.yaml` — reference Kubernetes Deployment manifest (namespace `validation`, single replica). Used by both the minikube dev loop and `scripts/sandbox-up.sh` (which rewrites the image reference to GCR).

### Read/Write Traffic Classification (Planned — Not Yet Implemented)

To avoid mutating staging state between sequential baseline/candidate runs, the platform will classify traffic and default to read-only replay. The `TrafficClassifier` was previously stubbed in the app module but removed as premature — it belongs in the replay engine, which will own this logic.

| Protocol | Read | Write | Reliability |
|---|---|---|---|
| HTTP REST | `GET`, `HEAD` | `POST`, `PUT`, `PATCH`, `DELETE` | ~95% |
| gRPC | Method name: `Get*`, `List*`, `Search*`, `Find*`, `Query*` | Everything else | ~80% |
| GraphQL | `query` in body | `mutation` in body | ~99% |

Conservative default: ambiguous = write = skip. User can override specific endpoints (e.g., mark `POST /api/search` as safe).

### Message Queue Support (Future, De-Risked)

Message queues use built-in fan-out for safe capture:
- **Kafka**: Separate consumer group, zero impact on production consumers
- **GCP Pub/Sub**: Mirror subscription on same topic
- **AWS SNS**: Add capture SQS queue as subscriber
- **RabbitMQ**: Bind capture queue to same exchange

**Key insight**: If the message *producer* is a service we're already replaying HTTP to, Kafka messages flow naturally as a side effect in staging. Separate message capture is only needed for "entry point" messages from external systems.

**Limitations**: Consumer offset resets, idempotency guards, cross-partition ordering, timing sensitivity.

### What Customers Provide

| Requirement | Required? | Details |
|---|---|---|
| Staging cluster | Yes | With real dependencies (DB, queues, caches) wired up |
| Kubeshark access | Yes | Platform deploys/manages in both clusters |
| Deployment mechanism | Yes | How to deploy candidate to staging (image tag, Helm, kustomize) |
| Endpoint classification | Optional | Mark ambiguous endpoints as safe/mutating |
| DB reset hook | Optional | Enables full replay including writes |

### Adapter Implementation Status

**ServiceAdapter Interface** (`app/src/main/kotlin/com/platform/adapters/ServiceAdapter.kt`):
```kotlin
interface ServiceAdapter {
    suspend fun discoverServices(organizationId: String): List<Service>
}
```

**Implemented Adapters:**

1. **ManualSeedAdapter** - Provides 8 hardcoded services (frontend, backend, messaging, data layers) for testing and development without external dependencies.

2. **KubernetesAdapter** - Discovers services from Kubernetes clusters via the Kubernetes API. Supports in-cluster config, KUBECONFIG, and ~/.kube/config. Filters system namespaces by default. Extracts metadata from labels and annotations.

3. **KubesharkAdapter** - Planned: pull captured HTTP traffic from Kubeshark API for the collector module.

**Test Infrastructure:**

- `KubernetesWorkloadTestBase` - Spins up k3s cluster with test workloads using TestContainers
- Manifests in `k8s/test-services/` used for both automated tests and local development
- Handles Colima socket complexities for image loading
- 3 namespaces: `infrastructure`, `production`, `external`
- 7 discoverable K8s Services (traffic-generator has no Service resource)

**Deployed test services (exercises every dependency type):**
```
traffic-generator → api-gateway → order-service → orders-db (PostgreSQL)
                                → Redis (cache)   → Kafka (produce: order-events)

Kafka (consume: order-events) → notification-service → webhook-stub (external)
```

| Namespace | Services |
|-----------|----------|
| infrastructure | orders-db (PostgreSQL 16), redis (7-alpine, 2MB maxmemory + allkeys-lru), kafka (apache/kafka:3.7.0, KRaft mode) |
| production | api-gateway (HTTP proxy + Redis cache), order-service (HTTP API + PostgreSQL + Kafka producer), notification-service (Kafka consumer + webhook caller), traffic-generator (5 reader + 1 writer coroutines, no Service resource) |
| external | webhook-stub (simulates third-party API endpoint) |

| Dependency type | Exercised by |
|----------------|-------------|
| APPLICATION | api-gateway → order-service |
| DATASTORE | order-service → orders-db |
| MESSAGE_QUEUE | order-service → Kafka → notification-service |
| CACHE | api-gateway → Redis |
| EXTERNAL | notification-service → webhook-stub |

Per-service PostgreSQL is colocated with service manifests (not shared). The Kafka path creates a real async coupling between order-service and notification-service.

---

## Tech Stack

### Language: Kotlin

- Strong typing catches errors early, aids refactoring
- Coroutines provide clean async handling for I/O-heavy workload
- Data classes reduce boilerplate
- JetBrains support ensures good tooling

### Framework: Ktor

- Kotlin-native, built by JetBrains
- Coroutines are first-class
- Lightweight, only include what you need
- Simple mental model, no magic annotations

### Database: PostgreSQL

- Production-grade relational database
- Exposed ORM for type-safe queries
- Flyway for schema migrations

### Key Libraries

- **Ktor**: Kotlin-native web framework
- **Exposed + PostgreSQL**: Type-safe database access
- **Fabric8 Kubernetes Client**: Service discovery from K8s clusters
- **TestContainers**: Integration testing with PostgreSQL and k3s
- See `build.gradle.kts` for complete dependency list

---

## Data Models

| Model | Purpose | Module | Status |
|-------|---------|--------|--------|
| Organization | Tenant/team in the platform | `app` | Implemented |
| Service | Deployable unit discovered from various providers | `app` | Implemented |
| CapturedInput | HTTP req/res pair captured from production traffic | `collector` | Implemented |
| ReplayRun | A replay run against staging (config, status, collected responses) | TBD (likely its own module) | Planned |
| ReplayResponse | Per-request response collected during replay (status, body, latency) | TBD | Planned |
| ObservationData | Kubeshark + K8s metrics collected during a replay run | TBD | Planned |
| ValidationResult | Comparison of baseline vs candidate runs with verdict | TBD | Planned |
| ResourceSample | Point-in-time CPU/memory usage during replay | TBD | Planned |

---

## Planned Features

### Feature 1: Traffic Capture (via Kubeshark/eBPF)

Pull HTTP request/response pairs from Kubeshark in production. Classify as read/write. Store for replay. Protocol-agnostic model supports future Kafka/gRPC capture.

### Feature 2: Replay Engine

Send captured traffic to a target service in the customer's staging cluster. Configurable concurrency (QUICK/STANDARD/LOAD). Read-only by default, full replay with optional DB reset hook.

### Feature 3: Staging Observation

During replay, collect metrics via Kubeshark in staging (outbound connections, call patterns) and K8s Metrics API (pod CPU/memory). Detect behavioral changes like increased DB connection counts.

### Feature 4: Comparison & Verdicts

Compare baseline run (current version) vs candidate run (PR branch). Response diffs, latency (Mann-Whitney U), error rates, outbound connection delta, memory trends (linear regression). Generate PASS/FAIL/INCONCLUSIVE verdict with evidence.

### Feature 5: Orchestration API

Single `POST /api/validations` endpoint that orchestrates: capture traffic → baseline replay → (optional reset) → candidate replay → compare → verdict.

### Feature 6: Message Queue Capture (Future)

Capture Kafka/PubSub messages via separate consumer groups. Replay by producing to staging topics. Only needed for "entry point" messages from external systems.

---

## Adapter Implementation Matrix

Adapters normalize data from different sources into the unified model.

| Adapter | Status | Services | Traffic Capture | Staging Observation |
|---------|--------|----------|----------------|---------------------|
| **Manual Seed** | Implemented | Yes | No | No |
| **Kubernetes** | Implemented | Yes | No | No |
| **Kubeshark (eBPF)** | In Progress | Yes (via observed traffic) | Yes (HTTP req/res at L7) | Yes (outbound connections, call patterns) |

---

## Delivery Plan

### Phase 1: Foundation

**Week 1: Project Setup + Data Model** - COMPLETE
- [x] Initialize Gradle project with dependencies
- [x] Create package structure
- [x] Define Organization and Service models
- [x] Create Exposed table definitions
- [x] Implement database repositories with CRUD and pagination
- [x] Write database tests with TestContainers
- [x] Set up Docker deployment
- [x] Configure Flyway migrations
- [x] Set up ktlint for code quality

**Week 2: Kubernetes Integration + Manual Seed** - COMPLETE
- [x] Implement ServiceAdapter interface
- [x] Implement KubernetesAdapter with service discovery
- [x] Create ManualSeedAdapter with hardcoded test data
- [x] Set up KubernetesWorkloadTestBase with k3s integration tests
- [x] Create test workloads (PostgreSQL, Redis, API Gateway, traffic generator)
- [x] Configure Colima for TestContainers on macOS
- [ ] Create API endpoints: `POST /api/seed`, `GET /api/topology` (deferred)

**Milestone:** Adapter pattern implemented, services discoverable from Kubernetes and manual seed data

---

### Phase 2: Test Services + Kubeshark Validation

**Week 3: Expand Test Microservices** - COMPLETE
- [x] Implement order-service (HTTP API, PostgreSQL for orders-db, Kafka producer)
- [x] Implement notification-service (Kafka consumer, external HTTP call to webhook-stub)
- [x] Add Kafka (apache/kafka:3.7.0, KRaft mode) to k8s/test-services infrastructure
- [x] Add per-service PostgreSQL (orders-db colocated with service manifest)
- [x] Add webhook-stub in external namespace for EXTERNAL dep testing
- [x] Update api-gateway to proxy to order-service with Redis LRU cache
- [x] Update traffic-generator with concurrent coroutines (5 readers + 1 writer)
- [x] Update KubernetesWorkloadTestBase and integration tests (7-service topology)

**Week 4: Kubeshark/eBPF Validation** - COMPLETE
- [x] Deploy Kubeshark to minikube with test services
- [x] Validate HTTP traffic capture at L7 (req/res pairs with bodies)
- [x] Validate PCAP extraction for Postgres (non-TLS) and Kafka
- [x] De-risk: TLS-encrypted connections → **BLOCKER** for PCAP-only approach
- [x] De-risk: PCAP size limits → NOT A RISK (11,324 frames, zero truncation)
- [x] Architecture decision: pivot from record-replay proxy to staging-based validation

**Milestone:** Kubeshark validated for HTTP capture. Staging-based approach chosen over PCAP record-replay.

---

### Phase 3: Traffic Capture + Replay - IN PROGRESS

**Traffic Capture (Feature 1)**
- [x] CapturedInput model (HTTP-first, non-nullable method/url/responseStatus) — `collector/src/main/kotlin/com/platform/collector/models/`
- [x] InputType enum simplified to HTTP + UNKNOWN (YAGNI: KAFKA, PUBSUB deferred)
- [x] TrafficClassification enum and TrafficClassifier removed — not needed until replay engine
- [x] CapturedInputs table definition — `collector/src/main/kotlin/com/platform/collector/database/CapturedInputs.kt`
- [x] Database migration V0004 (method/url/responseStatus non-nullable) — `shared/src/main/resources/db/migration/`
- [x] CapturedInputRepository (create, createBatch, findById, find, countByService, deleteByService) — `collector/src/main/kotlin/com/platform/collector/database/`
- [x] Collector API: `GET /api/captured-inputs`, `GET /api/captured-inputs/{id}`, `DELETE /api/captured-inputs?serviceId=` (port 8081)
- [x] POST endpoints in app module: `POST /api/organizations`, `POST /api/services`
- [x] Collector module: full Ktor server on port 8081 (no longer a skeleton)
- [x] Test infrastructure: 18 CapturedInputRepository tests, 12 CapturedInputRoutesTest, 1 HealthRoutesTest, 6 new POST endpoint tests in app
- [x] AppApiTestHelper: collector tests create org/service fixtures via POST API calls to app module (enforces module boundary)
- [x] Agent module: KubesharkClient (WebSocket), CollectorClient, ConfigClient, TrafficTransformer, AgentConfig, AgentApplication
- [x] Agent DTOs: KubesharkEntry/KubesharkProtocol/KubesharkRequest/KubesharkResponse/KubesharkPostData/KubesharkContent/KubesharkHeader (Kubeshark wire format), CapturedInputRequest/BatchCapturedInputRequest (collector POST)
- [x] Kubeshark WebSocket validated (2026-04-11): `/api/wsFull` streams HAR-ish JSON entries with request/response bodies inline. Request body at `request.postData.text` (plaintext), response body at `response.content.text` base64-encoded when `content.encoding == "base64"`. Empty KFL filter = stream all; non-empty strings silently match nothing if KFL syntax is wrong.
- [x] Agent base64-decodes response bodies before forwarding to collector
- [x] Agent config architecture: static env vars (URLs, auth) + dynamic polling (sampling, target services, batch size)
- [x] Agent Loop 2: `ConfigClient` polls `GET /api/agent/config` (with graceful fallback to defaults when endpoint doesn't exist)
- [x] Agent Loop 3: `KubesharkClient` WebSocket poll → `TrafficTransformer` filter → `CollectorClient` batch POST
- [x] Agent tests: 79+ unit/integration tests across KubesharkClient (WebSocket with embedded Ktor server), TrafficTransformer, LoopLogic, CapturePipelineIntegration, ConfigClient, CollectorClient, AgentConfig
- [x] End-to-end minikube verification (2026-04-11): agent deployed to `validation` namespace streams 100 entries/batch every ~2s via `kubeshark-front:80/api/wsFull` with zero WebSocket errors
- [x] Deployment artifacts: `deploy/Dockerfile.agent` (non-root user via `USER agent`), `agent/build.gradle.kts` Jib config, `k8s/agent/agent.yaml` (file-based liveness probe on `/tmp/agent-alive`), `scripts/sandbox-up.sh` builds+pushes agent image to GCR
- [x] Collector: `POST /api/captured-inputs` batch endpoint (accepts `BatchCreateCapturedInputRequest`, returns `BatchCreateCapturedInputResponse{created: Int}`) — #42 (2026-04-12)
- [x] Push HTTP + service-name filtering into KFL query for server-side filtering — `KubesharkClient.buildKflQuery()` sends `http` or `http and (dst.name == X or ...)` as the first WebSocket frame; `KubesharkClient` observes `StateFlow<DynamicConfig>` and forces reconnect on `targetServices` change — #41 (2026-04-18)
- [x] KubernetesAdapter implements `Closeable` — `close()` propagates to the underlying Kubernetes client connection pool; usable with Kotlin `use` extension — #43 (2026-04-18)
- [ ] Agent Loop 1: K8s service discovery → register with platform → receive ID map (stubbed as `discoverServices()` no-op; needs K8s client + platform registration endpoint)
- [ ] Platform: `GET /api/agent/config` endpoint (agent polls this — currently returns fallback defaults since endpoint doesn't exist; `ConfigClient` points at `COLLECTOR_URL` which is also wrong per ARCH-1)
- [ ] HTTP gzip on agent→collector POST (wire bandwidth optimization; pending load numbers to justify)

**Replay Engine (Feature 2)**
- [ ] ReplayRun model + database migration (likely in its own module)
- [ ] ReplayEngine: send captured HTTP requests to staging target (fetches inputs via collector API)
- [ ] Configurable fidelity: QUICK (sequential), STANDARD (10-50 concurrent), LOAD (prod-rate)
- [ ] Read-only flag (skip write-classified requests)
- [ ] Optional DB reset hook between runs
- [ ] API: `POST /api/replay-runs`, `GET /api/replay-runs/{id}`

**Milestone:** Captured traffic replayable against staging services via API.

---

### Phase 4: Observation + Verdicts

**Staging Observation (Feature 3)**
- [ ] StagingObserver: poll Kubeshark in staging during replay
- [ ] ResourceMonitor: poll K8s Metrics API for pod CPU/memory
- [ ] Collect outbound connection destinations, call counts

**Comparison & Verdicts (Feature 4)**
- [ ] ComparisonEngine: diff baseline vs candidate replay runs
- [ ] StatisticalTests: Mann-Whitney U (latency), linear regression (memory trends)
- [ ] VerdictGenerator: PASS/FAIL/INCONCLUSIVE with evidence
- [ ] API: `GET /api/validations/{id}`

**Milestone:** Full comparison with statistical verdict.

---

### Phase 5: Orchestration + Hardening

**Orchestration API (Feature 5)**
- [ ] ValidationOrchestrator: capture → baseline → (reset) → candidate → compare → verdict
- [ ] API: `POST /api/validations` (single endpoint)
- [ ] Candidate deployment to staging (image tag swap)

**Hardening**
- [ ] Error handling and retry logic
- [ ] Logging and observability
- [ ] End-to-end tests
- [ ] Message queue capture support (Feature 6)

**Milestone:** Production-ready V1 API

---

## Future Features (V2+)

- **CLI**: Optional command-line interface wrapping the API for terminal workflows
- **Web UI**: Dashboard for visualizing topology, validation results, and anomalies
- **PR Integration**: GitHub/GitLab webhook integration, automatic validation on PR
- **Deployment Correlation**: Correlate anomalies with recent deploys
- **Automatic Rollback**: Integration with Argo/Flux, anomaly-triggered rollback
- **Multi-Cluster Support**: Federated topology across clusters

---

## Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Validation approach | Staging-based (customer provides staging env) | TLS blocks PCAP-based dependency mocking for production databases. Staging-based avoids protocol-specific proxies entirely. Simpler, shippable sooner. |
| Traffic capture | Kubeshark (eBPF) for HTTP at L7 | Zero instrumentation in production. HTTP req/res pairs captured with bodies. Validated on minikube 2026-04-02. |
| Module boundaries | Cross-module access via HTTP API, not shared repositories | Enforces clean ownership. Collector tests create org/service fixtures via POST to app routes (AppApiTestHelper), not by importing app repositories directly. |
| InputType enum | HTTP + UNKNOWN only (KAFKA, PUBSUB removed) | YAGNI: message queue capture deferred until replay engine needs it. Enum can extend without breaking changes. |
| CapturedInput fields | method, url, responseStatus non-nullable | HTTP-only for now; nullable fields were premature abstraction before non-HTTP capture exists. |
| TrafficClassifier | Removed from app module | Premature: classification is only needed during replay, which may be its own module. Re-add there when the time comes. |
| Collector config format | `application.yaml` (not HOCON) | Ktor 3 requires the YAML config parser; HOCON is legacy Ktor 2 behavior. |
| Test base hierarchy | `DatabaseTestBase` (shared fixtures) → `AppDatabaseTestBase` / `CollectorDatabaseTestBase` | Each module's test base cleans only its own tables, preserving module ownership in tests. |
| Replay model | `CapturedInput` with InputType field | HTTP-first, but `type: HTTP | UNKNOWN` allows extension to KAFKA/PUBSUB without model changes. |
| Replay safety | Read-only by default, full with reset hook | Avoids DB mutation between sequential baseline/candidate runs. Conservative classification (ambiguous = write = skip). |
| Run model | Sequential (baseline then candidate) | Simpler than parallel — one set of staging infra. For 5-15 min runs, environmental drift is negligible. |
| Staging observation | Kubeshark in staging + K8s Metrics API | Kubeshark gives outbound connection counts and patterns. K8s Metrics gives CPU/memory for leak detection. |
| Statistical tests | Mann-Whitney U | Non-parametric, handles skewed latency distributions |
| Leak detection | Linear regression | Detect memory growth trend over time |
| Interface | API-first (CLI deferred) | Enables UI/webhook integration without binary distribution; CLI can wrap API later if needed |
| Traffic capture model | Push (agent → collector) not pull (platform → Kubeshark) | Agent in customer cluster pushes to platform. Only requires outbound network access. Platform never reaches into customer clusters. Scales naturally — N agents push, zero fan-out from platform. |
| Agent language | Kotlin (same as platform) | Same build toolchain, CI, team knowledge. Image size (~150MB with JRE vs ~10MB Go) acceptable for long-running agent. Swappable later — agent communicates via HTTP only. |
| Agent config | Static env vars (URLs, auth) + dynamic polling (sampling, filters) | Mutable config polled from platform avoids redeploying agent for config changes. Env vars only for deployment-time facts (cluster name, endpoints). |
| Agent service discovery | Agent queries K8s API directly, registers with platform | Agent has visibility into cluster service inventory. Platform can't know about new deployments without being told. Agent registers services and receives ID map. |
| Agent ↔ platform contract | Separate DTOs, API contract only, no shared compile-time types | Agent ships as container to customer clusters, versions independently. `ignoreUnknownKeys = true` on both sides enables additive API evolution without lockstep releases. |
| Traffic loss tolerance | Acceptable to lose some traffic (sampling, reconnect dupes/gaps, drops on permanent 4xx) | For mature high-traffic services, sampling is required anyway. On WebSocket reconnect, dedup window may miss or double-count a few seconds of entries — acceptable. `CollectorClient` retries 5xx/network errors indefinitely with backoff; 4xx are dropped as permanent failures rather than hammering the server. |
| Kubeshark transport (v53+) | WebSocket `/api/wsFull` with KFL query for server-side filtering | Kubeshark v53 removed the REST `/api/entries` endpoint. `KubesharkClient.buildKflQuery()` constructs a valid KFL string (`http` or `http and (dst.name == X or ...)`). KFL syntax nailed down 2026-04-18 — non-empty strings silently match nothing if syntax is wrong; confirmed `http and dst.name == "svc"` works. Agent now pushes filtering server-side; `TrafficTransformer` keeps client-side checks as a safety net during reconnect windows. |
| Response body encoding | Kubeshark base64-encodes `response.content.text`; agent decodes | Binary-safe for non-UTF-8 payloads (images, protobuf). Request bodies at `request.postData.text` are NOT encoded. Agent's `TrafficTransformer.decodeContent` checks `content.encoding == "base64"` and decodes before forwarding. Base64 decode is microseconds per 10KB — negligible against agent's 200m CPU budget. |
| Agent ↔ Kubeshark session model | Long-lived persistent WebSocket session + bounded channel, reconnect on failure with 5s backoff | Every fresh Kubeshark session replays ~4-10s of history before reaching live entries (measured 2026-04-11). Connect-per-poll would re-parse ~300 entries per reconnect at 75/sec. A persistent session pays the backlog cost once and streams live forever. Bounded channel (default 1000) applies TCP-level backpressure if the capture loop falls behind: `Channel.send` suspends → streamer stops reading → Ktor receive buffer fills → TCP window closes → Kubeshark slows. Agent never OOMs. |
| Agent reconnect dedup | `lastSeenTimestamp` sliding-window with 5s lookback (reject entries older than `lastSeen - 5s`) | Kubeshark's reconnect-replay would double-count ~10s of traffic without dedup. In-session out-of-order jitter goes up to ~5s on our test cluster (p50 8ms, p95 1.8s, max 4.8s), so 5s lookback covers 100% of observed jitter without dropping in-session data. Trade-off: up to ~5s worth of dupes per reconnect slip through — acceptable because reconnects are rare events. ID-based LRU dedup was rejected because it silently fails at high traffic once the cache overflows. |
| Agent abstractions | No `TrafficSource` interface; `KubesharkClient` used directly, mocked in tests via mockk | Earlier rev had a `TrafficSource` interface to decouple tests from WebSocket transport. Removed as YAGNI — still leaked `KubesharkEntry`, had a single impl, and mockk handles final-class mocking on JVM. |
| Config propagation | `MutableStateFlow<DynamicConfig>` shared between all three loops | Replaced `AtomicReference<DynamicConfig>` (2026-04-18). `KubesharkClient` observes via `configWatcherJob` and triggers reconnect when `targetServices` changes. `TrafficTransformer` reads `.value` on each transform call. Decouples config changes from imperative method calls — `AgentApplication` doesn't need to know about `KubesharkClient.updateKflQuery`. |
| KubernetesAdapter lifecycle | Implements `Closeable`; `close()` delegates to Kubernetes client | Connection pool is released when adapter goes out of scope. Usable with Kotlin `use` extension or Java try-with-resources. Added 2026-04-18. |

---

## Implementation Guidelines

### Module Assignment

Before implementing a feature, decide which module owns it:
- **`app`**: Organizations, Services, topology/discovery, agent config endpoint
- **`collector`**: CapturedInputs, traffic ingestion (POST endpoint for agent)
- **`agent`**: Kubeshark polling, K8s service discovery, traffic capture and forwarding (standalone process, no platform dependencies)
- **Future replay module**: ReplayRuns, ReplayResponses, ReplayEngine

### Implementation Order (per module)

1. Data model in the owning module's `models/` package
2. Database migration in `shared/src/main/resources/db/migration/` (V-numbered, sequential)
3. Exposed table definition in the owning module's `database/` package
4. Repository in the owning module's `database/` package
5. API endpoint in the owning module's `api/Routes.kt`
6. Tests: use the module's `*DatabaseTestBase` (which extends `DatabaseTestBase` from shared fixtures)

### Cross-Module Access

Cross-module data access uses HTTP REST calls, not direct repository imports.

Example: collector tests need org/service fixtures. They call `AppApiTestHelper`, which starts a `testApplication` with the app module's routing and issues `POST /api/organizations` and `POST /api/services`. The collector's `build.gradle.kts` declares `testImplementation(project(":app"))` only for this purpose.

This pattern will extend to the replay engine: it fetches captured inputs via `GET /api/captured-inputs` from the collector, not by importing `CapturedInputRepository` directly.

### Shared Infrastructure

`shared/` provides:
- `DatabaseFactory` — Exposed `Database.connect` (direct, no explicit connection pool) + Flyway migrations. Note: uses Exposed's internal connection management, not HikariCP. See ARCHITECTURE_REVIEW.md ARCH-4 for the known gap.
- `Page<T>` — cursor-based pagination model
- `InstantSerializer` — kotlinx.serialization adapter for `java.time.Instant`
- `DatabaseTestBase` (test fixtures) — starts a TestContainers PostgreSQL instance
- `KubernetesWorkloadTestBase` (test fixtures) — starts a k3s cluster with test workloads
