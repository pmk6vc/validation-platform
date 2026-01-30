# Traffic Replay & Validation Plan

## Problem Statement

Validate code changes against real production traffic before deployment by:
1. Spinning up isolated instances of affected services (control + candidate)
2. Replaying captured traffic against both versions at realistic load
3. Comparing responses, latency, and resource usage

**Key Challenge**: External dependencies (Stripe, managed DBs, third-party APIs) are difficult to replicate. Solution: Consumer-owned test environment policy with sensible defaults.

---

## Incremental Delivery Strategy

**Principle**: Build a solid core (API, data model, replay engine) first. Layer user-facing interfaces on top. The API is the stable contract - interfaces can evolve without breaking the core.

```
┌─────────────────────────────────────────────────────────────────┐
│                    USER INTERFACES (Later)                       │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐ │
│  │ Declarative │  │     PR      │  │    CLI / Dashboard      │ │
│  │  Manifests  │  │  Comments   │  │                         │ │
│  └──────┬──────┘  └──────┬──────┘  └───────────┬─────────────┘ │
└─────────┼────────────────┼─────────────────────┼───────────────┘
          │                │                     │
          └────────────────┼─────────────────────┘
                           │
          ┌────────────────▼────────────────┐
          │         PLATFORM API            │  ◄── Stable contract
          │   POST /api/validations         │
          │   GET  /api/validations/{id}    │
          │   ...                           │
          └────────────────┬────────────────┘
                           │
┌──────────────────────────┼──────────────────────────────────────┐
│                    CORE PLATFORM (First)                         │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐ │
│  │   Unified   │  │   Replay    │  │    Reproducibility      │ │
│  │ Data Model  │  │   Engine    │  │    (stored configs)     │ │
│  └─────────────┘  └─────────────┘  └─────────────────────────┘ │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐ │
│  │   Agent     │  │   Replay    │  │   Verdict Generation    │ │
│  │  (capture)  │  │   Proxy     │  │   (statistics)          │ │
│  └─────────────┘  └─────────────┘  └─────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

---

## Core Design Principles

### 1. Load Testing is First-Class

The platform's value proposition requires realistic load to catch:
- Memory leaks (need sustained traffic)
- N+1 queries (need realistic data volume)
- Connection pool exhaustion (need concurrent connections)
- Cache behavior under load

**Validation fidelity levels**:

| Level | Concurrency | Duration | Catches | Use Case |
|-------|-------------|----------|---------|----------|
| **Quick** | Sequential | ~1 min | Logic bugs, response diffs | Typo fixes, small changes |
| **Standard** | 10-50 concurrent | ~5 min | + Latency regressions, basic resource issues | Most feature work |
| **Load** | Production-rate | 15-30 min | + Memory leaks, pool exhaustion, cache behavior | Performance work, critical paths |

### 2. Consumer-Owned Test Environment

**Policy**: Everything outside the cloned services returns recorded responses by default. Consumer can override with real test infrastructure for higher fidelity.

**What gets cloned**: Only the service(s) explicitly listed in the validation request. Consumer decides scope.

**Everything else**: Recorded responses (mocked), unless consumer provides override.

### 3. Explicit Service Selection (No Automatic Blast Radius)

Consumer explicitly lists which services to validate together:

- **Single service** (most common): Clone target service only
- **Multi-service** (integration): Clone multiple services, traffic flows between them
- **Blast radius**: Remains a separate analysis/visualization tool, not used for validation infrastructure

### 4. Central Replay Proxy

Single proxy deployment handles all egress routing:

```
┌─────────────────────────────────────────────────────────────────┐
│                    Validation Namespace                          │
│                                                                 │
│   ┌──────────┐    ┌──────────┐                                 │
│   │ Control  │    │Candidate │   (Only listed services cloned) │
│   │   Pod    │    │   Pod    │                                 │
│   └────┬─────┘    └────┬─────┘                                 │
│        │               │                                        │
│        └───────┬───────┘                                        │
│                │  HTTP_PROXY                                    │
│        ┌───────▼───────┐                                       │
│        │ Replay Proxy  │                                       │
│        └───────┬───────┘                                       │
│                │                                                │
│    ┌───────────┴───────────┐                                   │
│    ▼                       ▼                                   │
│ Recorded responses    Consumer overrides                       │
│ (everything else)     (test DB, etc.)                          │
└─────────────────────────────────────────────────────────────────┘
```

### 5. Simple Egress Response Matching

No ingress-egress correlation needed. Signature-based lookup:

```
signature = hash(method, url, normalized_body)
recorded_responses[signature] → response
```

Same input → same outbound calls → same recorded responses. Simple.

---

## Implementation Phases

### Foundation Phase: Core Platform

Get the fundamentals right before building user interfaces.

#### F1: Validation Data Model + API

**Goal**: Rich API that can express any validation configuration.

**Deliverables**:
- ValidationRun model with full config support
- `POST /api/validations` - Create with explicit config
- `GET /api/validations/{id}` - Status, progress, results
- `DELETE /api/validations/{id}` - Cancel/cleanup
- Stored configs enable reproducibility (re-run any validation)

**Key**: API accepts complete configuration. No magic, no inference. Explicit is better.

```kotlin
data class ValidationRun(
    val id: String,
    val organizationId: String,
    val clusterId: String,
    val status: ValidationStatus,
    val config: ValidationConfig,       // Complete, stored config
    val progress: ValidationProgress?,
    val verdict: Verdict?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val error: String?
)

data class ValidationConfig(
    // What to validate
    val services: List<ServiceTarget>,

    // Traffic settings
    val traffic: TrafficConfig,

    // Load settings
    val replay: ReplayConfig,

    // Environment (dependencies)
    val environment: EnvironmentConfig
)

data class ServiceTarget(
    val name: String,
    val control: String,                // Version/tag for control
    val candidate: String,              // Version/tag for candidate
    val entryPoint: Boolean = false     // Where traffic enters
)

data class ReplayConfig(
    val fidelity: FidelityLevel,        // QUICK, STANDARD, LOAD
    val duration: Duration?,            // Max duration
    val concurrency: Int?               // Override default
)

data class EnvironmentConfig(
    val defaultMode: DependencyMode,    // RECORD_REPLAY
    val overrides: List<DependencyOverride>
)
```

#### F2: Validation Infrastructure

**Goal**: Provision and teardown validation environments.

**Deliverables**:
- Namespace provisioner (create `validation-{runId}`)
- Pod deployer (control + candidate for each listed service)
- Replay proxy deployment
- Resource limits and quotas
- Cleanup on completion or timeout

**Verification**: Create validation → pods running → delete → namespace gone.

#### F3: Replay Proxy + Recorded Responses

**Goal**: Route egress traffic, return recorded responses.

**Deliverables**:
- RecordedEgressResponse storage (signature → response)
- Replay proxy implementation
- Routing: cloned service → forward, override → test infra, default → recorded
- 503 response for missing recordings (explicit failure, not silent)

**Verification**: Pod calls external API → gets recorded response.

#### F4: Replay Engine

**Goal**: Send captured traffic at configured fidelity.

**Deliverables**:
- Traffic sender with concurrency control
- Fidelity levels (QUICK/STANDARD/LOAD)
- Response collection
- Progress tracking and updates

**Verification**: Replay 1000 requests at STANDARD → results stored.

#### F5: Resource Monitoring + Verdicts

**Goal**: Detect regressions, generate actionable verdicts.

**Deliverables**:
- Resource sampling (K8s Metrics API)
- Response comparison (semantic diff)
- Latency analysis (Mann-Whitney U)
- Leak detection (linear regression)
- Verdict generation with evidence

**Verification**: End-to-end validation → PASS/FAIL with evidence.

---

### Interface Phase: User Experience

Layer user-friendly interfaces on top of the stable API.

#### I1: CLI Tool

**Goal**: Easy local/CI usage.

```bash
# Create validation from explicit config
vp create --config validation.json

# Check status
vp status val-123

# Get verdict
vp verdict val-123

# Cancel
vp cancel val-123
```

#### I2: Declarative Manifests

**Goal**: Versionable, reviewable validation configs.

```yaml
apiVersion: validation.platform.io/v1
kind: ValidationRun
metadata:
  name: api-gateway-integration
spec:
  services:
    - name: api-gateway
      control: current
      candidate: ${CANDIDATE_VERSION}
      entryPoint: true
    - name: user-service
      candidate: current
  traffic:
    source: recent
    lookback: 1h
  replay:
    fidelity: standard
  environment:
    default: record_replay
    overrides:
      - match: postgres.internal
        mode: consumer_provided
        target: postgres-test.internal
```

CLI generates API request from manifest:
```bash
vp apply -f validation.yaml --set CANDIDATE_VERSION=pr-123
```

#### I3: Repository Templates

**Goal**: Org-wide defaults, reusable patterns.

```
.validation/
├── defaults.yaml       # Applied to all validations
├── integration.yaml    # Multi-service template
└── load-test.yaml      # Heavy load template
```

#### I4: PR Integration

**Goal**: Zero-config for common cases.

- Auto-detect changed services from PR
- Generate validation config using defaults + templates
- Post results as PR comment
- Override via PR comments: `/validate fidelity=load`

---

## API Endpoints (Foundation)

```
# Core CRUD
POST   /api/validations                    Create with explicit config
GET    /api/validations                    List (paginated, filterable)
GET    /api/validations/{id}               Status + progress
DELETE /api/validations/{id}               Cancel/cleanup

# Results
GET    /api/validations/{id}/verdict       Verdict with evidence
GET    /api/validations/{id}/results       Detailed comparison results
GET    /api/validations/{id}/resources     Resource samples over time

# Replay (for debugging/analysis)
POST   /api/validations/{id}/replay        Re-run with same config
GET    /api/validations/{id}/config        Get stored config (for reproduction)
```

### Example: Create Validation (API)

```json
POST /api/validations
{
  "services": [
    {
      "name": "api-gateway",
      "control": "v1.2.3",
      "candidate": "pr-456-abc123",
      "entryPoint": true
    }
  ],
  "traffic": {
    "source": "time_range",
    "start": "2026-01-30T00:00:00Z",
    "end": "2026-01-30T01:00:00Z"
  },
  "replay": {
    "fidelity": "standard"
  },
  "environment": {
    "default": "record_replay",
    "overrides": [
      {
        "match": "postgres.internal",
        "mode": "consumer_provided",
        "target": "postgres-test.internal"
      }
    ]
  }
}
```

Response:
```json
{
  "id": "val-123",
  "status": "PENDING",
  "config": { ... },  // Stored for reproducibility
  "createdAt": "2026-01-30T10:00:00Z"
}
```

---

## Data Models

### ValidationConfig (Stored for Reproducibility)

```kotlin
data class ValidationConfig(
    val services: List<ServiceTarget>,
    val traffic: TrafficConfig,
    val replay: ReplayConfig,
    val environment: EnvironmentConfig
)

data class ServiceTarget(
    val name: String,
    val control: String,
    val candidate: String,
    val entryPoint: Boolean = false
)

data class TrafficConfig(
    val source: TrafficSource,
    val start: Instant? = null,
    val end: Instant? = null,
    val lookback: Duration? = null,
    val sampleSize: Int? = null
)

enum class TrafficSource { TIME_RANGE, RECENT, SAMPLE }

data class ReplayConfig(
    val fidelity: FidelityLevel,
    val duration: Duration? = null,
    val concurrency: Int? = null
)

enum class FidelityLevel { QUICK, STANDARD, LOAD }

data class EnvironmentConfig(
    val default: DependencyMode = DependencyMode.RECORD_REPLAY,
    val overrides: List<DependencyOverride> = emptyList()
)

data class DependencyOverride(
    val match: String,              // Host pattern
    val mode: DependencyMode,
    val target: String? = null
)

enum class DependencyMode {
    RECORD_REPLAY,
    CONSUMER_PROVIDED,
    CLONE                          // Clone another service (for integration)
}
```

### Verdict

```kotlin
data class Verdict(
    val outcome: VerdictOutcome,
    val summary: String,
    val byService: Map<String, ServiceVerdict>,
    val statistics: ValidationStatistics
)

data class ServiceVerdict(
    val outcome: VerdictOutcome,
    val regressions: List<Regression>
)

enum class VerdictOutcome { PASS, FAIL, INCONCLUSIVE }

data class Regression(
    val type: RegressionType,
    val severity: Severity,
    val description: String,
    val evidence: Map<String, Any>
)
```

---

## File Structure

```
src/main/kotlin/com/platform/
├── models/
│   └── validation/
│       ├── ValidationRun.kt
│       ├── ValidationConfig.kt
│       ├── ServiceTarget.kt
│       ├── ReplayConfig.kt
│       ├── EnvironmentConfig.kt
│       ├── Verdict.kt
│       └── Regression.kt
├── database/
│   ├── ValidationRepository.kt
│   ├── ReplayResultRepository.kt
│   ├── RecordedEgressRepository.kt
│   └── ResourceSampleRepository.kt
├── features/
│   └── validation/
│       ├── ValidationService.kt
│       ├── NamespaceProvisioner.kt
│       ├── PodDeployer.kt
│       ├── ReplayEngine.kt
│       ├── ReplayProxy.kt
│       ├── ResourceMonitor.kt
│       ├── ResponseComparator.kt
│       └── VerdictGenerator.kt
├── api/
│   └── ValidationRoutes.kt
└── statistics/
    ├── MannWhitneyU.kt
    └── LinearRegression.kt
```

---

## Key Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Delivery order | Core first, interfaces later | API is stable contract; UX can evolve |
| Service selection | Explicit, not automatic | Consumer controls scope; no magic |
| Blast radius | Visualization only | Useful for humans, not for validation infra |
| Config storage | Full config stored | Enables reproducibility |
| Load testing | First-class | Core value prop requires realistic load |
| Dependencies | Record/replay default | Simple; consumer overrides for fidelity |

---

## Reproducibility

Every validation stores its complete config:

```bash
# Get config from past run
GET /api/validations/val-123/config

# Re-run with same config
POST /api/validations/val-123/replay

# Or: get config, modify, create new run
POST /api/validations
{ ...modified config... }
```

This enables:
- Debugging: "Why did this fail? Let me re-run."
- Regression: "Run the same validation against new candidate."
- Auditing: "What exactly was tested before this deploy?"

---

## Prerequisites

1. **Agent + Traffic Capture**: Need captured requests + egress responses
2. **Pixie Egress Capture**: Validate this works reliably
3. **Cluster Registration**: Need cluster context for deployments

---

## Open Questions

1. **Non-HTTP egress**: Consumer must provide test infra (no protocol-specific recording v1)
2. **Recorded response staleness**: Warn if > N days old
3. **Multi-cluster**: Out of scope for v1

---

## Out of Scope (V1)

- Automatic blast radius for validation (keep as visualization)
- Non-HTTP traffic (WebSocket, gRPC, database protocols)
- Async message replay (Kafka, RabbitMQ)
- Multi-cluster validation
- Automatic test infrastructure provisioning