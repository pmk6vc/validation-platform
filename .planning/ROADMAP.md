# Roadmap: Native eBPF Traffic Capture Cutover

## Overview

This milestone replaces the Kubeshark + Kotlin agent capture stack with our own Go eBPF tap and a Go agent that reaches full feature parity. The journey runs in six horizontal layers: finish the eBPF tap, build the Go agent core, wire the capture pipeline, harden for sandbox load, cut over the sandbox cluster, then delete the old path entirely. The platform and collector (Kotlin) are untouched throughout. The milestone is complete when the sandbox that previously broke Kubeshark is stable on the new stack and `agent/` is gone from the repo.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [ ] **Phase 1: eBPF Tap** - Complete TAP-3: kprobe capture, TCP reassembly, HTTP/1.1 parsing, pod-label enrichment, in-process channel emission
- [ ] **Phase 2: Go Agent Core** - TAP-4: port non-capture agent surface to Go (config, platform client, config client, K8s discovery, JWT wiring)
- [ ] **Phase 3: Capture Pipeline** - TAP-5: transformer + collector client + end-to-end goroutine pipeline; wire-format parity with Kotlin agent
- [ ] **Phase 4: Production Hardening** - TAP-6: Helm chart, DaemonSet, probes, metrics, backpressure tuning, sandbox load test
- [ ] **Phase 5: Sandbox Cutover** - TAP-7: swap scripts to vp-tap, validate end-to-end on the sandbox, bilingual e2e harness wired to Go agent
- [ ] **Phase 6: Decommission** - TAP-8: delete Kotlin agent module, Kubeshark code, Dockerfile, k8s overlays; update CLAUDE.md

## Phase Details

### Phase 1: eBPF Tap
**Goal**: The Go tap captures live HTTP/1.1 request/response pairs with pod-label enrichment and emits them on a typed in-process channel ready for the Go agent pipeline to consume
**Depends on**: Nothing (TAP-3 is already in progress on the active branch)
**Requirements**: TAP-01, TAP-02, TAP-03, TAP-04, TAP-05, TAP-06, TAP-07
**Success Criteria** (what must be TRUE):
  1. Running `vp-tap` on a GKE COS node (kernel ≥ 6.12) captures HTTP/1.1 pairs from a test workload without panics or OOM
  2. Each emitted pair carries namespace, pod name, and app-label fields populated from the K8s informer cgroup_id lookup
  3. The in-process channel receiver sees complete request/response pairs (method, URL, status, latency) with no truncated bodies for payloads under the configured size limit
  4. Drop events (full ring buffer) are counted and logged; zero silent data loss on overrun
  5. The tap binary builds CO-RE via `bpf2go` and `go build ./...` succeeds in CI without a live kernel
**Plans**: TBD

### Phase 2: Go Agent Core
**Goal**: The Go agent has the same three-loop surface area as the Kotlin agent for everything except traffic capture: static + dynamic config, config polling from platform, K8s service discovery + registration with RegistrationOutcome parity, and JWT bearer-token attachment
**Depends on**: Phase 1
**Requirements**: GOAGENT-01, GOAGENT-02, GOAGENT-03, GOAGENT-04, GOAGENT-05, GOAGENT-06, TEST-04
**Success Criteria** (what must be TRUE):
  1. `GET /api/agent/config` is polled on the configured interval and the latest `DynamicConfig` (target services, sampling rate, intervals) is observable by the rest of the process without locks
  2. `POST /api/services` returns `Success` for 201 and 409, `PermanentRejection` for 400/422, and `TransientFailure` for everything else — matching Kotlin `RegistrationOutcome` semantics exactly
  3. K8s service discovery lists cluster Services, filters system namespaces, validates `app=<name>` selector label, and emits a stable `name → service` map; newly discovered services are registered on the next tick
  4. All platform and collector requests carry `Authorization: Bearer <token>` read from `API_KEY` env var; a missing or empty `API_KEY` fails fast at startup
  5. Go-side unit tests cover RegistrationOutcome classification, namespace filter logic, and config-update propagation at parity with the Kotlin agent's per-class test count
**Plans**: TBD

### Phase 3: Capture Pipeline
**Goal**: The full in-process pipeline from tap ring buffer to collector POST is wired and byte-for-byte wire-compatible with the Kotlin agent's `BatchCreateCapturedInputRequest` contract
**Depends on**: Phase 2
**Requirements**: PIPELINE-01, PIPELINE-02, PIPELINE-03, PIPELINE-04, PIPELINE-05, PIPELINE-06
**Success Criteria** (what must be TRUE):
  1. A captured pair that does not match `targetServices` is dropped at the transformer and never reaches the collector client; a matching pair that fails the sampling check is counted but not forwarded
  2. The collector client sends a gzip-compressed JSON body with `Content-Encoding: gzip` that the existing collector (port 8081) accepts and stores without modification
  3. On a 5xx or network error the collector client retries up to 3 times with 200ms → 1.6s → 6.4s back-off; on a 400/401/403/422 it logs and does not retry
  4. A recorded Kotlin-agent batch replayed through the Go collector client produces an identical JSON payload (verified by byte comparison or schema equivalence test)
  5. Clean shutdown via `context.Context` cancellation drains the pipeline and exits without goroutine leaks
**Plans**: TBD

### Phase 4: Production Hardening
**Goal**: The Go agent + tap are packaged as a Helm-installable DaemonSet with probes, Prometheus metrics, and a documented backpressure policy that survives the sandbox pressure-test load for 30 minutes without OOM or excessive drop rate
**Depends on**: Phase 3
**Requirements**: HARDEN-01, HARDEN-02, HARDEN-03, HARDEN-04, HARDEN-05, HARDEN-06, HARDEN-07
**Success Criteria** (what must be TRUE):
  1. `helm install vp-tap helm/vp-tap/` deploys a DaemonSet with correct eBPF privileges (CAP_BPF + CAP_SYS_ADMIN) and ConfigMap + Secret wiring; all pods reach `Running` on a fresh GKE node pool
  2. The liveness probe fails (triggering a pod restart) if the ring-buffer drainer goroutine stops making progress; the readiness probe holds `NotReady` until the K8s informer completes its initial list-and-watch
  3. Prometheus metrics at `/metrics` expose ring-buffer fill ratio, drops/sec, HTTP pairs captured/sec, batches POSTed/sec, batch POST error rate by status code, and per-service registration outcomes
  4. After 30 minutes of sustained sandbox pressure-test traffic, the tap shows no OOMs or panics; drop rate stays below the agreed threshold (threshold recorded in `docs/tap-sandbox-bench.md`)
  5. Per-node CPU and memory footprint under load fit within the existing sandbox node-pool sizing with at least 20% headroom; Terraform retune rationale is committed with benchmark numbers if a change is needed
**Plans**: TBD

### Phase 5: Sandbox Cutover
**Goal**: The sandbox cluster runs exclusively on the vp-tap + Go agent stack; Kubeshark is removed from bring-up scripts; the bilingual e2e harness launches the Go agent binary and passes the same discovery-to-ingest flow that the Kotlin e2e tests covered; the cutover PR is reversible
**Depends on**: Phase 4
**Requirements**: CUTOVER-01, CUTOVER-02, CUTOVER-03, CUTOVER-04, CUTOVER-05, CUTOVER-06, TEST-02, TEST-03, TEST-05
**Success Criteria** (what must be TRUE):
  1. `scripts/sandbox-up.sh` on a cold cluster completes without touching Kubeshark; `helm install vp-tap` runs and the DaemonSet is Ready on every node
  2. From a cold cluster, at least one captured input from an existing test-service is visible in the collector DB within the first capture interval after agent startup
  3. `AgentDiscoveryE2ETest` (or successor) runs against the Go agent binary via Kotlin `e2e-tests/`; it covers K8s service discovery → registration → traffic capture → collector ingest end-to-end
  4. The Kotlin `e2e-tests/` harness can launch the Go agent as a `GenericContainer` or child binary with the same JWT + env var contract the production agent receives
  5. Reverting the cutover PR(s) restores the prior `sandbox-up.sh` and Kotlin agent overlay without requiring additional manual steps
**Plans**: TBD
**UI hint**: no

### Phase 6: Decommission
**Goal**: The Kotlin agent module, all Kubeshark-specific code and fixtures, the old Dockerfile and k8s overlays are deleted from the repo in small reviewable PRs; CLAUDE.md describes the Go capture path; `./gradlew build` and `go test ./...` both pass
**Depends on**: Phase 5
**Requirements**: DECOM-01, DECOM-02, DECOM-03, DECOM-04, DECOM-05, DECOM-06, DECOM-07, TEST-01
**Success Criteria** (what must be TRUE):
  1. Every integration and e2e behavior previously covered against the Kotlin agent has an equivalent passing test against the Go agent before `agent/` source is deleted (TEST-01 gate is met)
  2. `./gradlew build` succeeds after `agent/` is removed from `settings.gradle.kts`; no remaining Kotlin source references `agent/` or Kubeshark packages
  3. `grep -r "KubesharkClient\|kubeshark\|KUBESHARK_URL\|wsFull\|KFL\|KubesharkEntry" --include="*.kt" --include="*.yaml" --include="*.sh" .` returns no matches outside of `docs/` or git history
  4. CLAUDE.md accurately describes the Go capture path; all references to Kubeshark WebSocket transport, KFL, reconnect-dedup, and "Kotlin agent" are replaced or removed
  5. Each deletion lands as a separate, reviewable PR scoped to one logical layer; no single PR removes more than one of: agent source, Kubeshark Kotlin code, Dockerfile/Jib, k8s overlays, test fixtures
**Plans**: TBD

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4 → 5 → 6

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. eBPF Tap | 0/TBD | Not started | - |
| 2. Go Agent Core | 0/TBD | Not started | - |
| 3. Capture Pipeline | 0/TBD | Not started | - |
| 4. Production Hardening | 0/TBD | Not started | - |
| 5. Sandbox Cutover | 0/TBD | Not started | - |
| 6. Decommission | 0/TBD | Not started | - |
