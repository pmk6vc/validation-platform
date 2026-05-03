# Plan: Roadmap to MVP and First Customer

## Mission

PLAN.md serves two purposes:

1. **MVP / core product validation.** End-to-end capture → replay → compare flow that proves the value proposition on internal demos and to early design partners. Goal: *"this works."*
2. **Production readiness before first customer.** Security, onboarding, integrity, and compatibility hardening so a customer can deploy the agent in their cluster with confidence and pass a serious security review. Goal: *"you can trust this in production."*

Track 1 unblocks Track 2: there's no point hardening a feature that doesn't exist. But the most blocking compatibility tests (gRPC, Istio) and the most blocking security items (PII redaction, Helm chart) start the moment they don't depend on Track 1 being further along.

---

## Status

### Done

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

What this means: production-style traffic now flows through Kubeshark → agent → Cloud Run collector on real GCP. The "money test" from the original plan is demonstrated. The capture loop is real; the replay engine is the next workstream.

### Doc drift to clean up

A handful of merged PRs introduced doc drift that hasn't been swept yet — `claude-md-sync` follow-up:

- CLAUDE.md still references `KubernetesAdapter` / `ManualSeedAdapter` (deleted in #102) and the `discoverServices()` stub (replaced in #104).
- CLAUDE.md / ARCHITECTURE_REVIEW.md reference `k8s/agent/agent.yaml` (moved to `k8s/agent/base/agent.yaml` in #103).
- `test-services/overlays/gke/` has the same name-overload as the original `agent/overlays/gke/` (means "sandbox," not "GKE generally"); rename to `sandbox/` for consistency.

---

## Track 1: MVP / Core Product Validation

### Replay Engine (Feature 2 from CLAUDE.md)

**Goal:** Send captured HTTP requests to a target service in the customer's staging cluster.

#### Deliverables

- [ ] `ReplayRun` model + DB migration (likely its own module)
- [ ] `ReplayEngine`: fetches captured inputs from collector via `GET /api/captured-inputs`, replays against a staging target
- [ ] Configurable fidelity: QUICK (sequential), STANDARD (10–50 concurrent), LOAD (prod-rate)
- [ ] Read-only flag (default `true`); optional DB reset hook between runs
- [ ] API: `POST /api/replay-runs`, `GET /api/replay-runs/{id}`

**Milestone:** captured traffic replayable against staging services via API. A demo run prints "we sent 1000 captured GETs at staging, here are the response codes."

### Observation + Verdicts (Phase 4 from CLAUDE.md)

**Goal:** Compare baseline vs candidate replay runs with statistical rigor.

#### Deliverables

- [ ] `StagingObserver`: poll Kubeshark in staging during replay (outbound connections, call patterns)
- [ ] `ResourceMonitor`: poll K8s Metrics API for pod CPU / memory
- [ ] `ComparisonEngine`: response diffs, latency (Mann-Whitney U), error rates, outbound connection delta, memory trends (linear regression)
- [ ] `VerdictGenerator`: PASS / FAIL / INCONCLUSIVE with cited evidence
- [ ] API: `GET /api/validations/{id}`

**Milestone:** a merged PR can be validated end-to-end: capture → baseline → candidate → verdict, with the verdict citing specific evidence ("p99 latency increased 40%, p<0.01"). This is the demo we sell.

### Orchestration

**Goal:** Single API call wires the whole flow.

#### Deliverables

- [ ] `ValidationOrchestrator`: `POST /api/validations` (capture → baseline → optional reset → candidate → compare → verdict)
- [ ] Candidate deployment to staging (image tag swap, eventually Helm or kustomize for fancier deploys)

**Milestone:** Track 1 MVP done — internal demos and design-partner conversations are unblocked.

---

## Track 2: Production Readiness Before First Customer

Track 2 items are grouped by which customer maturity gate they unblock. *Customer #1* = our first paying user (or first design partner running in their own cluster). *Customer #~5* = second-tier scrutiny and scale. *Scale* = enterprise.

### Compatibility de-risk (run in parallel with Track 1)

The capture loop assumes HTTP/1.1 plaintext. These tests verify or break that assumption before customers find out the hard way.

| Item | What | Why |
|------|------|-----|
| **gRPC capture test** | Spin up a gRPC test service in the sandbox; capture with Kubeshark; observe `request.postData.text` shape (binary protobuf, base64-encoded) | gRPC is widespread. Decide: store as opaque base64 + protobuf descriptor (customer-supplied) for replay, OR drop gRPC capture until demanded. Don't speculate — run the test |
| **Istio compatibility test** | Deploy Istio sidecar in front of one test service; verify Kubeshark captures traffic before vs. after the sidecar | Service mesh is widespread. mTLS between services may break capture; need workaround documented |
| **DB interaction (already de-risked)** | Staging-based replay sidesteps prod DB capture. For *observation* during replay, TLS still blocks plaintext capture — fall back to "connection counts + latency from K8s metrics," not query content | Documented, no further work needed |
| **Pub/Sub / Kafka / SNS** | Decide and document: built-in fan-out (separate consumer group, mirror sub) is the architectural answer, NOT eBPF. Capture only when a specific customer demands it | Customers with queue-driven entry points self-select |

**Milestone:** capture compatibility matrix in `docs/CAPTURE_COMPATIBILITY.md`, updated with each test result. Ideally completes before customer #1 onboarding.

### Customer #1 must-haves (security and integrity)

These block onboarding any customer with a serious security review.

| Item | Scope |
|------|-------|
| **PII / header anonymization** | Configurable agent-side redaction layer applied **before** bodies leave the customer cluster: header allow/denylist (default-deny `Authorization`, `Cookie`, `X-API-Key`, `Set-Cookie`), jsonpath-based body field stripping, regex tokenization. Default-deny on sensitive headers, opt-in per-service for the rest. Configured via the platform's per-service settings, polled by the agent in `DynamicConfig` |
| **Helm chart (productize)** | Replace `k8s/agent/overlays/sandbox/` with a Helm chart accepting `discoveryNamespaces`, `platformUrl`, `collectorUrl`, `imageTag`, `samplingRate`, `redactionRules` values. Default `ClusterRoleBinding` with read-only on Services (same shape Datadog/New Relic agents use); per-namespace `RoleBinding` mode as a values flag for high-security tenants |
| **NetworkPolicy** | Explicit egress allowlist: platform + collector + Kubeshark only. All other egress denied. Bundled in the Helm chart |
| **Restricted Pod Security Standard** | Read-only root filesystem, drop all capabilities, seccomp `RuntimeDefault`, run as non-root (already done), no host networking/PID. Comply with `restricted` PSS profile |
| **Captured data retention + auto-purge** | `retentionDays` per org (default 30); a sweep job deletes expired captured inputs. Without it, GDPR is a non-starter |
| **Image signing + SBOM** | Cosign signature on agent + platform + collector images. SPDX or CycloneDX SBOM published with each release. Verifiable via standard tooling. Becomes table stakes the moment a customer's security team gets involved |

**Milestone:** customer #1 onboarding checklist passes a typical SOC 2-aware security review. The agent can be installed via `helm install` and a Helm values file.

### Customer #~5 must-haves (auth and scale)

| Item | Scope |
|------|-------|
| **Short-lived JWTs + rotation** | Move from long-lived bearer tokens (1h per `JwtTokenGenerator` default today) to short-lived (~5–15 min) tokens with refresh, OR a customer-managed cert exchange. Long-lived bearer tokens fail enterprise security review |
| **Postgres RLS** | Row-level security on `services` and `captured_inputs` keyed by `organization_id`. Defense in depth — JWT scoping is layer 1, RLS is layer 2 against route bugs and SQL injection. Cheap to add now, expensive to retrofit |
| **Multi-replica agent + leader election** | Lease-based leader election for the discovery + Kubeshark drain loops. Today a single agent restart loses ~60s of capture; HA needed for production traffic |
| **Sampling cost ceiling** | Per-org rate limit on capture volume (e.g. ≤1000 req/sec). Current `samplingRate` is per-service, no global budget. A customer with 100 services × default 1.0 sampling = collector firehose. Customer-visible cost dashboard |

**Milestone:** customer #5 onboards without us hand-holding the per-cluster rollout, and the platform stays up across pod restarts and traffic spikes.

### Customer interaction surfaces (highest-leverage product investments)

| Item | Scope |
|------|-------|
| **GitHub Action wrapping the platform** | The most common interaction pattern. A `validation-platform/run-validation@v1` action: customer's PR triggers it → calls `POST /api/validations` → polls for verdict → posts a structured comment on the PR with PASS/FAIL + evidence. Single-click integration into existing CI. **Do this before custom integrations** — every customer team already has GH Actions |
| **CLI wrapping the platform** | Second most common. `validation-cli` for ad-hoc validation runs, debugging, inspecting service topology, replaying specific captured inputs, querying validation history. Built on the same API as the GitHub Action — no parallel implementation |

**Milestone:** a customer can install the GH Action in 10 minutes and have validation runs comment on every PR. Engineers can run `validation-cli replay <run-id>` to debug locally.

### Scale and observability (platform-side)

| Item | Scope |
|------|-------|
| **OLAP export for captured inputs** | Captured inputs at scale (millions per customer per day) don't belong in Postgres. Export to BigQuery / ClickHouse / S3 + parquet. Postgres holds: orgs, services, replay-run metadata, verdicts. OLAP holds: captured_inputs, replay_responses, observation_data. Replay engine reads from OLAP, not Postgres. Keeps the OLTP path fast and cheap |
| **Telemetry on platform services** | The platform itself needs observability. Prometheus metrics (capture rate, channel depth, registration-outcome counts, replay-run latency, verdict distribution), structured logs to a queryable sink (Cloud Logging → BigQuery), traces (OpenTelemetry). Without this we can't debug customer issues without shelling into their cluster |

**Milestone:** the platform's own observability is good enough that we can root-cause a customer's broken deployment without their cooperation.

### Compliance and trust (begin early — evidence accrues over time)

| Item | Scope |
|------|-------|
| **Compliance evidence trail** | Begin SOC 2 evidence collection (audit logs, access reviews, change-management records). SOC 2 Type II requires 6 months of evidence — the day to start is "before your first enterprise customer asks for it" |
| **Reproducibility / chain-of-custody** | Cryptographic signature on captured inputs at capture time, verifiable at replay time. Tamper-evident audit log of who accessed what captured data. Worth thinking about *before* anyone treats a verdict as authoritative |
| **Customer dashboard / agent health UI** | "Is my agent healthy? what services discovered? how much traffic captured? how lagged?" Without this, every onboarding becomes a support call. Replaces ad-hoc `kubectl logs` |
| **Self-service onboarding** | Customer logs into platform → "Create cluster" → downloads Helm values OR `helm install` one-liner. Replaces the script-based path. ~8–12 weeks of work; defer until proof of demand |
| **Kubeshark dependency posture** | Decide and communicate: required, optional with alternative paths, or BYO. Some customers won't accept a privileged DaemonSet doing eBPF; need to know which segments we're walking away from |

**Milestone:** we can sell to an enterprise.

---

## Out of scope (for this plan)

- Replay engine fidelity beyond MVP (concurrency tuning, full prod-rate replay, write replay with reset hooks beyond the basic flag)
- Anomaly detection / baseline learning (Phase 6+ in CLAUDE.md)
- Multi-cluster / multi-region federation
- Replay against production (always staging-only by design)
- Generic message queue capture as a first-class feature (HTTP is the wedge)
- Web UI / dashboards beyond the customer-health view called out above

---

## Suggested cadence

A rough sequencing — not a commitment, just a sketch of dependencies:

| Phase | Theme | Output |
|-------|-------|--------|
| 1–2 | Replay MVP | `POST /api/replay-runs`, sequential replay against staging |
| 3 | **Compatibility tests in parallel** | gRPC + Istio results in `docs/CAPTURE_COMPATIBILITY.md` |
| 3–4 | Comparison + verdict MVP | `ComparisonEngine`, `VerdictGenerator`, basic statistical tests |
| 5–6 | Customer #1 hardening | PII redaction + Helm chart + NetworkPolicy + PSS + retention + image signing |
| 7 | Orchestration | `POST /api/validations` end-to-end |
| 8–9 | Customer #~5 hardening | JWT rotation + RLS + agent HA + cost ceiling |
| 10–11 | Customer surfaces | GitHub Action + CLI |
| 12+ | Scale-out | OLAP export + telemetry + compliance prep |

Roughly a 3-month plan. Compatibility tests and customer #1 hardening can run in parallel with replay MVP work since they touch different parts of the codebase.
