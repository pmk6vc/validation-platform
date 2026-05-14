# Validation Platform — Native eBPF Traffic Capture Cutover

## What This Is

The validation platform captures real production traffic with eBPF and replays it against staging to catch regressions before they ship — memory leaks, latency drift, behavioral changes, the things unit tests miss. This milestone replaces the existing Kubeshark + Kotlin agent capture stack with our own Go eBPF tap and a Go agent, so capture works under sustained production load and the team owns every layer of the data path.

## Core Value

**The tap and Go agent capture production traffic stably on the sandbox cluster that previously broke Kubeshark — and then Kubeshark + the Kotlin agent are gone from the repo.** If everything else slips, this must land.

## Requirements

### Validated

- ✓ `tap/` Go module bootstrap + CO-RE build + CI integration — TAP-2 / VAL-54 (existing)
- ✓ eBPF L7 capture spike on GKE COS (kernel 6.12) — TAP-1 / VAL-53 (existing)
- ✓ Platform (port 8080) + collector (port 8081) Ktor services with RS256 JWT auth — existing
- ✓ Cursor-paginated REST API for organizations, services, and captured inputs — existing
- ✓ PostgreSQL schema via Flyway migrations V0001–V0007 — existing
- ✓ Module ownership rule: cross-module access is HTTP-only, no DB-level FKs across modules — existing
- ✓ `BatchCreateCapturedInputRequest` / `Response` contract on `POST /api/captured-inputs` — existing
- ✓ `AgentConfigResponse` contract on `GET /api/agent/config` — existing
- ✓ Bilingual e2e harness exists in Kotlin (`e2e-tests/`) using TestContainers Postgres + k3s — existing
- ✓ Existing test-services microservices deployed to k3s (api-gateway, order-service, notification-service, webhook-stub, traffic-generator) for traffic generation — existing
- ✓ Sandbox GKE cluster + bring-up scripts (`platform-up.sh`, `bootstrap-db.sh`, `sandbox-up.sh`) — existing
- ✓ K8s pod informer (cgroup_id → pod metadata) for traffic attribution in the tap — VAL-55 PR2 (just merged)

### Active

- [ ] **TAP-3 / VAL-55** — MVP eBPF capture: HTTP/1.1 request/response pairs with pod labels (eBPF kprobes on `sys_enter_write` / `sys_exit_read`, Go userspace ring-buffer drainer, TCP reassembly, HTTP/1.1 parser, pod-label enrichment). In progress on branch `varunkulkarni/val-55-tap-3-pr2-k8s-informer`.
- [ ] **TAP-4 / VAL-56** — Go agent core: port the non-capture surface of the Kotlin agent to Go (`AgentConfig` → static + dynamic config types, `ConfigClient` for `GET /api/agent/config`, `PlatformClient` for `POST /api/services` with `RegistrationOutcome` parity, `K8sServiceDiscovery` via `client-go`, JWT bearer token wiring).
- [ ] **TAP-5 / VAL-57** — Go agent capture pipeline: port `TrafficTransformer` (filter + sample) and `CollectorClient` (batch + gzip + exponential-backoff retry) to Go; wire in-process from tap ring-buffer → transformer → collector POST.
- [ ] **TAP-6 / VAL-58** — Production hardening: `helm/vp-tap/` chart, DaemonSet manifest, liveness + readiness probes, metrics endpoint, backpressure tuning (ring-buffer sizing, drop policy) so the tap survives the sandbox load that broke Kubeshark.
- [ ] **TAP-7 / VAL-59** — Direct cutover in sandbox: remove Kubeshark Helm blocks from `scripts/sandbox-up.sh` and the agent overlay; `helm install vp-tap`; retune the Terraform node pool if TAP-6 benchmarks demand it.
- [ ] **TAP-8 / VAL-60** — Decommission: delete the Kotlin `agent/` module (and its `settings.gradle.kts` reference), `KubesharkClient.kt`, `kubeshark-v53` test fixtures, `deploy/Dockerfile.agent`, the `k8s/agent/` overlays; update `CLAUDE.md`.
- [ ] **Bilingual e2e preservation** — every integration / e2e behavior currently covered for the Kotlin agent must remain covered by the time TAP-8 lands. `e2e-tests/` (Kotlin) launches the Go agent binary or container alongside platform + collector. No coverage cliff during the swap.
- [ ] **PR shape constraint** — tear-out lands in small reviewable PRs (no single megacommit deletes the agent). Reviewability is part of the deliverable, not a nice-to-have.

### Out of Scope

- **TAP-9 / VAL-61** — HTTP/2 + gRPC framing support — deferred to post-cutover follow-on. Risks slipping the "validate ASAP" goal.
- **TAP-10 / VAL-62** — Customer install docs + compatibility matrix — deferred; ship the code first, document after the cutover stabilizes.
- **Replay engine, observation, comparison/verdicts, orchestration API** (Phases 4–5 of the original `CLAUDE.md` delivery plan) — explicitly not part of this milestone; this milestone is exclusively the capture-layer cutover.
- **Multi-cluster federation** — single-cluster scope per the existing platform design principle.
- **Customer onboarding work** — kept out so this milestone stays mechanical.
- **TLS / encrypted traffic capture** — relies on userspace uprobes that aren't in TAP-3's spike; out of scope until HTTP/2 work is reconsidered.
- **Message queue (Kafka / PubSub) capture** — already deferred at the architecture level; not affected by this milestone.

## Context

- The existing capture path runs Kubeshark (eBPF, distributed via Helm) as the data source and a Kotlin agent that consumes Kubeshark's WebSocket `/api/wsFull` endpoint, applies KFL filtering, dedups reconnect-replays in a bounded 1000-entry channel, and forwards batches to the collector. It works in development but **falls over under sustained load** on the sandbox cluster; the WebSocket reconnect/dedup logic is brittle, and Kubeshark licensing is a concern.
- The replacement is a **Go agent built on our own eBPF tap** (already prototyped in `tap/`, kernel hooks on `sys_enter_write` / `sys_exit_read`, ring-buffer drainer in userspace, cgroup_id → pod metadata enrichment via the K8s informer that just merged in TAP-3 PR2). The Go agent must reach **feature parity** with the Kotlin agent: K8s service discovery + registration, dynamic config polling, traffic capture + batch POST to the collector.
- The platform (port 8080) and collector (port 8081) Ktor services stay. The agent↔platform / agent↔collector wire contracts (RS256 JWT, `POST /api/services`, `GET /api/agent/config`, `POST /api/captured-inputs`) stay. **Nothing in the platform/collector should need to change** — the cutover is a swap on the customer-cluster side.
- The validation gate is **stable performance on the existing pressure-test sandbox** — the same GKE cluster that broke Kubeshark. No synthetic benchmark; we ship to where the problem was visible and check.
- The team works in **small reviewable PRs**. The tear-out (TAP-7 + TAP-8) must be chunked so reviewers can read each diff in one sitting. Megacommit deletions are explicitly rejected.
- Test infrastructure today: `shared/` exposes `DatabaseTestBase`, `KubernetesWorkloadTestBase` (k3s TestContainers), `TestJwtKeys`, `authedTestApplication`; `e2e-tests/` runs the full stack (Postgres + platform + collector via `GenericContainer`). The Go agent must plug into this harness — Kotlin remains the source of truth for cross-process e2e behavior, with the Go binary or container invoked as a child process.
- Bilingual repo state is intentional and OK per project convention ("Don't couple where coupling doesn't exist"): the Kotlin platform/collector and the Go tap/agent are genuinely separate ecosystems with their own toolchains. No Gradle wrappers around Go, no shared abstractions.
- `tap/` already has a Go test suite for the K8s pod informer (`pod_informer_test.go`); TAP-3 work has shifted main.go to the informer-based path. The Go agent (TAP-4/5) will extend this Go-side test base, while bilingual e2e covers the cross-process flow.

## Constraints

- **Tech stack** — Go ≥ 1.22 (per `tap/go.mod`), `cilium/ebpf` + `bpf2go`, `client-go`, standard library `net/http`. Compile with CO-RE so binaries portable across kernels ≥ 5.10 (target: GKE COS, kernel 6.12). Kotlin platform/collector stay on JDK 21 + Ktor 3.
- **Performance** — must hold steady at the load Kubeshark broke on (exact target lives in TAP-6 benchmarks; sandbox cluster is the bench rig). Memory + CPU footprint per node must fit comfortably inside whatever node-pool sizing the sandbox already runs with — retuning Terraform is allowed but should be a last resort.
- **Compatibility** — Go agent must speak the existing platform/collector wire contracts byte-for-byte (the platform side is not changing). `BatchCreateCapturedInputRequest` shape, `AgentConfigResponse` shape, JWT claim contract (`organizationId`, `cluster`, `role?`), RS256 signature, gzip request bodies on collector POSTs — all preserved.
- **Auth** — JWT bearer tokens minted by the existing platform `JwtTokenGenerator` work unchanged. No new key material, no new endpoints. Go-side libraries (e.g. `github.com/golang-jwt/jwt`) only need to *attach* the token; the platform/collector do all validation.
- **Test coverage** — integration / e2e coverage for any behavior the Go module owns cannot regress when the Kotlin agent is deleted. Bilingual e2e (Kotlin `e2e-tests/` driving the Go agent binary/container) is the chosen mechanism.
- **PR shape** — every cutover/tear-out PR must be reviewable in one sitting. No "delete the world" mega-PR. Tag the PR series so reviewers can follow the order.
- **Schedule** — "validate ASAP." No hard deadline named, but stretch scope (TAP-9 / TAP-10) is out, and "good enough" on the sandbox load test is the trigger to start tearing out.
- **Reversibility** — TAP-7 (cutover) must be reversible until TAP-8 lands. If the sandbox swap reveals a regression, we should be able to flip back to Kubeshark while we fix forward. After TAP-8, reversal becomes a git revert exercise — acceptable.

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Build our own eBPF tap instead of staying on Kubeshark | Kubeshark unstable under sandbox load; WebSocket reconnect/dedup brittle; licensing concern; own-the-stack control over capture pipeline | — Pending (validated when sandbox load test passes) |
| Port the agent to Go (not keep Kotlin and shell out to a Go tap) | Single process owns the capture pipeline; no Kotlin↔Go IPC; existing `tap/` is already Go; matches the "Don't couple where coupling doesn't exist" rule | — Pending |
| Feature-parity Go agent (all three loops) | Operationally identical surface area — same K8s discovery, config polling, traffic POST — so platform/collector and ops procedures don't change | — Pending |
| Validation = sandbox pressure-test only | The same cluster that broke Kubeshark is the realistic bar; synthetic benchmarks would lie | — Pending |
| Cutover = direct swap in sandbox, then aggressive chunked deletion | No long flag/coexistence regime; once sandbox is green, prove on prod-ish setup and rip out the old path | — Pending |
| Tear-out chunked into small PRs (TAP-7 then TAP-8) | Reviewability; preserves bisectability; reduces blast radius if a regression slips in | — Pending |
| Bilingual e2e (keep Kotlin `e2e-tests/`, launch Go agent from it) | Lowest-cost way to preserve cross-process e2e coverage during a polyglot transition. Rewriting e2e in Go is expensive and not what's at risk | — Pending |
| Defer TAP-9 (HTTP/2 + gRPC) and TAP-10 (docs) | "Validate ASAP" — HTTP/1.1 is enough for the cutover; docs are post-stabilization | — Pending |
| Keep platform + collector contracts unchanged | The wire is stable; only the customer-cluster side is changing. Single-variable change is easier to roll back | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-05-14 after initialization*
