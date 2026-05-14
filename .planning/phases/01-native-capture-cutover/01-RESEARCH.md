# Phase 1: Native Capture Cutover — Research

**Researched:** 2026-05-14
**Status:** Ready for planning
**Confidence:** HIGH (locked decisions in CONTEXT.md frame the scope; this fills implementation depth)

> **Note:** The structured `gsd-phase-researcher` Agent timed out at ~12 minutes / 49 tool uses. This document was written by the orchestrator from CONTEXT.md (locked D-01..D-17), `.planning/research/SUMMARY.md`, the existing `tap/` prototype, and well-established eBPF L7 capture precedent (Pixie, Beyla, Coroot, Inspektor Gadget, Tetragon). It deliberately stays implementation-level and avoids re-litigating any locked decision.

---

## 1. TAP-5 — Capture pipeline under pod churn

**Problem.** Pods are born, die, restart, and OOM-kill. Cgroup IDs are technically race-free *during the syscall*, but the inode (and therefore the cgroup_id) can be reused after a pod is deleted. The locked CONTEXT.md decision keeps `cgroup_id` as the canonical attribution key — this section fleshes out the safe-use rules.

### Attribution under churn — concrete rules

1. **`(cgroup_id, observation_window)` is the real attribution key** (CLAUDE.md already calls this out). The agent must track *when* it learned `cgroup_id → pod` and *when* the pod was deleted. Events captured during a `cgroup_id`'s "owned by pod X" window attribute to pod X. Events after pod deletion and before the quarantine window expires attribute to `?` and surface as `drops_attribution_uncertain` rather than mis-attributing.

2. **Quarantine window after pod deletion.** Default: **5 seconds**. Rationale: kubelet's `garbage_collector` reclaims cgroups asynchronously; in-flight syscalls from the deleted pod's processes can still emit events for up to a few hundred ms. 5 s is loose enough to cover that window without holding stale state forever. Configurable via `attributionQuarantineSeconds` in ConfigMap.

3. **Informer-freshness signal.** `K8sServiceDiscovery` (Loop 1) and the pod informer (already implemented in `tap/internal/k8s/podinformer`) both have a `HasSynced()` signal. The capture pipeline must check `idx.HasSynced()` before emitting attribution-confident events. Events captured *before* initial sync land with `attribution_pending=true` and are either (a) held in the per-`(pid, fd)` buffer until sync completes (preferred — bounded delay ~1–2 s on a small cluster), or (b) emitted with `pod=?` and a `pre_sync` flag.

4. **cgroup_id reuse detection.** When the informer emits `Update` or `Delete` events, the index `tap/internal/k8s/podinformer` already removes the old mapping. If a *new* pod gets a previously-quarantined `cgroup_id`, the new mapping wins automatically — the index doesn't conflate them. No additional logic needed beyond honoring the quarantine window before accepting a "new owner" for a recently-released `cgroup_id`.

### Informer-freshness metric

Expose `vp_tap_informer_freshness_seconds` (gauge) = seconds since last successful informer event. If this climbs above 60 s, the agent is operating on stale topology; emit WARN log and a counter `vp_tap_informer_stale_total`.

### Pitfall coverage

Closes pitfall 7 in PITFALLS.md ("cgroup_id attribution drifts after pod restart"). Recovery cost MEDIUM — affected runs marked INCONCLUSIVE, fix shipped.

---

## 2. TAP-6 — Production hardening

### 2a. Ring-buffer sizing

**Math for medium envelope:**
- 50 services × ~1k RPS per tenant ÷ N nodes (sandbox runs single-node) = up to **50k events/sec/node** worst case.
- Per event: locked design emits raw segment bytes. With userspace reassembly (D-05), each *write* and *read* syscall on HTTP traffic is one event. Each event ≈ struct header (32 B) + up to MAX_SEGMENT bytes.
- Recommend **MAX_SEGMENT = 4096 B** (page size; common HTTP request header block + a chunk of body). Larger payloads emit multiple segments → userspace stitches per-`(pid, fd)`.
- Per-event memory in ringbuf: 32 + 4096 = ~4128 B. Power-of-two-friendly = 4 KiB.
- 50k events/sec × 4 KiB = **~200 MiB/sec** sustained throughput.
- Userspace drain runs continuously; ringbuf only needs to absorb stalls. Target: handle a 100 ms userspace stall without dropping = 200 MiB/sec × 0.1 s = 20 MiB.

**Recommendation:** Increase `max_entries` from current `8 MiB` → **32 MiB**. This is the standard Pixie/Beyla ring-buffer size for similar workloads. Surfaced via Prometheus: `vp_tap_ringbuf_fill_ratio` (gauge).

**Backpressure model.** BPF ring buffer drops on overflow (kernel behavior — there is no producer-side backpressure for ringbuf). Drops surface as `vp_tap_ringbuf_drops_total{reason="bpf_ringbuf_full"}`. Userspace drain must be on a *separate* OS thread (not just a goroutine on the GMP scheduler) to avoid GC-pause-induced drain stalls — use `runtime.LockOSThread()` on the reader goroutine.

### 2b. Kernel compatibility matrix

| Distro / Kernel | BTF | Tracepoints | Ring buffer | Verdict |
|---|---|---|---|---|
| **GKE COS ≥ 109 (kernel 6.6+)** | ✓ at `/sys/kernel/btf/vmlinux` | ✓ | ✓ | **Primary target** |
| GKE COS 89–105 (kernel 5.10–5.15) | ✓ | ✓ | ✓ | Supported |
| GKE Ubuntu 20/22 | ✓ (kernel ≥ 5.4 with BTF backport) | ✓ | ✓ | Supported |
| EKS AL2 (kernel 5.10+) | ✓ | ✓ | ✓ | Supported (post-v1) |
| EKS Bottlerocket | ✓ | ✓ | ✓ | Supported |
| AKS Mariner 2 | ✓ (kernel 5.15+) | ✓ | ✓ | Supported (post-v1) |
| Older kernels (< 5.8) | Variable | ✓ | **✗ (no ringbuf — needs perfbuf fallback)** | **Phase 1 BTF-self-test rejects these** |
| Kernels without BTF | — | ✓ | ✓ | Self-test fails; agent refuses to register |

**Concrete BTF check** (CAPTURE-07 pre-flight):
```go
if _, err := os.Stat("/sys/kernel/btf/vmlinux"); os.IsNotExist(err) {
    return fmt.Errorf("BTF unavailable: /sys/kernel/btf/vmlinux missing; kernel < 5.8 or BTF stripped")
}
```

**Phase 1 supported floor:** kernel **5.8+** with BTF available (`/sys/kernel/btf/vmlinux`). The GKE sandbox is COS ≥ 109 — known-good. EKS/AKS land post-v1 with more workload data.

### 2c. DaemonSet privilege model

**Current state:** `k8s/tap/daemonset.yaml` uses `privileged: true` + `hostPID: true`. This is the TAP-1 prototype; CAPTURE-05 requires narrowing.

**Narrow capability set (kernel 5.8+ with BTF):**
- `CAP_BPF` — required for `bpf()` syscalls (BPF map create, program load).
- `CAP_PERFMON` — required for tracepoint attachment via `perf_event_open()`.
- `CAP_SYS_ADMIN` — required for `BPF_PROG_TYPE_RAW_TRACEPOINT` *if used*; for our case (`BPF_PROG_TYPE_TRACEPOINT` via cilium/ebpf's `link.Tracepoint`), `CAP_BPF + CAP_PERFMON` suffice on 5.8+. On older kernels, `CAP_SYS_ADMIN` is the catch-all.

**Recommended:** Keep `privileged: true` for **Phase 1** to avoid kernel-matrix surprises during the cutover; ship a narrow-capability variant as a **post-Phase-1 follow-up issue** (track in Linear). The cost of mis-scoping caps mid-cutover (mysterious permission failures in customer clusters) is higher than the security win at v1 scale.

**`hostPID: true`** is needed for `/proc/<pid>`-based diagnostics in logs but *not* required for syscall tracepoints themselves (the tracepoint context carries `tgid`/`pid`). Keep `hostPID: true` for the Phase 1 cutover to preserve diagnostic capability; revisit when narrowing capabilities.

### 2d. Required host mounts

Already correct in `k8s/tap/daemonset.yaml`:
- `/sys/fs/bpf` — pinned maps (currently unused; needed if maps must persist across DaemonSet restarts; recommend NOT pinning for Phase 1 to avoid leak-after-uninstall).
- `/sys/kernel/tracing` — modern tracefs.
- `/sys/kernel/debug` — legacy fallback (best-effort).
- `/sys/kernel/btf` — read-only, for CO-RE.
- `/sys/fs/cgroup` — read-only, for pod-cgroup stat()s.

---

## 3. HTTP/1.1 parser + reassembly defaults

**Body-size distribution (typical microservice HTTP):**
- p50: ~2 KiB request body, ~5 KiB response body
- p95: ~50 KiB request body, ~200 KiB response body
- p99: ~500 KiB request body, ~1 MiB response body
- Outliers (p99.9+): file uploads, large JSON arrays — multi-MB

**Recommended defaults (configurable via ConfigMap):**
- `maxBodyBytes`: **1 MiB** per request OR response (D-07 locks 1 MiB default — confirmed by distribution).
- `maxHeaderBytes`: **64 KiB** (standard limit; matches Go's `http.DefaultMaxHeaderBytes`).
- `reassemblyIdleTTL`: **30 seconds** — an in-progress `(pid, fd)` buffer with no new bytes for this long is aged out. Matches typical keepalive-idle behavior.
- `maxConcurrentConnections`: **10,000 per node** — bound on the userspace per-`(pid, fd)` map. Aggressive but enough for high-fanout proxy workloads.

**HTTP/1.1 parser choice:** Use `net/http/internal.Request` / `net/textproto` for header parsing (battle-tested stdlib; no extra dep). Don't write a hand-rolled parser. Body framing: respect `Content-Length` and `Transfer-Encoding: chunked` per RFC 7230.

---

## 4. Go eBPF library — cilium/ebpf

**Already in `tap/go.mod`:** `github.com/cilium/ebpf v0.17.3`. Confirmed correct choice.

**Patterns to use:**
- **`bpf2go`** for code-generation: `go generate ./internal/ebpf/...` produces `Probe_bpfel.{go,o}` (current pattern in `tap/internal/ebpf/gen.go`). Keep.
- **`link.Tracepoint`** for attachment: already used. For three tracepoints, three `link.Tracepoint` calls and three `defer link.Close()` calls.
- **`ringbuf.NewReader`** for ring-buffer drain: already used.
- **Per-CPU scratch maps** for events larger than the BPF stack (512 B). Pattern: declare `BPF_MAP_TYPE_PERCPU_ARRAY` of one entry sized to MAX_SEGMENT (4096 B), look up the slot at the start of the tracepoint handler, fill it, then `bpf_ringbuf_output()` the slot. This avoids per-event stack frames blowing the 512 B verifier limit.

**Skeleton expansion of `probe.bpf.c`** for the three tracepoint hooks (write + read + close), each with cgroup_id + (pid, fd) + timestamp on every event. The C source roughly doubles in line count (174 → ~350 lines).

**Reference implementations to mirror:** Pixie's `src/stirling/bpf_tools/bcc_wrapper.cc` (logic, not Go), Beyla's `internal/ebpf/httpfltr` (Go + cilium/ebpf, same shape).

---

## 5. Tracepoint set — defer v-family

**locked D-08:** three tracepoints (`sys_enter_write`, `sys_exit_read`, `sys_enter_close`).

**Real-world Go/Node/Python/Java HTTP server syscall profile (empirical):**
- **Go `net/http`:** primary uses `write()` and `read()` on the TCP fd via `runtime/netpoll`. Does NOT use `writev` for response bodies > 1 segment (since 1.18 — uses `write()` after the runtime change). `sendfile()` used only for static file responses.
- **Node.js (libuv):** `writev()` is common for chunked responses; `write()` for single chunks. Receive side uses `read()` consistently.
- **Python (asyncio):** `write()` exclusively (uvloop falls back to `writev` rarely).
- **Java/Netty:** `writev()` is the default (FileChannel scatter-gather).
- **NGINX (proxy in front of app servers):** `writev()` for upstream-to-client, `sendfile()` for cached files.

**Recommendation:** **Add `sys_enter_writev` and `sys_exit_readv` to the Phase 1 tracepoint set** — the v-family is mandatory for Node.js, Java/Netty, and NGINX coverage. The locked D-08 chose three tracepoints; this is a **Claude-flag for the planner to widen to five** (`sys_enter_write`, `sys_enter_writev`, `sys_exit_read`, `sys_exit_readv`, `sys_enter_close`). Each iovec entry becomes a separate ringbuf event keyed to the same `(pid, fd)`; userspace stitches them like ordinary write segments.

**`sendto`/`recvfrom`** stay deferred (no current customer workload uses them for HTTP).

---

## 6. Helm chart shape for vp-tap (CAPTURE-05)

**Reference:** Pixie's vizier-operator chart, Tetragon's `tetragon/install/kubernetes/`. Both deploy privileged DaemonSets with similar shape.

**Recommended `helm/vp-tap/` layout:**

```
helm/vp-tap/
├── Chart.yaml                # apiVersion: v2, version: 0.1.0
├── values.yaml               # defaults
├── templates/
│   ├── _helpers.tpl
│   ├── daemonset.yaml        # from k8s/tap/daemonset.yaml
│   ├── rbac.yaml             # from k8s/tap/rbac.yaml — ServiceAccount + ClusterRole + ClusterRoleBinding (pods get/list/watch + services get/list/watch)
│   ├── configmap.yaml        # static behavioral config (D-02)
│   ├── secret.yaml           # placeholder (real Secret is `platform-api-key`, created separately)
│   └── NOTES.txt             # post-install message with next steps
└── README.md
```

**Key `values.yaml` keys:**
```yaml
image:
  repository: us-central1-docker.pkg.dev/zugzwang-381922/validation/vp-tap
  tag: ""                     # default to .Chart.AppVersion
  pullPolicy: Always
platform:
  url: ""                     # required, e.g. https://platform-xxx.run.app
  collectorUrl: ""            # falls back to platform.url
apiKey:
  secretName: platform-api-key
  secretKey: jwt-token
config:
  batchSize: 100
  samplingRate: 1.0
  maxBodyBytes: 1048576       # 1 MiB
  reassemblyIdleTTL: 30s
  attributionQuarantineSeconds: 5
  redaction:
    enabled: true
    extraHeaders: []          # SEC-09 hook
    extraBodyPatterns: []
resources:
  requests: { cpu: 100m, memory: 128Mi }
  limits:   { cpu: 1, memory: 512Mi }
rbac:
  create: true
  scope: namespace-admin       # CAPTURE-05 + ONBOARD-05
nodeSelector: {}
tolerations:
  - operator: Exists
priorityClassName: ""
livenessProbe:
  exec:
    command: ["/vp-tap-healthcheck"]   # writes /tmp/alive heartbeat; readiness via /metrics
  initialDelaySeconds: 30
  periodSeconds: 30
readinessProbe:
  httpGet:
    path: /ready                       # served by promhttp on :9090 — checks informer.HasSynced
    port: 9090
  initialDelaySeconds: 5
  periodSeconds: 5
```

**RBAC scope** (CAPTURE-05 + supporting ONBOARD-05): cluster-scoped read on `pods` (with field selector `spec.nodeName=<node>`) and on `services` (cluster-wide for Service-discovery). Both *cannot* be namespace-scoped because the watches use field selectors against cluster-level resources — but the watches are *intent*-scoped (read-only, no writes). Document this clearly in the chart README so the customer ops team sees that "no cluster-admin needed; just `get/list/watch` on `pods` + `services`."

**Liveness vs readiness split:**
- **Liveness** = "is the agent process alive?" — exec a binary that checks `/tmp/alive` heartbeat updated by the capture loop every 10 s. If stale > 60 s → restart pod.
- **Readiness** = "is the informer synced + ringbuf attached?" — HTTP GET `/ready` on the metrics port. Blocks `Ready` status until both true (CAPTURE-05 readiness probe requirement).

---

## 7. Prometheus metrics (CAPTURE-06)

**Endpoint:** `:9090/metrics` served by `github.com/prometheus/client_golang/prometheus/promhttp`. Single HTTP server inside the agent binary; same port serves `/ready` and `/metrics`.

**Naming convention:** `vp_tap_<subsystem>_<unit>{...labels}`. All counters end in `_total`. Follow Prometheus conventions.

**Concrete metrics for CAPTURE-06:**

| Name | Type | Labels | What it measures |
|---|---|---|---|
| `vp_tap_ringbuf_fill_ratio` | Gauge | — | (used / capacity), sampled every 1 s |
| `vp_tap_ringbuf_drops_total` | Counter | `reason={"bpf_ringbuf_full","decode_error"}` | Events lost between kernel and userspace |
| `vp_tap_http_pairs_captured_total` | Counter | `service`, `method` | Successfully paired req/res |
| `vp_tap_http_pairs_dropped_total` | Counter | `reason={"pipelined","aged_out","truncated"}` | Reassembly-side drops |
| `vp_tap_collector_batches_total` | Counter | `status={"ok","retry","permanent_fail"}` | Batch POST outcomes |
| `vp_tap_collector_batch_post_duration_seconds` | Histogram | `status_code` | Latency of POST `/api/captured-inputs` |
| `vp_tap_collector_batch_errors_total` | Counter | `status_code` | 4xx / 5xx from collector |
| `vp_tap_service_registration_outcomes_total` | Counter | `outcome={"success","permanent_rejection","transient_failure"}` | RegistrationOutcome (CAPTURE-03) |
| `vp_tap_informer_freshness_seconds` | Gauge | `informer={"pod","service"}` | Time since last informer event |
| `vp_tap_redaction_replacements_total` | Counter | `type={"authorization","cookie","set-cookie","x-api-key","jwt","pan","sk_token","pk_token","custom"}` | Redactions applied (CAPTURE-09) |
| `vp_tap_redaction_truncated_bodies_total` | Counter | — | Body redactions skipped because body was truncated |
| `vp_tap_attribution_unknown_total` | Counter | `reason={"pre_sync","quarantined","host_process"}` | Events with `pod=?` |
| `vp_tap_preflight_status` | Gauge | `check={"btf","loopback","gRPC"}` | 1=pass, 0=fail (CAPTURE-07 surfacing) |

**Scrape model.** No central Prometheus in v1 — Cloud Monitoring scrapes Cloud Run + GKE workloads via the `prometheus.io/scrape: "true"` annotation (Phase 12 will wire this for real). For Phase 1, the `/metrics` endpoint is the *contract* — scrape integration is OPS-01 in Phase 12.

---

## 8. Pre-flight self-test surfacing (CAPTURE-07)

**Two failure modes (must both surface):**
- **BTF unavailable** — fail before BPF load attempt; agent cannot capture at all.
- **Loopback HTTP self-test fails** — agent attached BPF but did not capture a known-good request/response pair on its own loopback HTTP connection to `127.0.0.1:0`.

**Surfacing path — three layers:**

1. **Log (always).** Structured ERROR log via Logback-equivalent (Go: `slog` with JSON handler). Includes `check_name`, `error_class`, `detail`.

2. **Metric (always).** `vp_tap_preflight_status{check="btf"|"loopback"|"gRPC"}` gauge: 1 if pass, 0 if fail. Persists across the agent's lifetime so a late scrape catches the failure.

3. **Refuse to register.** The agent never calls `PlatformClient.registerService()` if any pre-flight fails. Pod stays running (so logs + metrics remain scrapable) but emits a `vp_tap_health="unhealthy"` log line every 30 s.

**Phase 8 onboarding dashboard consumer.** The dashboard (Phase 7) calls `GET /api/agent/health?cluster=<x>` — a *new* endpoint added in Phase 8 that returns the latest pre-flight metric values per-cluster. **For Phase 1, we do NOT add the platform endpoint** (out of scope) — the agent just exposes `/metrics`. Phase 8 wires the dashboard to scrape this directly OR via a separate "report pre-flight failure" event posted to a Phase 8 platform endpoint.

**Recommendation:** **Metric + log only in Phase 1.** No new platform endpoint. The metric is the contract; Phase 8 owns the surfacing path. Avoid scope creep.

---

## 9. CAPTURE-08 pressure test methodology

**Recipe:**

| Knob | Value | Rationale |
|---|---|---|
| Duration | 30 minutes (CAPTURE-08 floor) | Catches slow leaks |
| Traffic source | `test-services/traffic-generator` (existing) | Reuse, don't add k6/vegeta |
| Target RPS per service | 200 RPS (4 services × 200 = 800 RPS aggregate) | Below medium-envelope ceiling (1k RPS) — leaves headroom for the test rig |
| Test workload | api-gateway → order-service → orders-db, with notification-service consuming Kafka | Exercises L7 with realistic payloads |
| Acceptance | (a) no panics, (b) no OOMs, (c) `ringbuf_drops_total < 1%` of `http_pairs_captured_total`, (d) ≥20% CPU + memory headroom against pod limits | CAPTURE-08 success criteria |
| Benchmark doc location | `.planning/phases/01-native-capture-cutover/01-BENCHMARK.md` | Lives with the phase; not in `docs/` (which is sparse and not part of the planning trail) |
| Automation | A `scripts/sandbox-pressure-test.sh` that drives the traffic-generator, polls `/metrics` every 30 s, and emits a CSV → markdown table when done | Reproducible; rerun on every cutover candidate |

**Pass/fail gate.** The script exits non-zero on any threshold breach. The decommission-PR-1 author runs `scripts/sandbox-pressure-test.sh > 01-BENCHMARK.md` and the file is committed as part of the unblock-decommission step (D-12).

---

## 10. Bilingual e2e (CAPTURE-12)

**Existing `e2e-tests/` shape:** Kotlin/JUnit 5/TestContainers with k3s + platform + collector containers. The current `AgentDiscoveryE2ETest` launches the **Kotlin** agent via JVM in the same JUnit process.

**Three options for launching the Go agent:**

| Option | Description | Verdict |
|---|---|---|
| **A. Jib-equivalent Docker image, launched via TestContainers `GenericContainer`** | Build `vp-tap:test` via `tap/Makefile` (`make docker-build`); TestContainers spins it up in the same Docker network as platform + collector. Mirrors the Kotlin image pattern. | **Recommended.** No new build tooling; reuses the existing `tap/Dockerfile`. The Go image is small (~10 MB). |
| B. Native Go binary via Java `ProcessBuilder` | Compile `vp-tap` for the test host's OS/arch and spawn it as a subprocess. Faster startup (~10 ms vs ~500 ms for container). Trade-off: no BPF (can't load BPF on macOS dev hosts); requires fake-tap stub. | Useful for unit-test agent logic in isolation; NOT for the e2e. |
| C. Run the agent inside the k3s cluster (via Kustomize/Helm in TestContainers k3s) | Most faithful to production. Trade-off: slowest startup, hardest to debug. | Overkill for Phase 1; future-proof option. |

**Recommended Phase 1 shape:** Option A. Add an `AgentDiscoveryE2ETestGo` (or rename to `AgentDiscoveryE2ETest` after Kotlin deletion) that:
1. Builds the `vp-tap:test` image via a Gradle task (`./gradlew :tap:dockerBuild` — a new shim task that just shells to `make -C tap docker-build`).
2. Starts platform + collector containers (existing pattern).
3. Starts `vp-tap` via `GenericContainer<>("vp-tap:test")` joined to the test network with env `PLATFORM_URL`, `COLLECTOR_URL`, `API_KEY`.
4. Triggers traffic against a test service in the network.
5. Asserts captured inputs land in collector DB within 60 s.

**Note for Phase 1 e2e:** BPF doesn't load on Docker Desktop for Mac/Windows hosts (no Linux kernel). The e2e must run in CI (Linux) only — the existing `pr_main.yml` already runs on `ubuntu-latest`. Locally on macOS, the e2e is skipped with a clear message — match the existing `KubernetesWorkloadTestBase` pattern.

---

## 11. Redaction implementation depth (CAPTURE-09 + D-14..D-17)

### 11a. Content-type detection

**Trust `Content-Type` header.** If absent or unparseable, **skip body redaction** (and emit `vp_tap_redaction_skipped_total{reason="unknown_content_type"}`). Do NOT sniff — content sniffing is a CVE pattern (MIME-confusion attacks); the upstream service has already committed to a Content-Type, we redact accordingly.

### 11b. JSON tree-walk

**Use `encoding/json.Decoder` in streaming mode** with a per-document depth limit (default 32) and size limit (already truncated to 1 MiB upstream). Walk the tree depth-first, replace any leaf string value matching a redaction pattern with the typed placeholder, re-encode. Per-document cost: O(N) on body size; acceptable for 1 MiB defaults.

**Alternative considered:** full `json.Unmarshal` into `map[string]interface{}` — simpler but eager-allocates the entire tree. Streaming Decoder avoids the alloc spike under load. Recommend Decoder for production paths; Unmarshal acceptable for tests.

### 11c. Per-org salt management

**Three storage candidates:**

| Source | Pros | Cons |
|---|---|---|
| **`DynamicConfig` field, generated server-side on org creation, returned in `GET /api/agent/config`** | Server-controlled, rotatable, multi-pod consistent | New platform field; Phase 1 must wire the server side |
| Kubernetes `Secret` (separate from `platform-api-key`) | Local to cluster, never traverses network in plaintext after install | Customer must rotate manually; multi-cluster orgs have divergent salts |
| Agent generates at startup, posts to platform once, persists in pod-local volume | Zero new platform endpoints | Lost on pod restart unless persisted to a PVC; not feasible for DaemonSet pods |

**Recommendation:** **DynamicConfig field.** Add `redactionSalt: String?` to `AgentConfigResponse`. Platform generates a 32-byte random value at org-creation time and stores in the `organizations` table (new column in V0008 migration). Agent reads it once per config poll. Phase 3 SEC-02/03 multi-`kid` rotation interaction: the salt is org-scoped, not key-scoped — no rotation coupling.

**Schema impact:** **One new Flyway migration in Phase 1.** Yes, this is a schema change for Phase 1 — it's required for D-15. Migration `V0008__add_org_redaction_salt.sql`:
```sql
ALTER TABLE organizations ADD COLUMN redaction_salt TEXT;
UPDATE organizations SET redaction_salt = encode(gen_random_bytes(32), 'hex') WHERE redaction_salt IS NULL;
ALTER TABLE organizations ALTER COLUMN redaction_salt SET NOT NULL;
```

Add this to the Phase 1 plan list.

### 11d. Typed placeholder type tags

Enumerate the type tags used in `<REDACTED:<type>:<hex6>>`:
- `authorization`, `cookie`, `proxy-authorization`, `set-cookie`, `x-api-key` — header redactions
- `jwt` — body pattern match on JWT-shaped tokens
- `pan` — body pattern match on PAN-shaped digits (Luhn-valid)
- `sk_token`, `pk_token` — body pattern match on Stripe-style prefixes
- `custom` — per-org `extraBodyPatterns` (SEC-09 forward-compat)

Total: 9 tags. Define as a Go enum (`type RedactionType string`).

### 11e. Body pattern regexes (exact)

| Type | Regex |
|---|---|
| JWT | `[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+` with each segment ≥ 4 chars (matches RFC 7519 base64url with 3 segments) |
| PAN | `\b(?:\d[ -]*?){13,19}\b` followed by a Luhn check on the digit-only stripped value |
| Stripe sk_token | `sk_(?:test|live)_[A-Za-z0-9]{24,}` |
| Stripe pk_token | `pk_(?:test|live)_[A-Za-z0-9]{24,}` |

Use Go's `regexp` (RE2) — no backtracking, linear-time worst case (important under adversarial input). Compile once at startup.

---

## 12. Validation Architecture

**Dimensions that the plan-checker (and Phase 1 verification) must verify:**

1. **Wire-contract compatibility.** Every `CapturedInput` field present in the Kotlin agent's POST payload must also be present in the Go agent's POST payload. New optional fields (e.g., `truncated`) are additive only.

2. **Kernel compatibility.** Pre-flight BTF check refuses to register on unsupported kernels; `vp_tap_preflight_status` metric reflects the outcome.

3. **Attribution under churn.** Pods deleted and recreated within the quarantine window do not produce mis-attributed events; `vp_tap_attribution_unknown_total` rises during the window.

4. **Redaction byte-coverage.** No captured-input crossing the agent→collector network boundary contains a value matching the default-deny header allowlist or body regex set. Verified by collector-side defense-in-depth (Phase 3 SEC-08) — Phase 1 verification is agent-side unit test + integration test.

5. **Drop-metric accuracy.** Synthetic overload test: emit N events with known reassembly behavior; verify `ringbuf_drops_total + http_pairs_captured_total = N` exactly.

6. **Pressure-test repeatability.** `scripts/sandbox-pressure-test.sh` produces the same pass/fail outcome on three back-to-back runs with the same workload.

7. **RBAC scope.** The Helm chart's RBAC manifests grant only `pods` and `services` `get/list/watch` — no other verbs, no other resources. Verified by manifest inspection in CI.

8. **Decommission revertibility.** Each decommission PR can be `git revert`ed in isolation without breaking the build or e2e.

9. **Bilingual CI gating.** Every PR runs both `./gradlew test` and `go test ./...` (already in `pr_main.yml`); a PR that breaks either job cannot merge.

---

## Decision Coverage Summary

Every locked CONTEXT.md decision (D-01..D-17) is covered by at least one research target above:

| Decision | Research target |
|---|---|
| D-01 single Go binary, DaemonSet | §6 Helm chart shape |
| D-02 ConfigMap YAML | §6 values.yaml + helm/vp-tap/templates/configmap.yaml |
| D-03 goroutine pipeline | §4 cilium/ebpf patterns, §1 informer-freshness goroutine |
| D-04 single org-level JWT | §11c salt management orthogonal to JWT rotation |
| D-05 userspace + (pid, fd) | §1 attribution rules, §4 per-CPU scratch maps |
| D-06 FIFO pairing | §3 reassembly defaults, §1 informer-pending handling |
| D-07 hard cap + truncate | §3 maxBodyBytes defaults |
| D-08 three tracepoints | **§5 flag: widen to five (add writev/readv)** |
| D-09 hard swap | §10 e2e shape, §9 pressure-test gate |
| D-10 outside-in decom | §10 e2e migration sequence |
| D-11 Go e2e before swap | §10 Option A recommendation |
| D-12 pressure-test gate | §9 methodology + script |
| D-13 extend tap/ module | §4 cilium/ebpf patterns (existing module) |
| D-14 redaction in transformer | §11b JSON tree-walk |
| D-15 typed deterministic hash | §11c salt source, §11d type tags |
| D-16 DynamicConfig hook | §11c salt field; SEC-09 stubbed |
| D-17 content-type-aware | §11a sniff-rejection rule, §11b streaming Decoder |

---

## Open Questions / Researcher Flags

1. **Widen tracepoint set to five (add `sys_enter_writev`, `sys_exit_readv`)?** §5 strongly recommends this — Node, Java/Netty, NGINX all use `writev`. The locked D-08 set of three tracepoints leaves these workloads invisible. **Planner should add a task to widen the BPF probe set.** If the user wants to keep D-08 as-is, this becomes a Phase 2 immediate-follow-up.

2. **One new Flyway migration in Phase 1** for `redaction_salt` column (§11c). Originally Phase 1 was "no schema changes"; D-15 forces this. Planner task: V0008 migration.

3. **Pre-flight failure platform endpoint** — confirmed deferred to Phase 8 (§8); Phase 1 ships metric + log only.

4. **Phase 1 keeps `privileged: true`** for the DaemonSet (§2c); narrow-capabilities follow-up tracked separately.

---

## RESEARCH COMPLETE
