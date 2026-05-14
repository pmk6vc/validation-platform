# Requirements: Native eBPF Traffic Capture Cutover

**Defined:** 2026-05-14
**Core Value:** The tap and Go agent capture production traffic stably on the sandbox cluster that previously broke Kubeshark — and Kubeshark + the Kotlin agent are gone from the repo.

## v1 Requirements

Requirements for this milestone. Each maps to a roadmap phase.

### Tap (eBPF capture) — covers TAP-3

- [ ] **TAP-01**: Kernel-side eBPF programs attached to `sys_enter_write` and `sys_exit_read` (kprobes) capture in/out byte streams for TCP sockets, emitted via ring buffer to userspace.
- [ ] **TAP-02**: Userspace Go drainer consumes the ring buffer with bounded buffering and backpressure handling (drops are counted and observable, not silent).
- [ ] **TAP-03**: TCP stream reassembly stitches sequential write/read events into request/response byte streams keyed by (pid, fd).
- [ ] **TAP-04**: HTTP/1.1 parser extracts method, URL, request headers, response status, response headers, request/response bodies, and per-pair latency from the reassembled streams.
- [ ] **TAP-05**: Pod-label enrichment uses the K8s informer (cgroup_id → pod metadata, already landed in VAL-55 PR2) to attach `namespace`, `pod`, and `app`-label fields to each captured pair.
- [ ] **TAP-06**: Captured pairs are emitted on an in-process channel matching the shape that the Go agent's transformer will consume (TAP-5 contract).
- [ ] **TAP-07**: Tap binary builds CO-RE-compatible with `bpf2go` and runs on GKE COS kernel ≥ 6.12.

### Go agent core — covers TAP-4

- [ ] **GOAGENT-01**: `tap/internal/agent/config` package defines `StaticConfig` (env-driven: `PLATFORM_URL`, `COLLECTOR_URL`, `API_KEY`, kubeshark URL no longer applicable) and `DynamicConfig` (target services, sampling rate, batch size, intervals, namespace filters) matching the wire shape of `AgentConfigResponse` from platform.
- [ ] **GOAGENT-02**: `tap/internal/agent/platformclient` makes `POST /api/services` with bearer JWT and returns a `RegistrationOutcome`-equivalent enum: `Success` (201 and 409 both map to Success per Kotlin parity), `PermanentRejection` (400/422 only), `TransientFailure` (everything else).
- [ ] **GOAGENT-03**: `tap/internal/agent/configclient` polls `GET /api/agent/config` and exposes the latest `DynamicConfig` to the rest of the process via a reactive channel/observer.
- [ ] **GOAGENT-04**: `tap/internal/agent/discovery` uses `client-go` to list K8s `Service` resources, filters system namespaces (`kube-*`, `kubeshark`, `validation`, `default`, `gke-managed-*`, `gmp-*`), validates the `app=<name>` selector label requirement, and emits a stable `name → service` map.
- [ ] **GOAGENT-05**: Service-discovery loop runs at the dynamic-config discovery interval, diffs against `registered` and `permanentlyFailed` sets, calls platformclient per new service, and updates the in-memory `name → serviceId` map.
- [ ] **GOAGENT-06**: JWT bearer-token attachment library applies `Authorization: Bearer <token>` to all platform/collector requests. Tokens are read from `API_KEY` env var (mounted from K8s `Secret` `platform-api-key/jwt-token`); no Go-side validation needed.

### Go agent capture pipeline — covers TAP-5

- [ ] **PIPELINE-01**: `tap/internal/agent/transformer` filters tap-emitted pairs by the `targetServices` set from `DynamicConfig` and drops pairs with no matching service. (Safety net; tap also filters when possible.)
- [ ] **PIPELINE-02**: Transformer applies the dynamic-config sampling rate; sampled-out pairs are counted but not forwarded.
- [ ] **PIPELINE-03**: `tap/internal/agent/collectorclient` batches up to `batchSize` filtered pairs per `BatchCreateCapturedInputRequest`, serializes JSON, gzip-encodes the body, sets `Content-Encoding: gzip`, and POSTs to `/api/captured-inputs` with a bearer JWT.
- [ ] **PIPELINE-04**: Collector POST uses exponential-backoff retry (200ms → 1.6s → 6.4s, max 3 retries) on transient errors (5xx, network failures); permanent errors (400/401/403/422) fail fast and are logged.
- [ ] **PIPELINE-05**: End-to-end in-process flow: tap ring buffer → transformer (filter+sample) → batcher → collectorclient — runs as a goroutine pipeline coordinated by a top-level `context.Context` for clean shutdown.
- [ ] **PIPELINE-06**: Wire format is byte-for-byte compatible with the existing collector contract (verified by replaying a recorded Kotlin-agent batch against the Go agent's payload — same JSON shape).

### Production hardening — covers TAP-6

- [ ] **HARDEN-01**: `helm/vp-tap/` chart provides a DaemonSet manifest with required eBPF privileges (CAP_BPF + CAP_SYS_ADMIN scoped, hostPID where needed, ConfigMap for static config, Secret reference for `API_KEY`).
- [ ] **HARDEN-02**: Liveness probe verifies the userspace ring-buffer drainer goroutine is making progress (heartbeat file or HTTP `/healthz` updated by the drain loop, not just process-up).
- [ ] **HARDEN-03**: Readiness probe blocks pod from being marked ready until the K8s informer has done its initial list-and-watch and the first discovery tick has completed.
- [ ] **HARDEN-04**: Prometheus metrics endpoint exposes: ring-buffer fill ratio, drops/sec, HTTP pairs captured/sec, batches POSTed/sec, batch POST error rate (by status code), service registrations succeeded/permanently-rejected/transient.
- [ ] **HARDEN-05**: Backpressure policy on a full ring buffer is documented and tested: oldest events dropped with a counter increment; ring-buffer size is configurable via the chart values (default sized for the sandbox load).
- [ ] **HARDEN-06**: Tap survives a sustained load test on the existing pressure-test sandbox cluster for ≥ 30 minutes without OOMs, panics, or drop rates above an agreed threshold; results recorded in `docs/tap-sandbox-bench.md` (or chosen location) for revisit.
- [ ] **HARDEN-07**: Binary size, image size, and per-node CPU/memory footprint under the pressure-test load fit the existing sandbox node-pool sizing; Terraform retune only if measured headroom is < 20%.

### Sandbox cutover — covers TAP-7

- [ ] **CUTOVER-01**: `scripts/sandbox-up.sh` removes the Kubeshark `helm upgrade --install` block and the wait for Kubeshark to be ready; the script no longer attempts to install or configure Kubeshark.
- [ ] **CUTOVER-02**: `scripts/sandbox-up.sh` installs `vp-tap` via `helm upgrade --install vp-tap helm/vp-tap/` and waits for the DaemonSet to reach `Ready` on every node.
- [ ] **CUTOVER-03**: `k8s/agent/overlays/sandbox/` no longer references Kubeshark URLs (`KUBESHARK_URL` placeholder removed); the new Go agent is deployed as a Deployment (or merged into the vp-tap DaemonSet if the design lands that way) reading config from a ConfigMap and secret.
- [ ] **CUTOVER-04**: Sandbox bring-up from a cold cluster (no prior state) succeeds end-to-end: cluster up → vp-tap up → Go agent up → seeded org token issued → at least one captured input lands in the collector DB for an existing test-service.
- [ ] **CUTOVER-05**: Terraform node-pool config is retuned only if HARDEN-06/07 results require it; any change is committed with explicit rationale referencing benchmark numbers.
- [ ] **CUTOVER-06**: Cutover is reversible: a single revert of the cutover PR(s) restores the Kubeshark + Kotlin-agent path until TAP-8 lands.

### Decommission — covers TAP-8

- [ ] **DECOM-01**: Kotlin `agent/` Gradle module is deleted (source tree, `agent/build.gradle.kts`, and its include in `settings.gradle.kts`); `./gradlew build` still succeeds with the remaining modules.
- [ ] **DECOM-02**: `KubesharkClient.kt`, Kubeshark wire-format DTOs (`KubesharkEntry.kt`), and any other Kubeshark-specific Kotlin code are deleted.
- [ ] **DECOM-03**: `deploy/Dockerfile.agent` and any Jib config for the Kotlin agent image are deleted; agent container image is now produced from `tap/` only.
- [ ] **DECOM-04**: `k8s/agent/` overlay tree is deleted (Kustomize bases and overlays for the old Kotlin agent); the new Go agent's manifests live under `helm/vp-tap/` or the new chosen location.
- [ ] **DECOM-05**: Kubeshark test fixtures and integration setups (e.g. `kubeshark-v53` references in tests) are removed from the test tree.
- [ ] **DECOM-06**: `CLAUDE.md` is updated to describe the new Go capture path; references to Kubeshark, `KUBESHARK_URL`, WebSocket transport, KFL, reconnect-dedup, and "agent module (Kotlin)" are removed or rewritten.
- [ ] **DECOM-07**: All decommission work lands in small reviewable PRs (each PR explained by a single bullet from above, or finer); no single PR removes more than one logical layer.

### Test coverage preservation — cross-cuts TAP-7 and TAP-8

- [ ] **TEST-01**: Every integration / e2e behavior covered by `e2e-tests/` against the Kotlin agent today has an equivalent test against the Go agent before the Kotlin agent code is deleted (DECOM-01 is blocked by this).
- [ ] **TEST-02**: `e2e-tests/` (Kotlin) launches the Go agent as a child binary or `GenericContainer` alongside the existing platform + collector containers; the harness passes the same JWT and env vars the production agent receives.
- [ ] **TEST-03**: `AgentDiscoveryE2ETest` (or successor) covers: K8s service discovery → service registration → traffic capture from a real test-service → ingest at the collector — running through the Go agent end-to-end.
- [ ] **TEST-04**: Go-side unit and integration tests in `tap/` cover the new code at parity with the Kotlin agent's per-class tests (RegistrationOutcome classification, KFL-equivalent filter logic, batch + gzip serialization, retry policy).
- [ ] **TEST-05**: CI runs both the Kotlin (`./gradlew test`) and Go (`go test ./...`) test suites on every PR for the duration of the cutover; no green PR may regress coverage on either side.

## v2 Requirements

Deferred to a future milestone, tracked but not in current roadmap.

### Encrypted traffic

- **V2-TLS-01**: Capture from TLS-using services via userspace uprobes on libssl / OpenSSL / Go crypto stack — needed for production targets that don't terminate TLS at a sidecar.

### Protocol coverage (TAP-9)

- **V2-HTTP2-01**: HTTP/2 framing + HPACK decoding in the userspace dissector.
- **V2-GRPC-01**: gRPC length-prefixed message parsing on top of HTTP/2 streams.

### Documentation (TAP-10)

- **V2-DOC-01**: `docs/tap-architecture.md` — kernel hooks, ring-buffer drainer, dissector design.
- **V2-DOC-02**: `docs/customer-install.md` — operator-facing install guide for the new capture path.
- **V2-DOC-03**: `docs/compatibility-matrix.md` — supported kernel versions, distros, CNIs.
- **V2-DOC-04**: `docs/troubleshooting.md` — common failure modes and diagnostics.

### Replay / verdicts

- **V2-REPLAY-01..N** — existing Phase 4/5 plan from `CLAUDE.md` (ReplayEngine, StagingObserver, ComparisonEngine, VerdictGenerator, orchestration API). Unaffected by this milestone.

## Out of Scope

| Feature | Reason |
|---------|--------|
| Long-running coexistence regime (feature-flag swap between Kubeshark and vp-tap) | Adds operational and code complexity for a transition we want short. Cutover is a direct swap with a revert-able PR boundary. |
| Multi-cluster federation | Single-cluster scope per existing platform design principle; not affected by this milestone. |
| Customer onboarding work (separate Linear project) | Different surface area; this milestone is exclusively the capture-layer cutover. |
| Replay engine / observation / verdicts / orchestration | Belongs to Phase 4–5 of the original delivery plan; explicitly not in this milestone. |
| HTTP/2 + gRPC framing (TAP-9) | Post-cutover follow-on; risks slipping the "validate ASAP" goal. |
| Documentation set (TAP-10) | Deferred; ship the code first, document after the cutover stabilizes. |
| TLS / encrypted-traffic capture | Requires userspace uprobes outside the scope of TAP-3; addressed with HTTP/2 work when reconsidered. |
| Kafka / PubSub / SNS / SQS capture | Already deferred at the architecture level; unaffected by this milestone. |
| Synthetic / off-cluster benchmark suite | The pressure-test sandbox is the realistic bar; synthetic benchmarks would lie. |

## Traceability

Filled by the roadmapper. Empty for now.

| Requirement | Phase | Status |
|-------------|-------|--------|
| TAP-01..07 | TBD | Pending |
| GOAGENT-01..06 | TBD | Pending |
| PIPELINE-01..06 | TBD | Pending |
| HARDEN-01..07 | TBD | Pending |
| CUTOVER-01..06 | TBD | Pending |
| DECOM-01..07 | TBD | Pending |
| TEST-01..05 | TBD | Pending |

**Coverage:**
- v1 requirements: 44 total
- Mapped to phases: 0
- Unmapped: 44 (roadmapper will resolve)

---
*Requirements defined: 2026-05-14*
*Last updated: 2026-05-14 after initial definition*
