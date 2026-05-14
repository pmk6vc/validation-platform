# Requirements: Validation Platform v1

**Defined:** 2026-05-14
**Core Value:** Replay real production traffic against staging and return a trustworthy go/no-go decision — within a self-serve developer experience that a design partner installs and gets value from in under 30 minutes.

> Categories below align with the 12-phase structure proposed in `.planning/research/SUMMARY.md`. The roadmapper assigns final phase numbers; category names are stable.

## v1 Requirements

### Capture cutover (CAPTURE) — Phase 1: Native eBPF capture replacing Kubeshark + Kotlin agent

- [ ] **CAPTURE-01**: Go eBPF tap captures HTTP/1.1 request/response pairs via kprobes on `sys_enter_write` / `sys_exit_read`, ring-buffer to userspace, TCP reassembly keyed by `(pid, fd)`, HTTP/1.1 parser extracting method / URL / headers / status / bodies / latency.
- [ ] **CAPTURE-02**: Pod-label enrichment via K8s informer (cgroup_id → pod metadata) attaches `namespace`, `pod`, and `app`-label to each captured pair. `(cgroup_id, observation_window)` treated as the attribution key — cgroup_id reuse after pod restart is quarantined for a configurable window.
- [ ] **CAPTURE-03**: Go agent reaches full feature parity with the Kotlin agent: static + dynamic config (`PLATFORM_URL`, `COLLECTOR_URL`, `API_KEY`, polled `DynamicConfig`), config polling, K8s service discovery + registration with `RegistrationOutcome` semantics (Success=201/409, PermanentRejection=400/422, TransientFailure=everything else), JWT bearer-token attachment, no Go-side validation.
- [ ] **CAPTURE-04**: Capture pipeline `tap → transformer (filter + sample) → batcher → collectorclient` runs as a goroutine pipeline coordinated by `context.Context`; gzip-encoded JSON POSTs to `/api/captured-inputs` byte-for-byte compatible with existing `BatchCreateCapturedInputRequest`; exponential-backoff retry (200ms → 1.6s → 6.4s, max 3) on transient errors; permanent errors fail fast.
- [ ] **CAPTURE-05**: `helm/vp-tap/` chart deploys a DaemonSet with scoped eBPF privileges (CAP_BPF + CAP_SYS_ADMIN, hostPID where needed, ConfigMap for static config, Secret reference for `API_KEY`); liveness probe verifies ring-buffer drainer progress; readiness probe blocks `Ready` until K8s informer's initial list-and-watch completes.
- [ ] **CAPTURE-06**: Prometheus metrics endpoint exposes ring-buffer fill ratio, drops/sec, HTTP pairs captured/sec, batches POSTed/sec, batch POST error rate by status code, service-registration outcome counters.
- [ ] **CAPTURE-07**: Kernel pre-flight on agent startup: BTF availability check + loopback HTTP self-test capturing a known-good request/response pair; agent refuses to register on failure and surfaces the reason to the dashboard onboarding state.
- [ ] **CAPTURE-08**: Tap survives a sustained sandbox pressure-test for ≥ 30 minutes without OOMs, panics, or drop rates above an agreed threshold; results recorded in a benchmark doc; per-node CPU + memory footprint fits the existing sandbox node pool with ≥ 20% headroom.
- [ ] **CAPTURE-09**: Default-deny redaction on sensitive request/response headers (`Authorization`, `Cookie`, `Proxy-Authorization`, `Set-Cookie`, `X-API-Key`) and pattern-based body redaction (JWT-shaped tokens, PAN-shaped digits, `sk_`/`pk_` prefixes) running at the agent **before** the collector network call; redacted values replaced with deterministic placeholders.
- [ ] **CAPTURE-10**: Sandbox cutover: `scripts/sandbox-up.sh` removes the Kubeshark `helm upgrade --install` block, installs `vp-tap` via `helm upgrade --install`, waits for the DaemonSet to reach `Ready`; `k8s/agent/overlays/sandbox/` no longer references Kubeshark URLs; the cutover PR(s) are revertible until decommission lands.
- [ ] **CAPTURE-11**: Decommission of the Kotlin agent + Kubeshark in small reviewable PRs: `agent/` Gradle module removed (and `settings.gradle.kts` updated); `KubesharkClient.kt`, Kubeshark wire-format DTOs, and Kubeshark fixtures deleted; `deploy/Dockerfile.agent` and `k8s/agent/` overlay tree removed; `CLAUDE.md` updated to describe the Go capture path.
- [ ] **CAPTURE-12**: Bilingual e2e coverage preserved: Kotlin `e2e-tests/` launches the Go agent binary or container alongside platform + collector; `AgentDiscoveryE2ETest` (or successor) covers K8s discovery → registration → traffic capture → collector ingest end-to-end against the Go agent; CI runs both `./gradlew test` and `go test ./...` on every PR.

### gRPC + HTTP/2 capture (GRPC) — Phase 2: Cover the half-cluster invisible to HTTP/1.1

- [ ] **GRPC-01**: Userspace dissector parses HTTP/2 frames from the reassembled byte stream: HEADERS, DATA, CONTINUATION; HPACK decoding maintains per-connection static + dynamic table state.
- [ ] **GRPC-02**: gRPC length-prefixed message framing parsed on top of HTTP/2 DATA frames; method = `:path` header, message bodies captured per stream.
- [ ] **GRPC-03**: Per-stream reassembly buffers are bounded with a hard memory cap per stream; oversize streams are dropped with a counter increment and a customer-visible drop indicator — not silently corrupted.
- [ ] **GRPC-04**: Attach-time HPACK state handled gracefully: if the tap attaches mid-connection and the dynamic-table state is unknown, the stream is skipped (counted, not corrupted) until a fresh connection starts.
- [ ] **GRPC-05**: Loopback self-test extended with a known-good gRPC request/response pair; agent refuses to register if gRPC capture is enabled and the self-test fails.
- [ ] **GRPC-06**: Dashboard exposes a capture-quality indicator per service: % successfully captured vs dropped (HTTP/1.1, HTTP/2, gRPC broken out).

### Security hardening (SEC) — Phase 3: Foundation that precedes every new data surface

- [ ] **SEC-01**: `CallerIdentity` sealed hierarchy in `shared/` (`Agent` | `User` | `Service`) replaces the current `AgentIdentity` principal; existing JWT validation paths populate the correct variant; all routes that read tenancy switch to the new principal.
- [ ] **SEC-02**: Multi-`kid` JWT signing: `installJwtAuth()` validates against an allowlisted set of active keys; new tokens are issued with a `kid` header; JWKS endpoint publishes all currently-valid public keys.
- [ ] **SEC-03**: `jwt_signing_keys` table tracks key lifecycle (active / retired-but-validating / revoked) with rotation tooling that adds a new key, ages out the previous, and revokes a compromised one without downtime; first rotation runs on the sandbox.
- [ ] **SEC-04**: Application connects to Postgres as a non-owner `app_role` (not the schema owner); migrations run as the owner role; pool-checkout sets `SET LOCAL app.current_org = <jwt_organization_id>` and pool-return runs `RESET app.current_org`.
- [ ] **SEC-05**: Postgres RLS enabled with `FORCE ROW LEVEL SECURITY` on every multi-tenant table; per-table rollout behind feature flags, soaked for ≥ 24 hours each before the next; existing indexes rebuilt with `organization_id` as the leading column where they cover tenant queries.
- [ ] **SEC-06**: Ktor interceptor sets the RLS context from the JWT principal on every `/api/*` request; CI integration tests run **as the application role** (not superuser) and explicitly verify cross-tenant queries return zero rows.
- [ ] **SEC-07**: Every new table introduced from Phase 4 onward is born with RLS on and `organization_id` as the leading index column — no retrofitting.
- [ ] **SEC-08**: Collector-side redaction second pass: agent-side redaction (CAPTURE-09) is mirrored on ingest as a defense-in-depth check; any payload matching the redaction pattern in a non-redacted field is rejected with a counter increment.
- [ ] **SEC-09**: `DynamicConfig` exposes a per-org redaction allowlist (additional headers to redact, body redaction patterns) wired from platform to agent and applied before forwarding.

### Replay engine (REPLAY) — Phase 4: First control-plane module

- [ ] **REPLAY-01**: `orchestrator/` Ktor module created on port 8082; depends on `:shared` only; owns its `replay_runs` table (RLS-on, `organization_id` leading index, created in this phase's migration).
- [ ] **REPLAY-02**: `ReplayDispatcher` supports two fidelities: `sequential` (one request in flight at a time) and `actual` (emulate production concurrency + rate up to a configured ceiling).
- [ ] **REPLAY-03**: `actual` mode enforces a **two-knob ceiling** — both RPS and concurrent-in-flight limits, both required; default ceiling is 10% of captured production rate; customer opts up explicitly per replay run.
- [ ] **REPLAY-04**: Circuit breaker on staging error rate during replay: if staging 5xx rate or timeout rate crosses a configured threshold, the replay pauses and the run is marked INCONCLUSIVE with reason `staging_error` — the engine does not continue computing a verdict from melted staging.
- [ ] **REPLAY-05**: Read-only filtering by default: only requests matching the safe-method allowlist (`GET`, `HEAD`, configurable per-endpoint override) are replayed. Write requests are skipped with a counter, not silently included.
- [ ] **REPLAY-06**: `POST /api/replay-runs` accepts target service + capture-window selector + fidelity + ceiling; returns `replay_run_id`. `GET /api/replay-runs/{id}` returns status (`pending` / `running` / `complete` / `failed` / `inconclusive`), progress, and the captured responses for downstream comparison.
- [ ] **REPLAY-07**: Replay run output (per-request observed response: status, body, headers, latency) is persisted to `replay_responses` (RLS-on); each row keyed back to the originating captured-input id.
- [ ] **REPLAY-08**: Token-bucket rate limiter implemented with Resilience4j Kotlin; concurrency limiter via `kotlinx-coroutines` `Semaphore` + bounded `Channel`.

### Observation + Comparison + Verdict (VERDICT) — Phase 5: Produces the v1 output

- [ ] **VERDICT-01**: `observer/` package inside `orchestrator/` polls K8s Metrics API for pod CPU + memory and pulls outbound connection counts from the staging tap during replay; samples persisted per replay run.
- [ ] **VERDICT-02**: `comparison/` library is a pure-function module (no I/O, no state) imported directly by `orchestrator/` — the one allowed cross-module compile dependency.
- [ ] **VERDICT-03**: Response-diff comparator with auto-detected noisy-field elision: a field is treated as noisy if it varies across baseline samples; built-in patterns elide UUIDs, ISO-8601 timestamps, hex strings, monotonic counters; what was diffed and what was elided is surfaced in the verdict evidence.
- [ ] **VERDICT-04**: Latency comparator uses Mann-Whitney U with Benjamini-Hochberg correction across endpoints; verdict requires BOTH `p < alpha` AND `|effect size| > threshold` (no thresholds, no single-test cherry-picking).
- [ ] **VERDICT-05**: Error-rate comparator computes status-code class deltas per endpoint (2xx / 4xx / 5xx); flagged dimensions surface specific failing endpoints with examples.
- [ ] **VERDICT-06**: Memory-trend comparator runs linear regression on per-pod memory samples with a warm-up window excluded; positive slope above a configured threshold is a fail signal.
- [ ] **VERDICT-07**: `validations` and `verdicts` tables (RLS-on); verdict rollup is PASS / FAIL / INCONCLUSIVE with per-dimension status and evidence references.
- [ ] **VERDICT-08**: INCONCLUSIVE reason codes are first-class: `insufficient_traffic`, `staging_error`, `shape_mismatch`, `correction_applied`, `attribution_uncertain`; each surfaces a one-line explanation downstream.
- [ ] **VERDICT-09**: Baseline-vs-baseline calibration test suite ships as a release gate: a deploy that lifts the verdict false-positive rate above 5% on the calibration suite is blocked from being promoted.
- [ ] **VERDICT-10**: Statistical methods use Hipparchus `hipparchus-stat:4.0.1` (Mann-Whitney U, linear regression).

### Orchestration saga (ORCH) — Phase 6: End-to-end automation

- [ ] **ORCH-01**: `POST /api/validations` accepts target service + candidate image (or commit SHA) and starts a saga; `GET /api/validations/{id}` returns the saga state and the terminal verdict.
- [ ] **ORCH-02**: `ValidationSaga` state machine persisted in Postgres; workers claim work via `SELECT FOR UPDATE SKIP LOCKED`; step handlers are idempotent (re-runnable on cold restart without producing duplicate side effects).
- [ ] **ORCH-03**: Saga steps: select capture window (v1: last 5 minutes — dumb is fine), trigger baseline replay, deploy candidate to staging (image-tag swap or customer-provided hook), trigger candidate replay, run comparison, persist verdict, fan-out to GitHub App / Slack / dashboard.
- [ ] **ORCH-04**: Saga is observable: every transition emits a structured log; `GET /api/validations/{id}` returns a progress timeline with per-step duration; dashboard renders the timeline.
- [ ] **ORCH-05**: Saga failure modes are distinct from verdict outcomes: a saga that fails to dispatch replay returns 5xx-equivalent state, not a verdict.
- [ ] **ORCH-06**: Baseline and candidate runs are interleaved (not back-to-back) within the same time window to neutralize staging drift between runs.

### Web dashboard (UI) — Phase 7: Primary user surface, greenfield

- [ ] **UI-01**: Vite + React 19 + TypeScript + Tailwind 4 + shadcn/ui + TanStack Query v5 + TanStack Router v1 + Recharts 3 static bundle; built into `dashboard/dist/` and served from a Cloud Run container running nginx.
- [ ] **UI-02**: Auth via Google OIDC; access tokens kept in memory; httpOnly refresh cookie for session; all dashboard pages auth-gated.
- [ ] **UI-03**: Routes: `/` (org overview + onboarding state), `/services` (services + last-seen + capture quality), `/captures` (captured-traffic explorer with per-service filter + endpoint drill-in), `/validations` (validation-run history), `/validations/:id` (verdict drill-in per the external Claude Design artifact), `/settings` (API keys, redaction config, GitHub/Slack install), `/install` (agent install instructions with pre-filled commands).
- [ ] **UI-04**: Verdict drill-in shows headline PASS / FAIL / INCONCLUSIVE, then per-dimension breakdown (response diffs, latency, error rate, memory trend) with evidence: examples, percentiles, regression lines; click to drill into specific endpoint diffs.
- [ ] **UI-05**: INCONCLUSIVE reason codes (VERDICT-08) rendered with a one-line plain-language explanation in the UI.
- [ ] **UI-06**: Captured-traffic explorer never auto-loads raw request bodies in lists; bodies require an explicit row click and are shown with redaction indicators visible.
- [ ] **UI-07**: ETA + progress for in-progress runs uses the saga timeline from ORCH-04.
- [ ] **UI-08**: Empty states are explicit and intent-shaped ("No captures yet — install the agent in your cluster" with a deep link to install instructions); not "0 results."
- [ ] **UI-09**: Direct JWT-authenticated calls to platform (8080), collector (8081), orchestrator (8082); no BFF in v1.
- [ ] **UI-10**: Dashboard renders the agent install instructions with a one-click copy for the Helm command pre-filled with the org's API key.

### Self-serve onboarding (ONBOARD) — Phase 8: Closes the sub-30-minute promise

- [ ] **ONBOARD-01**: Signup via Google OIDC creates a new `User` with no org; a follow-up step creates the `Organization` with the user as initial owner.
- [ ] **ONBOARD-02**: `onboarding_state` table + state machine: signed-up → org-created → api-key-issued → helm-shown → first-capture-seen → first-validation-run → first-verdict — resumable across sessions, visible in the dashboard.
- [ ] **ONBOARD-03**: Pre-flight checks before showing the Helm command: kernel BTF available, sandbox cluster reachable, required RBAC scopable (namespace-admin, not cluster-admin), service selectors meeting the `app=<name>` requirement on the customer's target services — each surfaced with an explicit remediation if it fails.
- [ ] **ONBOARD-04**: Synthetic loopback traffic generated on first agent install so the customer sees their first capture within seconds, not whenever real traffic happens.
- [ ] **ONBOARD-05**: Helm install command defaults to namespace-admin RBAC scope, not cluster-admin; documented permissions list in the dashboard alongside the command.
- [ ] **ONBOARD-06**: Onboarding funnel observability: per-step completion time and drop-off counter; per-customer view in the dashboard.
- [ ] **ONBOARD-07**: Time-to-first-verdict (signup → first verdict) measured per customer; benchmark target: median < 30 minutes for a developer with no prior context.

### GitHub PR integration (PR) — Phase 9: Move the verdict to the PR

- [ ] **PR-01**: `github-app/` Ktor module deployed as a separate Cloud Run service with its own scaling profile.
- [ ] **PR-02**: HMAC webhook receiver validates signatures; writes to an in-Postgres `github_webhook_events` async queue; responds 202 immediately; an async worker processes events.
- [ ] **PR-03**: `github_installations` table records install metadata per org; PR-time validation runs map PR commit SHAs to candidate images.
- [ ] **PR-04**: Check Run posted per validation run: PASS → `success`, FAIL → `failure`, INCONCLUSIVE → `neutral`; deep link to the verdict page in the dashboard.
- [ ] **PR-05**: PR comment with headline verdict, top-N most-significant diffs by effect size, INCONCLUSIVE reason if applicable, deep link.
- [ ] **PR-06**: Check is **non-blocking by default**; per-repo opt-in to required-check status only available after the customer's measured FPR is below 5% across ≥ 50 baseline-vs-baseline calibration runs.
- [ ] **PR-07**: Per-installation rate limiting + circuit breaker; runaway repos do not break the GitHub App for other installations.
- [ ] **PR-08**: GitHub App auth uses `hub4j:github-api:1.327+` with BouncyCastle PEM and `nimbus-jose-jwt` for the GitHub App JWT (separate key from the platform RS256 JWT).

### Slack notifications (SLACK) — Phase 10: Close the alert loop

- [ ] **SLACK-01**: `slack_installations` table records channel webhook URLs (encrypted at rest via Cloud KMS or equivalent); installation flow lives alongside the GitHub App install in onboarding.
- [ ] **SLACK-02**: Outbound-only Slack client (`slack-api-client:1.45+`); platform never accepts inbound Slack interactions in v1.
- [ ] **SLACK-03**: Verdict notifications posted on completion: headline + dashboard deep link only — no raw captured request snippets in Slack.
- [ ] **SLACK-04**: Anomaly alerts (e.g. capture-rate-zero, agent unhealthy) posted on detection.
- [ ] **SLACK-05**: Per-channel throttle to prevent fatigue; no-self-notify for the user who triggered the run by default.

### Pluggable storage (STORE) — Phase 11: Skeptical-customer data-boundary answer

- [ ] **STORE-01**: `CapturedInputStorage` interface in `collector/` abstracts captured-input body persistence; metadata (id, service, latency, redaction status, etc.) stays in Postgres regardless.
- [ ] **STORE-02**: `HostedPostgresStorage` default implementation persists bodies in Postgres (existing behavior); no behavior change for default-hosted customers.
- [ ] **STORE-03**: `S3PlusPostgresMetadataStorage` implementation persists bodies as blobs in S3 (or GCS via the S3-compat layer) with a Postgres pointer in the metadata row; AWS SDK v2 `s3` client.
- [ ] **STORE-04**: Storage backend selected per collector instance via config (not per-tenant routing inside one collector); switching backends requires a redeploy.
- [ ] **STORE-05**: Collector startup runs a storage-schema-version check; mismatch → hard-fail with a clear error; never silent.
- [ ] **STORE-06**: Operational contract documented: customer-side storage is opt-in, white-glove enablement, with explicit responsibility split (customer owns S3 bucket + IAM; we own the schema and migrations).

### Beta operations (OPS) — Phase 12: Observe the whole stack

- [ ] **OPS-01**: Cloud Monitoring custom metrics per customer: capture rate, drop rate, agent informer freshness, batches POSTed/sec, replay-run throughput, verdict latency, FPR per customer.
- [ ] **OPS-02**: Per-customer health view in the dashboard surfaces the above metrics with green/yellow/red indicators.
- [ ] **OPS-03**: Oncall runbooks for the top operational scenarios: agent down on a customer cluster, capture rate dropped to zero, replay engine wedged, verdict FPR alarm.
- [ ] **OPS-04**: No-code path to onboard a new design partner — adding a partner does not require a code change or a redeploy.
- [ ] **OPS-05**: Status indicators in the dashboard show platform-side health (collector ingest healthy, orchestrator healthy, GitHub App healthy) — not just per-customer.
- [ ] **OPS-06**: Onboarding-funnel metrics from ONBOARD-06 visible to the team in a single view.

## v2 Requirements

Deferred to a future milestone. Tracked but not in current roadmap.

### Customer security (post-beta)

- **V2-CMEK-01**: BYO encryption key (customer-managed KMS) for at-rest encryption of captured-input bodies; key rotation + revocation paths.

### Capture coverage extensions

- **V2-TLS-01**: TLS / encrypted-traffic capture via userspace uprobes on libssl / OpenSSL / Go crypto for services without mesh-side termination.
- **V2-MQ-01..N**: Message-queue capture (Kafka, PubSub, SNS/SQS via consumer-group fan-out).

### Replay extensions

- **V2-REPLAY-01**: Write-traffic replay with customer-provided DB reset hook; safe-method allowlist override per endpoint.
- **V2-REPLAY-02**: Smarter capture-window selection (vs v1's "last 5 minutes" dumb default).
- **V2-REPLAY-03**: Per-endpoint diff ignore rules; per-service configurable verdict thresholds.

### Surface area

- **V2-PR-01**: Inline PR diff comments on lines suspected of regressing behavior.
- **V2-CLI-01**: `vp` CLI for power users (`vp validate run`, `vp validate status`, `vp validate verdict`).
- **V2-UX-01**: AI-generated natural-language summary of what changed and why.

### Platform extensions

- **V2-CUSTSTORE-01**: Customer-side collector deployment (not just pluggable storage backend in our collector).
- **V2-MULTI-01**: Multi-cluster federation.
- **V2-AUTO-01**: Auto-rollback / deployment integration (Argo / Flux); anomaly-triggered rollback.

### GA polish

- **V2-GA-01..N**: Billing, pricing tiers, public marketing site, SLA, status page, terms-of-service flow.

## Out of Scope

| Feature | Reason |
|---------|--------|
| Public GA polish (billing, SLA, status page) | Design-partner beta is v1 done. GA is the next milestone. |
| BYO encryption key (CMEK) | No design partner has asked. Pluggable storage already addresses data-boundary. Re-add when a partner requires it. |
| Customer-side collector deployment | Pluggable storage covers the same concern with less topology change. |
| LOAD-mode replay (uncapped prod rate) | Explicitly replaced by `actual` mode with a ceiling we control. Risk of melting staging is a hard no. |
| Write-traffic replay with DB reset hook | Read-only is the v1 bar. Write replay needs customer ops cooperation and a reset story; deferred. |
| Adaptive concurrency beyond the configured ceiling | Per the existing design principle. The ceiling is a knob; the engine does not auto-tune. |
| Multi-cluster federation | Single-cluster scope retained; no design-partner demand. |
| Message queue capture (Kafka / PubSub / SNS / SQS) | Already deferred at the architecture level. |
| CLI in v1 | Web + PR + Slack surfaces cover the design-partner use case. |
| Mobile app | Web-first. |
| TLS uprobes for encrypted-traffic capture | v1 assumes plaintext-side capture (post-sidecar termination). Customer environments without that land post-beta. |
| Inline PR diff comments | Check Run + comment + deep link is the v1 bar. Inline diffs are a larger product surface. |
| Per-tenant storage backend routing inside one collector | Per-instance routing is enough; per-tenant adds operational complexity without v1 demand. |
| Postgres `kid` rotation via KMS asymmetric-sign | Latency overhead (10–30 ms per validation) not justified. Secret Manager + multi-key validation is sufficient. |
| Next.js or any Node runtime in production for the dashboard | Static bundle on Cloud Run + nginx is sufficient; Node runtime adds infra surface for zero benefit. |

## Traceability

Filled by the roadmapper. Empty for now.

| Requirement | Phase | Status |
|-------------|-------|--------|
| CAPTURE-01..12 | TBD | Pending |
| GRPC-01..06 | TBD | Pending |
| SEC-01..09 | TBD | Pending |
| REPLAY-01..08 | TBD | Pending |
| VERDICT-01..10 | TBD | Pending |
| ORCH-01..06 | TBD | Pending |
| UI-01..10 | TBD | Pending |
| ONBOARD-01..07 | TBD | Pending |
| PR-01..08 | TBD | Pending |
| SLACK-01..05 | TBD | Pending |
| STORE-01..06 | TBD | Pending |
| OPS-01..06 | TBD | Pending |

**Coverage:**
- v1 requirements: 87 total
- Mapped to phases: 0
- Unmapped: 87 (roadmapper will resolve)

---
*Requirements defined: 2026-05-14*
*Last updated: 2026-05-14 after initial definition*
