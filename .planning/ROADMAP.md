# Roadmap: Validation Platform v1

## Overview

This roadmap delivers the design-partner beta of the Validation Platform — a hosted B2B SaaS that captures production traffic with eBPF, replays it against a customer staging cluster with real dependencies, and returns a trustworthy PASS / FAIL / INCONCLUSIVE verdict at PR time. The journey starts with replacing the existing Kubeshark + Kotlin agent capture path with a native Go eBPF tap (Phase 1) and extending it to gRPC + HTTP/2 (Phase 2). A cross-cutting security foundation lands next (Phase 3 — RLS, multi-`kid` JWT rotation, `CallerIdentity` hierarchy, defense-in-depth redaction) so every later component is born with tenancy and redaction correct, not retrofitted. The verdict loop then builds bottom-up: replay engine (Phase 4) → observation + comparison + verdict with a baseline-vs-baseline calibration gate (Phase 5) → saga that wires capture-to-verdict end-to-end (Phase 6). The dashboard (Phase 7) is the primary user surface; self-serve onboarding (Phase 8) closes the sub-30-minute time-to-first-verdict promise. The decision touchpoints land last: GitHub Check Run + PR comment (Phase 9), Slack notifications (Phase 10), pluggable storage for skeptical-customer data residency (Phase 11), and beta operations observability (Phase 12).

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [ ] **Phase 1: Native Capture Cutover** - Replace Kubeshark + Kotlin agent with Go eBPF tap + Go agent under stable wire contracts; ship redaction with capture.
- [ ] **Phase 2: gRPC + HTTP/2 Capture** - Extend the tap with HTTP/2 frame parsing, HPACK decoding, and gRPC framing so half the cluster is no longer invisible.
- [ ] **Phase 3: Security Hardening Foundation** - `CallerIdentity` hierarchy, multi-`kid` JWT rotation, Postgres RLS retrofit, and collector-side redaction defense-in-depth.
- [ ] **Phase 4: Replay Engine** - First control-plane module: `orchestrator/` skeleton on port 8082, `sequential` + `actual` (two-knob ceiling) replay with circuit breaker.
- [ ] **Phase 5: Observation + Comparison + Verdict** - Staging observer, statistical comparison engine, PASS/FAIL/INCONCLUSIVE rollup, and the baseline-vs-baseline calibration release gate.
- [ ] **Phase 6: Orchestration Saga** - `POST /api/validations` durable saga via Postgres `SELECT FOR UPDATE SKIP LOCKED`; capture → baseline → candidate → compare → verdict end-to-end.
- [ ] **Phase 7: Web Dashboard** - Vite + React static bundle on Cloud Run: services, captured-traffic explorer, validation runs, verdict drill-in, install instructions, settings.
- [ ] **Phase 8: Self-Serve Onboarding** - Google OIDC signup, resumable onboarding state machine, pre-flight checks, synthetic loopback traffic, sub-30-minute time-to-first-verdict.
- [ ] **Phase 9: GitHub PR Integration** - `github-app/` service on its own Cloud Run with HMAC webhooks, async queue, Check Run + PR comment, non-blocking by default until calibrated.
- [ ] **Phase 10: Slack Notifications** - Outbound-only verdict + anomaly notifications with per-channel throttle and no-self-notify default.
- [ ] **Phase 11: Pluggable Storage Backend** - `CapturedInputStorage` interface with hosted-Postgres default and S3+Postgres-metadata variant; per-instance config; schema-version startup check.
- [ ] **Phase 12: Beta Operations** - Cloud Monitoring metrics per customer, per-customer health view, runbooks, status indicators, no-code onboarding for new design partners.

## Phase Details

### Phase 1: Native Capture Cutover
**Goal**: Replace the Kubeshark + Kotlin agent capture path with a Go eBPF tap + Go agent under the existing collector wire contracts, with PII redaction shipping in the same phase (never retrofitted) and the Kotlin agent fully decommissioned by phase end.
**Depends on**: Nothing (existing brownfield code — `tap/` bootstrap, K8s informer, and collector wire contracts already exist)
**Requirements**: CAPTURE-01, CAPTURE-02, CAPTURE-03, CAPTURE-04, CAPTURE-05, CAPTURE-06, CAPTURE-07, CAPTURE-08, CAPTURE-09, CAPTURE-10, CAPTURE-11, CAPTURE-12
**Success Criteria** (what must be TRUE):
  1. A captured-input batch produced by the Go agent appears in the collector database within one minute of agent start on the sandbox cluster, byte-for-byte compatible with the existing `BatchCreateCapturedInputRequest` schema.
  2. The sandbox cluster has Kubeshark removed and `vp-tap` installed via Helm; cutover PRs are revertible until decommission lands, and `agent/` (Kotlin Gradle module) plus all Kubeshark wiring is deleted from the tree.
  3. Sensitive request/response headers (`Authorization`, `Cookie`, `Proxy-Authorization`, `Set-Cookie`, `X-API-Key`) and pattern-matched body tokens (JWT-shaped, PAN, `sk_`/`pk_` prefixes) are replaced with deterministic placeholders at the agent before any network call leaves the cluster.
  4. The tap survives a sustained 30-minute sandbox pressure-test without OOMs, panics, or drop-rate above the agreed threshold; per-node CPU and memory fit the existing sandbox node pool with at least 20% headroom; results recorded in a benchmark doc.
  5. On agent startup the BTF + loopback HTTP self-test runs; the agent refuses to register if either fails, and the failure reason is observable (logged plus emitted as a metric) so the onboarding surface can read it later.
  6. CI runs both `./gradlew test` and `go test ./...` on every PR; the bilingual e2e test launches the Go agent alongside platform + collector and exercises K8s discovery → registration → capture → ingest end-to-end.
**Plans**: TBD
**Research needed**: yes (TAP-5 capture-pipeline wiring under churn; TAP-6 production hardening ring-buffer sizing + kernel compatibility matrix — flagged in SUMMARY.md)

### Phase 2: gRPC + HTTP/2 Capture
**Goal**: Extend the Go tap's userspace dissector to parse HTTP/2 frames (HEADERS, DATA, CONTINUATION), decode HPACK with per-connection state, and parse gRPC length-prefixed messages — failing loud, not silently, when state is unrecoverable, and surfacing per-protocol capture quality to customers.
**Depends on**: Phase 1
**Requirements**: GRPC-01, GRPC-02, GRPC-03, GRPC-04, GRPC-05, GRPC-06
**Success Criteria** (what must be TRUE):
  1. A gRPC unary call against a known-good test service in the sandbox is captured end-to-end with method (`:path` header), request body, and response body present in the collector — byte-equivalent to a control capture from the language SDK.
  2. A malformed gRPC length prefix or oversized stream is dropped with a counter increment and surfaces as a "dropped" measurement; the agent does not OOM and continues capturing other streams.
  3. When the tap attaches mid-connection and HPACK dynamic-table state is unknown, the affected stream is counted-and-skipped (never captured with corrupted headers) until a fresh connection starts.
  4. The agent's loopback self-test (Phase 1) is extended with a known-good gRPC pair; the agent refuses to register if gRPC capture is enabled and the gRPC self-test fails.
  5. A per-service capture-quality measurement (% successfully captured vs dropped, broken out by HTTP/1.1, HTTP/2, gRPC) is computable from agent metrics — wired into the dashboard surface in Phase 7.
**Plans**: TBD
**Research needed**: yes (HTTP/2 + HPACK dissection is the highest-risk engineering work in v1; attach-time HPACK state, per-stream reassembly buffer bounds — flagged in SUMMARY.md)

### Phase 3: Security Hardening Foundation
**Goal**: Land cross-cutting security as a single phase before any new data surface is built — `CallerIdentity` sealed hierarchy (Agent / User / Service), multi-`kid` JWT validation with zero-downtime rotation, Postgres RLS with `FORCE ROW LEVEL SECURITY` and `app_role` separation, and collector-side redaction defense-in-depth. Every phase after this inherits these primitives; no later phase retrofits them.
**Depends on**: Phase 2 (capture surface stable before changing auth + storage semantics)
**Requirements**: SEC-01, SEC-02, SEC-03, SEC-04, SEC-05, SEC-06, SEC-07, SEC-08, SEC-09
**Success Criteria** (what must be TRUE):
  1. A test signed with an `agent`-claim JWT can read its own org's services but cannot read another org's services even when running the query as the application database role (not as superuser) — proving RLS is enforced and not bypassed by the owner.
  2. The platform issues a new RS256 JWT with a `kid` header; rotating the active signing key (add new, demote old to validating-only, age out after the configured grace) does not invalidate any currently-issued long-lived agent token; both keys appear in the JWKS endpoint during the grace window.
  3. The `CallerIdentity` sealed hierarchy (`Agent`, `User`, `Service`) replaces `AgentIdentity` across the codebase; routes that read tenancy use the new principal, and existing JWT validation paths populate the correct variant from claims.
  4. The application connects to Postgres as a non-owner `app_role`; per-request Ktor interceptor sets `SET LOCAL app.current_org` from the JWT principal and `RESET app.current_org` on pool return — verified by CI tests that run as `app_role`, not superuser.
  5. A captured input forwarded with a non-redacted `Authorization` header (CAPTURE-09 bypass simulation) is rejected by the collector ingest pipeline with a counter increment — defense-in-depth redaction works end-to-end.
  6. The agent's `DynamicConfig` includes a per-org redaction allowlist (additional headers, body patterns); changing it on the platform side propagates to the agent within one config-poll interval and is applied before forwarding.
**Plans**: TBD

### Phase 4: Replay Engine
**Goal**: Create the `orchestrator/` Ktor module on port 8082 with a `replay_runs` table (RLS-on from creation), implementing `sequential` and `actual` (two-knob ceiling: RPS AND concurrent in-flight) replay against a customer staging target, with a staging-error circuit breaker that pauses the run rather than computing a verdict from melted staging.
**Depends on**: Phase 3 (RLS must be on so the new `replay_runs` and `replay_responses` tables are born with `organization_id` leading index and `FORCE ROW LEVEL SECURITY` — never retrofitted per SEC-07)
**Requirements**: REPLAY-01, REPLAY-02, REPLAY-03, REPLAY-04, REPLAY-05, REPLAY-06, REPLAY-07, REPLAY-08
**Success Criteria** (what must be TRUE):
  1. A `POST /api/replay-runs` call with target service, capture-window selector, fidelity, and ceiling returns a `replay_run_id`; `GET /api/replay-runs/{id}` reports status (`pending`/`running`/`complete`/`failed`/`inconclusive`), progress, and the persisted replay responses.
  2. In `actual` mode both ceilings are enforced: the dispatcher does not exceed the configured RPS even with unlimited concurrency available, and does not exceed concurrent-in-flight even at higher allowed RPS — verified by integration test against a slow staging stub.
  3. When staging 5xx rate or timeout rate during a replay crosses the configured threshold, the run pauses and is marked INCONCLUSIVE with reason `staging_error`; no verdict is computed from the partial output.
  4. Default ceiling on a fresh replay run is 10% of the captured production rate; opting up beyond this requires an explicit `ceiling` parameter in the request.
  5. With read-only filtering enabled (default), only safe-method requests (`GET`, `HEAD`, configurable per-endpoint override) are replayed; write requests are recorded as "skipped — write method" with a counter, not silently dropped or sent.
  6. Each replayed request produces a `replay_responses` row (RLS-on) keyed to the originating captured-input id, with status, body, headers, and latency captured.
**Plans**: TBD

### Phase 5: Observation + Comparison + Verdict
**Goal**: Produce the v1 output. Add `observer/` (K8s Metrics API + staging-tap outbound counts) and a pure-functions `comparison/` library (response diff with auto-detected noisy-field elision, latency Mann-Whitney U with Benjamini-Hochberg correction + effect-size gate, error-rate deltas, linear-regression memory trend). Ship the baseline-vs-baseline calibration test suite as a release gate — a deploy that lifts the verdict false-positive rate above 5% is blocked.
**Depends on**: Phase 4 (need replay runs to compare)
**Requirements**: VERDICT-01, VERDICT-02, VERDICT-03, VERDICT-04, VERDICT-05, VERDICT-06, VERDICT-07, VERDICT-08, VERDICT-09, VERDICT-10
**Success Criteria** (what must be TRUE):
  1. Running two replay runs against the same baseline image with the same captured-input set against the calibration test suite produces a verdict false-positive rate below 5% across the suite; lifting that rate blocks promotion (this is the release gate for any required GitHub Check, see Phase 9).
  2. A verdict drill-in for a completed validation shows headline PASS / FAIL / INCONCLUSIVE plus per-dimension status (response diffs, latency, error rate, memory trend) with concrete evidence: which endpoints, which examples, what was elided as noise.
  3. The latency comparator requires BOTH `p < α` (after Benjamini-Hochberg correction across endpoints) AND `|effect size| > threshold` before flagging a regression — a 0.2ms shift with `p = 0.04` does not produce FAIL.
  4. Response-diff comparator auto-elides UUID-shaped, ISO-8601-shaped, hex-string, and monotonic-counter fields; the verdict evidence panel surfaces both what was diffed AND what was ignored, so a developer can audit the comparison.
  5. INCONCLUSIVE verdicts always carry a first-class reason code (`insufficient_traffic` / `staging_error` / `shape_mismatch` / `correction_applied` / `attribution_uncertain`) with a one-line explanation, never a bare "we don't know."
  6. K8s pod CPU + memory samples and staging-tap outbound connection counts are persisted per replay run; the memory-trend comparator runs linear regression on per-pod samples with the configured warm-up window excluded.
**Plans**: TBD

### Phase 6: Orchestration Saga
**Goal**: Wire the full capture → baseline → candidate-deploy → candidate-replay → comparison → verdict loop behind `POST /api/validations`, using a Postgres-backed durable saga (`SELECT FOR UPDATE SKIP LOCKED`) with idempotent step handlers, interleaved baseline/candidate runs to neutralize staging drift, and verdict fan-out to downstream surfaces.
**Depends on**: Phase 5 (verdict output is the saga's terminal state)
**Requirements**: ORCH-01, ORCH-02, ORCH-03, ORCH-04, ORCH-05, ORCH-06
**Success Criteria** (what must be TRUE):
  1. A single `POST /api/validations` call with a target service + candidate image (or commit SHA) starts a saga that completes end-to-end without manual intervention; `GET /api/validations/{id}` returns the saga state and the terminal verdict.
  2. Killing the orchestrator worker mid-run and restarting it does NOT produce duplicate side effects (no double-deploy of candidate, no duplicate replay runs); the saga resumes from the last committed step.
  3. Baseline and candidate replay runs are interleaved request-by-request within the same window — verified by inspecting timestamps in `replay_responses` — so staging drift cannot manufacture a verdict difference.
  4. Every saga transition emits a structured log entry; `GET /api/validations/{id}` returns a progress timeline with per-step duration, ready for the dashboard to render in Phase 7.
  5. A saga that fails to dispatch a replay run (e.g., staging unreachable) returns a distinct `saga_failed` status — not a verdict — so dashboards and downstream surfaces can distinguish "we never finished" from "we finished with INCONCLUSIVE."
**Plans**: TBD

### Phase 7: Web Dashboard
**Goal**: Ship the primary user surface as a Vite + React 19 + TypeScript + Tailwind 4 + shadcn/ui + TanStack Router/Query + Recharts 3 static bundle served by Cloud Run + nginx, with direct JWT auth to platform/collector/orchestrator (no BFF). All v1 routes — org overview, services, captured-traffic explorer, validation runs, verdict drill-in (per the external Claude Design artifact), settings, install instructions — function against real data.
**Depends on**: Phase 6 (verdict drill-in is the dashboard's centerpiece; needs saga output), Phase 3 (user-type JWTs via `CallerIdentity.User`)
**Requirements**: UI-01, UI-02, UI-03, UI-04, UI-05, UI-06, UI-07, UI-08, UI-09, UI-10
**Success Criteria** (what must be TRUE):
  1. A signed-in user can navigate from `/` → `/services` → `/captures` → `/validations` → `/validations/:id` and see real data driven by direct JWT-authenticated calls to platform (8080), collector (8081), and orchestrator (8082) — no BFF in the path.
  2. The verdict drill-in at `/validations/:id` shows headline PASS / FAIL / INCONCLUSIVE, then per-dimension breakdown with evidence (examples, percentiles, regression lines), with click-through to specific endpoint diffs.
  3. INCONCLUSIVE reason codes (from VERDICT-08) render in the UI with a one-line plain-language explanation; "we don't know" is never the user-visible message.
  4. The captured-traffic explorer never auto-loads raw request bodies in list views; bodies require an explicit row click and render with redaction indicators visible alongside the field.
  5. Empty states are intent-shaped ("No captures yet — install the agent in your cluster" with deep link to install instructions), not "0 results"; an in-progress validation shows an ETA + per-step progress sourced from ORCH-04's timeline.
  6. The `/install` page renders the agent Helm command with a one-click copy button pre-filled with the org's API key.
**Plans**: TBD
**UI hint**: yes

### Phase 8: Self-Serve Onboarding
**Goal**: Close the sub-30-minute time-to-first-verdict promise. Google OIDC signup, org provisioning, resumable `onboarding_state` machine surfaced in the dashboard, pre-flight checks (BTF, RBAC, service selectors) before showing the Helm command, synthetic loopback traffic on first agent install, namespace-admin RBAC defaults, and onboarding funnel observability per-customer.
**Depends on**: Phase 7 (the dashboard is the onboarding surface)
**Requirements**: ONBOARD-01, ONBOARD-02, ONBOARD-03, ONBOARD-04, ONBOARD-05, ONBOARD-06, ONBOARD-07
**Success Criteria** (what must be TRUE):
  1. A new developer with no prior context can sign up via Google OIDC, provision an organization, install the agent via the dashboard-provided Helm command, see first capture, run a validation, and view a verdict — measured median under 30 minutes on the calibration runbook.
  2. The dashboard reflects the current onboarding step from `onboarding_state` (signed-up → org-created → api-key-issued → helm-shown → first-capture-seen → first-validation-run → first-verdict); closing and reopening the browser resumes at the same step.
  3. Pre-flight checks (BTF availability, sandbox cluster reachable, namespace-admin RBAC scopable, service selectors matching the `app=<name>` requirement) run before the Helm command is shown; each failure surfaces an explicit one-line remediation, not a stack trace.
  4. On first agent install the dashboard shows "first capture seen" within seconds rather than waiting for real production traffic, driven by synthetic loopback traffic generated by the agent.
  5. The Helm command in the dashboard defaults to namespace-admin RBAC scope (not cluster-admin); the dashboard documents the exact permissions alongside the command.
  6. Per-customer onboarding funnel metrics (per-step completion time, drop-off counts, time-to-first-verdict) are queryable and visible in a dashboard view that the team can read.
**Plans**: TBD
**Research needed**: yes (Google OIDC with Ktor token validation + session management; Helm chart RBAC scoping for the observation use case — flagged in SUMMARY.md)
**UI hint**: yes

### Phase 9: GitHub PR Integration
**Goal**: Move the verdict to the PR via a `github-app/` Ktor module deployed as a separate Cloud Run service with HMAC-verified webhooks, an in-Postgres async work queue (responds 202 immediately, worker processes async), Check Runs that map verdict outcomes to GitHub conclusions (PASS=success, FAIL=failure, INCONCLUSIVE=neutral), and a PR comment with the headline + top-N most-significant diffs + deep link. Required-check status is gated by the FPR calibration shipped in Phase 5.
**Depends on**: Phase 8 (GitHub install is an onboarding step), Phase 6 (verdict is the payload)
**Requirements**: PR-01, PR-02, PR-03, PR-04, PR-05, PR-06, PR-07, PR-08
**Success Criteria** (what must be TRUE):
  1. A PR opened on a repo with the GitHub App installed triggers a validation run; within the saga's typical completion time, a Check Run appears on the PR with the correct conclusion (`success` for PASS, `failure` for FAIL, `neutral` for INCONCLUSIVE) and a deep link to the verdict drill-in.
  2. The PR comment carries the verdict headline, the top-N most-significant diffs by effect size (not every diff field), the INCONCLUSIVE reason if applicable, and a deep link — never a wall of raw JSON.
  3. Webhook delivery responds 202 within 10 seconds (write to `github_webhook_events` queue → return); the worker processes events asynchronously, with HMAC verification rejecting unsigned or mis-signed deliveries.
  4. A repo cannot enable "Validation Platform" as a required check until that org has accumulated at least 50 baseline-vs-baseline calibration runs with measured FPR below 5% (gate enforced by Phase 5's calibration suite).
  5. A single runaway repo (high PR volume / rapid webhook flood) does not break the GitHub App for other installations — per-installation rate-limiting and circuit-breaking keep blast radius contained.
**Plans**: TBD

### Phase 10: Slack Notifications
**Goal**: Close the alert loop with outbound-only Slack notifications. Channel webhook URLs encrypted at rest, verdict notifications + anomaly alerts pushed on detection, per-channel throttle to prevent fatigue, no-self-notify by default. No inbound interactions in v1 — actions still happen in the dashboard.
**Depends on**: Phase 6 (verdict events trigger it)
**Requirements**: SLACK-01, SLACK-02, SLACK-03, SLACK-04, SLACK-05
**Success Criteria** (what must be TRUE):
  1. When a validation run completes, the configured Slack channel receives a single message with the verdict headline and a dashboard deep link — never raw captured request snippets or response bodies.
  2. Anomaly events (capture-rate-zero, agent unhealthy) post to the same channel on detection, distinct from verdict notifications so they can be filtered.
  3. Slack credentials (channel webhook URLs) are stored encrypted at rest (Cloud KMS or equivalent), not as plaintext in the database.
  4. Per-channel throttling collapses duplicate verdicts for the same PR within a configurable window so a noisy PR does not produce alarm fatigue.
  5. The user who manually triggered a validation run does not get a Slack mention for it by default (no-self-notify), reducing self-notification noise on the channel.
**Plans**: TBD

### Phase 11: Pluggable Storage Backend
**Goal**: Address the skeptical-customer data-boundary objection with a `CapturedInputStorage` interface in the collector. Default `HostedPostgresStorage` keeps behavior unchanged; `S3PlusPostgresMetadataStorage` lets customers keep bodies in their own S3 bucket with a Postgres pointer in the metadata row. Storage backend is selected per-collector-instance (not per-tenant routing inside one collector); switching backends requires a redeploy; collector startup hard-fails on schema-version mismatch — never silent.
**Depends on**: Phase 3 (RLS already on, so the refactor doesn't combine with tenancy changes), Phase 1 (capture wire contracts stable)
**Requirements**: STORE-01, STORE-02, STORE-03, STORE-04, STORE-05, STORE-06
**Success Criteria** (what must be TRUE):
  1. The default-hosted collector continues to behave identically to today: a captured-input batch is persisted to hosted Postgres with no observable wire-contract change — verified by the existing collector test suite passing against the refactored storage layer.
  2. A collector instance configured for `S3PlusPostgresMetadataStorage` persists captured-input bodies as blobs in S3 (or GCS via S3-compat) and stores only the pointer plus metadata in Postgres; a `GET` on the captured-input round-trips the body byte-for-byte.
  3. Starting a collector against a Postgres whose schema version is older or newer than the collector code's expected version produces a hard fail with a clear error message at startup — never a silent "queries fail for hours."
  4. Switching storage backends for a deployed collector requires a redeploy (config change at startup, not runtime toggle); the interface forbids per-tenant routing inside a single collector instance.
  5. The operational contract for customer-side storage (customer owns S3 bucket + IAM; platform owns schema + migrations; opt-in, white-glove enablement) is documented in repo so a design partner asking for it gets one source of truth.
**Plans**: TBD
**Research needed**: yes (blob+metadata vs full-Postgres-schema decision and exact interface boundary — flagged in SUMMARY.md)

### Phase 12: Beta Operations
**Goal**: Observe the whole stack. Cloud Monitoring custom metrics per customer (capture rate, drop rate, agent informer freshness, batches POSTed/sec, replay-run throughput, verdict latency, FPR per customer), per-customer health view in the dashboard, oncall runbooks for top operational scenarios, customer-facing status indicators, no-code path to onboard a new design partner.
**Depends on**: everything else (it observes them)
**Requirements**: OPS-01, OPS-02, OPS-03, OPS-04, OPS-05, OPS-06
**Success Criteria** (what must be TRUE):
  1. Per-customer health is visible in the dashboard with green/yellow/red indicators for capture rate, drop rate, agent informer freshness, batches POSTed/sec, replay-run throughput, verdict latency, and FPR.
  2. The team can answer "is everything healthy for design partner X right now?" from a single dashboard view, without crossing into Cloud Monitoring directly for routine queries.
  3. Oncall runbooks exist for the top operational scenarios (agent down on customer cluster, capture rate dropped to zero, replay engine wedged, verdict FPR alarm) and are linked from the dashboard alerts.
  4. A new design partner can be onboarded without a code change or redeploy — provisioning is configuration-only.
  5. Onboarding-funnel metrics from ONBOARD-06 appear in the same operational view as platform-side health (collector ingest, orchestrator, GitHub App), so the team has a single pane of glass for product + platform health.
  6. Customer-facing status indicators in the dashboard show platform-side health (collector ingest healthy, orchestrator healthy, GitHub App healthy), not only per-customer metrics.
**Plans**: TBD
**UI hint**: yes

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11 → 12

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Native Capture Cutover | 0/TBD | Not started | - |
| 2. gRPC + HTTP/2 Capture | 0/TBD | Not started | - |
| 3. Security Hardening Foundation | 0/TBD | Not started | - |
| 4. Replay Engine | 0/TBD | Not started | - |
| 5. Observation + Comparison + Verdict | 0/TBD | Not started | - |
| 6. Orchestration Saga | 0/TBD | Not started | - |
| 7. Web Dashboard | 0/TBD | Not started | - |
| 8. Self-Serve Onboarding | 0/TBD | Not started | - |
| 9. GitHub PR Integration | 0/TBD | Not started | - |
| 10. Slack Notifications | 0/TBD | Not started | - |
| 11. Pluggable Storage Backend | 0/TBD | Not started | - |
| 12. Beta Operations | 0/TBD | Not started | - |

---
*Roadmap created: 2026-05-13*
*Coverage: 93/93 v1 requirements mapped (file enumeration; the upstream 87 estimate was off-by-six)*
