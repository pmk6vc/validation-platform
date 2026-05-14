# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-05-14)

**Core value:** The tap and Go agent capture production traffic stably on the sandbox cluster that previously broke Kubeshark — and then Kubeshark + the Kotlin agent are gone from the repo.
**Current focus:** Phase 1 — eBPF Tap

## Current Position

Phase: 1 of 6 (eBPF Tap)
Plan: 0 of TBD in current phase
Status: Ready to plan
Last activity: 2026-05-14 — Roadmap created; TAP-3 in progress on branch `varunkulkarni/val-55-tap-3-pr2-k8s-informer`

Progress: [░░░░░░░░░░] 0%

## Performance Metrics

**Velocity:**
- Total plans completed: 0
- Average duration: -
- Total execution time: 0 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| - | - | - | - |

**Recent Trend:**
- Last 5 plans: none
- Trend: -

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table. Relevant starting context:

- Bilingual repo is intentional: Go tap/agent and Kotlin platform/collector are separate ecosystems. No Gradle wrappers around Go.
- Wire contracts are frozen: `BatchCreateCapturedInputRequest`, `AgentConfigResponse`, RS256 JWT, gzip bodies — Go agent must match byte-for-byte.
- Cutover = direct sandbox swap (reversible PR boundary), not a long coexistence regime.
- Tear-out must land in small reviewable PRs; no megacommit deletes.
- TEST-01 explicitly gates DECOM-01: bilingual e2e parity must be proven before `agent/` source is deleted.

### Pending Todos

None yet.

### Blockers/Concerns

- CONCERNS.md S2: Header redaction gap (Authorization, Cookie not filtered on capture) — applies to Go agent's transformer too. Flag during Phase 3 planning.
- Phase 1 is already in progress on an active branch; plan-phase should pick up from current TAP-3 state rather than starting fresh.

## Deferred Items

| Category | Item | Status | Deferred At |
|----------|------|--------|-------------|
| v2 | TLS capture (V2-TLS-01) | Out of scope | Milestone start |
| v2 | HTTP/2 + gRPC (TAP-9) | Out of scope | Milestone start |
| v2 | Customer docs (TAP-10) | Out of scope | Milestone start |

## Session Continuity

Last session: 2026-05-14
Stopped at: Roadmap created; ready to plan Phase 1
Resume file: None
