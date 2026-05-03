# Architecture Review — Validation Platform

**Last updated:** 2026-05-02
**Reviewer:** Claude (architecture-reviewer agent)
**Scope:** Single source of truth for architecture state, product roadmap, and tech debt. Supersedes and absorbs PLAN.md. Covers all work through PR #107 (end-to-end traffic flow on GCP) plus the full build-out path to first customer and beyond.

---

## Mission

Two parallel tracks, one dependency constraint.

**Track 1 — Build the product.** End-to-end capture → replay → compare flow that produces a statistically grounded verdict on a PR. Without this, there is no product to harden. Track 1 is the prerequisite for Track 2.

**Track 2 — Harden for first customer.** Security, onboarding, and integrity hardening so a customer can deploy the agent in their cluster, pass a serious security review, and trust the verdicts. Track 2 items vary by customer maturity gate: some (PII redaction, NetworkPolicy) block customer #1; others (RLS, JWT rotation) block customer #5; others (OLAP export) block enterprise scale.

The two tracks are not fully parallel: you cannot onboard a customer until the product exists, and a verdict is only trustworthy once chain-of-custody on captured inputs is in place. The sequencing constraint is: **REPLAY-1 through REPLAY-7 before any customer sees a verdict**. MVP-1 (PII redaction) and MVP-3 (NetworkPolicy) can and should run in parallel with replay engine work since they touch completely different parts of the codebase.

---

## Status — What's Shipped

| Area | Reference |
|------|-----------|
| Cloud Run platform + collector with JDBC IAM auth | PRs #61, #66, #86, #87, #88 |
| Sandbox GKE cluster Terraform | `infra/sandbox/cluster.tf` |
| In-app RS256 JWT auth (Envoy removed) | PRs #71–#75 |
| Per-tenant authorization on `/api/*` | PR #81 |
| Agent → collector gzip POST | PR #80 |
| Structured JSON logging | PR #78 |
| Org seeding + agent JWT (`seed-org.sh`) | PR #96 |
| Agent service discovery loop (Loop 1) | PR #104 |
| Sandbox kustomize overlay | PR #103 |
| e2e test K8s → agent → platform | PR #106 |
| Registration outcome classification | PR #105 |
| `sandbox-up.sh` wires Kubeshark + agent end-to-end | PR #107 |
| CI: build + push + deploy on merge to main (incl. agent image) | `.github/workflows/push_main.yml` |

**What this means:** production-style traffic now flows through Kubeshark → agent → Cloud Run collector on real GCP. The capture loop is real. The replay engine is the next workstream and the critical path to the product.

---

## Catalog 1: Replay Engine MVP

The replay engine is the core product. Without it, the platform captures traffic but produces no verdict. These entries are in dependency order: read P0s top-to-bottom as the execution sequence.

---

### P0 — Critical Path to First Verdict

---

**REPLAY-1: `collected_at` Column + Stable Pagination Cursor (V0008 Migration)**

- **What:** Add a `collected_at TIMESTAMPTZ NOT NULL DEFAULT now()` column to `captured_inputs` via a V0008 migration. This is a server-assigned timestamp, not the agent-supplied `captured_at`. Add an index: `CREATE INDEX idx_captured_inputs_collected_at ON captured_inputs(collected_at, id)`. Update `CapturedInputRepository.find()` to sort and cursor on `(collected_at, id)` instead of `(captured_at, id)`. Retain `captured_at` as a queryable analytics field (the agent's wall-clock time is useful for latency analysis, but dangerous as a pagination key).
- **Why P0:** The replay engine fetches captured inputs via paginated `GET /api/captured-inputs`. The current cursor sorts on `captured_at` (agent wall-clock time from Kubeshark's `entry.timestamp`). Kubeshark delivers entries with up to 5 seconds of out-of-order jitter; the agent applies a 5s dedup window. This means a batch can contain entries whose `captured_at` predates the previous batch's cursor — those entries are silently skipped during cursor-paginated reads. Silent gaps = requests never replayed. Silent duplicates (from agent retries) = requests replayed twice. Both produce a wrong verdict with no error signal. A server-assigned `collected_at` is monotonic at insert time and makes cursor pagination sound.
- **Acceptance:** V0008 migration runs cleanly on the existing schema. `CapturedInputRepository.find()` sorts on `(collected_at, id)`. Integration test: insert 5 rows with out-of-order `captured_at` values; verify cursor-paginated reads return all 5 in `collected_at` order with no gaps. `captured_at` remains queryable via a filter parameter.
- **Effort:** S (migration + two-line repository change + index + test)
- **Depends on:** None
- **Note:** This fixes ARCH-6 from Catalog 3. Once REPLAY-1 ships, ARCH-6 is resolved.

---

**REPLAY-2: `ReplayRun` Model + V0009 Migration (New Module)**

- **What:** Create a new `replay/` module (owns `replay_runs` table). V0009 migration:
  ```sql
  CREATE TABLE replay_runs (
      id          UUID PRIMARY KEY,
      org_id      UUID NOT NULL,
      service_id  UUID NOT NULL,
      status      TEXT NOT NULL DEFAULT 'PENDING',  -- PENDING, RUNNING, COMPLETED, FAILED
      fidelity    TEXT NOT NULL DEFAULT 'QUICK',    -- QUICK, STANDARD, LOAD
      read_only   BOOLEAN NOT NULL DEFAULT TRUE,
      target_url  TEXT NOT NULL,
      started_at  TIMESTAMPTZ,
      finished_at TIMESTAMPTZ,
      created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
      -- Denormalized verdict fields (populated by ComparisonEngine)
      verdict     TEXT,                             -- PASS, FAIL, INCONCLUSIVE, NULL until complete
      evidence    JSONB
  );
  CREATE INDEX idx_replay_runs_org_id ON replay_runs(org_id);
  CREATE INDEX idx_replay_runs_service_id ON replay_runs(service_id);
  ```
  The `replay/` module owns this migration and follows the module-boundary pattern: no DB-level FK to `services` or `captured_inputs` (application-layer integrity only, consistent with V0006). Data class `ReplayRun` with `@JvmInline value class ReplayRunId(val value: String)` following the `OrganizationId`/`ServiceId` pattern in `shared/`.
- **Why P0:** Every subsequent REPLAY entry depends on a `ReplayRun` to attach results to. This is the anchor data model.
- **Acceptance:** `ReplayRunRepository.create()`, `findById()`, `updateStatus()`, `updateVerdict()` all work in tests against TestContainers Postgres. `ReplayRunId` is UUID-validated at construction. Module does not import `CapturedInputRepository` directly.
- **Effort:** M (module scaffolding + migration + model + repository + tests)
- **Depends on:** None

---

**REPLAY-3: `ReplayEngine` — HTTP Replay Against Staging Target**

- **What:** `ReplayEngine` in the `replay/` module. Implements sequential replay (QUICK fidelity): (1) fetches captured inputs from collector via `GET /api/captured-inputs?serviceId={id}&limit=100&cursor={cursor}` (HTTP client call, not repository import — module boundary), (2) for each `CapturedInput` where `method` is GET or HEAD (read-only default), sends an HTTP request to `targetUrl + input.url` with `requestHeaders`, (3) records `ReplayResponse` (status code, response body, latency). Fidelity modes: QUICK = sequential, STANDARD = 10-concurrent coroutines via `kotlinx.coroutines.async`, LOAD = prod-rate with token-bucket scheduling (LOAD is P1, not P0). The `readOnly = true` default skips POST/PUT/PATCH/DELETE methods.
- **Why P0:** This is the core product action. Without it there is nothing to observe or compare.
- **Acceptance:** Integration test: start a local HTTP server with a fixed response, create a `ReplayRun` pointing at it, run `ReplayEngine.replay(run)`, verify (a) GETs were sent, (b) `ReplayResponse` list has correct status codes, (c) POST methods were skipped when `readOnly = true`. Test with 50 captured inputs across 3 cursor pages — verify all 50 are replayed (no pagination gaps).
- **Effort:** M (HTTP client + pagination loop + response recording + tests)
- **Depends on:** REPLAY-1 (stable cursor), REPLAY-2 (ReplayRun model)

---

**REPLAY-4: `ReplayResponse` Storage + V0010 Migration**

- **What:** V0010 migration:
  ```sql
  CREATE TABLE replay_responses (
      id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      replay_run_id   UUID NOT NULL REFERENCES replay_runs(id) ON DELETE CASCADE,
      captured_input_id TEXT NOT NULL,   -- collector-side ID, no DB FK (module boundary)
      status_code     INT NOT NULL,
      response_body   TEXT,
      latency_ms      BIGINT NOT NULL,
      replayed_at     TIMESTAMPTZ NOT NULL DEFAULT now()
  );
  CREATE INDEX idx_replay_responses_run_id ON replay_responses(replay_run_id);
  ```
  Note: `captured_input_id` is a `TEXT` column (not a UUID FK) consistent with the module-boundary principle — the replay module cannot have a DB-level FK into the collector's table. `ReplayResponseRepository` with `createBatch()` for bulk inserts (mirror `CapturedInputRepository.createBatch()`). The FK to `replay_runs` is within-module and safe.
- **Why P0:** The `ComparisonEngine` needs `ReplayResponse` rows for both the baseline and candidate runs. Without storage, each run's responses are ephemeral.
- **Acceptance:** `ReplayEngine` writes a `ReplayResponse` for each replayed request. `ReplayResponseRepository.findByRunId(runId)` returns all responses for a given run. Bulk insert tested at 1000 rows.
- **Effort:** S (migration + repository + ReplayEngine wires it)
- **Depends on:** REPLAY-2, REPLAY-3

---

**REPLAY-5: `ComparisonEngine` — Statistical Verdict on Two Runs**

- **What:** `ComparisonEngine.compare(baselineRunId, candidateRunId): ComparisonResult`. Computes: (1) error rate delta (`4xx+5xx / total` for each run, absolute difference), (2) latency comparison via Mann-Whitney U test on `replay_responses.latency_ms` distributions (null hypothesis: identical distributions; p-value threshold 0.05), (3) response status code distribution diff (aggregate count per status code for each run), (4) `PASS` / `FAIL` / `INCONCLUSIVE` verdict logic: FAIL if error rate delta > 5 percentage points OR Mann-Whitney U p-value < 0.05 AND median latency increased, INCONCLUSIVE if sample size < 30 per run (not enough data for statistical power), PASS otherwise. Evidence struct records: sample sizes, p-value, error rates, status code distributions, median latency for each run. Minimum implementation: Apache Commons Math `MannWhitneyUTest` (already a common JVM dependency; add to `replay/build.gradle.kts`).
- **Why P0:** This is the product's output. A replay that compares nothing is an expensive curl loop.
- **Acceptance:** Unit test: two distributions with identical latency → PASS. Two distributions where candidate's latency is 2x baseline, p < 0.01 → FAIL with evidence citing the p-value and median latency delta. Sample size < 30 per run → INCONCLUSIVE regardless of raw numbers. Test that FAIL verdict always includes cited numeric evidence (not just "latency increased").
- **Effort:** L (statistical logic + evidence struct + edge case handling + tests)
- **Depends on:** REPLAY-4

---

**REPLAY-6: `POST /api/replay-runs` + `GET /api/replay-runs/{id}` API**

- **What:** Two endpoints in the `replay/` module's Ktor server (or as a new route group in the `platform` server — decide: a new server adds operational overhead; extending platform keeps things simple for now, and the replay module's job is data model + business logic, not serving). Recommendation: run the replay API on the existing platform server (port 8080) under `/api/replay-runs`, with the `replay/` module providing repositories and business logic. Routes: `POST /api/replay-runs` body: `{ serviceId, targetUrl, fidelity, readOnly }`, response: `ReplayRun` with status `PENDING`. `GET /api/replay-runs/{id}` returns the run with current status, verdict, and evidence. Both routes require JWT auth (existing `installJwtAuth()` via `authenticate(JWT_AUTH)`). The POST handler enqueues the run and returns immediately; a background coroutine starts the replay asynchronously.
- **Why P0:** This is the API surface the GitHub Action (MVP-9) and CLI will call. Without it, there is no external interface to trigger a replay.
- **Acceptance:** `POST /api/replay-runs` → 201 with `status: PENDING`. `GET /api/replay-runs/{id}` polling shows status transition: PENDING → RUNNING → COMPLETED with verdict. 404 for unknown ID. 403 for cross-org access. E2e test using `PlatformStackTestBase` pattern: create run, poll until COMPLETED, assert verdict is present.
- **Effort:** M (routes + async dispatch + auth scoping + e2e test)
- **Depends on:** REPLAY-2, REPLAY-5

---

**REPLAY-7: Chain-of-Custody on Captured Inputs**

- **What:** Before any verdict is produced, captured inputs must be tamper-evident. At capture time (collector's `POST /api/captured-inputs`), compute a SHA-256 hash of the canonical request payload (method + url + requestBody + capturedAt as ISO-8601 string, concatenated with `|` separator) and store it in a new `content_hash` column (V0011 migration: `ALTER TABLE captured_inputs ADD COLUMN content_hash TEXT`). At replay time, `ReplayEngine` re-computes the hash for each fetched `CapturedInput` and aborts the run with `FAILED: INTEGRITY_CHECK_FAILED` if any hash mismatches. Log the offending `CapturedInput.id`. This is not cryptographic signing (that requires a key management system) — it is a tamper-detection mechanism that makes accidental or deliberate modification of captured data visible before it poisons a verdict.
- **Why P0:** This item is classified here, not in the MVP/onboarding catalog, because it is a product integrity gate, not a compliance checkbox. A verdict backed by inputs that could have been tampered with (database admin corruption, a bug in the purge job, a SQL injection via a future query) is not a verdict the engineering team can trust. Chain-of-custody must be in place before any verdict is presented as authoritative — which means before any customer sees output from REPLAY-5. SOC 2 auditors will ask for this, but that is a secondary benefit. The primary reason is product correctness.
- **Acceptance:** V0011 migration adds `content_hash TEXT`. `CapturedInputRepository.createBatch()` computes and stores the hash. `ReplayEngine` verifies hashes before replaying. Unit test: mutate `requestBody` in a captured input after insertion, verify engine aborts with integrity error. Integration test: normal flow produces no integrity errors.
- **Effort:** S (hash computation + migration + engine check + tests)
- **Depends on:** REPLAY-3 (engine exists to add the check to), REPLAY-4 (responses exist to potentially be poisoned)

---

### P1 — Required Before Presenting Verdicts Externally

---

**REPLAY-8: `StagingObserver` — Kubeshark Observation During Replay**

- **What:** During a `ReplayRun`, poll Kubeshark in the staging cluster via WebSocket to observe outbound connections made by the target service. Capture: number of unique downstream services called, total outbound call count, error rates on outbound calls. Store as `ObservationData` (JSONB column on `replay_runs.evidence` — no separate table needed at this stage). Compare baseline vs candidate outbound connection counts as part of `ComparisonEngine`: a candidate that makes 3x more outbound calls than baseline is flagged (FAIL if delta > 50%, INCONCLUSIVE if delta 20–50%). This catches N+1 query regressions and cache-miss storms that don't surface in response latency alone.
- **Why P1:** This is the differentiator that justifies "replay real traffic" over synthetic load. Observing outbound connections catches classes of regressions that latency alone misses. It is not required for a first internal demo (where the baseline comparison exists and is interesting), but is required before selling to a customer who asks "what does this catch that load tests don't?"
- **Acceptance:** `StagingObserver` polls Kubeshark WebSocket during a replay run (reuses `KubesharkClient` patterns from the agent). `ReplayRun.evidence` JSONB includes `outboundCallCount` and `uniqueDownstreamServices` for each run. `ComparisonEngine.compare()` includes outbound delta in the evidence struct.
- **Effort:** L (Kubeshark WebSocket client in replay module + observation data model + ComparisonEngine integration)
- **Depends on:** REPLAY-5, REPLAY-6

---

**REPLAY-9: `ResourceMonitor` — K8s Metrics API for CPU/Memory**

- **What:** During a `ReplayRun`, poll the K8s Metrics API (via Fabric8, already a dependency) for pod CPU and memory usage at 15-second intervals. Store as a time series in `replay_runs.evidence` JSONB. Apply linear regression to the memory series for each run (baseline and candidate). If the candidate's memory slope is positive and statistically significant (p < 0.05 on the regression) while the baseline's is flat, flag as a potential memory leak in the evidence. Include in the verdict: "memory growth detected (candidate slope: +X MB/min, p=Y)." This catches memory leaks under realistic load that synthetic tests miss because synthetic tests don't use real request payloads.
- **Why P1:** Memory leak detection under real traffic is one of the top three value propositions in the platform's pitch. Without it, the verdict covers latency and error rates but misses an entire class of regressions. P1 (not P0) because the comparison engine already produces a useful verdict without it, and the K8s Metrics API integration requires access to the staging cluster's metric server.
- **Acceptance:** `ResourceMonitor` collects CPU/memory samples at 15s intervals during replay. Linear regression computed on the memory series. Unit test: flat memory series → no memory-leak flag. Growing memory series (simulate: +10 MB/sample, 10 samples) → memory-leak flag with slope and p-value in evidence. K8s Metrics API polling covered by a mock in unit tests.
- **Effort:** L (Fabric8 Metrics API client + time-series storage + regression computation + evidence integration)
- **Depends on:** REPLAY-5

---

**REPLAY-10: `ValidationOrchestrator` + `POST /api/validations`**

- **What:** `POST /api/validations` body: `{ serviceId, baselineTargetUrl, candidateTargetUrl, fidelity, readOnly, dbResetHookUrl? }`. The orchestrator: (1) starts a baseline `ReplayRun` against `baselineTargetUrl`, waits for completion, (2) if `dbResetHookUrl` provided, POSTs to it and waits for 200 (allows customer to reset staging DB state between runs), (3) starts a candidate `ReplayRun` against `candidateTargetUrl`, waits for completion, (4) calls `ComparisonEngine.compare()`, (5) writes verdict to a new `Validation` record (V0012 migration). `GET /api/validations/{id}` returns the validation with both run IDs, the verdict, and the full evidence struct. This is the endpoint the GitHub Action calls.
- **Why P1:** The orchestrator wires the whole product into a single API call. Without it, callers must coordinate baseline run → wait → candidate run → compare themselves. This is the external-facing product API.
- **Acceptance:** E2e test: `POST /api/validations` → poll `GET /api/validations/{id}` → verdict arrives with PASS/FAIL/INCONCLUSIVE + evidence. DB reset hook URL is called between runs when provided (mock server in test). Two sequential runs use the same captured input cursor position (both start from the beginning of the input set).
- **Effort:** L (orchestration logic + `Validation` model + V0012 migration + async coordination + e2e test)
- **Depends on:** REPLAY-6, REPLAY-7

---

### P2 — Load Fidelity and Scale

---

**REPLAY-11: LOAD Fidelity Mode — Production-Rate Replay**

- **What:** Token-bucket rate limiting to replay at the original production request rate. `CapturedInput.capturedAt` timestamps are used to reconstruct inter-request timing. The replay scheduler computes the inter-request delta and sleeps between dispatches. Add `maxConcurrency: Int` and `rateMultiplier: Double` to `CreateReplayRunRequest`. `rateMultiplier = 1.0` = exact prod rate. Values > 1.0 for load amplification. Values < 1.0 for slower replay.
- **Why P2:** QUICK (sequential) and STANDARD (fixed concurrency) are sufficient for functional regression detection. LOAD mode is a correctness amplifier for finding regressions that only appear under sustained concurrent load. It is not needed to ship a verdict — it is needed to find the hardest bugs.
- **Acceptance:** LOAD replay sends requests at the rate implied by `captured_at` deltas. Token bucket: burst allowance of `batchSize`, refill at `rateMultiplier × original_rate`. Integration test verifies that 100 inputs captured over 10 seconds are replayed over ~10 seconds at `rateMultiplier=1.0`.
- **Effort:** M (token bucket scheduler + timing computation + integration test)
- **Depends on:** REPLAY-3

---

## Catalog 2: Customer Onboarding

Items are sequenced by customer maturity gate. Read P0 items as the list of things that block deploying the agent into any customer's cluster. P1 items become visible at scale. P2 items are important but not blocking for the first few customers.

---

### P0 — Must Ship to Onboard Customer #1

These block any customer with a competent security team.

---

**MVP-1: Agent-Side PII / Header Redaction Before Data Leaves the Cluster**

- **What:** Add a configurable redaction layer in `TrafficTransformer.transform()`. Default-deny on `Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, `X-Auth-Token`, `Proxy-Authorization` (these headers ship raw today — see `TrafficTransformer.kt:66–68`). Add `redactionRules` to `DynamicConfig`: header denylist and optional JSON-path body field stripping. The platform's per-service settings page controls which rules are pushed down at config-poll time.
- **Why P0:** Headers containing bearer tokens and session cookies are currently captured verbatim and shipped to Cloud SQL. The capture loop is running on real GCP right now. A single customer's security review will catch this, and "your agent exfiltrates our Authorization headers" is a conversation-ender. The TODO comment in `TrafficTransformer.kt:43` already acknowledges this gap. This can and should be built in parallel with the replay engine — it touches only the agent, not the replay module.
- **Acceptance:** Default config redacts all headers in the denylist before POST to collector. Test: verify no `Authorization` header appears in captured input payload when the request carried one. Configurable per-service allowlist can restore specific headers.
- **Effort:** M (1–2 weeks: `DynamicConfig` schema change + transformer logic + platform settings storage + config-poll propagation + tests)
- **Depends on:** None

---

**MVP-2: Helm Chart for Agent Deployment**

- **What:** Replace `k8s/agent/overlays/sandbox/` with a Helm chart in `charts/validation-agent/`. Required values: `platformUrl`, `collectorUrl`, `apiKey` (as a secret ref), `imageTag`. Optional values: `kubesharkUrl`, `discoveryNamespaces`, `samplingRate`. Bundle RBAC and NetworkPolicy (MVP-3) inside the chart. Default to per-namespace `RoleBinding` scoped to `discoveryNamespaces`; add a `clusterWide: false` flag that upgrades to `ClusterRoleBinding` when the customer explicitly opts in.
- **Why P0:** The sandbox overlay uses sed-substitution of `__PLACEHOLDER__` strings via `scripts/sandbox-up.sh`. That is not a customer-facing install path. No serious engineering team will run a bash script that does string replacement on their YAML. The Helm chart is how every other production agent (Datadog, New Relic, Prometheus node-exporter) ships. Without it, onboarding is a hand-holding engagement, not a product.
- **Acceptance:** `helm install validation-agent charts/validation-agent --set platformUrl=... --set collectorUrl=... --set apiKey.secretName=...` deploys a working agent to a vanilla GKE cluster with no manual YAML editing. `helm upgrade` rolls a new version cleanly.
- **Effort:** M (1–2 weeks)
- **Depends on:** MVP-3

---

**MVP-3: NetworkPolicy — Egress Allowlist for the Agent Pod**

- **What:** Add a `NetworkPolicy` resource to the Helm chart (and sandbox overlay) that restricts the agent pod's egress to: (a) platform Cloud Run URL, (b) collector Cloud Run URL, (c) Kubeshark front service in-cluster. Deny all other egress. Add `ingress: []` rule since the agent never accepts inbound connections.
- **Why P0:** Without a NetworkPolicy, the agent pod has unrestricted egress on the customer's cluster network. If the agent is ever compromised (supply chain attack on our Jib build, malicious dependency), it has a foothold on the customer's internal network. This is the item a customer's CISO will ask about specifically — it is a standard control for any third-party agent that runs with cluster access.
- **Acceptance:** Agent pod can reach platform, collector, and Kubeshark front. `kubectl exec` into agent and `curl` a non-allowlisted in-cluster address returns connection refused or timeout. Bundled in the Helm chart as a default-enabled resource.
- **Effort:** S (≤1 week)
- **Depends on:** None

---

**MVP-4: Pod Security Standards — Harden the Agent Manifest**

- **What:** Add `securityContext` to the agent Deployment in `k8s/agent/base/agent.yaml` and the sandbox overlay: `readOnlyRootFilesystem: true` (mount `/tmp` as an `emptyDir` for the liveness probe file at `/tmp/agent-alive`), `allowPrivilegeEscalation: false`, `capabilities: drop: ["ALL"]`, `seccompProfile: type: RuntimeDefault`, `runAsNonRoot: true` (already handled by `USER agent` in Dockerfile.agent). Comply with Kubernetes `restricted` Pod Security Standard.
- **Why P0:** The Dockerfile.agent already runs as non-root, which is good. But the manifest sets no `securityContext` at all. A customer's admission controller enforcing `restricted` PSS will reject the pod on first deploy. This is a deployment blocker in security-conscious environments, not a nice-to-have.
- **Acceptance:** `kubectl --dry-run=server apply -k k8s/agent/base` succeeds in a cluster with `restricted` PSS enforced on the `validation` namespace. Pod starts and capture loop operates normally.
- **Effort:** S (≤1 week — manifest changes + emptyDir volume for /tmp)
- **Depends on:** None

---

**MVP-5: Short-Lived JWT Tokens + Documented Rotation Runbook**

- **What:** Reduce default token expiry in `JwtTokenGenerator.kt` from 365 days to 30 days. Add an explicit `--expiry-days` flag with a 30-day default. Update `seed-org.sh` to generate 30-day tokens. Write a rotation runbook in `docs/JWT_ROTATION.md`: (1) generate new token via `generateToken`, (2) `kubectl create secret ... --dry-run=client | kubectl apply` to update the K8s Secret, (3) `kubectl rollout restart deployment/validation-agent -n validation`. The long-term fix (short-lived tokens with a refresh endpoint) is MVP-13.
- **Why P0:** A 365-day token with no revocation path means a compromised agent JWT gives an attacker a year of write access to the collector for any captured data stamped with that org's ID, plus the ability to register phantom services. 30-day tokens + a documented runbook is the minimum acceptable answer for customer #1.
- **Acceptance:** `JwtTokenGenerator` default expiry is 30 days. `seed-org.sh` generates 30-day tokens. `docs/JWT_ROTATION.md` exists with a clear, tested runbook. 365-day behavior still accessible via `--expiry-days 365` for local dev.
- **Effort:** S (≤1 week)
- **Depends on:** None

---

**MVP-6: Captured Data Retention Policy + Auto-Purge**

- **What:** Add a `retentionDays` field to the Organization model (default 30). Add a Flyway migration. Add a scheduled job (Cloud Scheduler → Cloud Run job, or a background coroutine with a daily trigger) that deletes `captured_inputs` rows where `capturedAt < now() - retentionDays * interval '1 day'` for each org. Expose `retentionDays` in org creation and update APIs.
- **Why P0:** Without a retention policy, the platform permanently stores all production traffic bodies, including any PII that slips through the redaction layer (MVP-1). GDPR's right to erasure and data minimization principles require a defined retention period. A customer whose traffic includes user PII will ask "how long do you keep this?" and "how does it get deleted?" — today the answer is "forever."
- **Acceptance:** Org creation accepts `retentionDays` (default 30, min 1, max 365). Purge job deletes expired rows and logs count. Integration test verifies rows older than `retentionDays` are deleted and newer rows are preserved.
- **Effort:** M (1–2 weeks: model change + migration + purge job + API changes + tests)
- **Depends on:** None

---

**MVP-7: gRPC and Istio Compatibility Tests (Capture Compatibility Matrix)**

- **What:** Run two targeted experiments in the sandbox cluster: (a) Deploy a gRPC test service. Capture with Kubeshark. Observe what `request.postData.text` looks like — binary protobuf, base64, or something else. Determine whether the agent can pass gRPC traffic to the collector today, and what the `inputType` should be. (b) Install Istio sidecar injection on one test-service namespace. Verify Kubeshark can still capture traffic through the sidecar's mTLS. If it cannot, document the workaround. Publish results in `docs/CAPTURE_COMPATIBILITY.md`.
- **Why P0:** gRPC is table stakes in backend engineering. Istio is deployed in a significant fraction of production K8s environments. Finding out about incompatibility from customer #1 instead of before them burns trust. The tests are cheap (a few days in the sandbox). The discovery conversation mid-onboarding is expensive.
- **Acceptance:** `docs/CAPTURE_COMPATIBILITY.md` exists with tested results for: HTTP/1.1 plaintext (confirmed working), gRPC over h2c (result: concrete finding), Istio mTLS (result: concrete finding). The document states what we capture today and what we don't, so the sales team has a factual answer.
- **Effort:** S (≤1 week — sandbox experiments, not production code changes)
- **Depends on:** None

---

**MVP-16: Kubeshark Dependency Posture — Decide and Communicate**

- **What:** Make an explicit product decision and write it down. Options: (a) Required — Kubeshark is a prerequisite; customers who cannot run a privileged eBPF DaemonSet are outside our TAM for now, (b) Optional — document a fallback capture path for Istio/strict-mTLS environments (e.g., envoy access log sidecar as a capture source), (c) BYO — platform accepts captured inputs from any source, Kubeshark is our reference implementation. Publish the decision in `docs/KUBESHARK_POSTURE.md`. Update the sales-facing materials accordingly.
- **Why P0:** This is classified P0 because it is a sales-blocker and a design-constraint item that must be resolved before customer #1 onboarding conversations progress to "can we use this?" Some customers (financial services, healthcare) explicitly forbid privileged DaemonSets. If the answer is "required," we know which customers to walk away from. If it is "optional," we need the fallback path before those customers can onboard. Not knowing the answer is the worst option — it means a salesperson will say "we'll figure it out" and create an implicit promise.
- **Acceptance:** `docs/KUBESHARK_POSTURE.md` exists with the decision, its rationale, and its customer-segment implications. The customer-facing onboarding docs reference it. The decision is reviewed and signed off by the team.
- **Effort:** S (decision + documentation, not code — unless option (b) requires a fallback capture path, which is L)
- **Depends on:** MVP-7 (Istio compatibility test result informs the decision)

---

### P1 — Must Ship Before Customer #5

---

**MVP-8: Postgres Row-Level Security (RLS) on `captured_inputs` and `services`**

- **What:** Enable RLS on `captured_inputs` and `services` tables via a V0008+ migration (coordinate numbering with REPLAY migrations): `ALTER TABLE captured_inputs ENABLE ROW LEVEL SECURITY; CREATE POLICY tenant_isolation ON captured_inputs USING (organization_id = current_setting('app.current_organization_id')::uuid);`. The application sets `SET LOCAL app.current_organization_id = '...'` at transaction start.
- **Why P1:** JWT scoping is layer 1; it works. But it is the only layer. A route bug, SQL injection via a future ORM query, or a developer's ad-hoc query during an incident can expose another tenant's data. RLS is the database-enforced backstop. This is cheap to add now and nearly impossible to retrofit correctly on a production database with active tenants.
- **Acceptance:** RLS policies exist in a migration. Integration test verifies that executing a raw query with `organization_id` set to org A cannot read org B's rows. Application queries for org A are unaffected in performance.
- **Effort:** M (1–2 weeks: migration + app-layer session setup + test)
- **Depends on:** None

---

**MVP-9: GitHub Action — `validation-platform/run-validation@v1`**

- **What:** Create `.github/actions/run-validation/action.yml` as a composite action. Inputs: `platform-url`, `api-token`, `service-name`, `candidate-image`. The action calls `POST /api/validations` (once REPLAY-10 exists), polls for verdict, and posts a structured PR comment with PASS/FAIL/INCONCLUSIVE + evidence. Before the orchestration API exists, the action can call `GET /api/captured-inputs?serviceId=X` to confirm capture is live and post a "capture active, N requests captured" status comment — build this version now to establish the integration point.
- **Why P1:** Every engineering team already has GitHub Actions. "Install our action, get validation comments on your PRs" is the activation path with lowest friction. Build the capture-status version before Track 1 MVP completes to train users on the workflow, then upgrade to full verdict when REPLAY-10 ships.
- **Acceptance:** Action installs via `uses: validation-platform/run-validation@v1`. On a PR, it posts a comment showing either "capture active for service X (N requests in last 7d)" or a full validation verdict. Action fails the CI step if the verdict is FAIL.
- **Effort:** M (1–2 weeks for capture-status version; L for full verdict integration)
- **Depends on:** MVP-5 (token rotation) for the `api-token` input; REPLAY-10 for full verdict mode

---

**MVP-10: Image Signing + SBOM**

- **What:** Add Cosign signing to the CI workflow (`push_main.yml`) after each image push. Use keyless signing (OIDC identity, no static key) via the GitHub Actions OIDC token. Publish an SPDX SBOM via `syft` or `trivy` alongside each image push, attached to the image in the registry as an OCI artifact. Add a verification step to `sandbox-up.sh`.
- **Why P1:** Customer security teams increasingly require image signing and SBOM. The SBOM enables customers to scan our dependencies for known CVEs. Keyless signing is cheap with the existing WIF infrastructure. Not having this means a customer's security tooling (Sigstore policy controller, Kyverno) will flag unsigned images.
- **Acceptance:** Every image pushed by CI is signed. `cosign verify` succeeds. SBOM is available in the registry. A new Helm chart value `verifyImageSignature: true` runs `cosign verify` before applying the deployment.
- **Effort:** S (≤1 week — CI pipeline additions)
- **Depends on:** None

---

**MVP-11: Agent HA — Multi-Replica with Leader Election for Loop 1 and Capture**

- **What:** Add lease-based leader election (using the K8s coordination API `leases.coordination.k8s.io`) for the service discovery loop and Kubeshark drain loop. Non-leaders skip those loops but maintain the liveness probe. The K8s client is already Fabric8 (`K8sServiceDiscovery.kt`), which supports the Lease API.
- **Why P1:** The sandbox already uses `spot: true` nodes. Any customer running GKE Autopilot, Spot VMs, or AWS Spot will see routine evictions. A spot eviction kills the single agent replica and traffic goes uncaptured until the pod reschedules (typically 60–120s). Multi-replica with leader election is the standard K8s pattern for this.
- **Acceptance:** Two replicas run. Kill the leader pod — the standby becomes leader within two lease renewal intervals. Capture and discovery continue without interruption. Unit test for leader election logic.
- **Effort:** L (2–4 weeks)
- **Depends on:** MVP-2 (Helm chart — replica count and lease config belong in values)

---

**MVP-12: Per-Org Global Capture Rate Limit + Cost Dashboard**

- **What:** Add a `maxCaptureRps` field to Organization (default: 1000 req/s). Enforce it in the collector's batch ingest endpoint — compute the ingest rate per org over a sliding window and return `429` when exceeded. Add a `GET /api/orgs/{id}/metrics` endpoint returning: captured requests last 24h, last 7d, storage used (byte count of `captured_inputs` rows), ingest rate over last 60s.
- **Why P1:** The current `samplingRate` is per-service and has no global budget. A customer with 100 services all at `samplingRate=1.0` and 100 req/s each sends 10,000 req/s to the collector. At 1KB average body size that is 10 MB/s, 864 GB/day, filling Cloud SQL in hours. Before customer #5 (meaning multiple concurrent organizations), this is a billing and availability risk.
- **Acceptance:** Org with `maxCaptureRps=100` receives `429` from collector when ingest rate exceeds 100 req/s. Dashboard endpoint returns accurate metrics. Load test confirms the rate limiter under sustained load.
- **Effort:** M (1–2 weeks)
- **Depends on:** None

---

**MVP-17: Customer Dashboard / Agent Health UI**

- **What:** A read-only web view (can be a simple server-rendered page served by the platform server) showing per-org: agent connection status, number of services discovered, capture rate over the last 24h, last time each service's traffic was seen, and any active replay runs. The agent health status derives from the platform's knowledge of when the agent last polled `GET /api/agent/config` (add a `lastSeenAt` field to the agent's registration record).
- **Why P1:** Without this, every onboarding becomes a support call: "is my agent working?" The customer's only alternative today is `kubectl logs deployment/validation-agent -n validation`. A customer-visible health dashboard is table stakes by customer #3 and should be ready before customer #5. This is P1, not P0, because the first customer can tolerate log-based debugging during the initial setup phase.
- **Acceptance:** A URL the customer can visit shows agent connectivity status, service count, and recent capture volume. Agent "last seen" timestamp updates on every config poll. No authentication required for the dashboard if it shows only non-sensitive aggregate data; per-org auth required if it shows service names or topology.
- **Effort:** M (1–2 weeks for a minimal version; L for a polished UI)
- **Depends on:** MVP-9 (GitHub Action) for the PR comment integration; none for the dashboard itself

---

### P2 — Important for Scale-Out

---

**MVP-13: Short-Lived Tokens with Refresh Endpoint**

- **What:** Add `POST /api/auth/token/refresh` that accepts a long-lived "refresh token" (stored in Secret Manager, not a JWT) and returns a short-lived JWT (15-minute TTL, `aud: "validation-agent"`, `iss: "validation-platform"`). The agent polls this endpoint before each config-poll cycle. The Helm chart ships the refresh token as a separate `Secret`.
- **Why P2:** 30-day JWTs (MVP-5) reduce the blast radius enough for customer #1. Short-lived JWTs with refresh are the right long-term answer but the refresh endpoint requires careful design (revocation, replay prevention) and is not blocking for the first customer.
- **Acceptance:** Agent uses short-lived JWT in all API calls. JWT rotation is transparent — no `kubectl rollout restart` required. A compromised JWT expires within 15 minutes without any operator action.
- **Effort:** L (2–4 weeks)
- **Depends on:** MVP-5

---

**MVP-14: OLAP Export for `captured_inputs`**

- **What:** Move high-volume captured-input storage out of Cloud SQL. Export to BigQuery (or a customer-owned GCS bucket in Parquet). Cloud SQL keeps: org metadata, service registry, replay run metadata, verdicts. BigQuery/GCS keeps: `captured_inputs`, `replay_responses`, `observation_data`. The replay engine reads from BigQuery, not Postgres. Use a daily export job; the platform's `POST /api/captured-inputs` continues to write to Postgres as the staging buffer, and a downstream exporter copies rows to BigQuery and deletes them after `retentionDays`.
- **Why P2:** At 1,000 services × 100 req/s × 30-day retention, the `captured_inputs` table grows to approximately 260 billion rows. Cloud SQL will fill quickly at this rate. The OLAP export is the architectural answer to the storage problem. Not needed for customer #1 (bounded traffic volume) but required for any mid-sized customer.
- **Acceptance:** A batch job exports `captured_inputs` rows older than 24h to BigQuery and deletes them from Postgres. Cloud SQL storage stays bounded. Replay engine can query BigQuery for captured inputs by service ID and time window.
- **Effort:** XL (>4 weeks)
- **Depends on:** REPLAY-3 (replay engine exists to be migrated to BigQuery reads), MVP-6

---

**MVP-15: Platform + Agent Telemetry (Prometheus + OpenTelemetry)**

- **What:** Expose a `/metrics` endpoint from both platform and collector Cloud Run services (Prometheus format). Key metrics: capture_rate_rps per org, capture_channel_depth (agent-side), registration_outcome_total by outcome type, replay_run_duration_seconds, verdict_distribution, collector_batch_size_histogram. Add OpenTelemetry tracing for the capture pipeline and replay engine. Ship a Grafana dashboard JSON.
- **Why P2:** Without this, debugging a customer's "the agent isn't capturing anything" complaint requires shelling into their cluster and reading logs. With metrics, it's a dashboard lookup. Operational investment that pays off at customer #3.
- **Acceptance:** `/metrics` endpoint exists on platform and collector. Agent exposes metrics to stdout in structured logs. Grafana dashboard renders capture rate and channel depth in real time.
- **Effort:** L (2–4 weeks)
- **Depends on:** None

---

**MVP-18: SOC 2 Evidence Collection — Begin Now**

- **What:** This is not a discrete ship item but a continuous practice that must start before the first enterprise customer asks for it. SOC 2 Type II requires 6 months of evidence. Items to begin immediately: (a) audit log for all API mutations (which org ID, which principal, what action, when — stored in a separate `audit_log` table), (b) access reviews (quarterly review of who has GCP project access, documented), (c) change management (every production deployment linked to a PR, already true via CI), (d) vulnerability scanning of container images in CI (add `trivy` scan with HIGH/CRITICAL failure threshold). These can be done incrementally; none requires a large feature.
- **Why P2:** SOC 2 evidence collection is P2 in urgency (no customer is asking today) but P0 in "start date" — you cannot retroactively collect evidence. The day you decide to pursue SOC 2 determines when you can achieve Type II, and the clock starts with the first evidence-producing action. Starting now while the team is small is far cheaper than starting later.
- **Acceptance:** `audit_log` table exists with entries for all org/service/replay mutations. Container image scans run in CI. Quarterly access review process is documented and the first review is complete.
- **Effort:** M (audit log migration + logging hook + CI scan + process doc)
- **Depends on:** None

---

**MVP-19: Self-Service Onboarding Flow**

- **What:** Customer logs into the platform → creates a cluster → downloads a Helm values file OR gets a `helm install` one-liner with pre-populated `platformUrl`, `collectorUrl`, and `apiKey.secretName`. Replaces the script-based path (`seed-org.sh` + manual `kubectl create secret`). Requires: a customer login flow (even a simple email + password at first), a "clusters" concept in the data model, and a JWKS-backed API key issuance flow.
- **Why P2:** Self-service is only meaningful at customer #5+. Before that, hand-holding onboarding is fine and actually preferable (you learn more from watching customers struggle). Do not build this until you have at least 3 customers onboarded and you understand the actual friction points.
- **Acceptance:** A new customer can go from "sign up" to "agent running in my cluster" without any manual steps from the platform team. Time-to-first-captured-input under 30 minutes.
- **Effort:** XL (>4 weeks)
- **Depends on:** MVP-2 (Helm chart), MVP-5 (token lifecycle)

---

## Catalog 3: Tech Debt / Improvements

---

### P0 — Production Reliability Blocker

---

**ARCH-6: Cursor Pagination on `captured_inputs` Uses Agent-Supplied `capturedAt` — Clock Skew Causes Silent Replay Gaps**

- **Status:** Open — addressed by REPLAY-1. REPLAY-1 must ship before the replay engine is usable.
- **Description:** The pagination cursor for `GET /api/captured-inputs` sorts on `captured_at` (agent wall-clock time from Kubeshark's `entry.timestamp`). See `CapturedInputRepository.kt:find()`. This column is neither monotonic at insert time nor controlled by the database. Consequences: (1) NTP jitter between agent and collector causes overlapping `captured_at` ranges across batches — cursor-paginated reads skip rows in the gap. (2) Kubeshark's 5s out-of-order delivery window means a batch can contain entries with timestamps before the previous batch's cursor. (3) Agent retries insert the same `captured_at` values, causing potential duplicates in replay.
- **Risk if unaddressed:** The replay engine fetches captured inputs via paginated `GET /api/captured-inputs`. Silent gaps mean requests are never replayed. Silent duplicates mean a request is replayed twice. Both produce a wrong verdict with no error signal.
- **Fix:** REPLAY-1: V0008 migration adds `collected_at TIMESTAMPTZ NOT NULL DEFAULT now()`. Sort and cursor on `(collected_at, id)` in `CapturedInputRepository.find()`. Retain `captured_at` as a queryable analytics field.
- **Effort:** S (migration + two-line repository change + test)

---

**OPS-3: Cloud SQL `db-f1-micro` Is a Single Point of Failure for Multi-Tenant Production**

- **Status:** Open
- **Description:** `infra/platform/cloudsql.tf` provisions `db-f1-micro` (1 shared vCPU, 0.6 GB RAM, 10 GB storage) with `max_connections = 100`. Cloud Run has `min_instance_count = 0` (cold-starts) and `max_instance_count = 3` for both platform and collector. HikariCP defaults to a pool size of 10 per app instance. At max scale (3 platform + 3 collector = 6 instances × 10 pool connections), the platform consumes 60 of the 100 available Cloud SQL connections, leaving 40 for admin access, migrations, and any future service. A traffic spike that cold-starts all 6 instances simultaneously will cause connection pool exhaustion — HikariCP `connectionTimeout` defaults to 30s, then starts throwing and the platform returns 503.
- **Risk if unaddressed:** Correlated cold-starts under load will cause connection exhaustion and a cascade of 503s. The platform has no PgBouncer or connection proxy layer.
- **Fix (near-term):** Set `DATABASE_POOL_SIZE=5` in Cloud Run env vars. Add `maximumPoolSize: 5, minimumIdle: 1` to HikariCP config. Set Cloud Run `max_instance_count = 2` while on `db-f1-micro`. (Long-term): PgBouncer sidecar or upgrade to `db-g1-small`.
- **Effort:** S (env var changes in `cloudrun.tf`)

---

**SECURITY-5: `POST /api/organizations` Creates Orgs for Any Valid JWT — No Admin Gate**

- **Status:** Open (was SECURITY-2; re-scoped with new context)
- **Description:** `Routes.kt:69` has a TODO comment acknowledging that org creation is open to any authenticated caller. `seed-org.sh` exploits this by minting a throwaway JWT with a random `organizationId` UUID to bootstrap the first org. This means any agent JWT can create new orgs. In a multi-tenant production environment, this allows an agent with a valid JWT to inflate org count, exhaust org-namespaced resources, or probe for org-level data.
- **Risk if unaddressed:** Supply chain or credential compromise of any customer's agent JWT allows arbitrary org creation.
- **Fix:** Add a `role` claim check: `if (identity.role != "admin") return@post call.respond(HttpStatusCode.Forbidden, ...)`. Admin tokens are generated separately from agent tokens. The seeding script uses an admin token. Agent tokens never have `role: admin`.
- **Effort:** S (one-line route guard + `generateToken` flag + update `seed-org.sh`)

---

### P1 — Material Risk If Left Unaddressed

---

**SECURITY-4: JWT Has No `iss` or `aud` Claims**

- **Status:** Open (carried forward)
- **Description:** `JwtAuth.kt:42` builds the verifier with `JWT.require(algorithm).build()` — no `.withIssuer()` or `.withAudience()` check. `JwtTokenGenerator.kt` does not set `iss` or `aud` claims. Any RS256 token signed with the platform's private key is accepted by both platform and collector with no service binding. When short-lived tokens and a refresh endpoint (MVP-13) land, the audience binding becomes critical — the refresh token and the agent JWT must not be interchangeable.
- **Risk if unaddressed:** As more services share the signing key, a token issued for one service is accepted by another. Audience checking is the standard defense against this.
- **Fix:** Add `withIssuer("validation-platform")` and `withAudience(expectedAudience)` parameters to `installJwtAuth()`. Update `JwtTokenGenerator` to set `iss = "validation-platform"` and `aud = "validation-agent"`. Breaking change — requires minting new tokens after deployment.
- **Effort:** S

---

**SECURITY-6: Agent Dockerfile Uses Alpine (musl) — Potential Native Library Incompatibility**

- **Status:** Open
- **Description:** `deploy/Dockerfile.agent` uses `eclipse-temurin:21-jre-alpine` (musl libc). `deploy/Dockerfile.platform` explicitly uses `eclipse-temurin:21-jre` (glibc) with a comment explaining that cloud-sql-jdbc-socket-factory pulls in netty-tcnative, whose native libs are built against glibc and SIGSEGV on musl at startup. The agent does not use cloud-sql-jdbc-socket-factory, but it uses Fabric8 Kubernetes client, which pulls in native TLS dependencies. If a dependency upgrade adds a glibc-linked native library, the agent silently fails on alpine with a crash that is hard to debug in a customer cluster.
- **Risk if unaddressed:** A dependency upgrade could add a glibc-native lib, breaking the agent in production on alpine. The failure mode is a crash at startup and is not caught by CI which runs tests on the JVM directly.
- **Fix:** Switch `Dockerfile.agent` to `eclipse-temurin:21-jre` (glibc). Image size increases by ~30 MB but eliminates the musl risk class entirely. This is the right call given the platform already made this decision for the same reason.
- **Effort:** S (one-line Dockerfile change)

---

**QUALITY-1: `DynamicConfig` Not Validated After Deserialization**

- **Status:** Open (carried forward)
- **Description:** `ConfigClient.kt` deserializes `DynamicConfig` from the platform's JSON response with no bounds checking. A platform bug or misconfiguration could send `captureInterval = 0ms` (tight-spin CPU loop), `samplingRate = -0.5` (all entries drop silently since `random.nextDouble()` returns [0,1) which is always > -0.5), or `batchSize = 0` (empty-batch tight loop). The agent has no protection against these.
- **Risk if unaddressed:** Platform misconfiguration causes agent CPU spike, silent capture dropout, or tight loop.
- **Fix:** Add `fun DynamicConfig.validate(): DynamicConfig?` in `AgentConfig.kt`: clamp `samplingRate` to [0.0, 1.0], clamp `batchSize` to [1, 10000], clamp `captureInterval` to [100ms, 60s]. Log a warning for each clamped value. `ConfigClient.fetchConfig()` calls `validate()` before returning.
- **Effort:** S

---

**OPS-2: JWT Tokens Have 365-Day Default Expiry**

- **Status:** Partially addressed by MVP-5 (reduce to 30 days); the deeper fix is MVP-13.
- **Description:** `JwtTokenGenerator.kt` defaults to 365-day expiry. MVP-5 reduces the default to 30 days. The structural gap (no revocation, no refresh) remains until MVP-13.
- **Risk if unaddressed:** A leaked token is valid for up to a year.
- **Effort:** S (MVP-5 closes the immediate gap)

---

**OPS-4: No Circuit Breaker on `GET /api/agent/config` — Platform Outage Causes Agent CPU Spin**

- **Status:** Open
- **Description:** `configPollLoop` in `AgentApplication.kt:171–185` catches all non-cancellation exceptions and immediately retries after `configPollInterval`. If the platform is returning 200 with a 0ms interval in a misconfigured state (see QUALITY-1), the loop tight-spins. There is no exponential backoff in the config poll loop failure path (unlike `CollectorClient` which has proper backoff). There is no circuit breaker.
- **Risk if unaddressed:** A sustained platform outage causes the agent to log-spam and potentially CPU-spin on config polling, which competes with the capture loop's CPU budget.
- **Fix:** Add exponential backoff to the config poll loop failure path (mirror `CollectorClient`'s pattern). Add `min_instance_count = 1` to the platform Cloud Run service in `cloudrun.tf` to eliminate cold starts on the control path.
- **Effort:** S

---

**ARCH-2: Repositories Are `object` Singletons**

- **Status:** Open (carried forward)
- **Description:** `OrganizationRepository`, `ServiceRepository`, `CapturedInputRepository` are all Kotlin `object` singletons. Every route test requires a live database via TestContainers. The pattern cannot be unit-tested in isolation.
- **Risk if unaddressed:** Test suite speed grows proportionally with route complexity. The pattern will spread to the replay engine module unless addressed.
- **Fix:** Convert to classes injected via Ktor's `Application.attributes` or a DI framework. This is a refactor, not an emergency.
- **Effort:** M

---

### P2 — Quality / Maintainability

---

**QUALITY-6: `ignoreUnknownKeys` on Server-Side JSON Deserialization**

- **Status:** Open (carried forward)
- **Description:** Both `platform/Application.kt` and `CollectorApplication.kt` configure kotlinx.serialization with `ignoreUnknownKeys = true` on the server side. A client typo like `{"organizationid": "..."}` (lowercase 'i') silently produces a missing-field error rather than a "field not recognized" error, making the bug harder to diagnose.
- **Risk if unaddressed:** Developer experience issue. Integration debugging becomes harder as the API surface grows.
- **Fix:** Remove `ignoreUnknownKeys = true` from server-side Json configuration. Keep it on the agent (client side) where it enables additive API evolution.
- **Effort:** S

---

**QUALITY-9: `automountServiceAccountToken: false` Not Set on Agent Pod**

- **Status:** Open — this is a documentation clarification, not a code fix.
- **Description:** The agent pod mounts the default K8s service account token. `automountServiceAccountToken` is not set in `agent.yaml`, so it defaults to `true`. The agent uses Fabric8's `KubernetesClientBuilder().build()` which reads the token from the mounted path. This is correct and needed for Loop 1. However, if the RBAC is ever broadened beyond `list,watch` on `services`, the blast radius of an agent compromise grows proportionally.
- **Fix:** Add a comment in `agent.yaml` and the Helm chart explicitly noting that `automountServiceAccountToken` must remain `true` because Fabric8 needs it, and that the ClusterRole must remain read-only on `services` only. Add a CI check (OPA or kube-score) that validates the ClusterRole never gains write permissions. Do NOT set `automountServiceAccountToken: false` — that would break K8sServiceDiscovery.
- **Effort:** S

---

**QUALITY-7: `OrderService` No Connection Pool**

- **Status:** Open (carried forward, test-service only)
- **Description:** `test-services/order-service` uses `DriverManager.getConnection(...)` per request. Under traffic-generator load, this creates and tears down a new Postgres connection per HTTP request, which is ~5–10ms of latency overhead per request and saturates Cloud SQL connection slots quickly.
- **Risk if unaddressed:** Test services don't reflect realistic behavior. Replay comparison results from the sandbox are artificially penalized by connection-setup latency.
- **Fix:** Replace with `HikariDataSource` (10-line change).
- **Effort:** S

---

**ARCH-3: No Request Timeout on Outbound Agent HTTP Calls**

- **Status:** Open
- **Description:** `buildAgentPlatformHttpClient()` and `buildAgentCollectorHttpClient()` in `AgentApplication.kt` set no `HttpTimeout` plugin. If the platform or collector hangs, the agent's coroutines suspend indefinitely on `httpClient.post(...)` and `httpClient.get(...)`. The CIO engine holds an open TCP connection that structured cancellation from the outer `coroutineScope` won't cancel. The capture loop stalls.
- **Risk if unaddressed:** A hung Cloud Run revision could deadlock the agent's capture loop indefinitely. The liveness probe writes `HEARTBEAT_FILE` before the collector send, so the heartbeat would fire on successful drain but the pod would not be restarted — it would silently stop forwarding traffic.
- **Fix:** Install `HttpTimeout` plugin in both `configurePlatform()` and `configureCollector()` with `requestTimeoutMillis = 30_000`.
- **Effort:** S

---

**QUALITY-10: `seed-org.sh` Caches Org ID in `.platform/sandbox-org-id` — Git Leak Risk**

- **Status:** Open
- **Description:** `seed-org.sh:57` writes the sandbox org UUID to `.platform/sandbox-org-id`. If a developer accidentally commits it (the directory doesn't exist until the script runs, so there may be no existing `.gitignore` entry), the sandbox org ID leaks into git history.
- **Risk if unaddressed:** Low probability, low severity. But the file should be in `.gitignore` explicitly.
- **Fix:** Add `.platform/` to the root `.gitignore`.
- **Effort:** S (one-line `.gitignore` change)

---

**ARCH-4: HikariCP Pool Size Is Mis-Calibrated for Cloud Run**

- **Status:** Open (see OPS-3 for the production blocker; this is the code-level tracking item)
- **Description:** `DatabaseFactory.kt:72` reads `DATABASE_POOL_SIZE` from the environment, defaulting to 10. At 6 Cloud Run instances × 10 pool connections = 60 of the 100 available Cloud SQL connections. No `DATABASE_POOL_SIZE` env var is set in `cloudrun.tf`, so the default of 10 applies.
- **Risk if unaddressed:** Connection exhaustion under load (see OPS-3).
- **Fix:** Set `DATABASE_POOL_SIZE=5` in `cloudrun.tf` for both platform and collector until Cloud SQL is upgraded.
- **Effort:** S

---

### P3 — Cosmetic / Future-Proofing

---

**QUALITY-2: Doc Drift from Recent PRs**

- **Status:** Partially addressed; CLAUDE.md still needs a sync pass.
- **Description:** CLAUDE.md still references `KubernetesAdapter`/`ManualSeedAdapter` (deleted in PR #102), the `discoverServices()` stub (replaced in PR #104), and `k8s/agent/agent.yaml` (moved to `k8s/agent/base/agent.yaml` in PR #103).
- **Risk if unaddressed:** New contributors follow stale paths and waste time.
- **Effort:** S

---

**QUALITY-3: `test-services/overlays/gke/` Should Be Renamed `sandbox/`**

- **Status:** Open
- **Description:** The test-services overlay is named `gke` but it is specifically the sandbox GKE deployment, not a generic GKE overlay. The agent overlay uses `sandbox/` for the same environment. Naming inconsistency causes confusion.
- **Effort:** S (rename only)

---

## Items Removed from Previous Review

The following items from the previous ARCHITECTURE_REVIEW.md are **resolved** and removed from tracking:

| Previous ID | Resolution |
|-------------|------------|
| ARCH-7 | Resolved: PR #100 split Flyway migration ownership between platform (MIGRATE) and collector (VALIDATE). |
| QUALITY-8 | Resolved: PR #95 distinguishes idle Kubeshark (heartbeat) from disconnected Kubeshark (no heartbeat), fixing the liveness probe false-positive. |
| OPS-1 | Resolved: PR #93 addressed the operational gap. |
| ARCH-5 | Resolved: PR #93. |
| SECURITY-3 | Resolved: PR #93. |
| ARCH-1 | Resolved: PR #102 removed the dead `platform/adapters` package entirely. |

The following item is **superseded**:

| Previous ID | Supersession |
|-------------|-------------|
| QUALITY-4 (HikariCP) | Positive pattern #21 in the previous review confirmed HikariCP is now in use. The remaining concern is pool sizing (see ARCH-4). |

The following section is **deleted** from this document:

| Previous Section | Reason for Deletion |
|-----------------|---------------------|
| "Key Divergences from PLAN.md" | PLAN.md no longer exists as a separate document. The positions taken in that section are now reflected directly in the ordering and tier assignments within this document. |

---

## Positive Patterns Worth Preserving

Items marked in the previous review as positive are still valid. The following patterns from Phase B are specifically worth calling out:

1. **`CollectorClient` retry model with backpressure propagation** is correctly designed: the retry suspends the entire capture loop, which stops draining the Kubeshark channel, which applies TCP backpressure. No in-memory retry queue, no data loss path other than Kubeshark's own buffer.
2. **`KFL_NO_MATCH` sentinel** in `KubesharkClient.buildKflQuery()` is the right paranoid default: if all service names are unsafe to embed, the query falls back to a no-match sentinel rather than an unfiltered `"http"` query. Refusing to over-capture is the correct security posture.
3. **`isKflSafeToEmbed()` whitelisting approach** (check for `"`, `\`, and control characters only) is correct. It does not try to be a name validator — the platform owns that — and it only prevents the specific injection vectors that would break KFL query semantics.
4. **WIF for CI/CD with repository-scoped attribute condition** is correct. The `attribute_condition = "assertion.repository == 'pmk6vc/validation-platform'"` constraint prevents any other GitHub repository from impersonating the CI/CD SA. No JSON key anywhere.
5. **IAM database auth for Cloud SQL** (no static password, OAuth token via socket factory) eliminates an entire credential rotation class. The `bootstrap-db.sh` approach of using a temporary password to grant schema ownership and then rotating it to an unknown value is pragmatic and correct for the one-time bootstrap case.
6. **`RegistrationOutcome` sealed class** correctly distinguishes transient from permanent failures. The per-service `permanentlyFailed` set prevents the discovery loop from hammering the platform with a service name that the platform will always reject. This distinction matters when the platform returns 400 vs 429 — permanently excluding a 429 would be wrong.
7. **Module boundary discipline on `CapturedInput.serviceId`** — the collector's `ServiceId` type is defined locally in the collector module (`collector/src/main/kotlin/com/platform/collector/database/Ids.kt`), not imported from `shared/`. This is correct because the collector has no compile-time dependency on the platform module. The DB-level FK was dropped in V0006 for the same reason. The replay engine must follow this pattern: fetch captured inputs via HTTP, not via repository import.

---

## Out of Scope

These are deliberate deferrals, not forgotten items. Revisiting any of them requires an explicit decision, not just a PR.

- **Replay engine fidelity beyond MVP:** Advanced concurrency tuning, full prod-rate replay with real request-timing reconstruction (REPLAY-11 covers LOAD mode; anything beyond that — adaptive concurrency, latency simulation — is deferred).
- **Write replay with DB reset beyond the basic flag:** The `dbResetHookUrl` approach in REPLAY-10 is the MVP. Customer-managed DB snapshot/restore, Liquibase-based state machines, and other advanced reset strategies are out of scope.
- **Anomaly detection and baseline learning:** Phase 6+ in CLAUDE.md. Not part of the statistical comparison approach; a separate product direction.
- **Multi-cluster and multi-region federation:** Single-cluster capture and single-cluster staging is the design. Federation adds coordination complexity without a clear customer demand at this scale.
- **Replay against production:** Always staging-only by design. The platform captures from production, replays against staging. Replaying against production is explicitly not supported — the agent's read-only mode still means live user requests are affected.
- **Generic message queue capture as a first-class feature:** HTTP is the wedge. Kafka, Pub/Sub, and SNS fan-out via separate consumer groups is the architectural answer for queue-driven entry points, but it is only worth building when a specific customer demands it. Document the approach (it is already in CLAUDE.md); do not build it preemptively.
- **Full web UI and dashboards beyond agent health:** The GitHub Action (MVP-9) and the minimal agent health view (MVP-17) are in scope. A full topology visualization, custom analytics dashboard, and replay result explorer are not — those are V2 investments after the core product is proven.
- **CLI wrapping the platform:** Useful but not blocking any customer outcome. Build after the GitHub Action establishes the integration pattern.
