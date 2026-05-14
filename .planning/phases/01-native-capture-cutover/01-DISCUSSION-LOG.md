# Phase 1: Native Capture Cutover - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-05-14
**Phase:** 1-native-capture-cutover
**Areas discussed:** Tap↔agent topology, Reassembly + body capture, Cutover & parity strategy, Redaction pipeline + placeholders

---

## Tap ↔ Agent Topology

### Q1 — Packaging on a customer cluster

| Option | Description | Selected |
|--------|-------------|----------|
| One Go binary, one DaemonSet | Single vp-agent binary owns BPF + both informers + redaction + batching + POST. One DaemonSet per node. Reuses existing k8s/tap shape. | ✓ |
| Two containers, one DaemonSet pod | tap (privileged) + agent (non-privileged), IPC via shared volume/socket. Cleaner privilege boundary; adds IPC surface. | |
| DaemonSet + central Deployment | tap = DaemonSet, agent = single-replica Deployment aggregating events. Matches Kotlin shape; adds cross-node wire format. | |
| You decide | | |

**User's choice:** One Go binary, one DaemonSet (recommended).
**Notes:** Minimum new infra; matches existing `tap/cmd/vp-tap` evolution.

### Q2 — Static config delivery path

| Option | Description | Selected |
|--------|-------------|----------|
| ConfigMap-mounted YAML file | `config.yaml` mounted into pod; binary reads at startup. Helm templates ConfigMap from values.yaml. | ✓ |
| Env vars only (current Kotlin shape) | Keep all config as env vars; satisfy CAPTURE-05 via envFrom configMapRef. | |
| Hybrid | Secret→env for JWT, env for URLs, ConfigMap YAML for behavioral defaults. | |
| You decide | | |

**User's choice:** ConfigMap-mounted YAML file (recommended).
**Notes:** Decisions captured in CONTEXT.md D-02 ended up effectively hybrid (Secret + env + ConfigMap) because URLs and JWT have to go elsewhere; the YAML file is the *new* surface CAPTURE-05 calls for.

### Q3 — Go concurrency shape

| Option | Description | Selected |
|--------|-------------|----------|
| Goroutine pipeline + context.Context | ringbuf → reassembler → transformer → batcher → collectorclient as channel-connected stages. Service discovery + config polling are sibling goroutines. | ✓ |
| Mirror Kotlin 3 loops | Three independent goroutines, no internal pipeline. | |
| Single event loop + worker pool | One main loop dispatches to a worker pool. | |
| You decide | | |

**User's choice:** Goroutine pipeline + context.Context (recommended).

### Q4 — JWT delivery on N pods

| Option | Description | Selected |
|--------|-------------|----------|
| Single org-level JWT via Secret | Same Secret mounted into every pod. One JWT per cluster install. | ✓ |
| Per-node JWT issued at registration | Each pod registers and gets a node-scoped JWT. Requires new platform endpoint. | |
| Bootstrap token → long-lived JWT | Short-lived bootstrap traded for long-lived JWT on first run. | |
| You decide | | |

**User's choice:** Keep single org-level JWT via Secret (recommended).
**Notes:** Phase 3 SEC-02/03 multi-`kid` rotation will rotate without redeploy.

---

## Reassembly + Body Capture

### Q1 — Reassembly location and connection key

| Option | Description | Selected |
|--------|-------------|----------|
| Userspace reassembly + (pid, fd) | Kernel emits raw segments; Go agent stitches per (pid, fd). cgroup_id stays the canonical attribution key. | ✓ |
| Kernel reassembly | BPF maps hold per-(pid, fd) state; HTTP parsing in-kernel. | |
| Hybrid kernel/userspace | Kernel buffers per (pid, fd) briefly; userspace parses + pairs. | |
| You decide | | |

**User's choice:** Userspace reassembly + (pid, fd) (recommended), AFTER explicit pushback to confirm `cgroup_id` remains the canonical attribution key.
**Notes:** User asked "why do we need (pid, fd) at all — cgroup_id is canonical." Claude clarified: cgroup_id is attribution, `(pid, fd)` is reassembly correlation (a single pod has many concurrent TCP connections; bytes would interleave with only cgroup_id). User asked for recommendation + precedent; Claude provided Pixie / Beyla / Coroot / Inspektor Gadget L7 / Kubeshark precedent for `(pid|tgid, fd)` + userspace reassembly. `sock *` via kprobes rejected: unstable kernel-symbol ABI, no actual state savings. User locked the recommendation.

### Q2 — Request→response pairing

| Option | Description | Selected |
|--------|-------------|----------|
| FIFO on (pid, fd), drop pipelined | Queue per connection; multiple in-flight requests detected and dropped. | ✓ |
| FIFO with pipelining support | Same plus queue-position tracking. | |
| Time-window heuristic on (cgroup_id, fd) | Drop pid; pair by time windows. | |
| You decide | | |

**User's choice:** FIFO drop pipelined (recommended).

### Q3 — Per-(pid, fd) buffer overflow policy

| Option | Description | Selected |
|--------|-------------|----------|
| Hard cap + truncate + counter | Per-message cap; truncate; `truncated` flag on wire; counter. Idle buffers age out. | ✓ |
| Hard cap + full drop + counter | Drop the whole message on overflow; counter. | |
| Streaming POST | Stream large bodies to collector — changes wire shape; violates CAPTURE-04. | |
| You decide | | |

**User's choice:** Hard cap + truncate + counter (recommended).

### Q4 — BPF hook set

| Option | Description | Selected |
|--------|-------------|----------|
| Three tracepoints: sys_enter_write + sys_exit_read + sys_enter_close | Stable ABI; matches current probe.bpf.c. | (Claude discretion) |
| Add v-family + sendto/recvfrom tracepoints | Broader coverage; more hooks. | |
| Switch to kprobes on tcp_sendmsg/tcp_recvmsg | Unstable ABI; undoes existing choice. | |
| You decide | | ✓ |

**User's choice:** "You decide."
**Claude's call:** Three tracepoints. Flagged for researcher to revisit if any design-partner workload uses writev/readv-heavy code paths.

---

## Cutover & Parity Strategy

### Q1 — Sandbox swap strategy

| Option | Description | Selected |
|--------|-------------|----------|
| Side-by-side then promote | Both stacks run; manual parity inspection; remove Kubeshark + Kotlin agent after soak. | |
| Hard swap with revert window | Single PR removes Kubeshark + Kotlin agent and installs vp-tap. `git revert` is the rollback path. | ✓ |
| Shadow capture path | vp-tap POSTs to a separate /api/captured-inputs-shadow for parity validation. | |
| You decide | | |

**User's choice:** Hard swap (NOT the recommended option).
**Notes:** Sandbox is sandbox; fewer PRs preferred; revertibility satisfied by `git revert`.

### Q2 — Decommission PR sequence

| Option | Description | Selected |
|--------|-------------|----------|
| Outside-in: deployment → module → wire types | k8s/agent + Dockerfile → agent/ module → KubesharkClient + DTOs → CLAUDE.md. | ✓ (with notes) |
| Inside-out: wire types → module → deployment | Reverse order. | |
| One big decommission PR | Single PR removes everything; violates "small reviewable PRs." | |
| You decide | | |

**User's choice:** Outside-in (recommended) **+ user note: "update integration / e2e tests with the new vp-tap approach as code is deleted."**
**Notes:** Tests must not be in a broken state between PRs. Captured in CONTEXT.md D-10 sequence.

### Q3 — e2e timing relative to swap + deletion

| Option | Description | Selected |
|--------|-------------|----------|
| Go e2e green BEFORE sandbox swap | New Go-agent e2e lands alongside existing Kotlin-agent e2e, both green; Kotlin e2e deleted in PR 3. | ✓ |
| Go e2e lands with deletion PRs | Sandbox swap first; new e2e + old e2e deletion together. | |
| Go e2e in a separate later PR | Coverage gap window — rejected. | |
| You decide | | |

**User's choice:** Go e2e green BEFORE sandbox swap (recommended).

### Q4 — Decommission unblock signal

| Option | Description | Selected |
|--------|-------------|----------|
| CAPTURE-08 pressure test passes | 30-min pressure test (no OOMs/panics, drops below threshold, ≥20% headroom). | ✓ |
| Manual "looks good" soak | Eyeball capture rate + dashboard for a configurable window. | |
| Parity comparison against captured fixtures | Replay fixtures against both stacks; require structural equivalence. | |
| You decide | | |

**User's choice:** CAPTURE-08 pressure test passes (recommended).

### Q5 — Go CI for new agent code

| Option | Description | Selected |
|--------|-------------|----------|
| Extend tap/ module — single Go module | Existing tap-test job already runs `go test ./...` recursively. | ✓ |
| Separate agent-go/ module + new CI job | Two go.mod files; duplicated CI. | |
| Replace tap/cmd/vp-tap with tap/cmd/vp-agent | Same as option 1 essentially; rename. | |
| You decide | | |

**User's choice:** Extend tap/ module (recommended).
**Notes:** User clarification question — "isn't Go CI already set up?" — surfaced the existing `tap-test` job in `.github/workflows/pr_main.yml`. Question was reformulated to focus on *where* new Go agent code lives rather than *whether* CI exists.

---

## Redaction Pipeline + Placeholders

### Q1 — Redaction pipeline placement

| Option | Description | Selected |
|--------|-------------|----------|
| Inside transformer, post-reassembly | Parsing-aware redaction on fully-reassembled HTTP messages. | ✓ |
| Post-decode, before reassembly | Raw-byte regex on ringbuf segments. Fragile. | |
| Boundary-only, just before POST | Last step before sendBatch(); longest PII lifetime in memory. | |
| You decide | | |

**User's choice:** Inside transformer, post-reassembly (recommended).

### Q2 — Placeholder scheme

| Option | Description | Selected |
|--------|-------------|----------|
| Typed deterministic hash | `<REDACTED:<type>:<hex6-8>>`, per-org salt. | ✓ |
| Untyped deterministic hash | `<REDACTED:sha256:hex>` — same matching, no type tag. | |
| Static `<REDACTED>` | Simplest; replay engine can't match auth contexts. | |
| You decide | | |

**User's choice:** Typed deterministic hash (recommended).
**Notes:** Salt management (per-org salt source, rotation policy) flagged as researcher follow-up; not closed in this discussion.

### Q3 — DynamicConfig redaction-allowlist hook (forward to SEC-09 Phase 3)

| Option | Description | Selected |
|--------|-------------|----------|
| Design now, no platform endpoint yet | Phase 1 ships field shape; Phase 3 wires platform endpoint + UI. | ✓ |
| Hard-code only; design hook in Phase 3 | Touch redaction code twice. | |
| Full Phase 3 work in Phase 1 | Scope creep. | |
| You decide | | |

**User's choice:** Design now, no platform endpoint yet (recommended).

### Q4 — Body redaction approach

| Option | Description | Selected |
|--------|-------------|----------|
| Content-type-aware: parse JSON, regex elsewhere | Walk JSON tree; regex form/text; skip binary. | ✓ |
| Regex over whole body string | One pass; risks corrupting JSON structure. | |
| Regex + JSON-validity repair | Complex; not justified. | |
| You decide | | |

**User's choice:** Content-type-aware (recommended).

---

## Claude's Discretion

- **BPF hook set (Reassembly Q4)** — three tracepoints; flagged for researcher to revisit against design-partner workload profiles.
- **Truncation flag wire-field details** — implicit in Reassembly Q3; planner makes the final call on field name and placement.
- **Salt management for the per-org redaction hash** — flagged in Redaction Q2 user note for researcher.

## Deferred Ideas

- Per-node JWT or bootstrap-token exchange (Topology Q4) — Phase 3 SEC-01 may revisit.
- Streaming POST for very large bodies (Reassembly Q3) — violates wire contract; revisit if multi-MB bodies become a requirement.
- Kernel reassembly / `sock *` kprobes (Reassembly Q1) — rejected; may re-enter conversation for TLS uprobes post-v2.
- `writev` / `readv` / `sendto` / `recvfrom` tracepoints (Reassembly Q4) — researcher profiles workloads at integration time.
- Salt rotation policy for the per-org redaction hash (Redaction Q2).
- Pipelined HTTP/1.1 capture (Reassembly Q2) — dropped by design; revisit if a design partner uses pipelining.
- Phase 1 gray areas not selected for discussion: informer ownership unification, pre-flight failure surfacing path, benchmark doc location + threshold-as-gate, vp-tap image build pipeline.
