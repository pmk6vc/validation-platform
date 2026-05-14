<!-- refreshed: 2026-05-14 -->
# Concerns

**Analysis Date:** 2026-05-14

Findings are categorized by area and ordered by severity within each. Each item includes a location, the issue, why it matters, and a suggested follow-up.

## Security

### S1 — Admin role not enforced on organization creation
- **Where:** `platform/src/main/kotlin/com/platform/api/Routes.kt` (organization route handler).
- **What:** The route documents an admin-only requirement (see CLAUDE.md and TODO in `Routes.kt`), but does not check `AgentIdentity.role`. Any valid JWT can create organizations.
- **Why it matters:** Multi-tenant boundary violation. A compromised agent token could provision new orgs.
- **Follow-up:** Add role check on `POST /api/organizations`; reject with 403 when `role != "admin"`. Backfill a test in `OrganizationRoutesTest`.

### S2 — No request-header redaction on capture
- **Where:** `agent/src/main/kotlin/com/platform/agent/TrafficTransformer.kt` (capture path that forwards `request.headers` and `response.headers` verbatim).
- **What:** Customer-sensitive headers (`Authorization`, `Cookie`, `X-API-Key`, vendor session headers) are forwarded to the collector without filtering.
- **Why it matters:** Captured inputs become a secondary store of bearer tokens and cookies. Blocks compliance-sensitive deployments and inflates the blast radius of any collector DB breach.
- **Follow-up:** Add a configurable header allowlist/blocklist to `DynamicConfig`; default-deny `Authorization`, `Cookie`, `Proxy-Authorization`, `Set-Cookie`.

### S3 — JWT private key lives in env vars with no rotation
- **Where:** `shared/src/main/kotlin/com/platform/shared/auth/JwtAuth.kt` (reads `JWT_PRIVATE_KEY`).
- **What:** RSA private key is materialized in process memory from an env var; no rotation mechanism, no KMS integration.
- **Why it matters:** Compromise of any container's environment leaks the root signing key for all tenants. No way to rotate without simultaneous redeploy of platform, collector, and every agent.
- **Follow-up:** Move key material behind GCP Secret Manager / KMS. Support multi-key validation (kid header) so rotation can be staged. See Linear `[ARCH-*]` if a tech-debt ticket exists.

### S4 — `bootstrap-db.sh` rotates `postgres` superuser password to a random value
- **Where:** `scripts/bootstrap-db.sh`.
- **What:** Sets a random password on `postgres` for one privileged session, runs `GRANT ALL ON SCHEMA public TO <sa>`, then rotates to another random value nobody records.
- **Why it matters:** Intentional design ("no static credential exists"), but the operation is idempotent only because IAM auth is the actual access path. If IAM auth ever breaks, no human or script can rotate the superuser back without manual GCP console work. Document this trade-off so an oncall engineer doesn't waste hours trying to recover the password.
- **Follow-up:** Add a runbook note in `scripts/bootstrap-db.sh` and reference it from CLAUDE.md's bootstrap section.

## Reliability

### R1 — Kubeshark WebSocket dedup window is fragile under reconnect storms
- **Where:** `agent/src/main/kotlin/com/platform/agent/KubesharkClient.kt` (5s `lastSeenTimestamp` lookback).
- **What:** Reconnect dedup assumes Kubeshark replays at most ~4-5s of history. Under aggressive reconnects or clock skew, duplicates leak through; under long-lived sessions with bursty load, the bounded `Channel<KubesharkEntry>(1000)` will start suspending sends — TCP backpressure is the intended outcome but could starve high-volume customers.
- **Why it matters:** Captured-input counts inflate (corrupting validation runs) or fall behind production rate (missing edge cases). Both undermine the platform's core value prop.
- **Follow-up:** Add metrics for channel utilization and dedup-drop rate. Stress-test under 10x burst load. Consider an ID-based LRU dedup instead of timestamp window if the trade-off proves wrong.

### R2 — `RegistrationOutcome` classification could silently retry forever
- **Where:** `agent/src/main/kotlin/com/platform/agent/PlatformClient.kt` and the `serviceDiscoveryLoop()` in `AgentApplication.kt`.
- **What:** `PermanentRejection` is narrowed to 400/422 only — by design (so caller-level 401s don't poison every service). But a misconfigured target (404 on the platform, 405 wrong method) would also retry forever on every discovery tick.
- **Why it matters:** Slow burn — discovery logs fill with errors but the agent keeps "working." Operationally invisible until someone reads logs.
- **Follow-up:** Surface a retry-count gauge per service; warn when a single service has > N failed registrations. Consider distinguishing 404 (wrong URL — permanent operational error) from 5xx (transient).

### R3 — No request size limits on collector ingest
- **Where:** `collector/src/main/kotlin/com/platform/collector/api/Routes.kt` (`POST /api/captured-inputs`).
- **What:** Batch ingest accepts arbitrary body size; no `maxRequestSize` or per-batch entry-count guard at the route level.
- **Why it matters:** A misconfigured or malicious agent can OOM the collector with a single giant POST. With Cloud Run instances at default memory, this is a one-request DoS.
- **Follow-up:** Enforce a max body size (e.g. 10 MB compressed) and a hard cap on `items.size` (`MAX_BATCH_SIZE` exists — apply it in the route handler).

### R4 — V0006 removed FK without application-layer integrity check
- **Where:** `shared/src/main/resources/db/migration/V0006__drop_captured_inputs_service_fk.sql`.
- **What:** `captured_inputs.service_id` is now a plain string with no DB FK; the agent stamps service IDs from its in-memory `name → serviceId` map, but no application code rejects unknown IDs at ingest.
- **Why it matters:** Intentional decoupling, but a stale agent (still holding deleted service IDs) silently writes dangling rows. The replay engine will need to handle missing service references gracefully.
- **Follow-up:** Decide whether the collector should soft-validate service IDs against a cached or queried set, or whether the replay engine swallows missing-service errors. Document the chosen invariant.

### R5 — k8s discovery silently skips services missing the `app` selector label
- **Where:** `agent/src/main/kotlin/com/platform/agent/K8sServiceDiscovery.kt`.
- **What:** Services without `spec.selector.app == metadata.name` are filtered out without surfacing the reason; users see "service didn't get captured" with no signal why.
- **Why it matters:** Common onboarding pitfall — selector mismatches are the #1 reason capture appears broken. Silent skipping costs onboarding time.
- **Follow-up:** Log a one-time WARN per skipped service per agent lifetime, including the actual selector seen. Optionally surface skip reasons in `GET /api/agent/config` response so the platform can show them.

## Performance

### P1 — HikariCP pool size hard-coded default (10) without Cloud Run calibration
- **Where:** `shared/src/main/kotlin/com/platform/shared/database/DatabaseFactory.kt` (reads `DATABASE_POOL_SIZE`, defaults to 10).
- **What:** Cloud Run scales instances horizontally; each instance opens its own pool. Total connections to Cloud SQL = instances × pool size. With default min-instances=0, max-instances=N, this can spike connections and blow Cloud SQL limits.
- **Why it matters:** Cloud SQL `max_connections` is finite (~100 on small tiers). Aggressive scale-up under load can exhaust the pool and 503 the API.
- **Follow-up:** Lower pool size per instance (e.g. 4) and document the math in CLAUDE.md. Linear `[ARCH-4]` already tracks this.

### P2 — Kubeshark entry parsing has no per-frame timeout
- **Where:** `agent/src/main/kotlin/com/platform/agent/KubesharkClient.kt` (frame read loop).
- **What:** A malformed or partial WebSocket frame can stall the read loop indefinitely; the client relies on TCP keepalive / WebSocket ping for recovery.
- **Why it matters:** Tail-latency events in the agent block traffic capture. The bounded channel buffers ~1000 entries but won't recover if the underlying read is wedged.
- **Follow-up:** Add `withTimeout(...)` around frame reads; trigger reconnect on timeout.

## Tech Debt

### T1 — Replay engine, observation, comparison, and verdicts are unimplemented
- **Where:** Phase 4 and Phase 5 in `CLAUDE.md`.
- **What:** The core value prop ("validate every change against real prod traffic") depends on the replay engine + comparison verdict. None of it exists yet — only the capture/agent pipeline.
- **Why it matters:** Platform currently only stores traffic. Until replay ships, value-prop messaging in CLAUDE.md/README outpaces the codebase.
- **Follow-up:** Prioritize `ReplayRun` model, `ReplayEngine` send/observe loop, and `ComparisonEngine` per the delivery plan.

### T2 — PCAP-based architecture remnants
- **Where:** Sparse — verify with grep for "PCAP", "Envoy", "record-replay" across `agent/`, `platform/`, `collector/`, `docs/`.
- **What:** Architecture pivoted from PCAP-based DB capture to staging-based validation (TLS blocker on prod DB capture). Comments/docs/code referring to PCAP may still exist.
- **Why it matters:** Misleading reads for new contributors; potential dead code.
- **Follow-up:** Grep + sweep. Delete anything that no longer describes how the system works. CLAUDE.md is already updated.

### T3 — Message queue capture (Kafka/PubSub/SNS/SQS) is documented but absent
- **Where:** `CLAUDE.md` "Message Queue Support (Future, De-Risked)" section.
- **What:** Described as a future feature with built-in fan-out designs; no code yet.
- **Why it matters:** Customers with event-driven services can't be validated end-to-end. Won't block MVP, but reset expectations.
- **Follow-up:** Defer until a customer asks. Keep CLAUDE.md's "(Future)" label.

### T4 — Two parallel ecosystems: Kotlin platform + Go `tap/`
- **Where:** Root vs `tap/` directory; separate build (`go.mod`) and tooling.
- **What:** The Go `tap` is an experimental eBPF traffic-attribution component. It doesn't participate in the Gradle build and isn't referenced from any Kotlin code.
- **Why it matters:** Per project guidance ("Don't couple where coupling doesn't exist"), this is fine — but make sure CI runs Go-side checks independently. Otherwise rot is invisible.
- **Follow-up:** Confirm a separate CI job exists for `tap/` (or add one). Track Go-side changes in commits with a `tap:` scope prefix (already happening in git log).

## Test Gaps

### TG1 — No coverage tool wired
- **Where:** `build.gradle.kts` at root; absent in `libs.versions.toml`.
- **What:** Kover/JaCoCo not configured. No way to track coverage drift.
- **Why it matters:** Repository-wide coverage trends are invisible. Hard to argue for or against test investment.
- **Follow-up:** Add Kover (Kotlin-native) at root with per-module aggregation. Wire into CI as a non-blocking report.

### TG2 — End-to-end coverage skews to happy paths
- **Where:** `e2e-tests/src/main/kotlin/com/platform/e2e/`.
- **What:** `AgentDiscoveryE2ETest` covers the success path. Cross-tenant isolation, JWT expiry, malformed tokens, and oversize batches are mostly route-level unit tests, not e2e.
- **Why it matters:** Auth boundaries are highest-value to e2e because misconfigurations are systemic.
- **Follow-up:** Add e2e cases for: wrong-org JWT trying to read another org's services; expired JWT rejected at `/api/services`; oversize batch rejected by collector.

### TG3 — Kubeshark client tests rely on MockEngine, not a real WebSocket
- **Where:** `agent/src/test/kotlin/com/platform/agent/KubesharkClientTest.kt` (if exists; otherwise scattered).
- **What:** Persistent-session, reconnect-dedup, and KFL-update logic is hard to fully exercise without a real WS server.
- **Why it matters:** The fragile parts (R1, P2) live exactly where coverage is thinnest.
- **Follow-up:** Stand up a TestContainers Kubeshark or a lightweight WS test double; cover reconnect-replay, KFL-change reconnect, and channel saturation.

## Operational Risk

### O1 — Agent liveness probe is file-based, not real health
- **Where:** `k8s/agent/base/agent.yaml`; `agent/src/main/kotlin/com/platform/agent/AgentApplication.kt` (touches `/tmp/agent-alive`).
- **What:** Liveness signal is "the JVM is alive and touched a file recently." Says nothing about whether Kubeshark WS is connected, config polling is working, or any traffic is being captured.
- **Why it matters:** A dead WebSocket loop doesn't trigger a restart; the agent appears healthy while silently capturing nothing.
- **Follow-up:** Tier the probe: liveness = touch file; readiness = "WS connected and config polled in the last 2× interval." Surface a `/health` HTTP endpoint with sub-checks.

### O2 — Image pull policy `Always` in sandbox
- **Where:** `k8s/agent/overlays/sandbox/`.
- **What:** Sandbox overlay sets `imagePullPolicy: Always` for rapid iteration. Fine for sandbox; ensure prod overlays pin a digest and use `IfNotPresent`.
- **Why it matters:** Production rollouts with `Always` + mutable tag = unreproducible deploys.
- **Follow-up:** Verify any future prod overlay pins by image digest, not `:latest`.

### O3 — No alerting on capture pipeline gaps
- **Where:** General (no dashboards/alerts directory yet).
- **What:** Capture rate dropping to zero is the most important signal for the platform's value, and there's no Grafana board or alert wired.
- **Why it matters:** Silent capture failure = silent loss of validation coverage.
- **Follow-up:** Add Cloud Monitoring metrics for `collector POST` rate, agent registration success/failure counts, and alert on sustained zero.

---

*Concerns analysis: 2026-05-14*
