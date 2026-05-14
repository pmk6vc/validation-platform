# Phase 1: Native Capture Cutover - Context

**Gathered:** 2026-05-14
**Status:** Ready for planning

<domain>
## Phase Boundary

Replace the Kubeshark + Kotlin agent capture path with a Go eBPF tap + Go agent under the existing collector wire contracts (`BatchCreateCapturedInputRequest` byte-for-byte compatible), shipping PII redaction in the same phase, with the Kotlin `agent/` Gradle module and Kubeshark wiring fully decommissioned by phase end — all delivered in small reviewable PRs.

Scope is fixed by REQUIREMENTS.md (CAPTURE-01..12) and ROADMAP.md Phase 1 success criteria. Discussion clarified HOW; new capabilities (gRPC, RLS retrofit, replay engine) belong to Phases 2–4.

</domain>

<decisions>
## Implementation Decisions

### Tap ↔ Agent Topology

- **D-01:** Single Go binary in a per-node DaemonSet. The binary owns the BPF program, the cgroup_id→pod informer, the K8s Service-discovery informer, redaction, batching, and the JWT-authenticated POST to the collector. No separate central Deployment; no sidecar split. Matches the existing `tap/cmd/vp-tap/` shape; minimum new infra.
- **D-02:** Static config is a ConfigMap-mounted YAML file (`config.yaml`) — operational defaults (batch size, sample rate ceiling, namespace excludes, redaction defaults) live there. URLs (`PLATFORM_URL`, `COLLECTOR_URL`) stay as env vars in the DaemonSet spec. JWT (`API_KEY`) stays in `Secret platform-api-key/jwt-token` mounted via `secretKeyRef`. CAPTURE-05's ConfigMap requirement is satisfied by the YAML file.
- **D-03:** Internal concurrency is an idiomatic-Go goroutine pipeline coordinated by `context.Context`: `ringbuf reader → reassembler → transformer (filter + redact + sample) → batcher → collectorclient`, plus separate goroutines for service discovery and config polling. `DynamicConfig` shared via `atomic.Pointer[DynamicConfig]` (or `sync.RWMutex` if richer notification semantics are needed). Matches CAPTURE-04 and the existing tap's four-goroutine pattern.
- **D-04:** JWT delivery: one cluster-wide org-level JWT in the existing `platform-api-key` Secret, mounted into every DaemonSet pod via `secretKeyRef`. No per-node tokens, no bootstrap-token exchange in Phase 1. Phase 3 SEC-02/03 multi-`kid` rotation will rotate this token without redeploy.

### Reassembly + Body Capture

- **D-05:** TCP reassembly runs in **userspace**. The kernel emits raw write/read segment events with `(cgroup_id, pid, tgid, fd, timestamp, bytes)` via ringbuf. The Go agent stitches segments per `(pid, fd)` into HTTP messages. `cgroup_id` remains the canonical pod-attribution key (unchanged from VAL-55 design); `(pid, fd)` is a *transient* connection-correlation key carried on each event — no kernel hash map. Precedent: Pixie, Beyla, Coroot, Inspektor Gadget L7, Kubeshark all use this shape. `sock *` via kprobes was rejected — unstable internal-symbol ABI, no state savings.
- **D-06:** Request→response pairing: FIFO on same `(pid, fd)`. Each completed request is paired with the next completed response on the same connection. HTTP/1.1 pipelining (multiple in-flight requests on one connection) is detected via unmatched-queue depth and dropped with a `drops_pipelined` counter. Latency = first-response-byte timestamp − first-request-byte timestamp.
- **D-07:** Per-`(pid, fd)` userspace buffer policy: hard cap (default 1 MiB, configurable in ConfigMap) → truncate → `truncated: true` flag on the captured-input → `drops_truncated{reason=body_too_large}` counter. Idle `(pid, fd)` buffers age out via TTL (default 30s) and surface as `drops_aged_out`. Additive `truncated` wire field is safe under the additive-evolution rule (`ignoreUnknownKeys = true` on the collector).
- **D-08:** BPF hooks: three stable-ABI tracepoints — `syscalls:sys_enter_write`, `syscalls:sys_exit_read`, `syscalls:sys_enter_close`. Matches the existing `probe.bpf.c` stability choice. `sys_enter_close` invalidates the userspace `(pid, fd)` buffer. Claude's discretion on this; revisit if any design-partner workload is heavy on `writev`/`readv` or non-TCP sockets.

### Cutover & Decommission Strategy

- **D-09:** Hard swap in sandbox. One PR removes the Kubeshark `helm upgrade --install` block + the Kotlin agent overlay from `scripts/sandbox-up.sh` and installs `vp-tap` via `helm upgrade --install`. Revertibility = `git revert` until the decommission PRs land. Sandbox is sandbox; no real customer impact justifies side-by-side complexity.
- **D-10:** Decommission sequence is **outside-in**, with integration/e2e tests updated *inline* with each deletion PR so CI is never red between commits:
  - **PR 1** (after CAPTURE-08 pressure test passes): remove `k8s/agent/` overlay tree + `deploy/Dockerfile.agent` + sandbox-up.sh references.
  - **PR 2**: remove `agent/` Gradle module + entry in `settings.gradle.kts`.
  - **PR 3**: delete `KubesharkClient.kt`, Kubeshark wire DTOs, fixtures from `collector/` + `e2e-tests/`; delete the Kotlin-agent-based `AgentDiscoveryE2ETest` at the same time.
  - **PR 4**: update `CLAUDE.md` to describe the Go capture path.
- **D-11:** The new Go-agent e2e test (CAPTURE-12) lands and is green *before* the hard-swap PR, alongside the existing Kotlin-agent e2e. After the swap, the Kotlin-agent e2e is deleted in PR 3 of decommission.
- **D-12:** Decommission unblock gate: CAPTURE-08 30-minute sandbox pressure test passes (no OOMs/panics, drop rate below the agreed threshold, ≥20% CPU+memory headroom). Pressure-test results recorded in a benchmark doc — exact location TBD by planner (most likely under `docs/benchmarks/` or `.planning/phases/01-native-capture-cutover/`).
- **D-13:** All new Go agent code lives in the **existing `tap/` module** (single `go.mod`). The existing `tap-test` CI job in `.github/workflows/pr_main.yml` already runs `go test ./...` recursively and covers it. The vp-tap prototype binary (`tap/cmd/vp-tap/`) evolves into the full agent; no separate `agent-go/` module.

### Redaction Pipeline + Placeholders (CAPTURE-09)

- **D-14:** Redaction runs **inside the transformer**, post-reassembly, before the batcher. The transformer operates on fully parsed HTTP request/response objects so header and body redaction are parsing-aware (not raw-byte regex). Headers redacted via the default-deny allowlist (`Authorization`, `Cookie`, `Proxy-Authorization`, `Set-Cookie`, `X-API-Key`). Body redaction is content-type-aware (see D-16).
- **D-15:** Placeholders are **typed deterministic truncated-sha256 hashes**:
  - Format: `<REDACTED:<field-or-pattern-type>:<hex6-8>>`, e.g. `<REDACTED:authorization:a1b2c3>` or `<REDACTED:jwt:d4e5f6>`.
  - Same input → same placeholder (replay engine can match identical auth contexts across sessions without seeing the secret).
  - Truncation rules out rainbow-table reverse lookup.
  - Salted with a per-org constant pulled from `DynamicConfig` so the same secret across two orgs hashes differently. (Salt management itself — generation, rotation — is out of Phase 1 scope; researcher should surface options.)
- **D-16:** Body redaction is **content-type-aware**:
  - `application/json`: parse to a tree, walk leaf string values, replace pattern matches with placeholders (preserves JSON validity for downstream replay).
  - `application/x-www-form-urlencoded`, `text/plain`: regex over the decoded string.
  - Binary or unknown content-type: skip body redaction in Phase 1 (rare for HTTP/1.1; gRPC/HTTP/2 binary gets its own handling in Phase 2).
  - Body patterns from CAPTURE-09: JWT-shaped tokens, PAN-shaped digit sequences (Luhn-valid), `sk_*`/`pk_*` prefixes.
- **D-17:** **`DynamicConfig` redaction-allowlist hook is designed in Phase 1, populated in Phase 3.** Wire-shape additions to `DynamicConfig`: `extraRedactedHeaders: List<String>?` and `extraBodyRedactionPatterns: List<String>?`. Phase 1 ships these always-empty on the platform side; the agent reads and honors them if non-empty. Phase 3 (SEC-09) wires the platform endpoint + UI to populate. Zero wasted work, no schema churn at the Phase 3 boundary.

### Claude's Discretion

- **BPF hook set (D-08).** User deferred ("You decide"). Claude chose three tracepoints; flagged for researcher to revisit against design-partner workload profiles.
- **Truncation flag wire-shape detail.** Implicit in D-07: a `truncated: bool?` optional field on `CreateCapturedInputRequest`. Final field name + placement is planner's call; the additive-evolution rule keeps this safe.
- **Salt management (D-15).** Per-org salt source, rotation policy, and what happens to historical captures when salt rotates — flagged for researcher; not closed in this discussion.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Scope & Requirements
- `.planning/REQUIREMENTS.md` §"Capture cutover (CAPTURE)" — CAPTURE-01..12, the locked phase requirements.
- `.planning/ROADMAP.md` §"Phase 1: Native Capture Cutover" — success criteria 1–6.
- `.planning/PROJECT.md` — v1 scope, constraints (small reviewable PRs, reversibility, bilingual-OK), Key Decisions table.

### Research
- `.planning/research/SUMMARY.md` §"Phase 1: Native Capture Cutover" — research rationale and the TAP-5/TAP-6 research flags (capture-pipeline wiring under churn; ring-buffer sizing + kernel compatibility matrix).
- `.planning/research/ARCHITECTURE.md` — research-time architecture.
- `.planning/research/PITFALLS.md` — pitfalls 4 (eBPF kernel skew), 5 (PII honeypot), 7 (cgroup_id drift).
- `.planning/research/FEATURES.md`, `.planning/research/STACK.md` — backing detail.

### Codebase Maps (existing patterns to follow)
- `.planning/codebase/ARCHITECTURE.md` — modular monolith, HTTP-only cross-module access, JWT model.
- `.planning/codebase/STACK.md` — Kotlin + Go stack constraints, image build pipeline.
- `.planning/codebase/INTEGRATIONS.md` — Kubeshark wire format being replaced; Kubernetes API access patterns.
- `CLAUDE.md` — current capability inventory; design decisions; module-ownership rules.

### Existing Code (the brownfield surface this phase changes)
- `tap/cmd/vp-tap/main.go` — current TAP-1 prototype that the Phase 1 binary evolves from.
- `tap/internal/ebpf/probe.bpf.c` — current BPF program (`MAX_DATA_SIZE=256`, write-only tracepoint); Phase 1 extends to three tracepoints and removes the 256B cap by switching to userspace reassembly.
- `tap/internal/k8s/podinformer/` — existing `cgroup_id → pod` informer; reuse for D-01 attribution.
- `agent/src/main/kotlin/com/platform/agent/` — Kotlin agent being decommissioned (KubesharkClient, ConfigClient, CollectorClient, K8sServiceDiscovery, PlatformClient, TrafficTransformer). The Go port replicates the *behavior* (RegistrationOutcome semantics, config polling cadence) but not the *types* (wire-compatible only, per `ignoreUnknownKeys = true`).
- `collector/src/main/kotlin/com/platform/collector/models/` — `CreateCapturedInputRequest` / `BatchCreateCapturedInputRequest` — the byte-for-byte wire contract.
- `k8s/tap/daemonset.yaml`, `k8s/tap/rbac.yaml` — existing vp-tap DaemonSet base; Helm chart in CAPTURE-05 builds on this shape (privileged, hostPID, BTF mount, cgroupfs mount, NODE_NAME via downward API).
- `k8s/agent/base/agent.yaml`, `k8s/agent/overlays/sandbox/` — Kotlin agent manifests; deleted in decommission PR 1.
- `scripts/sandbox-up.sh` — the hard-swap PR mutates this script.
- `.github/workflows/pr_main.yml` — existing `tap-test` job that already runs `go test ./...` against `tap/`; no new CI job needed.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **`tap/cmd/vp-tap/main.go` four-goroutine orchestration pattern** — shutdown handler, heartbeat, informer Run, capture loop. Phase 1 extends this to the goroutine pipeline (D-03); the orchestration shell stays.
- **`tap/internal/k8s/podinformer/`** — production-ready `cgroup_id → pod` informer with `NODE_NAME`-scoped field selector and `Closeable`-equivalent lifecycle. Reuse directly for attribution; Service-discovery informer is a sibling addition.
- **`probe.bpf.c` HTTP-prefix in-kernel filter** — first 4 bytes pre-screened against HTTP/1.1 method tokens. Reuse; extend with read-side hook.
- **`.github/workflows/pr_main.yml` `tap-test` job** — already gates Go code on every PR. Recursive `go test ./...` covers any new `tap/...` subpackages without modification.
- **Existing JWT model** — `installJwtAuth()` in `shared/` + `Authorization: Bearer <JWT>` on every `/api/*` call. The Go agent re-implements the client side only (raw `Authorization` header on `net/http` requests); platform-side validation is unchanged.

### Established Patterns
- **Wire DTO duplication with `ignoreUnknownKeys = true`** — agent and platform/collector ship and version independently; new optional fields are additive. The Go agent should mirror this with `encoding/json` + lenient decoding. Truncation flag, `extraRedactedHeaders`, etc. are all additive.
- **`RegistrationOutcome` semantics** — Success (201/409), PermanentRejection (400/422), TransientFailure (everything else). Go port preserves this exactly (CAPTURE-03).
- **Single-pod attribution via `cgroup_id` (VAL-55)** — leading 8-byte aligned field on every event. Phase 1 keeps this exactly; pid/fd are *additional* fields, not replacements.
- **Cursor-paginated REST + tenant scoping via JWT** — already in place on collector; agent never reads, only POSTs.

### Integration Points
- **`POST /api/services`** (platform, port 8080) — agent Loop 1 registration; Go port preserves headers, body shape, status-code semantics.
- **`GET /api/agent/config`** (platform) — `AgentConfigResponse` DTO; Go agent decodes into Go-side `DynamicConfig` struct. New optional fields land here (`extraRedactedHeaders`, `extraBodyRedactionPatterns`).
- **`POST /api/captured-inputs`** (collector, port 8081) — gzip-encoded JSON batch; one optional new field (`truncated`) per item under the additive rule.
- **Kubernetes API watches** — Pods (cgroup_id→pod, by `NODE_NAME` field selector) and Services (org-wide). The Service watch is *not* node-scoped (services are cluster resources), so each DaemonSet pod watches the same set — fine at 50-service v1 envelope but the planner should note it.

</code_context>

<specifics>
## Specific Ideas

- **"cgroup_id is canonical for attribution, full stop"** — user pushback during reassembly discussion clarified that `cgroup_id` must remain the unambiguous pod-attribution key. `(pid, fd)` is *only* a transient correlation key for reassembly, not an attribution key, and lives on individual events — never in a kernel hash map that could compete with `cgroup_id`.
- **"Sandbox is sandbox"** — user rejected side-by-side cutover complexity. The revertibility requirement in CAPTURE-10 is satisfied by `git revert` of a single PR; no shadow capture path, no parallel ingest plumbing.
- **"Update tests inline with deletion PRs"** — user explicit ask on decommission sequencing: as Kotlin code is deleted, the e2e / integration tests that exercise it are updated in the *same* PR. CI must never be red between commits during the decommission window.
- **Verify Go CI before designing it** — user caught a discussion-blocker by asking "isn't Go CI already set up?" Answer: yes, `tap-test` job in `pr_main.yml`. This pattern (verify existing infrastructure before designing replacements) applies to every Phase 1 sub-decision.

</specifics>

<deferred>
## Deferred Ideas

- **Per-node JWT or bootstrap-token exchange** — discussed for D-04; deferred. Phase 3's `CallerIdentity.Service` variant may revisit this; not now.
- **Streaming POST for very large bodies** — discussed for D-07; deferred (violates CAPTURE-04 byte-for-byte wire contract). If multi-MB request bodies become a customer requirement, revisit in a future capture-coverage phase.
- **Kernel-side reassembly / sock\* via kprobes** — discussed for D-05; rejected. If a future capture phase needs to capture pre-TLS bytes inside libssl uprobes, kprobes may re-enter the conversation.
- **`writev`/`readv`/`sendto`/`recvfrom` tracepoints** — discussed for D-08; not in Phase 1. Researcher should profile design-partner workloads at integration time; trivial to add.
- **Salt rotation policy for the per-org redaction hash** — surfaced in D-15; researcher to recommend.
- **Pipelined HTTP/1.1 capture support** — D-06 explicitly drops pipelined requests. If a design partner's workload uses HTTP pipelining (rare), revisit.
- **Phase 1 gray areas not selected:** informer ownership (one informer factory vs two), pre-flight failure surfacing path (CrashLoopBackoff vs platform endpoint vs metric only), benchmark doc location + threshold-as-gate question, vp-tap image build pipeline (`ko` vs plain Dockerfile vs Jib-equivalent). Planner makes the call; flag for researcher only if blocking.

</deferred>

---

*Phase: 1-native-capture-cutover*
*Context gathered: 2026-05-14*
