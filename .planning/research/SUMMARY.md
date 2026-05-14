# Project Research Summary

**Project:** Validation Platform v1
**Domain:** Hosted B2B SaaS — eBPF production-traffic capture + staging replay + statistical verdict, with self-serve onboarding and PR-time decision surface
**Researched:** 2026-05-13 / 2026-05-14
**Confidence:** HIGH (existing codebase grounded; external sources well-corroborated)

## Executive Summary

This is a verdict product, not an observability product. Every architectural and feature decision is judged against one question: can a developer push a PR and get a trustworthy go/no-go signal in under 30 minutes, self-serve, without any human intervention? The research confirms that the staging-with-real-deps replay model is a defensible moat: every alternative (Speedscale, Diffy, GoReplay) either mocks dependencies (brittle against TLS-encrypted production DBs), routes live traffic (no record-then-replay), or runs synthetic load (misses real edge cases). Capturing production HTTP + gRPC with eBPF, replaying read-only against a staging cluster with real dependencies, and comparing statistically is meaningfully differentiated and technically achievable at v1 scale (2-5 design partners, ~1k RPS per tenant, 50 validation runs/day).

The brownfield codebase (platform, collector, agent, tap) already handles the data plane: JWT auth, service registration, captured-input ingest, and the Go tap foundation. What v1 builds on top is the entire control plane: replay engine, comparison engine, verdict surface, orchestration saga, web dashboard, onboarding flow, GitHub App, Slack notifications, security hardening (RLS, JWT rotation, PII redaction), and pluggable storage. The correct module decomposition is a new `orchestrator/` Ktor module on port 8082 (not bolted onto `platform/`), `comparison/` as a pure stateless library, and `github-app/` as a separate Cloud Run service with its own scaling profile. Every new table from Phase 3 onward is born with RLS and `organization_id` as a leading index column — no retrofitting after the fact.

The dominant execution risk is trust erosion: a verdict system that produces false positives at any detectable rate will be disabled within 48 hours. Three pitfalls with permanent or near-permanent recovery costs (RLS cross-tenant leak, PII honeypot breach, uncalibrated false-positive rate killing the required GitHub Check) must be treated as hard blockers, not quality-of-life improvements. The sequence implication: do security hardening before building any surface that stores new data, and calibrate the comparison engine on null-hypothesis baseline-vs-baseline before any Check Run is enabled as required.

## Key Findings

### Recommended Stack

The backend stack is fixed (Kotlin 2.2.21, Ktor 3.3.3, Exposed 0.57.0, JDK 21, PostgreSQL). Net-new additions: **Hipparchus 4.x** (`hipparchus-stat`) for Mann-Whitney U and linear regression — the actively maintained fork of Apache Commons Math (stale since 2016). **Resilience4j Kotlin 2.2.x** for replay rate-limiting (token-bucket, suspend-fn native). **hub4j github-api 1.327+** for GitHub App auth (JWT to installation token flow; BouncyCastle PEM loading already in stack). **slack-api-client 1.45+** (official SDK, outbound-only, not Bolt). For JWT key rotation: Secret Manager with version aliases and `kid` header — not KMS asymmetric-sign (10-30ms latency overhead not justified for this path). For pluggable storage: `BodyStore` interface with `HostedPostgresStorage` default and `S3PlusPostgresMetadataStorage` variant (AWS SDK v2 — MinIO CE is archived since Feb 2026, do not use).

The frontend is greenfield: **Vite 6 + React 19.2 + TypeScript 5.6+ + TanStack Query v5 + TanStack Router v1.169 + Tailwind 4 + shadcn/ui + Recharts 3** (locked in PROJECT.md). Recharts 3 is the official shadcn/ui chart primitive covering every dashboard shape needed. React Hook Form 7 for sparse forms; Zod 4 for schema validation and typed search params; Vitest 3 + Playwright 1.50+ for tests. No Next.js — the dashboard is an auth-gated CSR app; a Node runtime adds zero value and complicates Cloud Run deployment.

**Core technologies (net-new):**
- `hipparchus-stat:4.0.1` — Mann-Whitney U + linear regression for verdict engine; only maintained fork of Commons Math
- `resilience4j-kotlin:2.2.x` — token-bucket rate limiter for `actual`-mode replay ceiling; suspend-fn native
- `hub4j:github-api:1.327+` — GitHub App JWT + installation token flow; community standard for JVM
- `slack-api-client:1.45+` — outbound-only Slack notifications; official SDK, lighter than Bolt
- `nimbus-jose-jwt:9.40+` — GitHub App RS256 JWT generation (separate from agent JWT)
- AWS SDK v2 `s3` — pluggable body storage; S3-compatible, works against GCS via compat layer
- Google Sign-In via OIDC — user auth for dashboard; no managed auth service in v1
- Vite 6 + React 19.2 + TanStack Router/Query + Tailwind 4 + shadcn/ui + Recharts 3 — full frontend stack

### Expected Features

The verdict loop is the product. Every feature is judged by whether it makes the PASS/FAIL/INCONCLUSIVE call faster, more trustworthy, or more actionable at PR time.

**Must have — table stakes (design partner walks without these):**
- HTTP/REST + gRPC production capture (eBPF tap, Go agent)
- Service registration + topology view
- Replay engine: sequential + `actual` (capped concurrency, not uncapped LOAD)
- Response diff (JSON-aware, noisy-field elision by default)
- Latency comparison with Mann-Whitney U + effect size (not threshold-based)
- Error-rate comparison (status-code class deltas per endpoint)
- PASS / FAIL / INCONCLUSIVE verdict with per-dimension breakdown + evidence
- Orchestration API (`POST /api/validations`) — makes the loop automatic
- GitHub PR Check Run + comment + deep link
- Slack verdict notifications (read-only, outbound-only)
- Self-serve signup + org provisioning + Helm agent install in under 30 minutes
- First-capture confirmation in dashboard
- PII / sensitive-header redaction default-deny
- Captured-traffic explorer + validation-run history
- Postgres RLS on every multi-tenant table
- JWT signing-key rotation with `kid` header
- Agent health/status indicator
- Beta operations: per-customer health, capture rate, verdict throughput, runbooks

**Should have — differentiators (where we beat alternatives):**
- Staging-with-real-deps architectural story (moat vs Speedscale's dependency mocking)
- Statistical verdict framing visible in UX: effect size + sample size shown, not just "passed"
- Per-dimension verdict drill-in: which endpoints, which requests, specific evidence
- gRPC + HTTP/2 capture in v1 (half a design-partner cluster is invisible without it)
- Sub-30-minute time-to-first-verdict (the design-partner promise)
- Pluggable storage backend (data stays in customer boundary without splitting the topology)
- GitHub Check Run as the decision surface, not a notification — carries the go/no-go signal
- Resumable onboarding state in dashboard
- Linear-regression memory leak detection during replay
- JWT key rotation (B2B SaaS security differentiator)

**Defer to v2+:**
- Smarter capture-window selection (v1: last 5 minutes is fine; optimize post-beta)
- Per-endpoint diff ignore rules; configurable verdict thresholds per service
- CLI, inline PR diff comments, mobile app, multi-cluster federation
- Write-traffic replay with DB reset hook; message-queue capture (Kafka/PubSub/SNS/SQS)
- BYO KMS / CMEK at rest; auto-rollback / deployment integration
- Public GA polish (billing, pricing, status page, SLA, ToS)

### Architecture Approach

The system splits cleanly into a working data plane (Go tap + Go agent + collector ingest) and a greenfield control plane. All new v1 work is control-plane, built on top of an already-working data plane. New components: `orchestrator/` Ktor module (port 8082) owning the validation saga, replay-engine, and observer as packages inside it; `comparison/` as a pure stateless library imported directly by orchestrator; `github-app/` as a separate Cloud Run service with HMAC-only auth and in-Postgres async work queue; `dashboard/` as a Vite + React static bundle served by nginx. The `CallerIdentity` sealed hierarchy (Agent | User | Service) must land in `shared/` alongside JWT rotation — it is the prerequisite for dashboard user-type JWTs and orchestrator service-type JWTs.

**Major components:**
1. `tap/` (Go, extend) — eBPF L7 capture: HTTP/1.1, HTTP/2, gRPC frames
2. `agent/` (Go, port from Kotlin) — K8s informer, config polling, traffic batching; no shared types with platform
3. `shared/` (extend) — `CallerIdentity` sealed hierarchy, multi-kid JWT, RLS context interceptor, redaction types
4. `collector/` (extend) — `CapturedInputStorage` interface, redaction pipeline, quota enforcement
5. `orchestrator/` (new, port 8082) — ValidationSaga state machine (`SELECT FOR UPDATE SKIP LOCKED`), replay-engine package, observer package
6. `comparison/` (new, library) — response diff, Mann-Whitney U, error rate, linear regression; pure functions, no I/O
7. `github-app/` (new, separate Cloud Run) — HMAC webhook receiver, in-Postgres async queue, Check Runs + PR comments
8. `dashboard/` (new, greenfield) — Vite + React, direct JWT auth to platform/collector/orchestrator, no BFF in v1
9. `platform/` (extend) — users, memberships, onboarding state machine, Google OIDC, GitHub/Slack install records

**Key patterns to follow:**
- Saga state machine with Postgres `SELECT FOR UPDATE SKIP LOCKED` — not Pub/Sub; at 50 validations/day Postgres is sufficient and simpler
- `comparison/` as direct Kotlin import by orchestrator — the one allowed cross-module compile dependency (pure functions, no I/O, no state)
- `github-app/` as a separate service — GitHub webhooks have a different auth model (HMAC, not JWT) and different blast radius
- No BFF for dashboard in v1 — direct calls to three services; add BFF in V2 if coordination dominates
- Every new table born with RLS on and `organization_id` as leading index column

### Critical Pitfalls

Top 10 pitfalls ordered by recovery difficulty (irrecoverable first):

1. **RLS retrofit creates a cross-tenant data leak** — Application must connect as a non-owner `app_role`; every multi-tenant table needs `FORCE ROW LEVEL SECURITY` (not just ENABLE); Hikari pool-checkout must `SET LOCAL app.current_org` and pool-return must `RESET`; CI isolation tests must run as the application role, not superuser. Enable per-table behind feature flags, soak 24h each. Every existing index rebuilt with `organization_id` as leading column. Recovery cost: CRITICAL — permanent reputational damage.

2. **PII honeypot: captured-inputs become a secondary credential store** — Default-deny `Authorization`, `Cookie`, `Set-Cookie`, `Proxy-Authorization`, `X-API-Key` at the agent, pre-network. Body redaction by pattern (JWT shape, PAN, `sk_`/`pk_` prefixes). Redacted values replaced with deterministic placeholders for replay-time substitution. Redaction must ship with the capture cutover, not retrofitted. Recovery cost: HIGH — breach notification, reputation damage.

3. **Uncalibrated comparison engine makes the GitHub required Check a PR-blocker** — Run baseline-vs-baseline with no code change; gate deployment on end-to-end false-positive rate < 5% per run. Apply Benjamini-Hochberg correction across endpoints; require p < alpha AND |effect size| > threshold. Default GitHub Check to non-blocking; customer opts into "required" only after measured calibration period. Recovery cost: MEDIUM (trust erosion, rapid disabling of the Check).

4. **eBPF capture silently corrupts on customer kernels** — On agent startup: pre-flight BTF check + loopback self-test; refuse to register on failure. Surface in dashboard onboarding. Track ring-buffer drop count as a customer-visible metric. Recovery cost: HIGH — roll back to Kubeshark on affected customers; per-customer kernel inventory.

5. **`actual`-mode replay melts staging** — Two-knob ceiling (RPS AND concurrent in-flight, both required). Default ceiling: 10% of captured production rate; customer opts up explicitly. Circuit breaker on staging error rate: pause, do not continue computing a verdict from melted staging. Recovery cost: LOW per incident, HIGH for trust.

6. **Response diff verdict blocked by noisy fields (trust dies in days)** — Auto-detect noisy fields from the captured-input set (if a field varies across samples, it is noisy by default). Built-in noise patterns: UUID-shaped, ISO-8601-shaped, hex strings, monotonic counters. Structural-with-value diff for stable fields only. Show what was diffed AND what was ignored. Recovery cost: LOW (ship auto-detection, re-run verdicts).

7. **cgroup_id attribution drifts after pod restart** — Treat `(cgroup_id, observation_window)` not `cgroup_id` alone; quarantine window after pod deletion before trusting cgroup_id reuse. Track informer freshness as a metric. CI test under pod churn. Recovery cost: MEDIUM (mark affected runs INCONCLUSIVE, ship fix).

8. **JWT rotation breaks tokens during the rotation window** — Multi-key validation from day one of `kid` rollout; grace period >= longest token TTL (30 days for agent JWTs); `kid` looked up against an allowlist, not raw string interpolation; JWKS cache TTL in minutes not hours; first rotation staged on sandbox. Recovery cost: MEDIUM (roll back to old key if multi-key implemented; HIGH if not).

9. **Onboarding 30-minute goal missed 10x by invisible prerequisites** — Pre-flight BTF + RBAC + service selector check before asking investment. Explicit failure states in dashboard with remediation. Synthetic loopback traffic on first install. Default to namespace-admin Helm install, not cluster-admin. Funnel observability ships with the dashboard. Recovery cost: MEDIUM (add observability, iterate; concierge bridge for affected partners).

10. **Pluggable storage schema drift breaks customer capture silently** — Prefer S3 + thin metadata (not full Postgres schema in customer cluster); collector hard-fails on startup if connected DB schema is older than expected — never silent. Customer storage is opt-in with white-glove enablement. Recovery cost: HIGH (coordinate migration with customer ops; potential downtime).

## Implications for Roadmap

Research points to 12 phases matching the ARCHITECTURE.md build order. The dependency direction is strict: data plane before control plane; security hardening before any new data surfaces; comparison calibration before enabling the GitHub required Check.

### Phase 1: Native Capture Cutover (TAP-3 through TAP-8)
**Rationale:** Every verdict depends on trustworthy captured traffic. The Kubeshark path is current state; Go tap is already bootstrapped (TAP-2). Replace the data plane under stable wire contracts before building anything on top. PII redaction ships here — do not retrofit.
**Delivers:** Production HTTP capture via Go eBPF tap + Go agent; Helm-deployable agent; kernel pre-flight + loopback self-test; ring-buffer drop metrics surfaced to customer; side-by-side equivalence period before retiring Kubeshark; Kubeshark decommission (TAP-8); default-deny header allowlist + body redaction running at agent.
**Avoids:** Pitfall 4 (eBPF kernel skew), Pitfall 5 (PII — redaction must ship here), Pitfall 13 (cgroup_id drift — attribution-under-churn is a TAP-5 success criterion).
**Research flag:** NEEDS phase-level research for TAP-5 (capture pipeline wiring under churn) and TAP-6 (production hardening — ring-buffer sizing, kernel compatibility matrix).

### Phase 2: gRPC + HTTP/2 Capture
**Rationale:** Design-partner hard requirement. Half the cluster is invisible without it. Pure tap-side work — sequence before security hardening so the capture surface is stable when Phase 3 runs.
**Delivers:** HTTP/2 frame parsing, HPACK decoding, gRPC length-prefixed message support; per-stream reassembly bounds (hard memory cap per stream); gRPC length-prefix sanity validation; loopback self-test with known-good gRPC pair; dashboard capture-quality indicator (% successfully captured vs dropped).
**Avoids:** Pitfall 12 (gRPC/HPACK corruption — per-stream memory bounds and attach-time tracking must ship; graceful degradation, not corruption).
**Research flag:** NEEDS phase-level research. HTTP/2 + HPACK dissection is the highest-risk engineering work in v1. The attach-time HPACK state problem and per-stream reassembly buffer bounds need a structured research pass before this phase is planned.

### Phase 3: Security Hardening Foundation
**Rationale:** Cross-cutting work that must precede every new data surface. Every component built after this inherits: multi-kid JWT validation, RLS on all new tables from creation, `CallerIdentity` sealed hierarchy. Retrofitting RLS onto live tables after the control plane is built is the most expensive mistake to undo.
**Delivers:** `jwt_signing_keys` table + `installJwtAuth()` multi-kid rewrite + JWKS multi-key publishing; Ktor RLS-context interceptor (`SET LOCAL app.current_org` in `shared/`); per-table RLS rollout behind feature flags (one table at a time, 24h soak each, error-rate monitoring); composite indexes with `organization_id` as leading column on all existing tenant tables; `CallerIdentity` sealed hierarchy (Agent | User | Service) in `shared/`; `DynamicConfig` redaction allowlist wired to collector ingest pipeline.
**Avoids:** Pitfall 5 (PII, collector-side second pass), Pitfall 6 (RLS bypass — FORCE + app-role separation + pool-checkout hook + CI-as-app-role tests), Pitfall 10 (JWT rotation window).
**Research flag:** Standard patterns. No additional research needed.

### Phase 4: Replay Engine (inside `orchestrator/`)
**Rationale:** First control-plane phase. Creates the `orchestrator/` module skeleton. RLS must be on first so new tables are born correctly.
**Delivers:** `orchestrator/` Ktor module (port 8082); `replay_runs` table (RLS-on, `organization_id` leading index from creation); `ReplayDispatcher` with `sequential` and `actual` fidelity strategies; two-knob ceiling (RPS + concurrent in-flight); default ceiling 10% of captured rate; circuit breaker on staging error rate; `POST /api/replay-runs` + `GET /api/replay-runs/{id}`.
**Uses:** Resilience4j Kotlin (token-bucket rate limiter), kotlinx-coroutines Semaphore + Channel.
**Avoids:** Pitfall 11 (`actual`-mode melts staging — two-knob ceiling + conservative default + circuit breaker are all v1 requirements).
**Research flag:** Standard patterns. No library research needed.

### Phase 5: Observation + Comparison + Verdict
**Rationale:** Produces the v1 output. Depends on replay runs existing. The comparison engine must be calibrated before verdict is exposed externally — FPR < 5% per run is a release gate.
**Delivers:** `observer/` package (K8s Metrics API polling, tap egress counts from staging); `comparison/` library (response diff with auto-detected noisy-field elision + UUID/timestamp built-in patterns; Mann-Whitney U with Benjamini-Hochberg correction + effect-size gate; error-rate delta; linear regression on memory with warm-up window); `validations` + `verdicts` tables; PASS / FAIL / INCONCLUSIVE rollup; INCONCLUSIVE reason codes (`insufficient_traffic`, `staging_error`, `shape_mismatch`, `correction_applied`); baseline-vs-baseline calibration test suite shipped as a release gate.
**Uses:** Hipparchus `hipparchus-stat:4.0.1`.
**Avoids:** Pitfall 1 (staging drift — baseline and candidate run interleaved, not back-to-back); Pitfall 2 (FPR — BH correction + effect-size gate + calibration gate); Pitfall 3 (noisy fields — auto-detected from capture set).
**Research flag:** Standard patterns for Hipparchus APIs. Calibration workflow (how many baseline-vs-baseline runs, what intervention threshold) warrants a brief look before Phase 5 planning.

### Phase 6: Orchestration Saga
**Rationale:** Wires the full end-to-end loop. Depends on verdict output from Phase 5.
**Delivers:** `POST /api/validations` saga; `GET /api/validations/{id}`; `ValidationSaga` state machine with Postgres `SELECT FOR UPDATE SKIP LOCKED` worker; capture-window selection (v1: last 5 minutes — dumb is fine, optimize post-beta); candidate deploy step; idempotent step handlers (re-runnable on cold restart); verdict fan-out to github-app, slack, and dashboard poll.
**Avoids:** Anti-pattern of Pub/Sub/Cloud Tasks for saga (Postgres queue is sufficient at this scale).
**Research flag:** Standard patterns. No additional research needed.

### Phase 7: Web Dashboard
**Rationale:** Primary user surface. Depends on Phase 6 (visualizes saga output) and Phase 3 (user-type JWTs via `CallerIdentity`). Greenfield — no frontend exists in the repo today.
**Delivers:** Vite + React + TS + Tailwind 4 + shadcn/ui + TanStack Router/Query + Recharts 3 static bundle on Cloud Run (nginx); routes: org + services, captured-traffic explorer, validation runs, verdict drill-in (per-dimension + per-endpoint evidence), agent install instructions, settings; direct JWT calls to platform/collector/orchestrator (no BFF); INCONCLUSIVE reason codes displayed with one-line explanations; ETA on in-progress runs; error messages intent-shaped, not architecture-revealing.
**Uses:** Full frontend stack from STACK.md. Verdict UX shape from external Claude Design artifact (required input).
**Avoids:** Anti-pattern of BFF in v1; UX pitfalls (FAIL with no breakdown, INCONCLUSIVE with no reason, silent zero-capture state, raw request bodies auto-loaded).
**Research flag:** Standard patterns. The external Claude Design artifact is the required input for this phase, not a research task — the roadmapper must surface it.

### Phase 8: Self-Serve Onboarding
**Rationale:** Closes the self-serve loop. Dashboard is the onboarding surface. The sub-30-minute target is a measurable product metric, not a vibe.
**Delivers:** Google OIDC signup + org provisioning; API-key generation; Helm install snippet pre-filled with org key; `onboarding_state` table and state machine (resumable across sessions); explicit failure states with remediation in dashboard; synthetic loopback traffic on first install; pre-flight checks (BTF, RBAC, service selectors) before showing Helm command; funnel observability (per-step completion time + drop-off tracked); namespace-admin default Helm RBAC, not cluster-admin.
**Avoids:** Pitfall 7 (onboarding miss — all five mitigations are v1 requirements, not polish).
**Research flag:** NEEDS brief research on Google OIDC with Ktor (token validation, session management) and Helm chart RBAC scoping for the observation use case.

### Phase 9: GitHub App
**Rationale:** Moves the verdict touchpoint to the PR. Depends on Phase 8 (GitHub install is an onboarding step) and Phase 6 (verdict is the payload). Separate Cloud Run service by architecture requirement.
**Delivers:** `github-app/` Ktor module on a dedicated Cloud Run service; HMAC-verified webhook receiver; `github_webhook_events` in-Postgres async queue; `github_installations` table; Check Run posting (PASS = `success`, FAIL = `failure`, INCONCLUSIVE = `neutral`); PR comment with verdict headline + top-N most-significant diffs by effect size + deep link; calibration period default (non-blocking until FPR measured on >= 50 baseline-vs-baseline runs); per-dimension override knobs; per-installation rate-limiting + circuit breaker.
**Uses:** hub4j `github-api:1.327+`, BouncyCastle (already in stack), nimbus-jose-jwt for GitHub App JWT.
**Avoids:** Pitfall 8 (PR check noise — non-blocking default + calibration gate + INCONCLUSIVE = `neutral` + per-dimension overrides); anti-pattern of inline webhook processing (write to queue, return 202, worker processes async).
**Research flag:** Verify hub4j against GitHub's April-June 2026 installation-token format rollout at the time this phase starts.

### Phase 10: Slack Notifications
**Rationale:** Closes the alert loop. Outbound-only. Depends on Phase 6 (verdict events trigger it).
**Delivers:** `slack_installations` table (channel webhook URLs encrypted at rest); outbound verdict + anomaly notifications; throttle per-channel; no-self-notify by default; Slack payload contains verdict + dashboard deep link only — no raw captured request snippets.
**Uses:** `slack-api-client:1.45+`.
**Avoids:** Alarm fatigue; accidental payload leak in Slack channels.
**Research flag:** Standard patterns. No additional research needed.

### Phase 11: Pluggable Storage Backend
**Rationale:** Addresses skeptical-customer data-boundary concern. Late by design — the interface is easiest to define once the feature exists. Default hosted-Postgres path remains primary.
**Delivers:** `CapturedInputStorage` interface in collector; `HostedPostgresStorage` (default); `S3PlusPostgresMetadataStorage` (bodies on S3 via AWS SDK v2, metadata on Postgres); per-collector-instance configuration (not per-tenant routing); schema-version check on startup — hard-fail on schema mismatch, never silent; operational contract docs (opt-in, white-glove at enablement).
**Uses:** AWS SDK v2 `s3`, `google-cloud-storage`.
**Avoids:** Pitfall 9 (schema drift — startup hard-fail + prefer blob+metadata over full schema in customer cluster); anti-pattern of per-tenant routing in the collector.
**Research flag:** NEEDS brief design decision before code starts: blob+metadata (S3 bodies + Postgres pointer) vs full Postgres schema for customer storage. The interface boundary depends on this choice.

### Phase 12: Beta Operations
**Rationale:** Observes the whole stack. Depends on everything else deployed and design partners running real validation runs.
**Delivers:** Cloud Monitoring custom metrics per customer; per-customer health view in dashboard; agent status and informer freshness metrics; capture/drop ratio surfaced to customer in real time; oncall runbooks; customer-facing status indicators; onboarding funnel metrics; no-code path to onboard a new design partner.
**Avoids:** All "looks done but isn't" items from PITFALLS.md checklist.
**Research flag:** Standard observability patterns. No additional research needed.

### Phase Ordering Rationale

- **Data plane before control plane (Phases 1-2 before 4-12):** Every verdict depends on trustworthy captured traffic. Swapping the capture path while also building the verdict surface creates two simultaneous risk vectors.
- **Security before new data surfaces (Phase 3 before 4+):** Retrofitting RLS onto live tables is the highest-risk operation in the project. Born-correct is dramatically cheaper. Every new table from Phase 4 onward is born with RLS on.
- **Replay before comparison (Phase 4 before 5):** The comparison engine operates on replay run outputs. Nothing to compare without runs.
- **Comparison before saga (Phase 5 before 6):** The saga's terminal state is the verdict. A saga without verdicts is a job runner.
- **Calibration gate before GitHub required Check:** The comparison engine's false-positive rate must be measured and gated before the Check Run is exposed as a required check. This gate lives in Phase 5; the Check Run ships in Phase 9.
- **Saga before dashboard (Phase 6 before 7):** The dashboard's most important surface (verdict drill-in) visualizes saga output. A dashboard without verdicts is a CRUD explorer.
- **Dashboard before onboarding (Phase 7 before 8):** Onboarding is a flow through the dashboard.
- **Onboarding before GitHub App (Phase 8 before 9):** The GitHub App install is a step in the onboarding flow; it references the org's API key which the onboarding flow provisions.
- **Pluggable storage late (Phase 11):** The interface is easiest to define once the feature exists; combining it with the RLS retrofit would increase risk surface without benefit.

### Research Flags

**Needs `/gsd-research-phase` during planning:**
- **Phase 2 (gRPC + HTTP/2 capture):** HTTP/2 frame reassembly + HPACK state machine is the highest-risk engineering work. The attach-time HPACK state problem and per-stream reassembly buffer bounds need a structured research pass before planning.
- **Phase 8 (Onboarding — Google OIDC with Ktor):** Token validation, session management, and Helm chart RBAC scoping need research before implementation planning.
- **Phase 11 (Pluggable storage interface):** The blob+metadata vs full-schema decision and exact interface boundary need a brief research pass before the phase plan is written.

**Standard patterns — skip research phase:**
- **Phases 1, 3, 4, 6, 10, 12:** Capture cutover, RLS hardening, Resilience4j replay, Postgres saga, Slack outbound, Cloud Monitoring — all well-trodden.
- **Phase 5:** Hipparchus APIs are well-documented; calibration methodology workflow is the only open question.
- **Phase 7:** TanStack Router + shadcn/ui + Recharts are well-documented. External Claude Design artifact is the required input, not a research task.
- **Phase 9:** hub4j is well-documented. Verify against GitHub's April-June 2026 token format change at integration time — version check, not research.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | Official docs and changelogs verified for all recommendations as of 2026-05-14. Verify Hipparchus 4.0.1 and hub4j 1.327 on Maven Central at integration time. |
| Features | MEDIUM-HIGH | Table stakes well-attested by competitor analysis. Differentiator framing is opinionated and unvalidated until design partners are in. gRPC requirement comes from design-partner conversation, not documented prior customer feedback. |
| Architecture | HIGH | Brownfield codebase is ground truth; new component decomposition follows established SaaS patterns and the project's existing module-boundary discipline. Replay dispatch Option A (orchestrator egress to customer staging) needs validation with first design partner's network topology. |
| Pitfalls | HIGH | Heavily corroborated by prior-art post-mortems, CONCERNS.md, and design-partner-grade SaaS patterns. All 13 pitfalls are specific to replay/verdict systems. Recovery cost classifications are conservative. |

**Overall confidence:** HIGH for architecture and security hardening sequence; MEDIUM-HIGH for features (unvalidated by design partners until the verdict loop closes on real PRs).

### Gaps to Address

- **gRPC dissector design:** No detailed dissector design exists in the research. The attach-time HPACK state problem and per-stream reassembly bounds need a Phase 2 research task.
- **Comparison engine calibration workflow:** The exact calibration workflow (how many baseline-vs-baseline runs, over what window, what intervention threshold) is unspecified. Address during Phase 5 planning.
- **Verdict UX shape:** PROJECT.md references an external Claude Design project not in version control. The roadmapper must surface this artifact as a required input for Phase 7 planning.
- **Replay dispatch egress model:** Validate orchestrator-direct egress to customer staging with first design partner's network topology before Phase 4 implementation planning finalizes.
- **Google OIDC session management:** Auth model details for the dashboard (httpOnly refresh cookie, access token in memory, token TTL, Ktor plugin choice) not fully specified. Address during Phase 8 research.
- **Helm chart RBAC scope for observation:** Exact RBAC scope needed for K8s Metrics API access during staging observation is unspecified. Address during Phase 1 or Phase 8.

## Sources

### Primary (HIGH confidence)
- `/Users/prathameshkulkarni/repos/validation-platform/.planning/PROJECT.md` — v1 scope, constraints, out-of-scope, key decisions
- `/Users/prathameshkulkarni/repos/validation-platform/CLAUDE.md` — current capability inventory and architectural commitments
- `/Users/prathameshkulkarni/repos/validation-platform/.planning/codebase/ARCHITECTURE.md` — existing brownfield architecture
- React 19.2 releases, TanStack Query/Router npm, Tailwind 4 + shadcn/ui docs, Recharts 3 — frontend stack versions confirmed
- Hipparchus Math official site + GitHub — active maintenance confirmed, Commons Math fork lineage
- Resilience4j Kotlin docs — suspend-fn integration, token-bucket variants confirmed
- PostgreSQL RLS docs (official) — `SET LOCAL`, `FORCE`, owner bypass semantics
- AWS multi-tenant RLS (AWS blog) — `app_role` separation pattern confirmed
- GitHub Apps webhook architecture (official GitHub docs) — HMAC, async handling
- Auth0 signing key rotation (official) — `kid` multi-key lifecycle
- GCP Secret Manager version aliases (official) — GA for rotation pattern confirmed

### Secondary (MEDIUM confidence)
- Speedscale traffic-replay guide — competitor feature analysis, PII handling baseline
- Argo Rollouts analysis docs — AnalysisRun verdict pattern, INCONCLUSIVE handling
- Diffy (archived) + Signadot + k6 Cloud + GoReplay — competitor positioning matrix
- Permit.io RLS guide + thenile.dev multi-tenant RLS — RLS pitfall coverage
- Pixie Labs blog (HTTP/2 eBPF tracing) — HPACK state machine challenges
- JWKS zero-downtime rotation (David Sulc) — multi-key grace period pattern
- Northflank SaaS BYOC — pluggable storage complexity beyond ~10 customers
- GraphPad Mann-Whitney U guide + PMC multiple comparisons review — statistical calibration methodology

### Tertiary (LOW confidence — verify at integration time)
- hub4j github-api 1.327: verify against GitHub's April-June 2026 installation-token format rollout
- Hipparchus 4.0.1 exact artifact: verify on Maven Central at integration time
- Replay dispatch Option A (orchestrator egress to customer staging): validate with first design partner's network topology

---
*Research completed: 2026-05-14*
*Ready for roadmap: yes*
