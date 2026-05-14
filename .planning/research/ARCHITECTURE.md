# Architecture Research

**Domain:** Hosted multi-tenant SaaS for production-traffic capture, replay, and statistical verdicts (brownfield modular monolith on Kotlin/Ktor + Go tap + greenfield React dashboard)
**Researched:** 2026-05-13
**Confidence:** HIGH on existing-surface integration (codebase already in repo); MEDIUM on new-component decomposition (drawn from established SaaS patterns + the project's stated constraints); LOW only on the GitHub App queuing question, which is flagged.

This document maps the v1 capability set in `.planning/PROJECT.md` onto the existing brownfield architecture in `.planning/codebase/ARCHITECTURE.md`. It is not a fresh design exercise — it is opinionated guidance on where each new piece of v1 lands inside the existing module boundary discipline, and a build order that respects dependency direction.

## Standard Architecture

### System Overview

```
                            CUSTOMER PRODUCTION CLUSTER (GKE)
┌──────────────────────────────────────────────────────────────────────────────────┐
│  Go tap (DaemonSet, eBPF)        Go agent (Deployment)                           │
│  - HTTP/1.1 + HTTP/2 + gRPC      - K8s informer → service registration           │
│  - cgroup_id → pod metadata      - Config polling                                │
│  - L7 dissection                 - Batches → collector                           │
│                                  - Replay-execution worker (Phase 4+)            │
└─────────────────┬───────────────────────────────┬────────────────────────────────┘
                  │ JWT bearer                    │ JWT bearer
                  ▼                               ▼
═══════════════════════════════════════════════════════════════════════════════════
                       HOSTED SAAS CONTROL PLANE (Cloud Run + Cloud SQL)
═══════════════════════════════════════════════════════════════════════════════════

  ┌─────────────────────────┐    ┌──────────────────────────┐    ┌──────────────┐
  │  platform (8080)        │    │  collector (8081)        │    │  dashboard   │
  │  - Organizations        │    │  - CapturedInputs (POST) │    │  (static)    │
  │  - Services             │    │  - Storage interface     │    │  React+Vite  │
  │  - Users + Memberships  │◀───│  - Redaction pipeline    │    │  served via  │
  │  - JWKS (multi-kid)     │    │  - Quota enforcement     │    │  Cloud Run   │
  │  - Onboarding state     │    │                          │    │  + nginx     │
  │  - Agent config         │    │                          │    │              │
  │  - Slack/GitHub installs│    │                          │    │              │
  └────────┬────────────────┘    └────────────┬─────────────┘    └──────┬───────┘
           │                                  │                          │
           │  ┌───────────────────────────────┴──────────────────────────┘
           │  │                            JWT auth (user OR agent principal)
           ▼  ▼
  ┌─────────────────────────────────────────────────────────────────────────────┐
  │                         orchestrator (new module, 8082)                      │
  │   POST /api/validations  →  Saga: capture-window → baseline → deploy →       │
  │                                  candidate → compare → verdict               │
  │   - State machine in Postgres (idempotent, resumable)                        │
  │   - Drives replay-engine + observer + comparison                             │
  └────────┬────────────────────────────────────────────────────────────────────┘
           │
   ┌───────┼───────────────────────┬────────────────────────┐
   ▼       ▼                       ▼                        ▼
┌────────┐ ┌────────────┐  ┌──────────────────┐  ┌─────────────────────┐
│ replay │ │ observer   │  │ comparison       │  │ integrations        │
│ -engine│ │ (passive)  │  │ - response diff  │  │ - github-app        │
│  - dis-│ │ - Kubeshark│  │ - latency M-W U  │  │   (webhook → queue) │
│  patch │ │   tap in   │  │ - error rate     │  │ - slack             │
│  - to  │ │   staging  │  │ - mem regression │  │ - email             │
│  agent │ │ - K8s      │  │ - verdict        │  │                     │
│        │ │   metrics  │  │                  │  │                     │
└────────┘ └────────────┘  └──────────────────┘  └─────────────────────┘
           │                       │
           ▼                       ▼
  ┌─────────────────────────────────────────────────────┐
  │  Cloud SQL Postgres (single instance, RLS-enforced) │
  │  Module-owned tables; tenant_id (= organizationId)  │
  │  on every multi-tenant table                        │
  └─────────────────────────────────────────────────────┘

                              CUSTOMER STAGING CLUSTER
┌──────────────────────────────────────────────────────────────────────────────────┐
│  target service       ←───  replay traffic (driven by agent or direct egress)    │
│  staging-db, kafka                                                               │
│  Kubeshark / tap (observation only — outbound conn counts, call patterns)        │
└──────────────────────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Status | Responsibility | Where it lives |
|-----------|--------|----------------|----------------|
| `platform` | existing — extends | Orgs, services, users, memberships, JWKS (multi-kid), agent config, onboarding state, GitHub-install records, Slack-install records | `platform/` (existing module, port 8080) |
| `collector` | existing — extends | Captured-input ingestion; storage backend interface; redaction; quota | `collector/` (existing module, port 8081) |
| `agent` | existing — language port | K8s informer, config polling, traffic batching, replay execution worker | Rewritten in Go (`agent/` becomes Go module; eventually replaces Kotlin agent) |
| `tap` | existing — extends | eBPF L7 capture: HTTP/1.1 + HTTP/2 + gRPC | `tap/` (Go, DaemonSet) |
| `orchestrator` | new | `POST /api/validations` saga: window → baseline → candidate → compare → verdict; durable state machine in Postgres | `orchestrator/` (new Ktor module, port 8082) |
| `replay-engine` | new | Dispatch captured inputs to a staging target at `sequential` or `actual` fidelity; collects responses | Lives **inside `orchestrator/`** initially; carve out only if it grows |
| `observer` | new | Passive collection: Kubeshark/tap outbound counts; K8s Metrics API CPU/mem samples | Lives **inside `orchestrator/`** initially |
| `comparison` | new | Stateless library: response diff, Mann-Whitney U latency, error-rate, linear-regression memory | Library module (`comparison/`) consumed by `orchestrator/`; no own service |
| `verdict` | new | PASS / FAIL / INCONCLUSIVE rollup with per-dimension evidence | Table + repository inside `orchestrator/` (NOT a separate service) |
| `github-app` | new | Receives GitHub webhooks; posts Check Runs + PR comments; queues async work | New module `github-app/` (Ktor, dedicated public endpoint, separate Cloud Run service) |
| `slack-integration` | new | Outbound posts to Slack channel webhook URLs | Library module consumed by `orchestrator/` + `github-app/`; no inbound endpoint in v1 |
| `dashboard` | new (greenfield) | Primary user surface; auth-gated CSR; consumes the existing JWT API surface | `dashboard/` (Vite + React + TS); static bundle on Cloud Run/nginx |
| `shared` | existing — extends | JWT auth (multi-kid), DatabaseFactory, RLS context setter, redaction policy types, value classes | `shared/` (existing) |

### Control Plane vs Data Plane

This is a critical distinction for the architecture and informs the build order.

**Control plane** (hosted, low volume, latency-tolerant, must always be available for onboarding):
- `platform`, `orchestrator`, `github-app`, `dashboard`. All reads/writes hit Cloud SQL. RLS enforced. Bursts measured in single-digit RPS per tenant.

**Data plane** (hosted, high volume, latency-sensitive, must be available for capture but capture can briefly degrade without losing the customer):
- `collector` ingest endpoint. Bursts measured in 100s-1000s RPS per tenant. Backpressure must not propagate to the customer's production cluster — the agent has its own bounded channel; the collector should fail fast under overload, not buffer indefinitely.

**Customer-side data plane** (in customer's GKE, the hosted system never reaches in):
- `tap` (DaemonSet) and `agent` (Deployment). All egress to hosted; no inbound from hosted. Replay execution either runs from the agent (egress only, customer's own egress to their staging cluster) or from a hosted worker that has VPC peering / network egress to the staging cluster — see "Replay dispatch location" below.

**Why this matters for the build order:** the data plane is already working end-to-end in the brownfield code. Phase 1 (capture cutover) replaces the data-plane implementation under stable wire contracts. Everything new in v1 is in the control plane on top of an already-working data plane.

## Recommended Project Structure

The existing layout in `.planning/codebase/STRUCTURE.md` is sound. The diff for v1:

```
validation-platform/
├── shared/                      # extend: add RLS context, redaction types, multi-kid JWT
├── platform/                    # extend: users, memberships, onboarding, GitHub-install, Slack-install
├── collector/                   # extend: pluggable storage interface, redaction pipeline, quotas
├── orchestrator/                # NEW Kotlin Ktor module (port 8082)
│   ├── api/                     #   POST /api/validations, GET /api/validations/{id}
│   ├── saga/                    #   ValidationSaga state machine
│   ├── replay/                  #   ReplayDispatcher + fidelity strategies
│   ├── observer/                #   K8s Metrics + Kubeshark/tap pollers
│   ├── verdict/                 #   Verdict + Evidence DTOs + repo
│   └── database/                #   replay_runs, validations, verdicts tables
├── comparison/                  # NEW Kotlin library module (pure functions, no server)
│   ├── responsediff/
│   ├── latency/                 #   Mann-Whitney U
│   ├── errorrate/
│   └── memory/                  #   linear regression
├── github-app/                  # NEW Kotlin Ktor module (separate Cloud Run service)
│   ├── api/                     #   POST /webhooks/github (HMAC-verified)
│   ├── queue/                   #   in-Postgres queue or Pub/Sub
│   └── client/                  #   GitHub REST client (Check Runs, comments)
├── agent/                       # REPLACE Kotlin → Go during Phase 1 (TAP-4 .. TAP-8)
├── tap/                         # extend: HTTP/2 + HPACK + gRPC framing
├── dashboard/                   # NEW frontend (Vite + React + TS + Tailwind + TanStack)
│   ├── src/
│   │   ├── routes/              #   TanStack Router file-based
│   │   ├── features/            #   org/services/captures/validations/onboarding
│   │   ├── lib/api/             #   typed client over the platform + collector + orchestrator APIs
│   │   └── lib/auth/            #   JWT acquisition + refresh
│   └── vite.config.ts
├── e2e-tests/                   # extend: orchestrator + dashboard + github-app paths
└── shared/src/main/resources/db/migration/
    ├── V0008__create_users_and_memberships.sql
    ├── V0009__add_organization_id_to_all_tenant_tables.sql    # RLS prereq
    ├── V0010__enable_rls_on_all_tenant_tables.sql
    ├── V0011__create_validations_and_verdicts.sql
    ├── V0012__create_replay_runs.sql
    ├── V0013__create_github_installations.sql
    ├── V0014__create_slack_installations.sql
    ├── V0015__create_jwt_signing_keys.sql                     # multi-kid rotation
    └── V0016__create_onboarding_state.sql
```

### Structure Rationale

- **`orchestrator/` as a new module, not bolted onto `platform/`:** The validation saga has its own lifecycle (long-running, durable, restartable on Cloud Run cold start), its own tables (`validations`, `replay_runs`, `verdicts`), and a different access pattern (background workers reading queue tables). Mixing it into `platform/` would muddy a module that's currently a clean CRUD surface.
- **`replay-engine` and `observer` as packages inside `orchestrator/`, not separate modules:** Both are driven exclusively by the saga and have no independent API surface. Carving them out is premature; do it only when one of them needs independent scaling (it won't in v1).
- **`comparison/` as a pure library:** No I/O, no DB, no HTTP. Just functions over inputs. Testable in isolation. Consumed by `orchestrator/` via direct import — this is the one place a cross-module dependency is fine because `comparison/` owns no state and exposes only functions.
- **`github-app/` as a separate Ktor module on a separate Cloud Run service:** GitHub webhooks need a public endpoint with HMAC validation (not JWT). Putting that on `platform/` would mix two auth models on one service and increase the blast radius if the webhook receiver is overloaded by a noisy customer. Separate Cloud Run service, separate scaling profile.
- **`dashboard/` is greenfield and ecosystem-isolated:** Node-at-build-time, no Node in production. Static bundle served by nginx on Cloud Run. The dashboard talks to `platform`, `collector`, `orchestrator` directly via the existing JWT — no BFF in v1.
- **Migrations stay in `shared/`:** Schema is one logical thing even if owned by multiple modules. Continue the `V0001..V000N` sequence. Each migration documents which module's tables it touches.

## Architectural Patterns

### Pattern 1: Module-owned tables with HTTP-only cross-module access

**What:** Each Kotlin module owns its tables and the only repository that touches them. Cross-module reads go through the owning module's REST API.
**When to use:** Already the convention (V0006 removed the last cross-module FK). Continue for all new tables.
**Trade-offs:** Extra HTTP hop for cross-module reads (e.g. `orchestrator` fetching captured inputs from `collector`). Net: this is the right call — it keeps the modular monolith carve-out-able if a single component ever needs to become a service.

**Example for v1:**
```kotlin
// orchestrator fetching captured inputs — NOT importing CapturedInputRepository
class ReplayInputFetcher(private val collectorClient: CollectorHttpClient) {
    suspend fun fetch(serviceId: ServiceId, window: TimeWindow): List<CapturedInput> =
        collectorClient.list(serviceId = serviceId, since = window.start, until = window.end)
}
```

### Pattern 2: Saga state machine for validation orchestration

**What:** `POST /api/validations` returns immediately with an ID. State machine driven by background workers polls Postgres for pending steps, runs them, persists state, repeats. Each step is idempotent.
**When to use:** Any multi-step workflow that crosses (a) external systems (customer's K8s, GitHub, Slack), (b) Cloud Run cold starts, (c) human-perceivable time (minutes).
**Trade-offs:** More complex than a synchronous `runBlocking { capture(); baseline(); candidate(); compare() }`. But synchronous fails the moment Cloud Run scales the worker to zero mid-run. Saga is required.

**Example shape:**
```kotlin
enum class ValidationStep { PENDING, CAPTURE_WINDOW_SELECTED, BASELINE_REPLAY_RUNNING,
                            BASELINE_REPLAY_DONE, CANDIDATE_DEPLOYED, CANDIDATE_REPLAY_RUNNING,
                            CANDIDATE_REPLAY_DONE, COMPARISON_DONE, VERDICT_POSTED, FAILED }

// ValidationsTable: id, org_id, current_step, last_transition_at, error?
// Worker: SELECT FOR UPDATE SKIP LOCKED WHERE current_step IN (...pending steps...) LIMIT 10
// Each handler is idempotent: re-running CANDIDATE_DEPLOYED produces same state.
```

Postgres `SELECT ... FOR UPDATE SKIP LOCKED` is the idiomatic queue primitive for this scale (50 validation runs/day per tenant — single-digit RPS aggregate). Do not introduce a separate queue (Pub/Sub, Cloud Tasks) in v1.

### Pattern 3: Row-level security as belt-and-braces on app-layer tenancy

**What:** Every tenant-scoped table gets a `tenant_id` column (= `organization_id`) and an RLS policy `USING (organization_id = current_setting('app.current_org')::uuid)`. The application sets `app.current_org` from the JWT principal at the start of every transaction.
**When to use:** All multi-tenant tables. Singleton tables (e.g. `jwt_signing_keys`) are bypass-only.
**Trade-offs:** RLS is two orders of magnitude slower without a leading composite index `(organization_id, ...)`. Every existing index must be reviewed and rebuilt with `organization_id` first. This is the dominant cost of the retrofit.

**Example retrofit pattern:**
```sql
-- V0009: prerequisite — every tenant table must have organization_id (already true for
-- services, captured_inputs after V0007). For any new table, organization_id is added in
-- the same migration that creates it.

-- V0010: enable RLS, no force, with a single org-scoped policy
ALTER TABLE services ENABLE ROW LEVEL SECURITY;
CREATE POLICY services_org_isolation ON services
    USING (organization_id = current_setting('app.current_org', true)::uuid);

-- Application side: shared/ adds a Ktor interceptor that wraps every authenticated
-- request in `SET LOCAL app.current_org = '<jwt.organizationId>'` before the route runs.
```

**Critical:** retrofit must roll out behind a feature flag per table (`rls_enabled_services`, etc.). Enable RLS one table at a time, soak for 24h, watch error rate and p99. The #1 RLS retrofit failure mode is "we enabled it everywhere and a query that worked yesterday now returns zero rows because the context wasn't set on a background job." Sources: [Permit.io RLS guide](https://www.permit.io/blog/postgres-rls-implementation-guide), [thenile.dev RLS](https://www.thenile.dev/blog/multi-tenant-rls), [AWS multi-tenant RLS](https://aws.amazon.com/blogs/database/multi-tenant-data-isolation-with-postgresql-row-level-security/).

### Pattern 4: JWKS-driven multi-kid JWT rotation

**What:** Every issued JWT carries a `kid` header. The platform `jwt_signing_keys` table holds N (typically 2) active keys: one `signing` (used for new tokens) and one `validating-only` (no longer signs, still verifies for the grace period). Rotation: generate a new key, mark it `signing`, demote the old one to `validating-only`, wait `2 × max_token_TTL`, delete the old one.
**When to use:** Always in production for multi-tenant. Replaces the current single-key-in-env-var approach.
**Trade-offs:** Code change in `installJwtAuth()` (must look up by `kid`, not assume one key); database round-trip on JWT validation (mitigate with an in-process cache invalidated by a polled version column on `jwt_signing_keys`); a JWKS endpoint that publishes all currently-valid public keys keyed by `kid`.

**Example interface:**
```kotlin
interface JwtKeyStore {
    fun signingKey(): SigningKey               // exactly one
    fun validatingKeys(): List<ValidatingKey>  // signing key + grace-period keys
}
// installJwtAuth(keyStore) — reads kid header, looks up the matching validating key, verifies.
// JWKS route iterates validatingKeys() and publishes public-only JWKs with the kid.
```

Sources: [JWKS zero-downtime rotation](https://www.davidsulc.com/blog/jws-apis-jwks-basics), [Auth0 key rotation](https://auth0.com/docs/get-started/tenant-settings/signing-keys/rotate-signing-keys), [Zalando JWK automation](https://engineering.zalando.com/posts/2025/01/automated-json-web-key-rotation.html).

### Pattern 5: Pluggable storage interface in the collector

**What:** The collector's `CapturedInputRepository` becomes one implementation of a `CapturedInputStorage` interface. The interface is the contract; the concrete is selected per organization (or per collector instance, depending on isolation needs).

**Interface shape:**
```kotlin
interface CapturedInputStorage {
    suspend fun create(organizationId: OrganizationId, batch: List<CapturedInput>): Int
    suspend fun list(organizationId: OrganizationId, filter: ListFilter): Page<CapturedInput>
    suspend fun findById(organizationId: OrganizationId, id: CapturedInputId): CapturedInput?
    suspend fun deleteByService(organizationId: OrganizationId, serviceId: ServiceId): Int
    suspend fun countByService(organizationId: OrganizationId, serviceId: ServiceId): Long
}

class HostedPostgresStorage(...) : CapturedInputStorage   // default; current implementation
class CustomerPostgresStorage(...) : CapturedInputStorage // BYO Postgres URL + creds
class S3PlusPostgresMetadataStorage(...) : CapturedInputStorage // bodies on S3, metadata on Postgres
```

**Selection model:** the simplest v1 choice is **collector-wide configuration, not per-tenant**. The collector reads `STORAGE_BACKEND={hosted-postgres|customer-postgres|s3+pg}` at startup. A customer who wants their data in their boundary gets a dedicated collector instance pointed at their Postgres (their Cloud Run, their VPC, their credentials).

**Why not per-tenant in v1:** per-tenant routing means the single hosted collector connects to N different customer databases, holds N credential sets, and inherits N failure modes. The interface is ready for per-tenant later; ship per-instance first.

**When to use:** Any storage operation that needs to plug different backends.
**Trade-offs:** Forces every storage call through the interface (no shortcut imports of the Postgres repo). RLS lives only in the `HostedPostgresStorage` implementation — `CustomerPostgresStorage` doesn't have RLS (it's a single-tenant deployment by construction).

### Pattern 6: GitHub App with HMAC-verified webhook receiver + async work queue

**What:** GitHub posts webhooks to a single public endpoint with an HMAC signature. The receiver verifies HMAC, looks up the installation → organization mapping, writes a row to `github_webhook_events` (Postgres table acting as a queue), and returns 202 immediately. A worker polls the queue, calls back to GitHub (Check Runs API, PR comments API) using an installation token.
**When to use:** Always for GitHub Apps. Inline processing inside the webhook handler will eventually 504 GitHub out, and they'll start dropping deliveries.
**Trade-offs:** Two-hop latency (webhook → queue → worker → GitHub callback). Acceptable; GitHub Check Runs are not sub-second-sensitive.

**Tenant resolution:** GitHub installation_id is the routing key. `github_installations` table maps installation_id → organization_id. The webhook receiver looks up the row, sets `app.current_org`, then writes to the queue under that org's RLS scope. Sources: [GitHub webhook architecture](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/using-webhooks-with-github-apps), [Async webhook handling](https://docs.github.com/en/webhooks).

### Pattern 7: Dashboard as auth-gated CSR consumer of existing JWT surface

**What:** Vite + React static bundle. On first load, redirects to platform's signup/login. Platform issues an RS256 JWT (same algorithm, same key store as agent JWTs — different audience claim). Dashboard stores JWT in memory + httpOnly refresh cookie. All API calls are direct: `dashboard → platform`, `dashboard → collector`, `dashboard → orchestrator`. No BFF.

**Auth surface delta from today:**
- Today: JWT principal is `AgentIdentity(organizationId, cluster, role?)`. Cluster is required.
- Dashboard: JWT principal is a **user**, not an agent. Needs a `user_id` claim, an `organizationId` claim, and a role. The `cluster` claim does not apply.
- Solution: extend `AgentIdentity` to a sealed hierarchy:
  ```kotlin
  sealed interface CallerIdentity {
      val organizationId: OrganizationId
      data class Agent(...) : CallerIdentity   // existing AgentIdentity rename
      data class User(...) : CallerIdentity    // dashboard sessions
      data class Service(...) : CallerIdentity // orchestrator-to-collector internal calls
  }
  ```
  `installJwtAuth()` populates the right variant from claims. Routes that require an agent context (`POST /api/services`, `POST /api/captured-inputs`) explicitly check `caller is Agent`. Routes that the dashboard uses don't.

**Why not a BFF:** Adding a BFF doubles the deployment surface and the auth surface for a v1 where the API is already shaped right. Add a BFF in V2 if multi-API coordination starts dominating the dashboard codebase. Don't add it preemptively.

## Data Flow

### Primary Flow 1: Capture (production → hosted, continuous)

```
Customer's prod K8s pod
    │
    ▼ (TCP send/recv intercepted by eBPF)
tap (DaemonSet) — extracts L7 framing, attributes via cgroup_id → pod metadata
    │
    ▼ (unix socket or in-cluster gRPC)
agent (Deployment) — batches, samples per DynamicConfig, gzip-compresses
    │
    ▼ (HTTPS POST /api/captured-inputs with JWT bearer)
collector (Cloud Run) — JWT validate, stamp organization_id, redact headers/PII,
                       quota-check, pass to CapturedInputStorage
    │
    ▼ (SQL INSERT under RLS scope)
Cloud SQL captured_inputs table
```

Existing today: the post-tap path works under wire contracts. Phase 1 swaps Kubeshark → tap and Kotlin agent → Go agent under those same wire contracts.

### Primary Flow 2: Validation (orchestrated, multi-step, durable)

```
User (dashboard) OR github-app
    │
    ▼ POST /api/validations { service_id, candidate_image, fidelity }
orchestrator — INSERT INTO validations (...) WITH current_step='PENDING'
    │
    ▼ returns { validation_id, status: PENDING }
                                                    ┌──────────────────────────┐
                                                    │  background worker loop  │
                                                    │  every ~5s:              │
                                                    │  SELECT ... FOR UPDATE   │
                                                    │  SKIP LOCKED             │
                                                    └────────┬─────────────────┘
                                                             │
                                  ┌──────────────────────────┼──────────────────────────┐
                                  ▼                          ▼                          ▼
                          CAPTURE_WINDOW_           BASELINE_REPLAY_              CANDIDATE_DEPLOYED
                          SELECTED                  DONE                          (deploy candidate)
                              │                         │                              │
                              ▼                         ▼                              ▼
                  fetch captured_inputs       replay against staging           replay against staging
                  from collector              (target = current image)         (target = candidate image)
                              │                         │                              │
                              └─────────────────────────┴──────────────────────────────┘
                                                        ▼
                                              COMPARISON_DONE
                                              comparison/ library runs:
                                              - response diff
                                              - Mann-Whitney U on latency
                                              - error-rate test
                                              - linear regression on memory
                                              writes verdicts row
                                                        ▼
                                              VERDICT_POSTED
                                              push to: github-app (Check Run)
                                                       slack (channel webhook)
                                                       dashboard (via polling/SSE)
```

### Primary Flow 3: Onboarding (signup → first verdict)

```
User on landing page (dashboard)
    │
    ▼ "Sign up with Google" (OAuth)
platform — creates user; if first user for the email's domain, creates organization;
           writes onboarding_state(org_id, current_step='ORG_CREATED')
    │
    ▼ redirects to dashboard /onboarding
dashboard — renders step 1: "Install the agent"
    │ shows Helm command pre-filled with the org's API key
    │ (API key minted on-demand from platform; stored hashed)
    ▼
User runs Helm command in their cluster
    │
    ▼ agent registers first service
platform — POST /api/services succeeds; onboarding worker observes new service,
           transitions to step 2: 'FIRST_SERVICE_REGISTERED'
    │
    ▼ dashboard polls onboarding_state, advances UI
User configures GitHub App (one click)
    │
    ▼ github-app installation webhook fires
github-app — links installation_id to org_id, transitions to step 3
    │
    ▼
Wait for first capture batch to land — collector observes first row, transitions to step 4
    │
    ▼
Dashboard shows "Run your first validation" CTA — POST /api/validations
    │
    ▼ orchestrator runs the saga; on VERDICT_POSTED, transitions to step 5 = 'DONE'
```

The `onboarding_state` table is the source of truth. The dashboard renders state, not events. This is resumable across signin sessions, browser closes, and Cloud Run cold starts.

### Cross-cutting flow: every request sets the RLS scope

```
HTTP request
    │
    ▼ Ktor authenticate { jwt { ... } }   — populates CallerIdentity
    │
    ▼ Ktor interceptor (new)              — Exposed: newSuspendedTransaction {
    │                                        SET LOCAL app.current_org = '<organizationId>'
    │                                        <route handler runs here>
    │                                      }
    ▼
Route handler — Repository calls run inside the txn with RLS scope set
```

The interceptor is the single chokepoint. It's the only code that sets `app.current_org`. Background workers (orchestrator saga, github-app queue worker) set it explicitly per work item. Forgetting to set it = queries return zero rows under RLS — a fail-closed mode (not a fail-open one), which is the right default.

## Build Order

The phases below are dependency-driven; the roadmapper assigns final numbers. Each phase produces something demonstrable; later phases assume earlier ones landed.

### Phase 1: Native capture cutover (TAP-3..TAP-8)
**Already scoped in PROJECT.md.** Replaces the Kubeshark + Kotlin agent capture path with Go tap + Go agent, under the existing wire contracts. No new control-plane work. Lands the data-plane foundation that everything else assumes.
**Depends on:** existing brownfield code. No prerequisite v1 work.
**Cross-cutting work it touches:** none — wire contracts unchanged.

### Phase 2: gRPC + HTTP/2 capture
Extends the tap dissector with HPACK + gRPC framing. Pure tap-side work; no control-plane impact.
**Depends on:** Phase 1 (the Go tap path is live).

### Phase 3: Security retrofit foundation — JWT rotation + RLS prerequisites
**This is the cross-cutting work that everything after depends on; doing it early avoids retrofitting once `orchestrator`, `dashboard`, and `github-app` are live.**

3a. **JWT multi-kid rotation.** Add `jwt_signing_keys` table; rewrite `installJwtAuth()` to look up by `kid`; update JWKS endpoint to publish all valid keys; backfill existing single-key as the seed row. Two-week soak before declaring rotation-ready.
3b. **RLS prerequisites.** Audit every tenant-scoped table; ensure each has `organization_id` (V0007 already added it to captured_inputs; sweep the rest). Add the Ktor RLS-context interceptor in `shared/`. Land it as a no-op (sets the variable but no policies use it yet).
3c. **RLS rollout.** Per-table, behind feature flags. Start with the lowest-volume table (organizations). Each table: enable RLS, soak 24h, watch error rate, repeat.
3d. **Header/PII redaction.** Default-deny `Authorization`, `Cookie`, `Proxy-Authorization`, `Set-Cookie`. Configurable allowlist in `DynamicConfig`. Runs in the collector on ingest, before storage.

**Depends on:** Phase 1 (so capture is solid before changing auth/storage semantics).
**Why now and not later:** every component built after this assumes RLS is on and JWT rotation exists. Bolting it on later means rewriting every repository call site.

### Phase 4: Replay engine (lives inside orchestrator/)
Create `orchestrator/` module. Implement `replay-engine` as a package inside it: fetch captured inputs from collector, dispatch to staging target in `sequential` or `actual` fidelity, persist responses to `replay_runs` table. Read-only by default. `POST /api/replay-runs` + `GET /api/replay-runs/{id}`.

**Replay dispatch location decision:**
- **Option A:** Hosted orchestrator dispatches HTTPS requests directly to the customer's staging cluster. Requires the customer to expose staging endpoints to the hosted system (VPC peering, public ingress, or signed URL).
- **Option B:** Agent in the customer cluster pulls a replay job and dispatches locally. No new ingress. Latency between job creation and dispatch.
- **Decision:** Option A for v1 (simpler, direct, no agent code duplication). Document the staging-egress requirement in onboarding. Revisit Option B if a design partner can't open staging egress.

**Depends on:** Phase 3 (RLS must be on for the new tables from the start).

### Phase 5: Observation + comparison + verdict
Build `observer` package (K8s Metrics API polling, Kubeshark/tap egress observation) and `comparison/` library (response diff, M-W U, error rate, linear regression). Wire `replay_runs` → comparison → `verdicts` table.

**Depends on:** Phase 4 (need replay runs to compare).

### Phase 6: Orchestration saga
Wire the full saga: `POST /api/validations` → capture-window → baseline → candidate deploy → candidate replay → comparison → verdict. State machine + `SELECT ... FOR UPDATE SKIP LOCKED` worker.

**Depends on:** Phase 5 (verdict output is the saga's terminal state).

### Phase 7: Web dashboard (greenfield)
Vite + React + TS + Tailwind + TanStack Router/Query + shadcn/ui. CallerIdentity extension landed in `shared/` to support user-type JWTs. Routes: org/services, captured-traffic explorer, validations, verdict drill-in, agent install, settings, onboarding flow.

**Depends on:** Phase 6 (it visualizes the saga's output), Phase 3 (multi-kid JWT supports user tokens).

### Phase 8: Onboarding flow
Signup (Google OAuth), org provisioning, API-key generation, Helm install snippet generation, onboarding-state machine, dashboard onboarding UI. Targets the sub-30-minute first-verdict bar.

**Depends on:** Phase 7 (the dashboard is the onboarding surface).

### Phase 9: GitHub App
New `github-app/` module on its own Cloud Run service. HMAC-verified webhook receiver, in-Postgres event queue, Check Runs + PR comments. `github_installations` table maps installation_id → organization_id.

**Depends on:** Phase 8 (onboarding flow includes the GitHub install step), Phase 6 (a validation's verdict is what gets posted).

### Phase 10: Slack notifications
Outbound-only library consumed by `orchestrator` and `github-app`. Slack-install records in `slack_installations`, channel webhook URLs encrypted at rest.

**Depends on:** Phase 6 (verdict events trigger it).

### Phase 11: Pluggable storage backend
Refactor collector's `CapturedInputRepository` behind `CapturedInputStorage` interface. Ship `HostedPostgresStorage` (default) and `CustomerPostgresStorage` (BYO Postgres URL + creds). Per-instance configuration, not per-tenant.

**Depends on:** Phase 3 (RLS already on, so the refactor doesn't combine with tenancy changes), Phase 1 (capture wire contracts stable).
**Why late:** the abstraction is easy when the feature exists; doing it earlier risks designing the wrong seam.

### Phase 12: Beta operations + observability
Cloud Monitoring metrics for capture rate, registration outcomes, validation throughput; per-customer health view in the dashboard; runbooks; status indicators.

**Depends on:** everything else (it observes them).

### Build-order summary (one line each)

1. **Native capture cutover** — replaces capture path; foundation.
2. **gRPC + HTTP/2 in tap** — extends capture protocol coverage.
3. **JWT rotation + RLS retrofit + redaction** — cross-cutting security; do once, benefit everywhere.
4. **Replay engine** — orchestrator module skeleton; replay-only.
5. **Observation + comparison + verdict** — produces the v1 output.
6. **Orchestration saga** — `POST /api/validations` end-to-end.
7. **Dashboard** — primary user surface; consumes everything above.
8. **Onboarding flow** — closes the self-serve loop.
9. **GitHub App** — moves the decision touchpoint to the PR.
10. **Slack notifications** — closes the alert loop.
11. **Pluggable storage** — addresses skeptical-customer data-boundary concern.
12. **Beta ops** — observability over the whole stack.

## Cross-Cutting Concerns

These cut across many phases. Calling them out explicitly so the roadmapper sequences them right.

### Postgres RLS retrofit
- **Touches:** every tenant-scoped table; every repository; every route; every background worker.
- **Phase:** primarily Phase 3, but every new table from Phase 4 onward is born with RLS on, not retrofitted.
- **Risk:** missing context setter on a background job = silent zero-row queries. Mitigation: a shared test helper in `shared/testFixtures/` that fails any test running a query without `app.current_org` set.
- **Performance:** every existing index needs review for a leading `organization_id` column. Plan a 1-day index audit in Phase 3.

### JWT signing-key rotation
- **Touches:** `shared/auth/JwtAuth.kt`, `platform/JwtTokenGenerator.kt` (now reads the signing key from DB, not env), JWKS route, all token consumers (agent + dashboard + github-app callbacks).
- **Phase:** Phase 3.
- **Risk:** rolling out multi-kid validation before any tokens carry a `kid` means today's tokens (kid-less) must still validate. Mitigation: treat kid-less tokens as kid=`legacy-v1` and seed the signing-keys table with that row.

### Header / PII redaction
- **Touches:** collector ingest path, `DynamicConfig` (agent-side), captured-input model (no schema change — redaction is on-the-fly).
- **Phase:** Phase 3.
- **Risk:** over-aggressive redaction destroys replay fidelity (e.g. stripping a request body field that's actually a query parameter). Mitigation: default-deny on a small, well-known list of header names; everything else allowed; redact body fields only with explicit customer-configured rules.

### CallerIdentity sealed hierarchy (Agent + User + Service)
- **Touches:** `shared/auth/`, every route's principal access, every test using `TestJwtKeys`.
- **Phase:** prerequisite for Phase 7 (dashboard); land in Phase 3 alongside JWT rotation so it's all one change to `installJwtAuth()`.
- **Risk:** existing routes that pattern-match `AgentIdentity` directly will break. Mitigation: do the rename + sealed-class split as a refactor commit before introducing User variant.

### Reversibility / feature flags
- Cutover-style changes (RLS per table, capture-path swap, JWT rotation activation) need flags. Use a simple `feature_flags` table keyed by (flag_name, organization_id?) for org-scoped toggles. Don't introduce LaunchDarkly or similar in v1 — flags are operational, not product.

### Bilingual ecosystem hygiene
- Kotlin (platform, collector, orchestrator, github-app, agent-during-cutover) and Go (tap, agent-post-cutover) and TypeScript (dashboard) are three independent build/CI/test ecosystems. Per `MEMORY.md` rule: don't introduce wrappers. CI runs three separate jobs; PR titles get a scope prefix (`platform:`, `tap:`, `dashboard:`).

## Scaling Considerations

The v1 envelope per design partner (from PROJECT.md): 50 services, ~1k RPS captured, ~50 validation runs/day, 30-day retention. With 2–5 design partners, peak aggregate is ~5k RPS captured.

| Scale | What breaks first | Fix |
|-------|-------------------|-----|
| 1 design partner (~1k RPS capture, ~50 validations/day) | Nothing. Single Cloud Run instance per service + small Cloud SQL handles it. | Ship as-is. |
| 5 design partners (~5k RPS capture aggregate) | Collector ingest CPU (gzip decompression + JSON parsing + RLS-scoped INSERTs). | Scale collector Cloud Run horizontally; verify Cloud SQL pool sizing (Linear `[ARCH-4]` already tracks this). |
| 20 customers / public GA | Cloud SQL connections (instances × pool size), captured_inputs table bloat. | (a) Lower per-instance pool to 4. (b) Partition `captured_inputs` by month + drop partitions for retention. (c) Consider read replica for dashboard queries. |
| 100+ customers | Captured_inputs storage cost; cross-table RLS overhead on the validation/verdict joins. | (a) S3-backed bodies (Phase 11's `S3PlusPostgresMetadataStorage` lands here). (b) Per-tenant materialized views for dashboard summaries. |

The interesting v1 bottleneck is **collector ingest CPU**, not Cloud SQL or the dashboard. Plan a load test on the sandbox cluster as part of Phase 1's exit criteria.

## Anti-Patterns

### Anti-Pattern 1: Microservice-ifying replay/observer/comparison

**What people do:** Make `replay-engine`, `observer`, `comparison`, `verdict` each a separate Cloud Run service from day one.
**Why it's wrong:** They have a single caller (`orchestrator`), no independent scaling needs at v1 volume, and would each need their own JWT auth, Cloud Run config, deploy pipeline, and IAM bindings. The coordination cost dwarfs the benefit.
**Do this instead:** packages inside `orchestrator/`. Carve out to a separate module only if independent scaling becomes real.

### Anti-Pattern 2: BFF for the dashboard from day one

**What people do:** Build a `dashboard-bff` Ktor service that fans out to `platform` + `collector` + `orchestrator` and presents a single API to the React app.
**Why it's wrong:** Doubles the deploy surface, doubles the auth surface, adds a hop on every dashboard call, and the API surfaces are already shaped right for direct consumption. The BFF justification (versioning, API shape mismatch, mobile) isn't here in v1.
**Do this instead:** dashboard talks directly to the three services. Add a BFF in V2 if coordination starts dominating the dashboard codebase.

### Anti-Pattern 3: Pub/Sub or Cloud Tasks for the validation saga

**What people do:** Reach for Pub/Sub or Cloud Tasks to drive the saga's step transitions.
**Why it's wrong:** At ~50 validations/day per tenant (single-digit RPS aggregate), Postgres `SELECT ... FOR UPDATE SKIP LOCKED` is operationally simpler and more debuggable than another GCP service with its own IAM, dead-letter handling, and observability surface.
**Do this instead:** In-Postgres queue table. Revisit at 100+ validations/second (not in v1).

### Anti-Pattern 4: Per-tenant routing in the collector storage layer

**What people do:** Pluggable storage where the collector inspects `organizationId` and routes to one of N storage backends per request.
**Why it's wrong:** Now the hosted collector holds credentials to N customer databases, has N failure modes, and gets weird outage semantics (one customer's DB down = N% errors in the hosted collector). The "data in customer boundary" promise also gets diluted — the data still flows through hosted code.
**Do this instead:** Per-collector-instance configuration. A customer who wants their data in their boundary gets a dedicated collector deployment.

### Anti-Pattern 5: GitHub webhook handler doing work inline

**What people do:** Webhook receiver verifies HMAC, then synchronously runs the work (calls Check Runs API, posts comment) inside the webhook handler.
**Why it's wrong:** GitHub expects sub-10s acks. Inline work eventually times out under load; GitHub starts dropping deliveries; the customer's PR check goes silent.
**Do this instead:** Verify, write to in-Postgres queue, return 202. Worker processes the queue.

### Anti-Pattern 6: RLS without composite indexes

**What people do:** Enable RLS on a table, ship, watch p99 collapse, conclude RLS is too slow, roll it back.
**Why it's wrong:** The RLS policy adds an implicit `WHERE organization_id = $1` to every query. Without a leading `organization_id` column in the index, every query becomes a sequential scan filtered post-hoc.
**Do this instead:** Phase 3 includes an index audit. Every existing index on a tenant-scoped table gets rebuilt as `(organization_id, ...existing columns...)`.

### Anti-Pattern 7: Sharing compile-time types across the language boundary

**What people do:** Auto-generate Kotlin types from Go DTOs or vice versa to keep the agent and platform in sync.
**Why it's wrong:** Locks the two ecosystems together; defeats independent versioning; the bilingual rule in `MEMORY.md` exists for a reason.
**Do this instead:** wire contracts only. Both sides declare their DTOs independently with `ignoreUnknownKeys = true` (Kotlin) and lenient unmarshal (Go). Contract tests in `e2e-tests/`.

## Integration Points

### External Services

| Service | Integration Pattern | Notes |
|---------|---------------------|-------|
| GitHub | OAuth App for user signin; GitHub App for repo integration; webhook receiver with HMAC; installation tokens for callbacks | App must be installed per org; installation_id → organization_id mapping is the routing primitive |
| Slack | Slack App with `chat:write` scope; outbound only in v1 (no slash commands, no interactivity); channel webhook URLs stored encrypted at rest | Per-org install; org may configure multiple channels for different verdict severities post-v1 |
| Google OAuth | OIDC for dashboard signin; map email domain → organization on first signin | Domain-based org claim has known edge cases (gmail.com, generic domains) — require manual org-link for those |
| Customer K8s API | Read-only from the agent (already exists); never from hosted | Customer-provided RBAC scope: `services.get/list/watch`, pods read for metrics observation |
| Customer Kubeshark / tap (staging) | Polled by `observer` during replay runs | Re-uses the same auth model as production-side capture |
| Cloud SQL | Private IP, IAM auth, Postgres protocol | Already in place; RLS adds `SET LOCAL app.current_org` per txn |
| GCP Secret Manager | Stores JWT signing keys (post-Phase 3), Slack webhook URLs, GitHub App private key | Already in repo via `shared/secrets/SecretsProvider.kt` — extend usage |
| Artifact Registry | Container images for platform, collector, orchestrator, agent, github-app | Already wired |

### Internal Boundaries

| Boundary | Communication | Notes |
|----------|---------------|-------|
| dashboard ↔ platform/collector/orchestrator | HTTPS + JWT bearer (user identity) | Direct from browser; CORS allowlist on hosted services |
| orchestrator ↔ platform | HTTPS + JWT bearer (service identity) | Internal-flavored JWT with `Service` CallerIdentity variant |
| orchestrator ↔ collector | HTTPS + JWT bearer (service identity) | Reads captured inputs for replay; no inter-module DB access |
| orchestrator ↔ comparison/ | Direct Kotlin import (library, no state) | The one allowed cross-module compile dependency |
| github-app ↔ orchestrator | HTTPS + JWT bearer (service identity) | github-app posts `POST /api/validations` when a PR is opened with the integration enabled |
| github-app ↔ GitHub | HTTPS, installation token | Token minted from the GitHub App private key per request; cached for ~50min |
| Background worker (any module) ↔ Postgres | JDBC with explicit `SET LOCAL app.current_org` per work item | Workers don't have a JWT; they explicitly scope per item |
| agent ↔ tap | unix socket or in-cluster localhost gRPC | Co-located in the same pod or daemonset+sidecar |
| agent ↔ platform/collector | HTTPS + JWT bearer (agent identity) | Existing wire contracts |

## Sources

- [PostgreSQL Row-Level Security docs](https://www.postgresql.org/docs/current/ddl-rowsecurity.html) — HIGH confidence (official)
- [AWS multi-tenant RLS](https://aws.amazon.com/blogs/database/multi-tenant-data-isolation-with-postgresql-row-level-security/) — HIGH (vendor authoritative, AWS pattern doc)
- [Permit.io RLS implementation guide](https://www.permit.io/blog/postgres-rls-implementation-guide) — MEDIUM (third-party, pitfall coverage)
- [thenile.dev multi-tenant RLS](https://www.thenile.dev/blog/multi-tenant-rls) — MEDIUM
- [JWKS zero-downtime rotation (David Sulc)](https://www.davidsulc.com/blog/jws-apis-jwks-basics) — MEDIUM
- [Auth0 signing key rotation](https://auth0.com/docs/get-started/tenant-settings/signing-keys/rotate-signing-keys) — HIGH (vendor authoritative)
- [Zalando JWK automation](https://engineering.zalando.com/posts/2025/01/automated-json-web-key-rotation.html) — MEDIUM (engineering blog)
- [GitHub Apps webhook architecture](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/using-webhooks-with-github-apps) — HIGH (official)
- [GitHub async webhook handling](https://docs.github.com/en/webhooks) — HIGH (official)
- [Existing codebase analysis](/Users/prathameshkulkarni/repos/validation-platform/.planning/codebase/ARCHITECTURE.md) — HIGH (in-repo)
- [Existing structure](/Users/prathameshkulkarni/repos/validation-platform/.planning/codebase/STRUCTURE.md) — HIGH (in-repo)
- [Existing concerns](/Users/prathameshkulkarni/repos/validation-platform/.planning/codebase/CONCERNS.md) — HIGH (in-repo)
- [Project scope](/Users/prathameshkulkarni/repos/validation-platform/.planning/PROJECT.md) — HIGH (in-repo)

---
*Architecture research for: Validation Platform v1*
*Researched: 2026-05-13*
