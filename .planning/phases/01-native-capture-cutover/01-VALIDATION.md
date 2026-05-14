---
phase: 1
slug: native-capture-cutover
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-14
---

# Phase 1 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution. See `01-RESEARCH.md` §"Validation Architecture" for the dimensions this strategy must cover.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Frameworks** | JUnit 5 + TestContainers (Kotlin); `go test` + `testify`-style assertions (Go); k3s via TestContainers for K8s workloads |
| **Config files** | `build.gradle.kts` (Kotlin); `tap/go.mod` (Go); `.github/workflows/pr_main.yml` (CI gates) |
| **Quick run commands** | `./gradlew :collector:test :platform:test` (Kotlin per-module); `cd tap && go test ./...` (Go) |
| **Full suite command** | `./gradlew test && cd tap && go test ./...` |
| **Bilingual e2e command** | `./gradlew :e2e-tests:test` (Linux only — BPF requires Linux kernel; macOS local skips) |
| **Estimated runtime (quick)** | ~30 s Kotlin per-module; ~10 s Go |
| **Estimated runtime (full)** | ~3 min full suite + e2e |

---

## Sampling Rate

- **After every task commit:** Run the quick command for the module touched (Kotlin → `./gradlew :<module>:test`; Go → `go test ./tap/...`).
- **After every plan wave:** Run the full suite (`./gradlew test && go test ./tap/...`).
- **Before `/gsd-verify-work`:** Full suite + bilingual e2e (in CI or on a Linux dev box) must be green.
- **Max feedback latency:** ≤30 s for per-task quick runs; ≤3 min for full suite.

---

## Per-Task Verification Map

> Populated by the planner from PLAN.md `<verification>` blocks. Each task ID maps to a CAPTURE requirement, the test type, and an automated command. Manual-only behaviors are listed below the table.

| Task ID | Plan | Wave | Requirement | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------------|-----------|-------------------|-------------|--------|
| 01-XX-XX | 0X   | N    | CAPTURE-NN  | {expected}      | unit/integration/e2e | `{command}` | ✅ / ❌ W0 | ⬜ pending |

*Planner: fill this table after PLAN.md files exist. Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky.*

---

## Wave 0 Requirements

> Test scaffolding that must exist before per-task work starts. Driven by §"Validation Architecture" dimensions in `01-RESEARCH.md`.

- [ ] `tap/internal/transformer/transformer_test.go` — table-driven tests for redaction patterns (CAPTURE-09); fixtures for JWT/PAN/sk_/pk_ matches and non-matches; JSON tree-walk preservation tests.
- [ ] `tap/internal/reassembly/reassembly_test.go` — synthetic `(pid, fd)` segment streams; assert FIFO pairing; assert pipelined-drop counter; assert idle-TTL eviction (CAPTURE-01 + D-06).
- [ ] `tap/internal/preflight/preflight_test.go` — BTF presence check, loopback HTTP self-test (in-process loopback listener; runs on Linux CI only) (CAPTURE-07).
- [ ] `tap/internal/metrics/metrics_test.go` — registers every metric name listed in RESEARCH §7 and asserts label cardinality bounds (CAPTURE-06).
- [ ] `tap/internal/collectorclient/collectorclient_test.go` — gzip POST, exponential-backoff retry, RegistrationOutcome classification, wire shape parity with existing `CreateCapturedInputRequest` (CAPTURE-04, CAPTURE-03).
- [ ] `tap/internal/k8s/podinformer/` — *exists*; add a churn test (pod create → delete → recreate with same cgroup_id; assert quarantine window honored) (CAPTURE-02).
- [ ] `e2e-tests/src/test/kotlin/com/platform/e2e/AgentDiscoveryGoE2ETest.kt` — k3s + platform + collector + `vp-tap:test` container; emit traffic via existing test-services; assert captured-inputs land in collector DB within 60 s (CAPTURE-12, ROADMAP success criterion #1).
- [ ] `scripts/sandbox-pressure-test.sh` — 30-min sandbox pressure driver; emits CSV → `01-BENCHMARK.md` (CAPTURE-08).
- [ ] `shared/src/main/resources/db/migration/V0008__add_org_redaction_salt.sql` — adds `organizations.redaction_salt` (D-15 + RESEARCH §11c).
- [ ] `helm/vp-tap/` chart skeleton with `helm template | kubeval` smoke test (CAPTURE-05).

---

## Manual-Only Verifications

> Behaviors that cannot be reduced to a CI-runnable test in Phase 1. Each MUST be documented as a manual checklist step before phase verification.

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Sandbox hard-swap to vp-tap works against a real GKE node | CAPTURE-10 | Requires live GKE cluster; not CI-reproducible | Run `scripts/sandbox-up.sh` post-swap; verify `kubectl get pods -n validation` shows `vp-tap` DaemonSet ready; verify `kubectl logs ds/vp-tap` shows captured events; verify collector `/api/captured-inputs` returns rows. |
| Sandbox pressure-test passes with ≥20% headroom | CAPTURE-08 | Requires live GKE node-pool sizing; 30-min duration | Run `scripts/sandbox-pressure-test.sh`; verify `01-BENCHMARK.md` shows pass; verify per-node CPU and memory peak ≤80% of pod limits. |
| Kubeshark + Kotlin agent fully removed from sandbox | CAPTURE-10, CAPTURE-11 | Cluster-state assertion against live sandbox | `kubectl get -n validation deploy,ds,svc,cm` should not contain `kubeshark*` or `validation-agent*`; `git log --oneline` should show the four decommission PRs landed. |
| Helm chart RBAC is namespace-admin-scopable | CAPTURE-05 | Verifies install ergonomics for a real customer | `helm install vp-tap helm/vp-tap/ --dry-run` against a kubeconfig with namespace-admin (not cluster-admin) creds; verify no permission errors at install time. |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify references or are listed under "Wave 0 Requirements" / "Manual-Only Verifications"
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags (`--watch`, `--watchAll`) in any test command
- [ ] Feedback latency < 30 s (per-task) and < 3 min (full suite)
- [ ] `nyquist_compliant: true` set in frontmatter after planner populates the verification map

**Approval:** pending
