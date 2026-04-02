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

- **REST API** with Ktor server (health check, organizations, services endpoints)
- **PostgreSQL database** with Flyway migrations
- **Multi-tenant data model** with Organizations and Services
- **Pagination and filtering** on list endpoints
- **Docker deployment** (docker-compose with PostgreSQL)
- **Test infrastructure** with TestContainers (PostgreSQL + k3s Kubernetes)
- **Code quality** with ktlint
- **Adapter pattern** with ServiceAdapter interface
- **Service discovery** via ManualSeedAdapter and KubernetesAdapter
- **Provider tracking** (UNKNOWN, MANUAL_SEED, KUBERNETES)

### Implemented API Endpoints

```
GET  /health                              # Health check
GET  /api/organizations                   # List organizations (paginated)
GET  /api/organizations/{id}              # Get organization by ID
GET  /api/services                        # List services (paginated, filterable)
GET  /api/services/{id}                   # Get service by ID
```

### Current Data Models

```kotlin
// Organization - a tenant/team in the platform
data class Organization(
    val id: String,
    val name: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

// Service - a deployable unit discovered from various providers
// Uniquely identified by: organizationId + cluster + namespace + name
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
enum class Provider {
    UNKNOWN,        // Provider unknown or not specified
    MANUAL_SEED,    // Manually seeded test data
    KUBERNETES      // Discovered via Kubernetes API
    // PIXIE - Reserved for future Pixie integration
}
```

### Development Setup

```bash
# Prerequisites (macOS): Install Colima for TestContainers
# Why Colima? Docker Desktop has socket compatibility issues with TestContainers.
# Colima provides a lightweight Docker runtime that works reliably with both
# TestContainers and Jib. build.gradle.kts auto-detects Colima's socket.
brew install colima docker && colima start

# Start PostgreSQL
./gradlew dockerUp

# Run application
./gradlew run

# Run tests (includes k3s Kubernetes integration tests)
./gradlew test

# Lint code
./gradlew ktlintCheck
```

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
4. **Pixie is the prerequisite**: Traffic capture, topology, and replay all depend on Pixie. KubernetesAdapter provides node discovery; Pixie provides edges and traffic
5. **Statistical rigor**: Use proper statistical tests, not arbitrary thresholds

### Service-Centric Replay Model

Replay is **service-centric and protocol-agnostic**. Every input to a service — whether an HTTP request, a consumed Kafka message, or a gRPC call — is treated as a `CapturedInput` that can be replayed uniformly.

```
CapturedInput:
  serviceId
  protocol: HTTP | KAFKA | GRPC | ...
  timestamp
  payload          # HTTP body or Kafka message value
  metadata         # headers, topic/partition/key, gRPC method, etc.
```

**Replay dispatches by protocol:**
- `HTTP` → send request to the pod's HTTP port
- `KAFKA` → produce message to the pod's isolated topic
- `GRPC` → call the gRPC method

**Capture per protocol:**
- HTTP/gRPC: Pixie (eBPF, zero instrumentation)
- Kafka: lightweight consumer sidecar that tails topics and records messages (read-only, no app instrumentation)

### Onboarding & Topology Discovery

**Pixie is the prerequisite for everything.** Without observed production traffic, there's nothing to replay and no topology to build. The onboarding flow is:

```
1. User registers cluster
2. Platform deploys Pixie
3. Pixie observes traffic for N hours/days — simultaneously:
   - Discovers services and edges (who talks to whom)
   - Captures HTTP traffic with request/response bodies
4. Platform presents topology + environment profile suggestion
5. User confirms/edits profile
6. Validation runs are now possible
```

**Topology comes from Pixie, not static analysis.** Parsing env vars or DNS logs gives partial graphs that users have to fix. Pixie observes actual network connections and gives you the real dependency graph as a byproduct of traffic capture.

**Service type is user-declared, not auto-classified.** Port-based or image-based heuristics are fragile (non-standard ports, managed services outside the cluster). Pixie tells you "order-service talks to orders-db." The user tells you "orders-db is a Postgres database." The platform suggests, the user confirms.

### Dependency Types & Provisioning

Each dependency in the environment profile has a type that determines how it's provisioned in the validation namespace:

| Type | Examples | Provisioning strategy |
|------|----------|----------------------|
| `APPLICATION` | Your microservices | Deploy from container image (current prod version) |
| `MESSAGE_QUEUE` | Kafka, RabbitMQ, NATS | Ephemeral instance; inputs injected by replay engine |
| `CACHE` | Redis, Memcached | Ephemeral instance; start empty (cold cache) or snapshot restore |
| `RECORDED` | Databases, third-party APIs (Stripe, etc.) | Record-replay proxy; serves captured responses from traffic observation phase |

**Why databases use `RECORDED` instead of ephemeral instances:** Production databases can be terabytes, sharded, managed (RDS, CloudSQL). Snapshotting into the validation namespace is prohibitively expensive. Instead, the record-replay proxy captures query responses during the Pixie observation phase and serves them back during replay.

**Record-replay proxy as an instrumentation layer:** The proxy intercepts every outbound query, which enables behavioral comparison beyond just replaying responses:

```
Validation results for order-service:

  Datastore: orders-db (postgresql)

  Query volume:
    Control:    142 reads,  38 writes
    Candidate:  1,847 reads, 38 writes
    ⚠️ 13x increase in read queries

  Query pattern analysis:
    SELECT * FROM orders WHERE user_id = ?
      Control: 38 calls    Candidate: 1,847 calls   ⚠️ +4,760%

    SELECT * FROM users WHERE id = ?
      Control: 5 calls     Candidate: 5 calls        ✅

    INSERT INTO audit_log (...)
      Control: 0 calls     Candidate: 38 calls       🆕 New query pattern

  Write diff:
    38 writes in both — payload hashes match ✅
```

This catches N+1 regressions, unexpected new queries, and write behavior changes — without running a real database. Stronger signal than running against a real DB in some ways, because it compares **behavior** not **outcomes** (a real DB might hide an N+1 if the query is fast enough).

### Validation Environment & Isolation

**Hard isolation is non-negotiable.** Validation runs may replay historical peak traffic (Black Friday). If that hits production dependencies, it's a production incident. The validation namespace must be a closed box.

**Default-deny network policy** is applied before any pods start:

```yaml
# Applied FIRST, before any pods are deployed
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: deny-all-egress
  namespace: val-order-service
spec:
  podSelector: {}
  policyTypes: [Egress]
  egress: []              # nothing gets out
```

Then holes are punched only for declared isolated dependencies. Hardcoded credentials, baked-in config, undeclared services — all blocked at the network level before the first packet leaves.

**Blocked connections are a detection mechanism:** If the service tries to reach something not in the environment profile, the connection is blocked and reported in validation results. This surfaces undeclared dependencies and hardcoded connection strings.

**Environment builder sequence (order matters):**
```
1. Create namespace
2. Apply default-deny network policy        ← BEFORE any pods
3. Deploy isolated dependencies (ephemeral infra + record-replay proxies)
4. Label them role=isolated-dependency
5. Create DNS overrides (K8s Services matching production hostnames)
6. Apply allow-isolated-deps network policy (whitelist)
7. Deploy control + candidate pods
8. Replay captured inputs
9. Tear down or scale to zero
```

**DNS overrides handle hardcoded hostnames:** If a service hardcodes `prod-db.rds.amazonaws.com`, create a K8s Service with that hostname in the validation namespace pointing at the record-replay proxy. The service never knows the difference.

**Validation namespace layout:**
```
val-order-service/
├── NetworkPolicy: deny-all-egress          (created first)
├── NetworkPolicy: allow-isolated-deps      (whitelist only)
├── control pod                             (current version)
├── candidate pod                           (PR branch)
├── kafka-ephemeral                         (role=isolated-dependency)
├── redis-ephemeral                         (role=isolated-dependency)
├── record-replay-proxy                     (serves captured DB/API responses)
├── inventory-service                       (prod image, role=isolated-dependency)
└── DNS overrides                           (map production hostnames → isolated instances)
```

### Change Detection & Profile Drift

The environment profile is confirmed once during onboarding, then maintained via two feedback loops:

**Reactive (during validation):** Blocked connections surface new/undeclared dependencies. "order-service tried to reach recommendation-service:8080, which isn't in your profile. Add it?"

**Proactive (continuous):** Pixie continuously observes traffic. If a service starts talking to a new dependency, the platform notifies the team before they run a validation. Pod spec env vars are periodically re-scanned and compared against the saved profile.

```
┌──────────────────┐
│  Discover         │ ← Pixie observes traffic (topology + capture)
│  (build topology) │
└────────┬─────────┘
         ▼
┌──────────────────┐
│  Confirm          │ ← User reviews topology, classifies deps, saves profile
│  (env profile)    │
└────────┬─────────┘
         ▼
┌──────────────────┐
│  Validate         │ ← Replay traffic in isolated namespace
│  (run tests)      │
└────────┬─────────┘
         ▼
┌──────────────────┐
│  Detect drift     │ ← Blocked connections + continuous Pixie observation
│  (find changes)   │
└────────┘
         │
         └──→ back to Confirm (user updates profile)
```

### Environment Provisioning Optimizations (Phased)

| Phase | Strategy | Spin-up time | Complexity |
|-------|----------|-------------|------------|
| Phase 3 (MVP) | Fresh namespace per run, tear down after | ~3-5 min | Low |
| Phase 5 (Hardening) | Warm namespaces, scale-to-zero, image pre-pulling | ~10-30s | Medium |
| V2 | Shared infra pools, snapshot-based state seeding | ~5-10s | Higher |

**Key optimizations (Phase 5+):**
- **Warm namespaces**: keep infra running between runs, reset state instead of tearing down (topic truncation, FLUSHALL, proxy cache clear)
- **Scale-to-zero**: service dependencies scale to 0 pods between runs via KEDA or a simple controller, scale up on validation request (sub-second if image cached)
- **Image pre-pulling**: DaemonSet-based pre-puller ensures commonly-used images are cached on nodes
- **Namespace-per-service, not namespace-per-run**: standing namespace per service avoids repeated setup
- **Shared infra pools**: one Kafka cluster serving all validation namespaces via topic isolation; Redis via database-number-per-run

**Critical design rule:** The replay engine must not know or care how the namespace was provisioned. The validation environment interface abstracts provisioning strategy so it can be swapped from cold-start to warm to pooled without changing replay logic.

### Adapter Implementation Status

**ServiceAdapter Interface** (`src/main/kotlin/com/platform/adapters/ServiceAdapter.kt`):
```kotlin
interface ServiceAdapter {
    suspend fun discoverServices(organizationId: String): List<Service>
}
```

**Implemented Adapters:**

1. **ManualSeedAdapter** - Provides 8 hardcoded services (frontend, backend, messaging, data layers) for testing and development without external dependencies.

2. **KubernetesAdapter** - Discovers services from Kubernetes clusters via the Kubernetes API. Supports in-cluster config, KUBECONFIG, and ~/.kube/config. Filters system namespaces by default. Extracts metadata from labels and annotations.

3. **PixieAdapter** - Planned for future implementation (traffic capture and dependency discovery).

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

## Planned Data Models

These models will be added as features are implemented:

| Model | Purpose | Status |
|-------|---------|--------|
| Dependency | Observed connection between services (edge in topology graph) | Planned |
| EnvironmentProfile | User-confirmed dependency declarations + provisioning config per service | Planned |
| DependencyDeclaration | Single dependency within a profile (type, provider, connection env var) | Planned |
| CapturedInput | Protocol-agnostic captured input (HTTP, Kafka, gRPC) | Planned |
| CapturedResponse | Recorded dependency response for record-replay proxy (DB queries, API calls) | Planned |
| ReplayRun | Traffic replay validation experiment | Planned |
| QueryBehavior | Per-query-pattern read/write counts during a replay run (control vs candidate) | Planned |
| ResourceSample | Point-in-time CPU/memory usage | Planned |
| Baseline | Learned normal behavior | Planned |
| Anomaly | Detected deviation from baseline | Planned |

---

## Planned Features

### Feature 1: Traffic Capture & Topology (via Pixie)

Deploy Pixie to observe production traffic. Simultaneously captures HTTP traffic with request/response bodies AND builds the topology graph (services + edges) from observed connections. This is the prerequisite for all other features — without traffic, there's nothing to validate.

### Feature 2: Environment Profiles & Onboarding

Present Pixie-discovered topology to users. Users confirm/edit dependencies and classify each (APPLICATION, MESSAGE_QUEUE, CACHE, RECORDED). Saved as the environment profile that drives validation namespace provisioning.

### Feature 3: Isolated Validation Environment

Build validation namespaces with default-deny egress, ephemeral infra (queues, caches), record-replay proxies (databases, external APIs), and DNS overrides. Hard isolation — nothing escapes to production. Blocked connections surface undeclared dependencies.

### Feature 4: Traffic Replay & Comparison

Replay captured inputs against control and candidate versions. Compare HTTP responses, query behavior (read/write counts, query patterns), and resource usage. Record-replay proxy instruments all dependency interactions.

### Feature 5: Statistical Analysis & Verdicts

Mann-Whitney U for latency comparison, linear regression for leak detection, query pattern analysis for N+1 detection. Generate pass/fail/inconclusive verdict with evidence.

### Feature 6: Change Detection & Profile Drift

Continuous Pixie observation detects new dependencies. Blocked connection feedback during validation catches undeclared deps. Proactive notifications when topology drifts from saved profile.

---

## Adapter Implementation Matrix

Adapters normalize data from different sources into the unified model.

| Adapter | Status | Services | Dependencies | Endpoints | Metrics | Traffic Bodies |
|---------|--------|----------|--------------|-----------|---------|----------------|
| **Manual Seed** | Implemented | Yes | Planned | Planned | Planned | Planned |
| **Kubernetes** | Implemented | Yes | Planned | Planned | Planned | No |
| **Pixie** | Planned | Yes | Yes | Yes | Yes | Yes |
| **AWS X-Ray** | Planned | Yes | Yes | No | Yes | No |

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

### Phase 2: Test Services Expansion + Pixie Integration

Pixie is the prerequisite for everything — without observed traffic, there's nothing to replay and no topology to build. But Pixie needs representative traffic patterns to observe, so the test services must be expanded first.

**Week 3: Expand Test Microservices** - COMPLETE
- [x] Implement order-service (HTTP API, PostgreSQL for orders-db, Kafka producer)
- [x] Implement notification-service (Kafka consumer, external HTTP call to webhook-stub)
- [x] Add Kafka (apache/kafka:3.7.0, KRaft mode) to k8s/test-services infrastructure
- [x] Add per-service PostgreSQL (orders-db colocated with service manifest)
- [x] Add webhook-stub in external namespace for EXTERNAL dep testing
- [x] Update api-gateway to proxy to order-service with Redis LRU cache
- [x] Update traffic-generator with concurrent coroutines (5 readers + 1 writer)
- [x] Update KubernetesWorkloadTestBase and integration tests (7-service topology)

**Week 4: Pixie Setup + Traffic Capture**
- [ ] Deploy Pixie to k3s test cluster
- [ ] Implement PixieAdapter (services, dependencies, HTTP events)
- [ ] CapturedInput model + database migration + repository
- [ ] Store captured inputs (HTTP requests with bodies)
- [ ] Sampling strategy (don't store everything)
- [ ] Sensitive header filtering

**Week 4: Topology + Environment Profile**
- [ ] Topology model from Pixie observations (nodes + edges)
- [ ] EnvironmentProfile + DependencyDeclaration models
- [ ] Dependency type enum: APPLICATION, MESSAGE_QUEUE, CACHE, RECORDED
- [ ] API: `POST /api/discover` (trigger Pixie observation)
- [ ] API: `GET /api/services/{id}/topology` (observed dependencies)
- [ ] API: `GET/PUT /api/services/{id}/environment-profile` (confirm/edit)

**Milestone:** Pixie observes traffic, topology visible via API, users can confirm environment profiles

---

### Phase 3: Validation Environment + Replay

**Week 5: Namespace Builder + Isolation**
- [ ] Validation namespace creation with default-deny egress network policy
- [ ] Deploy isolated dependencies by type:
  - APPLICATION → deploy from prod container image
  - MESSAGE_QUEUE → ephemeral instance (Kafka/RabbitMQ)
  - CACHE → ephemeral instance (Redis)
  - RECORDED → record-replay proxy
- [ ] DNS overrides for hardcoded hostnames
- [ ] Env var rewriting for declared dependencies
- [ ] Blocked connection detection and reporting

**Week 6: Replay Engine + Record-Replay Proxy**
- [ ] Record-replay proxy: capture dependency responses during observation, serve during replay
- [ ] Query behavior instrumentation: read/write counts, query pattern grouping, new query detection
- [ ] CapturedResponse model + storage
- [ ] ReplayEngine: dispatch captured inputs to control + candidate by protocol
- [ ] Collect responses from both versions
- [ ] API: `POST /api/validations` (trigger validation run)
- [ ] API: `GET /api/validations/{id}` (status + results)

**Milestone:** `POST /api/validations` triggers full isolated validation run with record-replay proxy

---

### Phase 4: Analysis & Verdicts

**Week 7: Statistical Analysis**
- [ ] Implement Statistics module (Mann-Whitney U, linear regression)
- [ ] Latency comparison (control vs candidate)
- [ ] Error rate comparison
- [ ] Query behavior comparison (read/write counts, new patterns)
- [ ] ResourceMonitor: poll K8s Metrics API during replay
- [ ] Memory trend analysis (leak detection)

**Week 8: Verdicts + API**
- [ ] Implement ValidationService (orchestrates everything)
- [ ] Generate verdict (pass/fail/inconclusive) with evidence
- [ ] Blocked connection reporting in validation results
- [ ] API: `GET /api/validations/{id}/verdict`

**Milestone:** Full validation with verdict: `{"verdict": "FAIL", "regressions": ["N+1 query: 13x increase in reads to orders-db"]}`

---

### Phase 5: Hardening

**Week 9: Change Detection + Profile Drift**
- [ ] Continuous Pixie observation: detect new dependencies not in profile
- [ ] Pod spec reconciliation: compare env vars against saved profile
- [ ] Proactive notifications when topology drifts
- [ ] Warm namespaces: scale-to-zero between runs, fast spin-up
- [ ] Image pre-pulling

**Week 10: Stabilization**
- [ ] Error handling and retry logic
- [ ] Configuration management
- [ ] Logging and observability
- [ ] End-to-end tests

**Milestone:** Production-ready V1 API with continuous drift detection

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
| Traffic capture | Pixie (eBPF) for HTTP; sidecar consumer for Kafka | Zero instrumentation, captures bodies; Kafka needs broker-level capture since Pixie can't parse Kafka protocol |
| Replay model | Service-centric, protocol-agnostic | All inputs (HTTP requests, Kafka messages, gRPC calls) are treated uniformly as "captured inputs" to replay — no special-casing per protocol |
| Topology source | Pixie (observed traffic), not static analysis | Env var parsing and DNS logs give partial graphs; Pixie observes real connections. Topology is a byproduct of traffic capture, not a separate feature |
| Service type classification | User-declared, not auto-detected | Port/image heuristics are fragile (non-standard ports, managed services outside cluster). Pixie suggests, user confirms |
| Dependency types | APPLICATION, MESSAGE_QUEUE, CACHE, RECORDED | Four types based on provisioning strategy. Databases use RECORDED (record-replay proxy) because snapshotting production DBs is prohibitively expensive |
| Datastore handling | Record-replay proxy with query behavior analysis | Proxy serves captured responses AND instruments query patterns (read/write counts, query templates, new queries). Catches N+1 regressions without a real database |
| Network isolation | Default-deny egress, applied before pods start | Hard isolation is non-negotiable — validation runs may replay peak traffic. Blocked connections also serve as undeclared dependency detection |
| Resource metrics | K8s Metrics API | Simple, always available |
| Statistical tests | Mann-Whitney U | Non-parametric, handles skewed latency distributions |
| Leak detection | Linear regression | Detect growth trend over time |
| Comparison approach | Control vs candidate simultaneously | Eliminates infrastructure noise |
| Interface | API-first (CLI deferred) | Enables UI/webhook integration without binary distribution; CLI can wrap API later if needed |

## Implementation Guidelines

For each feature, implement in this order:
1. Data model (models/)
2. Database operations (database/)
3. Business logic (features/)
4. API endpoint (api/)
5. Tests