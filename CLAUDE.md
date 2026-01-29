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
- **Test infrastructure** with TestContainers
- **Code quality** with ktlint

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

// Service - a deployable unit belonging to an organization
data class Service(
    val id: String,
    val organizationId: String,
    val name: String,
    val cluster: String,
    val namespace: String,
    val createdAt: Instant,
    val updatedAt: Instant
)
```

### Development Setup

```bash
# Start PostgreSQL
./gradlew dockerUp

# Run application
./gradlew run

# Run tests
./gradlew test

# Lint code
./gradlew ktlintCheck
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
4. **Graceful degradation**: Topology/baselines work even without Pixie; replay requires Pixie
5. **Statistical rigor**: Use proper statistical tests, not arbitrary thresholds

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

### Current Dependencies

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    id("io.ktor.plugin") version "3.3.3"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
}

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")
    implementation("io.ktor:ktor-server-config-yaml")

    // Database (Exposed + PostgreSQL)
    implementation("org.jetbrains.exposed:exposed-core:0.57.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.57.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.57.0")
    implementation("org.jetbrains.exposed:exposed-json:0.57.0")
    implementation("org.postgresql:postgresql:42.7.4")

    // Database migrations
    implementation("org.flywaydb:flyway-core:9.22.3")

    // Logging
    implementation("ch.qos.logback:logback-classic")

    // Testing
    testImplementation("io.ktor:ktor-server-test-host-jvm")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.testcontainers:testcontainers:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:postgresql:1.20.4")
}
```

### Planned Dependencies (Not Yet Added)

- `io.fabric8:kubernetes-client` - Kubernetes integration for service discovery and metrics
- AWS SDK (optional) - X-Ray and CloudWatch integration

---

## Planned Data Models

These models will be added as features are implemented:

| Model | Purpose | Status |
|-------|---------|--------|
| Dependency | Connection between services | Planned |
| Endpoint | Specific API endpoint on a service | Planned |
| CapturedRequest | HTTP request with body from Pixie | Planned |
| ReplayRun | Traffic replay validation experiment | Planned |
| ResourceSample | Point-in-time CPU/memory usage | Planned |
| MetricSample | Time-series metrics for an endpoint | Planned |
| Baseline | Learned normal behavior | Planned |
| Anomaly | Detected deviation from baseline | Planned |

---

## Planned Features

### Feature 1: Traffic Capture (via Pixie)

Continuously capture HTTP traffic with request/response bodies for later replay using eBPF-based observation.

### Feature 2: Traffic Replay & Validation

Replay captured traffic against control and candidate versions, comparing latency, error rates, and response bodies.

### Feature 3: Resource Monitoring

Track CPU and memory usage during replay using Kubernetes Metrics API to detect leaks and growth patterns.

### Feature 4: Topology & Blast Radius

Discover services and their dependencies, compute impact analysis for changes.

### Feature 5: Baselines & Anomaly Detection

Learn normal behavior patterns, detect anomalies in production metrics.

---

## Adapter Interface

Adapters normalize data from different sources into the unified model.

| Adapter | Services | Dependencies | Endpoints | Metrics | Traffic Bodies |
|---------|----------|--------------|-----------|---------|----------------|
| Pixie | Yes | Yes | Yes | Yes | Yes |
| Kubernetes | Yes | | | Yes (resources) | |
| AWS (X-Ray) | Yes | Yes | | Yes | |
| Manual Seed | Yes | Yes | Yes | Yes | Yes (fake) |

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

**Week 2: Kubernetes Integration + Manual Seed** - NOT STARTED
- [ ] Implement KubernetesAdapter (services + resource metrics)
- [ ] Create ManualSeedAdapter with fake traffic data
- [ ] Create API endpoints: `POST /api/seed`, `GET /api/topology`
- [ ] Deploy test workloads to kind cluster

**Milestone:** `POST /api/seed` populates database, `GET /api/services` returns discovered services

---

### Phase 2: Pixie Integration

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
- [ ] API endpoints: `POST /api/discover`, `GET /api/topology/{serviceId}`

**Milestone:** `POST /api/discover` triggers traffic capture, topology visible via API

---

### Phase 3: Replay Engine

**Week 5: Validation Environment + Replay**
- [ ] Create validation namespace in K8s
- [ ] Implement pod deployment for control/candidate
- [ ] Implement ReplayEngine - send traffic to both
- [ ] Record response timing and status

**Week 6: Resource Monitoring**
- [ ] Implement ResourceMonitor (poll K8s Metrics API)
- [ ] Store resource samples during replay
- [ ] Implement resource analysis (leak detection)
- [ ] API endpoint: `POST /api/validations`, `GET /api/validations/{id}`

**Milestone:** `POST /api/validations` triggers validation run, results queryable via API

---

### Phase 4: Analysis & Verdicts

**Week 7: Statistical Analysis**
- [ ] Implement Statistics module (Mann-Whitney U, linear regression)
- [ ] Latency comparison (control vs candidate)
- [ ] Error rate comparison
- [ ] Memory trend analysis
- [ ] Generate regressions list

**Week 8: Verdicts + API**
- [ ] Implement ValidationService (orchestrates everything)
- [ ] Generate verdict (pass/fail/inconclusive)
- [ ] Complete validation API endpoints
- [ ] API endpoint: `GET /api/validations/{id}/verdict`

**Milestone:** Full validation with verdict via API: `{"verdict": "FAIL", "reason": "memory leak detected"}`

---

### Phase 5: Hardening

**Week 9: Blast Radius + Baselines**
- [ ] Implement BlastRadiusService
- [ ] Implement BaselineService
- [ ] Implement AnomalyService
- [ ] API endpoints: `GET /api/blast-radius/{serviceId}`, `GET /api/health`

**Week 10: Stabilization**
- [ ] Error handling and retry logic
- [ ] Configuration management
- [ ] Logging and observability
- [ ] End-to-end tests

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
| Traffic capture | Pixie (eBPF) | Zero instrumentation, captures bodies |
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