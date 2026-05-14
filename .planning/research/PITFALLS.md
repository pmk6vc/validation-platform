# Pitfalls Research

**Domain:** Production-traffic replay + statistical verdict service shipped as hosted multi-tenant SaaS with design-partner beta + eBPF-based in-cluster capture agent
**Researched:** 2026-05-13
**Confidence:** HIGH (heavily corroborated by prior-art post-mortems, project's own CONCERNS.md, and design-partner-grade SaaS patterns)

The pitfalls below are specific to the v1 surface defined in PROJECT.md: capture cutover (TAP-3 through TAP-8), gRPC/HTTP-2 dissection, pluggable storage, replay engine (`sequential` + `actual`), comparison + verdict, web dashboard, sub-30-minute self-serve onboarding, Postgres RLS retrofit, JWT key rotation, header/PII redaction, GitHub Check Run + Slack surfaces. Generic engineering advice ("write more tests", "monitor things") is intentionally omitted — this document is for things that specifically eat replay-validation startups for breakfast.

---

## Critical Pitfalls

### Pitfall 1: Stateful replay returns "regressions" that are actually staging drift

**What goes wrong:**
The replay engine fires `GET /orders/12345` against staging. Production has order 12345; staging doesn't. The candidate returns 404; the baseline returned 404 too (same staging). But on a different run the staging DB has been touched by something else, baseline now returns 200 OK, candidate still returns 404, and the verdict says FAIL. The "regression" is staging state drift — not the PR.

Worse: read-only-by-default sounds like it removes the problem. It doesn't. `GET /orders` is read-only at the protocol level but its **response** depends on whatever state staging has accumulated since the last reset. The verdict's distributional comparison amplifies even tiny drift into "statistically significant" deltas.

**Why it happens:**
Teams reach for read-only as a safety knob and conclude "read-only ⇒ deterministic". It doesn't — read-only protects production from replay, not the replay from staging. And without an explicit "freeze staging state for the duration of comparison" story, the baseline and candidate runs see different worlds.

**How to avoid:**
- Run baseline and candidate **interleaved** (request-by-request) against the same staging state, not back-to-back. Each request hits staging in whatever state it's currently in; baseline and candidate see the same state per request pair.
- For responses where state drift dominates (e.g. listing endpoints with pagination cursors that depend on insert order), surface the dimension as INCONCLUSIVE rather than FAIL.
- For the small set of endpoints where staging-state-coupling is unavoidable (auth tokens, idempotency keys), let the customer mark them as "compare structurally only, not value-equal".
- Document the staging contract explicitly: customer is responsible for staging containing data that **resembles** production shape; we do not require an exact mirror. The replay engine compares baseline-on-this-staging vs candidate-on-this-staging, never production vs anything.

**Warning signs:**
- High FAIL rate on first run, near-zero FAIL rate on rerun without code change.
- Verdict "evidence" disproportionately calls out fields like timestamps, IDs, totals, counts.
- Customer-reported "false alarms" cluster on listing/query endpoints, not on POST/PUT.

**Phase to address:**
Replay engine + comparison engine (Phase 3-4 in the indicative ordering). The interleaving decision must be baked into the replay engine's run-loop, not bolted onto the comparison engine. ([Shadow Testing pitfalls — Speedscale](https://speedscale.com/blog/definitive-guide-to-traffic-replay/), [Shadow traffic stateful application limits — debugg.ai](https://debugg.ai/resources/from-staging-to-shadow-traffic-production-replay-patterns-2025))

---

### Pitfall 2: Statistical comparison declares "significant" because the test was wrong, not because the candidate is

**What goes wrong:**
PROJECT.md commits to Mann-Whitney U for latency. Per dimension you also have response diff, error rate, memory trend. That's at least four hypothesis tests per validation run, often per endpoint. With 50 endpoints in a service and α=0.05, you expect ~10 spurious "regressions" per run on identical code. PRs get blocked, developers re-run "to see if it sticks", trust collapses in a week.

Mann-Whitney U also assumes the two distributions have **the same shape** for the result to be interpretable as "median shifted". Latency distributions are heavy-tailed and bimodal (cache hit / cache miss). Two distributions with the same median but different tail mass — totally normal between staging runs — will produce statistically significant Mann-Whitney results that are not regressions.

Linear regression on memory growth is even worse: a 30-second replay run on a JVM that just GC'd looks like memory growth no matter what.

**Why it happens:**
Picking a test by name ("non-parametric, sounds safe") without testing the test against actual paired baseline-vs-baseline runs (the null-hypothesis check). The product treats "p < 0.05" as a verdict primitive, not a calibration knob.

**How to avoid:**
- **Calibrate on null:** run baseline against baseline with no code change. The false-positive rate must be < 5% per run end-to-end (not per test). Bonferroni or Benjamini-Hochberg correction across endpoints; the verdict must adjust α for the number of comparisons being performed.
- **Effect-size gate, not just p-value:** require both p < α and |effect size| > threshold. A statistically significant 0.2ms latency shift is not a regression worth blocking on; tune the threshold per customer.
- **Distribution-shape check:** before Mann-Whitney, run a quick Kolmogorov-Smirnov or compare 90th/99th percentile separately. If shapes diverge, surface as INCONCLUSIVE, not FAIL.
- **Warm-up window:** discard first N seconds of each run. JVM warm-up, JIT, connection pool spin-up, autoscaler reaction time — all polluting the distribution.
- **Memory trend ≠ linear regression on a 30-second window.** Either run long enough that the linear fit is meaningful (minutes, not seconds) or use a non-parametric trend test like Mann-Kendall.

**Warning signs:**
- Baseline-vs-baseline runs report any non-zero FAIL rate.
- Verdict drill-in shows "p = 0.04" with effect sizes like "median shifted from 12.3ms to 12.5ms".
- Same PR alternates PASS/FAIL across reruns.
- Memory-trend dimension fails more often than response-diff (suggests the test is over-firing on transient curves).

**Phase to address:**
Comparison engine (Phase 4). The calibration discipline is a hard prerequisite to enabling the GitHub Check Run as a required check — if verdict drift exists, required checks block PRs forever and the product is dead. ([Mann-Whitney U interpretation requires similar shapes — GraphPad](https://www.graphpad.com/guides/prism/latest/statistics/stat_checklist_mannwhitney.htm), [Multiple comparisons correction — PMC review](https://pmc.ncbi.nlm.nih.gov/articles/PMC7720730/))

---

### Pitfall 3: Response-diff verdicts blocked by noisy fields developers can't suppress

**What goes wrong:**
Baseline response: `{"id": "abc-123", "created_at": "2026-05-13T10:00:00Z", "total": 42}`. Candidate response: `{"id": "abc-124", "created_at": "2026-05-13T10:00:01Z", "total": 42}`. The diff engine flags the response as "changed" — UUIDs and timestamps differ. Verdict: FAIL. The developer rolls their eyes and clicks "ignore".

After a week of this, developers route around the check. The product's trust is dead.

**Why it happens:**
Diffing JSON as opaque bytes treats every difference as significant. Customers can't enumerate all noisy fields up-front, and asking them to is a UX failure that compounds the onboarding-time problem.

**How to avoid:**
- **Default-noisy-field detection from production capture itself:** if a field varies across the captured-input set (id, timestamp, signature), it is noisy by default and elided from diff.
- **Built-in noise patterns:** UUID-shaped, ISO-8601-shaped, hex-strings-over-N-chars, monotonic counters. Apply by default; let customers turn off per-endpoint.
- **Structural vs value diff:** "this field exists and has type X" vs "this field has value V". The default should be structural-with-value-for-stable-fields, not full value equality.
- Verdict UX must show **what was diffed and what was ignored**, otherwise customers can't reason about why a run passed/failed.

**Warning signs:**
- "FAIL on every run" reports cluster on response-diff dimension.
- Customer logs show developers manually marking PRs as "ignore validation" repeatedly.
- Verdict evidence is dominated by `id`, `timestamp`, `request_id` fields.

**Phase to address:**
Comparison engine (Phase 4). Noise classification must ship in v1, not as a "tunability roadmap item". ([API response diff dynamic fields — ArrayDiff](https://arraydiff.com/api-response-diff))

---

### Pitfall 4: eBPF capture works on the dev kernel and silently corrupts on customer kernels

**What goes wrong:**
TAP-1 spike validated capture on GKE COS kernel 6.12. A design partner runs GKE Ubuntu 5.15 LTS, EKS Bottlerocket 5.10, or — worst case — a 4.x-era kernel still pinned because of a downstream driver issue. CO-RE relocations fail silently, the eBPF program loads with zeroed offsets, capture rate appears non-zero in metrics but bodies are garbage or empty. Validation runs against this customer return PASS/FAIL verdicts derived from corrupt traffic.

**Why it happens:**
Kernel-version variance across customers is enormous. BTF availability isn't universal (custom-built kernels strip it; older LTS lines don't ship it; some managed-K8s images haven't enabled it). CO-RE doesn't error loudly when a relocation fails — it just continues with the field unreadable.

**How to avoid:**
- **Hard pre-flight check:** on agent startup, query `/sys/kernel/btf/vmlinux`, kernel version, and the specific BPF helpers + program types we depend on. Refuse to start with a clear actionable error message if any are missing. Surface this in the dashboard onboarding flow ("your cluster's kernel is X; we need Y; here's how to upgrade or here's a fallback path").
- **Self-test on startup:** generate synthetic traffic from the agent to the agent (loopback), run it through the full capture pipeline, assert the bytes round-trip. Refuse to register with the platform on self-test failure. This is the single most important onboarding-failure detector.
- **Ship a BTF hub** (BTFHub-style) for kernels that don't ship BTF.
- **Kernel-version compatibility matrix** documented and tested in CI against multiple kernel images, not just the one the dev team runs.
- **Track ring buffer drop counts as a first-class capture metric.** A non-zero drop rate means the verdict is being computed on partial data and must be surfaced — not buried.

**Warning signs:**
- Captured-input counts inconsistent with workload's actual RPS.
- Bodies are empty or truncated on a specific customer.
- Self-test passes on the build machine, fails in customer's cluster.
- Ring buffer drops > 0 sustained for more than a few seconds.

**Phase to address:**
Phase 1 (capture cutover, TAP-3 through TAP-8). Pre-flight + self-test must ship with TAP-6 (production hardening), not after the cutover. ([eBPF kernel-version portability — iximiuz](https://labs.iximiuz.com/tutorials/portable-ebpf-programs-46216e54), [BPF ring buffer drops + memory tradeoffs — kernel.org](https://docs.kernel.org/6.6/bpf/ringbuf.html), [eBPF observability + ring-buffer backpressure — ThinhDA](https://thinhdanggroup.github.io/ebpf-observability/))

---

### Pitfall 5: Captured-inputs table becomes a credentials honeypot

**What goes wrong:**
The agent captures `Authorization: Bearer <production JWT>` headers, `Cookie: session=<prod session token>` headers, request bodies containing PAN numbers, response bodies containing internal user emails. All of it lands in our collector's Postgres. A breach of our collector DB is now a breach of every design partner's production credential store. This is the explicit fear in CONCERNS.md S2; project's R3 (no size limit) compounds it.

**Why it happens:**
Default-allow on capture is the path of least resistance — the agent forwards whatever Kubeshark/tap saw. Redaction is "future work" until it isn't.

**How to avoid:**
- **Default-deny header allowlist:** `Authorization`, `Cookie`, `Set-Cookie`, `Proxy-Authorization`, `X-API-Key`, `X-Auth-Token`, custom vendor session headers. Customer must opt-in to capture specific headers (with a UI warning).
- **Body redaction by pattern:** regex catalog for common credential shapes (JWT shape, PAN, common API-key prefixes like `sk_`, `pk_`, AWS access key shapes). Default-on, customer-extensible.
- **Apply redaction at the agent, not at the collector.** Captured-input bodies must never leave the customer's cluster un-redacted. This is the whole point of pluggable storage downstream — but redaction is the upstream half.
- **Redaction is replay-aware:** redacted values are replaced with deterministic placeholders (`__REDACTED_AUTH_1__`) that the replay engine substitutes per-run with fresh staging-valid values. Without this, redaction breaks the replay.
- **Audit log of what's being captured:** dashboard must surface, per customer, "X% of requests had Authorization redacted, Y% had Cookie redacted". If a customer's redaction config is letting credentials through, they need to see it before a breach does.

**Warning signs:**
- Collector body bytes per request increases without RPS increase (suggests bodies are getting larger ⇒ less redaction).
- Customer-side compliance review surfaces "your captured-inputs table contains tokens".
- Sample query on captured-inputs returns rows where `request_headers ->> 'authorization'` is non-null.

**Phase to address:**
Phase 1 alongside capture cutover — redaction must ship with the new capture pipeline, not be retrofitted. Re-redacting historical data is expensive and never complete. ([Sensitive data in API capture — Grepture](https://grepture.com/guides/redact-pii-any-api), [PII leak risk through APIs — Trend Micro](https://www.trendmicro.com/vinfo/us/security/news/online-privacy/pii-leaks-and-other-risks-from-unsecure-e-commerce-apis))

---

### Pitfall 6: Postgres RLS retrofit looks protective but isn't enforced on the actual code path

**What goes wrong:**
RLS policies get added to every multi-tenant table with `ENABLE ROW LEVEL SECURITY` and a policy keyed on `current_setting('app.tenant_id')`. Tests pass. Then in production, one of the following:
- Hikari connection pool reuses a connection across requests without resetting `app.tenant_id` ⇒ request 2 sees request 1's tenant data.
- The platform connects to Postgres as the **table owner** ⇒ RLS is silently bypassed (RLS doesn't apply to owners without `FORCE ROW LEVEL SECURITY`).
- Prepared-statement plan cache pins a stale `current_setting()` value across pool checkouts.
- A migration runs as a superuser ⇒ RLS bypassed; new tables get FK references to old tables and nobody notices the new tables don't have RLS enabled.

The catastrophic case: a customer queries their own org's services and gets back a row from another org's services. No SQL error. No 500. Just a quiet cross-tenant data leak. This is the worst possible outcome for a product whose value prop depends on customer trust.

**Why it happens:**
RLS is genuinely good defense-in-depth, but it is **not** drop-in. It rewards teams that understand the entire connection lifecycle (pool checkout, transaction boundaries, plan cache, role grants, FORCE flag) and silently fails for teams that don't.

**How to avoid:**
- **Application connects as a non-owner role.** Create a dedicated `app_role` with `LOGIN`, grant it SELECT/INSERT/UPDATE/DELETE on tables; the migration owner is a different role. This is the single most important RLS rule.
- **`FORCE ROW LEVEL SECURITY` on every multi-tenant table.** Default RLS doesn't apply to owners; FORCE does. Belt and braces.
- **Connection-pool reset hook:** Hikari supports `connectionInitSql` + a per-checkout `SET app.tenant_id = ...` plus a `RESET app.tenant_id` on return. Validate at the JDBC layer that every checkout resets and that there are zero queries between checkout and the SET.
- **CI test for RLS isolation that runs as the application role, not as superuser.** A test that runs as superuser will pass even with RLS broken. Use a separate test connection.
- **Negative tests:** seed two orgs, run every list endpoint with org-A's JWT, assert zero rows from org-B. Fuzz with malformed JWTs trying to set `tenant_id` to wildcards.
- **Audit every new table during code review:** "did you `ALTER TABLE ... ENABLE / FORCE ROW LEVEL SECURITY` and add a policy?" Make this a migration-template default, not a check the reviewer has to remember.

**Warning signs:**
- A test that connects as superuser asserting RLS works (anti-signal; that test proves nothing).
- New tables added in a migration without an accompanying RLS policy.
- `pg_class.relrowsecurity = true` AND `pg_class.relforcerowsecurity = false` for any tenant-scoped table.
- The application's Postgres role is the same as the migration role.

**Phase to address:**
Phase containing the RLS retrofit. This needs its own phase with a reversibility plan (feature flag the RLS enforcement at the pool-init level until isolation tests pass on real customer fixtures). Do not roll this into a phase with other security work — RLS retrofit alone has enough failure modes to deserve dedicated attention. ([Postgres RLS pitfalls + connection pools — thenile blog](https://www.thenile.dev/blog/multi-tenant-rls), [RLS bypass via table owner / superuser — AWS RLS multi-tenant](https://aws.amazon.com/blogs/database/multi-tenant-data-isolation-with-postgresql-row-level-security/))

---

### Pitfall 7: 30-minute time-to-first-verdict is missed by 10× because of an invisible prerequisite

**What goes wrong:**
The dashboard signup-to-verdict flow looks like: signup (1 min) → org provisioned (instant) → "run this Helm command" (5 min) → first capture lands (5 min) → first validation run (5 min) → verdict (5 min). Looks like 21 minutes.

What actually happens with the design partner:
- Helm needs kubectl context — developer has access to staging cluster but not capture cluster; spends 20 min asking ops for kubeconfig. Or vice versa.
- Helm needs cluster-admin to install the agent's RBAC; developer has namespace-admin only. Spends 45 min finding someone who can install it.
- Agent starts, fails BTF pre-flight, error message says "missing /sys/kernel/btf/vmlinux"; developer doesn't know what to do.
- Agent registers, captures nothing — service selectors don't match (the R5 concern). Developer thinks the product is broken.
- First validation run runs against a service with no captured traffic; verdict is INCONCLUSIVE; developer doesn't understand why.

Actual time-to-verdict: 4 hours, 2 ops involvements, and one Slack escalation. Industry data: developers abandon at 5-15 minutes; sub-30 is aspirational for the easy path, brutal for everyone else.

**Why it happens:**
"Self-serve" is the property of the **easiest** path, not the **median** path. Internal demos always work; real developers always have something unusual about their cluster, their permissions, their workload's selectors, or their CI pipeline.

**How to avoid:**
- **Profile the actual median path with a real design partner, not internal team.** Onboarding times stated in product copy must be measured, not estimated.
- **Pre-flight everything before asking the developer to invest:** "we'll need cluster-admin on a cluster running Linux kernel 5.15+ with BTF; click here to check yours". A pre-flight CLI that runs `kubectl auth can-i ...` and reports exactly what's missing.
- **Onboarding state is resumable AND has explicit failure states.** If capture isn't landing, the dashboard must say "0 captures in 5 min. Likely reasons: (1) service selector mismatch (we saw 3 services with no matching pods), (2) RBAC denied (we got 403 listing pods in namespace X). Click here to debug."
- **Synthetic-traffic-on-first-install:** if the developer's services aren't generating capturable traffic, the agent generates a synthetic loopback request so the developer can see *some* verdict within 30 minutes. Then the dashboard says "this was synthetic; to validate real traffic, generate some on service X."
- **Default to namespace-admin install, not cluster-admin.** Tighter scope, fewer ops-team conversations. Cluster-admin is acceptable only if a specific feature genuinely requires it; even then, document why.
- **The R5 concern (silent skip on selector mismatch) directly destroys onboarding.** Every silent failure in the agent is a 30-minute-time-to-verdict killer.

**Warning signs:**
- Design partners' first verdict timestamps cluster around "the next business day", not 30 minutes.
- Onboarding sessions that complete the install step but never reach the verdict step (drop-off mid-funnel).
- Slack escalation channel has more onboarding questions than validation questions.

**Phase to address:**
The phase containing onboarding + dashboard. Onboarding observability — funnel metrics, drop-off, time-per-step — must ship with the dashboard, not after. ([Developer onboarding drop-off + time-to-value — daily.dev](https://business.daily.dev/resources/why-developers-never-finish-your-onboarding-and-how-to-fix-it/), [TTFV under 15 min for SaaS — Chameleon](https://www.chameleon.io/blog/reduce-time-to-value-onboarding))

---

### Pitfall 8: GitHub Check Run is required by a customer on day 2 and the verdict drift becomes a PR-blocker

**What goes wrong:**
Customer turns on "Validation Platform" as a required check. Verdict false-positive rate (Pitfall 2) sits at 8% per run. One day in ten, every PR in the repo gets blocked for an unrelated reason. Developers can't merge. Customer disables the required check within 48 hours and the product loses the only spot where it actually changes behavior.

A second variant: a Check Run is posted, but the underlying validation run takes 12 minutes. The Check Run status sits at "in_progress" for 12 minutes, blocking merge queues, and the developer doesn't know whether to wait or re-run.

**Why it happens:**
A verdict is only as valuable as the workflow surface it plugs into; the workflow surface (required PR check) has zero tolerance for noise. Required checks operate on a different trust contract than dashboards.

**How to avoid:**
- **Default the GitHub Check to "non-blocking" mode for the first N runs per repo.** Customer opts into "required" only after a calibration period (e.g. 50 baseline-vs-baseline runs with < 2% FAIL rate). Make this the platform's recommendation, not customer responsibility.
- **Three Check Run states must include INCONCLUSIVE.** Conclusion `neutral` (or `skipped` with action `none`) for INCONCLUSIVE — never `failure`. Required-check semantics treat `neutral` as not-blocking.
- **Time-budget every validation run.** If the run will take > 10 min, post the Check Run as `queued` with a clear ETA; allow the developer to skip and re-request later. Don't sit on `in_progress` indefinitely.
- **Per-dimension overrides.** Customer can say "memory trend is noisy; treat its FAIL as INCONCLUSIVE for the Check Run." The PR check must be tunable per-repo without redeploying the platform.
- **PR comment summarizes what was compared, what was ignored, what changed.** A `failure` with no actionable evidence is the fastest way to lose trust.

**Warning signs:**
- Required Check Run gets removed by customer within a week of enabling.
- Check Runs stuck `in_progress` past their timeout in operational dashboards.
- PR comments with a FAIL verdict but evidence sections that are empty / "various".

**Phase to address:**
GitHub PR integration phase. Calibration period and per-dimension overrides must ship in v1 — they're not a "tunability roadmap" item. ([Flaky CI checks destroy developer trust — edgedelta](https://edgedelta.com/company/knowledge-center/flaky-tests-ci-cd-pipelines), [GitHub App Check Run conclusion semantics — GitHub Docs](https://docs.github.com/en/apps/creating-github-apps/about-creating-github-apps/best-practices-for-creating-a-github-app))

---

### Pitfall 9: Pluggable storage backend ships, customer routes to their Postgres, then a schema migration breaks them silently

**What goes wrong:**
Day 1: collector uses our hosted Postgres. We ship V0007 migration that adds a column. Cloud SQL runs migration, everything works.

Day 60: Customer A points collector at their own Postgres. We ship V0010 with a new column. We need to migrate **their** Postgres too — but they don't run our Flyway. Their schema diverges from ours; collector code expects V0010 columns; queries fail; capture stops; nobody notices because operationally the failure is on their side.

A second failure mode: customer Postgres has different defaults (different `default_transaction_isolation`, different `statement_timeout`, sequence start values, JSONB GIN index defaults). Our code passes on Cloud SQL with our defaults, fails on theirs. The pluggable storage interface looks clean at the SQL-API level but in practice depends on operational defaults.

**Why it happens:**
"Pluggable storage" sounds like an interface but is actually a control-plane vs data-plane split. The data plane sits in the customer's cloud; the control plane (the schema, the migrations, the code that writes to the schema) sits with us. Synchronizing them over time is the entire complexity.

**How to avoid:**
- **Customer-side storage is a thin schema, not the full collector schema.** The pluggable interface should be append-only blob storage with a tiny metadata header — not "run our Postgres schema in your cluster". S3 + customer Postgres for **metadata only** is a much smaller surface.
- **If customer Postgres for full schema is non-negotiable, ship the migration as an in-band step that runs from our control plane via the customer's Postgres connection.** Flyway/Liquibase per-customer, gated by an in-app feature flag. Migration failures must alert us, not them.
- **Version-pin the collector deployment per-storage-backend.** A collector with code expecting V0010 columns must refuse to connect to a V0007 customer DB until migrated. Hard fail on startup, never silent.
- **Document the operational contract explicitly:** what Postgres version, extensions, defaults, IAM. Pre-flight on configuration change.
- **Keep the default hosted Postgres path the supported path; customer storage is opt-in, advanced, and operationally heavier.** Customer-facing docs must be clear: "BYO storage costs more in setup and we provide white-glove support during enablement."

**Warning signs:**
- Customer's collector pod log shows `column "X" does not exist` after a release.
- Customer's capture rate drops to zero after a platform upgrade.
- Different customers run different schema versions; bug reports become impossible to triage.

**Phase to address:**
The phase containing pluggable storage. The interface-shape decision (full Postgres vs blob+metadata) is architectural and pre-implementation — get it right before any code lands. ([SaaS BYOC operational cost beyond ~10 customers — Northflank](https://northflank.com/blog/saas-deployment-in-customer-environment), [Data residency + schema sync complexity — Alation](https://www.alation.com/blog/data-residency-by-design-global-compliance/))

---

### Pitfall 10: JWT key rotation breaks tokens issued during the rotation window

**What goes wrong:**
S3 in CONCERNS.md says private key is in env vars with no rotation. The fix: introduce `kid` header, support multi-key validation. Done? Not quite.

Failure modes during/after the first rotation:
- Old key removed before all tokens signed with it have expired ⇒ agents using long-lived API keys get 401s, capture stops, customer is broken.
- New key gets the same `kid` as the old (UUID re-roll bug) ⇒ verification picks the wrong key, fails.
- `kid` is taken from the incoming token and used as a map lookup without an allowlist ⇒ attacker-controlled `kid` injection (path traversal / unbounded growth).
- JWKS cache TTL is 24h; we rotate; new agents validate against stale JWKS for 24h.
- The platform issues tokens with one `kid` but the collector validates with a different cache state ⇒ asymmetric availability.
- One service deploys the new validation code, the other doesn't; rotation-during-partial-deploy = outage.

**Why it happens:**
Rotation is a coordinated multi-service deploy with a state machine, not a config change. Teams reach for "add `kid` and call it done"; the lifecycle of "issue, validate, expire, retire" is the actual hard part.

**How to avoid:**
- **Multi-key validation from day one of `kid` rollout.** Validator accepts current + previous N keys. Rotation = add new key, mark current as "previous", new tokens use new key. Old tokens valid until natural expiry.
- **Grace period >= longest token TTL.** If agent JWTs are 30-day, keys must live in the validator for at least 30 days after rotation. Hardcode this; don't make it operator-tunable.
- **`kid` allowlist:** validator's `kid` lookup must check the kid is in our known set; reject unknown `kid` rather than fetching/looking up.
- **JWKS cache TTL must be < the time between "add new key" and "issue new tokens with it"**. Tune to minutes, not hours. Or: invalidate on demand from the platform.
- **Rotation runbook + dry-run.** First rotation is staged: dry-run on sandbox cluster, verify both old and new tokens validate, then run on prod. The rotation runbook is its own deliverable.
- **Agent registration uses short-lived JWTs derived from a long-lived API key**, so rotation rarely needs to touch deployed agents. Long-lived JWTs in env vars (the current model) are themselves a smell.

**Warning signs:**
- Any rotation that requires a redeploy of platform AND collector AND every agent.
- `kid` lookup uses string interpolation or pattern matching, not an allowlist.
- JWKS endpoint has no monotonic version field.

**Phase to address:**
The phase containing JWT rotation. The deeper smell — long-lived JWTs as the agent's auth credential — should be addressed in the same phase: move agents to short-lived JWTs derived from a registration-time API key. ([Multi-key validation + grace period — David Sulc](https://www.davidsulc.com/blog/jws-apis-jwks-basics), [kid injection vector — Raijuna JWT attacks](https://www.raijuna.com/knowledge/jwt-attacks))

---

### Pitfall 11: `actual`-mode replay melts staging because "capped concurrency" was capped at the wrong thing

**What goes wrong:**
`actual` mode emulates production concurrency and rate "up to a configured ceiling". Customer captures 1000 RPS in production. Sets ceiling to 1000. Replay sends 1000 RPS into staging. Staging is sized for ~200 RPS. Staging melts. Customer's other workloads on staging melt. Customer is angry. Possibly the on-call gets paged at a real company on a Saturday.

A more insidious variant: the captured 1000 RPS was the **target service's** RPS, but each request fans out to 5 downstream services. The ceiling caps the target's RPS, not the downstream RPS. The customer's database — shared with the target service in staging — melts.

**Why it happens:**
"Concurrency" and "rate" are different knobs. A ceiling on one doesn't bound the other. And the **blast radius** of a replay run is the union of everything the target service touches, not just the target service itself.

**How to avoid:**
- **Two-knob ceiling: requests per second AND concurrent in-flight requests.** Both required. Production-rate-emulation is bounded by both.
- **Default ceiling is conservative: 10% of captured production rate.** Customer must opt up explicitly, with a warning showing what blast radius they're enabling.
- **Pre-flight blast radius:** before run starts, count distinct downstream services in captured traffic and warn "this replay will likely touch services X, Y, Z. Are they sized for the same load as production?"
- **Circuit breaker on staging error rate.** If staging starts 5xx-ing > N% during replay, pause and surface to the operator. Never blindly continue to compute a verdict from a melted staging.
- **Customer-marked staging-only resources:** customer can mark a database/cache as "shared with other workloads"; we throttle harder against those.

**Warning signs:**
- Replay run completed with a high error rate that the verdict reports as "candidate regressed" — but the same error rate appears in the baseline run.
- Customer staging pages during replay runs.
- Verdict latency comparison shows extreme tail latencies on both baseline and candidate (staging was saturated, not the candidate).

**Phase to address:**
Replay engine phase. Defaults matter more than configurability — a "configurable safety knob" defaulted unsafely is unsafe. ([Replay engine state + side effects — Speedscale](https://speedscale.com/blog/definitive-guide-to-traffic-replay/))

---

### Pitfall 12: gRPC + HPACK capture decodes correctly on the dev cluster, garbage on the customer cluster

**What goes wrong:**
HPACK uses a dynamic header table mutually-agreed between client and server. The compression is **stateful within a TCP/2 connection**: header `Authorization` might appear once explicitly, then be referenced by index in every subsequent request on that connection. If our capture misses the explicit declaration (because the connection started before the agent did, because the agent reconnects mid-stream, because the kernel buffered before we attached), every subsequent header is undecodable.

A second failure mode: gRPC frames are length-prefixed. Our dissector reads the length, allocates that much memory, then reads. A garbled frame can claim length = 2GB. Without bounds, capture OOMs the agent on a single bad frame.

A third: HTTP/2 frame interleaving means a single request's data is spread across multiple frames on multiple streams. Our dissector must reassemble per-stream. A naive "frame-at-a-time" implementation will see fragments out of order and corrupt the captured request.

**Why it happens:**
HTTP/2 + HPACK + gRPC framing is genuinely hard. Wireshark — a tool with decades of dissector work — has known limitations capturing HTTP/2 mid-connection. The probability that v1 ships a perfect HTTP/2 dissector is zero; the question is whether the agent fails loud (drop request, mark "uncapturable") or quiet (capture corrupt bytes).

**How to avoid:**
- **Capture must include the connection lifecycle**, not just frames. Agent records "I attached at frame N of stream M" so the dissector knows what state it's missing. Frames before that are dropped, not guessed at.
- **Per-stream reassembly buffer with hard bounds.** Per-stream memory cap (e.g. 10MB); if a stream exceeds, drop the stream, log loud, do not attempt to capture it.
- **gRPC length-prefix validation:** sanity-check declared length against the connection's overall byte budget. Refuse frames claiming sizes that don't make physical sense.
- **HPACK dynamic table per-connection, never shared.** Memory grows with the number of live connections; cap connections we'll dissect, evict on LRU.
- **Self-test on startup with a known-good gRPC client/server pair (loopback).** Same self-test idea as Pitfall 4 — refuse to register if dissection doesn't round-trip.
- **Capture indicators in the dashboard:** "of the gRPC traffic on your cluster, we successfully captured X%; Y% was dropped due to attach-time issues; Z% was dropped due to malformed framing." Customers need to see this number, not guess at it.

**Warning signs:**
- gRPC captures have empty header maps but non-empty bodies (HPACK references couldn't be resolved).
- Agent memory grows linearly with cluster connection count.
- Replay against gRPC services has high "request malformed" error rate on baseline (suggests captured requests were corrupt).

**Phase to address:**
gRPC capture phase (was TAP-9, now part of v1). This is risk-heavy; the phase should explicitly include a "dissector round-trip self-test" success criterion. ([HTTP/2 capture is hard, HPACK state is the reason — Pixie Labs](https://blog.px.dev/ebpf-http2-tracing/), [HPACK dynamic table semantics — RFC 7541](https://www.rfc-editor.org/rfc/rfc7541))

---

### Pitfall 13: cgroup_id-to-pod attribution drifts after pod restart and validation runs get assigned to the wrong service

**What goes wrong:**
The VAL-55 PR2 informer maps cgroup_id → pod metadata. A pod restarts; the OS reuses the cgroup inode for the new pod; the informer hasn't observed the deletion event yet (informer lag, watch reconnect, namespace event ordering). Captures attributed to the new cgroup_id get tagged with the old pod's service. Validation runs for service A include traffic that was actually destined for service B. Verdict is computed from polluted data.

**Why it happens:**
cgroup IDs aren't globally unique across time — they're inode numbers, and inodes get reused. The kernel doesn't notify userspace when a cgroup ID is recycled. The informer is a userspace shim with inevitable lag.

**How to avoid:**
- **Treat cgroup_id as `(cgroup_id, observation_window)` not as a primary key.** Once the informer observes a pod-deletion, mark that cgroup_id as "retired" for a quarantine window (e.g. 30 seconds); captures during quarantine are dropped or marked-suspect.
- **Cross-check against PID-namespace or container runtime ID** for high-stakes captures. Belt-and-braces attribution rather than trusting cgroup_id alone.
- **Track informer freshness as a metric.** "Time since last successful informer event" >= some bound ⇒ degrade attribution confidence, possibly stop capturing until the informer recovers.
- **End-to-end attribution test:** stand up two test services, restart pods rapidly, assert capture attribution stays correct under churn. CI must run this on the test-services rig.

**Warning signs:**
- Validation runs for service A include captures with bodies that look like service B's API surface.
- Captured-input counts increase right after a pod restart in a way that doesn't match the workload.
- Informer event lag > 5s in operational metrics.

**Phase to address:**
Capture cutover phase (TAP-5, wiring). Attribution correctness under churn is a TAP-5 success criterion, not a TAP-6 nice-to-have. ([cgroup_id attribution + container churn — Wiz container security overview](https://www.wiz.io/academy/container-security/ebpf-in-kubernetes), [eBPF observability + Kubernetes context enrichment — ThinhDA](https://thinhdanggroup.github.io/ebpf-observability/))

---

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| Skip the baseline-vs-baseline calibration; just ship verdicts | Faster MVP demo; "look it works" | False-positive rate of unknown magnitude; required-check adoption blocked; trust erosion that's nearly impossible to recover | Never. Calibration must be a release gate. |
| Ship redaction as "configurable, default-allow" | One-week schedule saving; "customer can configure" | Captured-inputs DB becomes a secondary credential store; one breach is unrecoverable | Never. Default-deny on sensitive headers is non-negotiable. |
| RLS retrofit as "enable policy, run tests, ship" without forcing app-role separation | Faster ticket close | A migration ran as superuser, new table has no policy, cross-tenant leak in a future release | Never. Role separation is the only RLS pattern that's actually safe. |
| Single hardcoded p-value threshold (0.05) for all dimensions | Simpler verdict logic | Multiple-comparisons false-positive rate scales with feature surface; trust erodes; required check disabled | Acceptable in MVP only with **baseline-vs-baseline calibration proving end-to-end FPR < 5%**; otherwise must adopt Bonferroni or BH. |
| Pluggable storage = "swap connection string" with no migration story | Looks like a config flip | Customer schema drift; silent capture failures; bug reports impossible to triage | Only if customer storage is blob+metadata (S3 + thin metadata), not full schema. |
| JWT key rotation via "redeploy everything with new key" | Skip the multi-key lifecycle | Every rotation = scheduled outage; rotation gets skipped under pressure; old keys never rotate | Acceptable only during the agent-bootstrap period (before any customer); not acceptable in design-partner beta. |
| Agent silently skips services without `app` selector (R5) | Avoid log noise | #1 onboarding failure mode; customers think product is broken | Never. Surface in dashboard with actionable remediation. |
| File-based liveness probe (O1) | One-line change | A wedged WebSocket loop reports healthy while capturing nothing; verdict computed from missing data; customer trust erodes | Acceptable as liveness; not acceptable as readiness. Tier the probes. |
| Hard-coded HikariCP pool 10, Cloud Run autoscale (P1) | "It works in dev" | Cloud SQL connections exhausted at autoscale spike; 503s during traffic burst | Until benchmarked at Cloud Run's median + 95th-percentile autoscale shape on Cloud SQL's connection ceiling. |
| Validation runs hold all baseline + candidate response bodies in process memory for comparison | Simple comparison engine | Per-run memory grows linearly with capture window × bodies; OOM at customer-realistic capture rates | Never at design-partner scale (~1k RPS × 30-day retention). Stream-compare or chunk-compare from collector storage. |

---

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| GitHub App (Check Run) | Treat `failure` conclusion as the only "bad" state; INCONCLUSIVE conflated with FAIL | Use `neutral` for INCONCLUSIVE; never block PRs on inconclusive data |
| GitHub App | Poll the PR API for status; hit secondary rate limit | Subscribe to webhooks; treat webhook delivery as eventually-consistent, dedupe by delivery ID |
| GitHub App | New permission added in a release; existing installs silently lose function | Use `installation` webhook `new_permissions_accepted`; gate the feature until accepted |
| GitHub App | One App handles all customers' validations; one buggy customer floods the App's rate limit budget | Per-installation rate-limiting and circuit-breaking |
| Slack | "Verdict failed" notifications fire on every dimension flake; alarm fatigue in two weeks | Throttle per-channel; collapse duplicate verdicts; "we already told you about this PR" semantics |
| Cloud Run + Cloud SQL | Pool size × max instances >> Cloud SQL `max_connections`; 503s under spike | Lower pool size, use Cloud SQL Auth Proxy with connection multiplexing, or sit a connection pooler in front |
| Cloud SQL (Postgres) IAM auth | IAM auth fails (rotation, IAM lag); no fallback path to recover the DB | Document the bootstrap-db.sh runbook (CONCERNS.md S4); explicitly note recovery requires GCP console |
| Kubeshark (sandbox) | Cutover happens before the Go tap has matched its capture rate, output shape, drop-rate | Run both in parallel during cutover; assert output equivalence on the sandbox cluster for N hours before retiring Kubeshark (TAP-7) |
| Fabric8 K8s client / informer | Long-lived informer leaks watches on the API server | Verify watch lifecycle on agent shutdown; surface informer lag as a metric |
| Helm install (customer cluster) | Chart assumes cluster-admin; customer has namespace-admin only | Default chart uses namespace-scoped RBAC; document cluster-admin features as optional add-ons |
| GCP Secret Manager (post-rotation fix for S3) | App fetches secret at startup; new secret deployed but app never re-fetches | Periodic refresh or on-401 refresh; never assume "deploy = re-fetch" because of rolling restart timing |

---

## Performance Traps

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| Ring-buffer drops under burst load | Capture rate < actual workload RPS; verdict computed from partial data | Track drop count as first-class metric; size buffer for peak, not median; surface drops in dashboard | Once a single customer's burst RPS exceeds the agent's drained-per-second rate; specific number depends on body size, but well within the medium envelope (1k RPS) for chunky payloads |
| Captured-input INSERT contention on a single hot service | Collector p99 ingest latency climbs; agent backpressure activates; capture rate drops | Partition `captured_inputs` by `service_id` or by capture-day; batch INSERTs aggressively (already doing); ensure WAL settings tuned for write-heavy load | At ~10 services × 1k RPS sustained on a small Cloud SQL tier |
| Per-validation-run full-table scan on captured-inputs | Validation start time grows over weeks as the table grows | Composite index on `(service_id, captured_at)`; query-plan-verify on realistic-size tables, not empty CI fixtures | At ~30-day retention × 1k RPS ≈ 2.5B rows per design partner |
| JSONB headers/body columns unindexed but queried | Per-request lookups during replay setup take seconds, not ms | Don't query into JSONB at hot paths; precompute the indexable fields | At any meaningful scale |
| Comparison engine loads all responses into JVM heap | OOM on long replay runs | Stream-compare from storage; chunk by N requests; release between chunks | Anywhere past ~10k captured requests in a single run |
| HPACK dynamic table per-connection without LRU | Agent memory grows linearly with open HTTP/2 connections in target cluster | Cap concurrent dissected connections; LRU evict; surface as a metric | Customers with many long-lived gRPC connections (typical in service meshes) |
| Connection pool exhaustion under Cloud Run autoscale | Cloud SQL `too many connections` errors; 503s | Pool size × max instances ≤ Cloud SQL `max_connections` × 0.7 (leave headroom for migrations + ops); document the math | When platform RPS spikes past Cloud Run min-instances; specific number depends on tier |
| Web dashboard initial bundle loads all routes/components | Slow first paint kills onboarding step 1 | Code-split routes; lazy-load heavy verdict-drill-in views; aggressive caching on static bundle | At median consumer internet ≥ 1.5s first-paint |

---

## Security Mistakes

Beyond OWASP basics, the following are specific to validation/replay platforms.

| Mistake | Risk | Prevention |
|---------|------|------------|
| Captured-inputs stored without per-field encryption | DB breach exposes every captured production payload in plaintext | Encrypt request/response bodies at rest with per-org key; the BYO-KEY architectural placeholder lives here even though v1 defers the customer-managed-key UX |
| `kid` lookup on incoming JWT used as map key without allowlist | `kid` injection ⇒ unbounded map growth, possible path-traversal | Validate `kid` against known allowlist of issued keys before lookup |
| Cross-tenant queries via JWT manipulation (org-scope claim spoofing) | Cross-tenant data leak | RLS + JWT-claim-to-Postgres-session-var mapping + tests that run as the **application role**, not superuser (see Pitfall 6) |
| Captured `Authorization`/`Cookie` headers used during replay | Replay sends production tokens to staging; possible token leak to logs / SIEM in staging | Redact at agent (Pitfall 5); on replay, substitute deterministic placeholders with staging-valid credentials |
| Agent's long-lived JWT in env var | Compromise of any node = compromise of that agent's tenant scope until manual rotation | Short-lived JWTs derived from long-lived API key in Secret Manager; rotate JWTs every few hours, API keys rarely |
| Self-serve signup with no email verification + agent install instructions reveal cluster URLs | Signup-then-pivot to phishing of customer ops teams | Require email verification before agent install instructions are displayed; agent install requires platform-issued install token, not arbitrary signup |
| Slack notifications include captured request snippets | Captured payload leaks into Slack channel with broader audience than the validation team | Slack notifications include verdict + dashboard link only; payload snippets only in dashboard, gated by org membership |
| Webhook receivers for GitHub events not verifying signature | Spoofed events trigger validation runs (DoS, or worse, evidence forgery) | HMAC verification on every webhook delivery; reject unsigned/mis-signed events |
| Postgres connection pool reused across tenant requests without resetting `app.tenant_id` | Request 2 sees request 1's tenant scope (Pitfall 6 variant) | Pool-checkout hook always sets the session var; pool-return hook always RESETs it |
| Replay engine sends real production payloads to staging without authorization stripping | Captured bearer token replayed at staging; staging logs the token, expanding blast radius | Same as redaction-aware replay: redact at capture, substitute at replay |

---

## UX Pitfalls

| Pitfall | User Impact | Better Approach |
|---------|-------------|-----------------|
| Verdict says FAIL with no per-dimension breakdown | Developer doesn't know what to fix; ignores future verdicts | Headline + per-dimension status + sample evidence (request/response excerpt, latency histogram, memory chart). Default to "show me the smallest piece of evidence that justifies this verdict" |
| INCONCLUSIVE verdict with no reason | Developer can't tell "low confidence" from "broken" | INCONCLUSIVE always carries a reason code: `insufficient_traffic`, `staging_error`, `shape_mismatch`, `correction_applied`, etc. Display the code with a one-line explanation |
| Onboarding silently completes with zero captures | "Looks installed" but no value; developer doesn't know why | Onboarding flow requires "first capture received within N minutes" as an explicit step; show progress and timeouts; surface common-cause hints (selector mismatch, RBAC denied) |
| Agent install instructions assume kubectl context = cluster you want to monitor | Wrong cluster, wrong namespace, captures from the wrong workload | Onboarding asks the developer to confirm cluster name + namespace before generating the Helm command; verify post-install that the agent landed where expected |
| Captured-traffic explorer lets users view raw request bodies | Shoulder-surfing in a public space exposes another customer's prod data | Default-redact bodies in the explorer; click-to-reveal with audit logging; never auto-load body in list views |
| PR comment fails are noisy: every diff field listed, no prioritization | Developer ignores comments | Show top N most-significant diffs by effect size; collapsible details; link to dashboard for full drill-in |
| Dashboard shows "verdict in progress" with no time estimate | Developer waits, loses context, gives up | Always show ETA; allow run cancellation; auto-link to Slack notification preference for "tell me when done" |
| Slack notifications fire even when the developer is the one who triggered the run | Developer's own action notifies them; alarm fatigue | Slack notifications only for verdicts that change a Check Run state; default no-self-notify |
| Customer-facing error messages reveal internal architecture | "ConnectionPool#42 timeout after 5000ms" tells customer nothing actionable | Customer-facing errors are intent-shaped: "we couldn't reach your cluster's agent for 30 seconds; check agent pod logs in namespace X" |
| Verdict assumes everyone reads English / understands p-values | Statistical jargon is not the user's job | Verdict copy is plain-language: "candidate is 12% slower at the 99th percentile based on N=500 requests, after correcting for X". The p-value is on a "show me the math" panel |

---

## "Looks Done But Isn't" Checklist

- [ ] **Capture pipeline:** Often missing **ring-buffer drop telemetry surfaced to the customer** — verify dashboard shows per-customer "captured / dropped" ratio in real time, not just an internal metric.
- [ ] **Capture pipeline:** Often missing **kernel pre-flight + self-test on agent startup** — verify agent refuses to register if BTF missing or loopback self-test fails (Pitfall 4).
- [ ] **gRPC capture:** Often missing **per-stream reassembly bounds + connection-attach-time tracking** — verify a malformed length-prefix can't OOM the agent and that mid-stream HPACK dictionary failures degrade gracefully (Pitfall 12).
- [ ] **Replay engine:** Often missing **baseline-vs-candidate interleaving** — verify the two are run against the same staging state, not back-to-back (Pitfall 1).
- [ ] **Replay engine:** Often missing **two-knob ceiling (RPS + concurrency) with conservative defaults** — verify default ceiling is < 100% of production rate and customer must opt up (Pitfall 11).
- [ ] **Replay engine:** Often missing **staging circuit breaker** — verify run pauses on staging error spike, doesn't blindly continue (Pitfall 11).
- [ ] **Comparison engine:** Often missing **null-hypothesis calibration** — verify baseline-vs-baseline FPR < 5% before any verdict is exposed to a customer (Pitfall 2).
- [ ] **Comparison engine:** Often missing **multiple-comparisons correction (Bonferroni/BH)** — verify α is corrected for per-endpoint, per-dimension comparison count.
- [ ] **Comparison engine:** Often missing **effect-size gate** — verify p-value AND effect-size threshold must both pass before declaring a regression.
- [ ] **Comparison engine:** Often missing **noisy-field elision** — verify timestamps, UUIDs, monotonic IDs are auto-elided from response diffs (Pitfall 3).
- [ ] **Verdict UX:** Often missing **INCONCLUSIVE reason codes** — verify every INCONCLUSIVE carries an actionable reason, not a "we don't know" label.
- [ ] **Onboarding:** Often missing **pre-flight + funnel observability** — verify per-step time-to-completion and drop-off are tracked and visible in operational dashboards (Pitfall 7).
- [ ] **Onboarding:** Often missing **synthetic-traffic-on-first-install** — verify a developer can see *some* verdict within 30 minutes even on a cluster without natural traffic.
- [ ] **Onboarding:** Often missing **explicit failure states with remediation** — verify "0 captures in N minutes" surfaces likely causes (R5 selector mismatch, RBAC denied, kernel BTF missing).
- [ ] **Redaction:** Often missing **default-deny on sensitive headers** — verify `Authorization`, `Cookie`, `Set-Cookie`, `X-API-Key` are denied by default at the agent (Pitfall 5, CONCERNS.md S2).
- [ ] **Redaction:** Often missing **replay-time substitution of redacted placeholders** — verify redacted values are replaced with staging-valid credentials at replay, not literal `__REDACTED__` strings.
- [ ] **Redaction:** Often missing **redaction audit metrics** — verify dashboard shows "X% of requests had Authorization redacted" per-customer.
- [ ] **RLS retrofit:** Often missing **app-role separation from migration-role** — verify application connects as a non-owner role; migration owner is different (Pitfall 6).
- [ ] **RLS retrofit:** Often missing **`FORCE ROW LEVEL SECURITY`** — verify every multi-tenant table has FORCE, not just ENABLE.
- [ ] **RLS retrofit:** Often missing **pool-checkout `SET` + pool-return `RESET`** — verify connection pool resets tenant context per-checkout.
- [ ] **RLS retrofit:** Often missing **CI test as application role** — verify isolation tests run as the app role, not superuser; otherwise the test asserts nothing.
- [ ] **JWT rotation:** Often missing **multi-key validation + grace period >= longest token TTL** — verify rotation doesn't break tokens issued just before it.
- [ ] **JWT rotation:** Often missing **`kid` allowlist** — verify `kid` is validated against known keys before lookup (Pitfall 10).
- [ ] **GitHub integration:** Often missing **calibration period before "required" check enablement** — verify default is non-blocking until baseline-vs-baseline FPR is measured (Pitfall 8).
- [ ] **GitHub integration:** Often missing **INCONCLUSIVE = `neutral` not `failure`** — verify conclusion mapping doesn't conflate the two.
- [ ] **Pluggable storage:** Often missing **schema-drift detection on connection startup** — verify collector refuses to start if customer DB schema is older than expected (Pitfall 9).
- [ ] **Pluggable storage:** Often missing **storage operational contract docs** — verify customer-storage path is documented as opt-in/heavier-touch, not as a first-class equivalent to hosted.
- [ ] **Capture cutover:** Often missing **side-by-side equivalence period** — verify Kubeshark and Go-tap run in parallel during cutover and output equivalence is measured before retiring Kubeshark (TAP-7).
- [ ] **Capture cutover:** Often missing **decommission test plan** — verify Kubeshark removal (TAP-8) doesn't break sandbox or test infra; CI must run the full e2e flow against the new path before removal.
- [ ] **Beta operations:** Often missing **per-customer health view** — verify ops has a dashboard showing capture rate, verdict throughput, error rate per design partner.

---

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| Pitfall 1 (staging drift verdicts) | LOW | Switch to interleaved runs; mark recent FAIL verdicts as INCONCLUSIVE retroactively; communicate to customer |
| Pitfall 2 (statistical FPR) | MEDIUM | Lock GitHub Check to non-blocking mode immediately; re-calibrate; ship Bonferroni / effect-size gate; replay last N verdicts with new logic; communicate clearly with customer |
| Pitfall 3 (noisy fields) | LOW | Ship noisy-field auto-detection; re-run verdicts with new logic; mark old verdicts as superseded |
| Pitfall 4 (kernel skew) | HIGH | Roll back capture cutover to Kubeshark on affected customers (reversibility per PROJECT.md constraints); ship pre-flight; investigate per-customer kernel inventory |
| Pitfall 5 (PII honeypot) | HIGH | Lock down DB access immediately; force-redact existing rows (lossy); notify affected customers per their breach-notification contracts; ship default-deny |
| Pitfall 6 (RLS bypass) | CRITICAL | Disclose to affected customers; force-rotate every JWT; re-architect for app-role separation; re-test all isolation paths. Reputational damage likely permanent |
| Pitfall 7 (onboarding miss) | MEDIUM | Add funnel observability; identify drop-off step; ship targeted fixes; offer concierge onboarding to affected design partners as bridge |
| Pitfall 8 (PR check noise) | MEDIUM | Switch to non-blocking; re-calibrate; refund customer trust by being transparent about FPR |
| Pitfall 9 (storage drift) | HIGH | Coordinate migration with customer's ops; possible downtime; document operational contract clearly; consider walking back to "default hosted only" temporarily |
| Pitfall 10 (JWT rotation breakage) | MEDIUM | Roll back to old key (multi-key supports this if you implemented it; HIGH if not); reissue tokens; document rotation runbook |
| Pitfall 11 (`actual`-mode melts staging) | LOW per-incident, HIGH for trust | Pause replays on affected customer; reduce ceiling; ship circuit breaker; communicate aggressively |
| Pitfall 12 (gRPC dissection corrupt) | HIGH | Mark gRPC verdicts as INCONCLUSIVE on affected customers; ship dissector fixes; backfill if possible |
| Pitfall 13 (cgroup_id drift) | MEDIUM | Mark affected validation runs as INCONCLUSIVE; ship quarantine window; verify CI test catches future regressions |

---

## Pitfall-to-Phase Mapping

The phases below are indicative — the roadmapper assigns final phase numbers per PROJECT.md guidance. The intent is to show *which capability area* should own each pitfall.

| Pitfall | Prevention Phase / Capability Area | Verification |
|---------|------------------------------------|--------------|
| Pitfall 1 (staging drift) | Replay engine | Baseline-vs-baseline run on shared staging state shows < 5% FAIL rate |
| Pitfall 2 (statistical FPR) | Comparison engine | Null-hypothesis test passes; required-Check Run enablement gated on this |
| Pitfall 3 (noisy fields) | Comparison engine | Demo with timestamp/UUID-heavy responses produces no FAIL on identical traffic |
| Pitfall 4 (eBPF kernel skew) | Capture cutover (Phase 1 / TAP-6) | Agent refuses to start without BTF; loopback self-test required to register |
| Pitfall 5 (PII honeypot) | Capture cutover (Phase 1, alongside redaction) | Default-deny header allowlist enforced at agent; collector rejects requests with un-redacted Authorization on a sample basis |
| Pitfall 6 (RLS retrofit) | Security hardening (RLS dedicated phase) | App-role separation tests pass; FORCE RLS verified on every multi-tenant table; CI runs isolation tests as app role |
| Pitfall 7 (onboarding miss) | Self-serve onboarding + dashboard | Median time-to-first-verdict measured on a real design partner < 30 min; funnel drop-off < threshold |
| Pitfall 8 (PR check noise) | GitHub integration | Default non-blocking; calibration gate; INCONCLUSIVE = `neutral` |
| Pitfall 9 (pluggable storage drift) | Pluggable storage backend | Schema-drift detection on startup; default path remains hosted; customer-storage is opt-in |
| Pitfall 10 (JWT rotation) | JWT key rotation phase | First rotation runs on sandbox; multi-key validation tested; grace period configured |
| Pitfall 11 (`actual`-mode melts staging) | Replay engine (`actual` mode) | Default ceiling < 100% production rate; circuit breaker on staging errors verified in test rig |
| Pitfall 12 (gRPC dissection) | gRPC + HTTP/2 capture phase | Round-trip dissector self-test on loopback gRPC; per-stream memory bounds enforced |
| Pitfall 13 (cgroup_id drift) | Capture cutover (TAP-5 wiring) | CI test under pod churn confirms attribution stability; informer freshness metric surfaced |

---

## Sources

**Traffic replay / shadow testing pitfalls (Pitfalls 1, 3, 11):**
- [From Staging to Shadow Traffic: Production Replay Patterns](https://debugg.ai/resources/from-staging-to-shadow-traffic-production-replay-patterns-2025) — stateful application limits of shadow traffic
- [Why Traditional API Testing Falls Short — Keploy](https://dev.to/keploy/why-traditional-api-testing-falls-short-a-comparison-of-shadow-production-and-replay-techniques-1m4b)
- [API Traffic Replay: The Definitive Guide — Speedscale](https://speedscale.com/blog/definitive-guide-to-traffic-replay/) — non-determinism, redaction, canary analysis
- [Shadow Testing 101 — Mathieu Lamiot](https://mathieulamiot.com/shadow-testing-101/)
- [Eliminating Flaky Tests with Traffic Replay — Speedscale](https://speedscale.com/blog/eliminating-flaky-tests-with-traffic-replay/)

**Statistical comparison pitfalls (Pitfall 2):**
- [Mann-Whitney U test interpretation requires similar shapes — GraphPad](https://www.graphpad.com/guides/prism/latest/statistics/stat_checklist_mannwhitney.htm)
- [Comparing multiple comparisons: practical guidance — PMC](https://pmc.ncbi.nlm.nih.gov/articles/PMC7720730/)
- [Nonparametric multiple comparisons — Springer](https://link.springer.com/article/10.3758/s13428-019-01247-9)
- [Bonferroni correction overview — Medium](https://yll0620.medium.com/statistical-analysis-acabc1fa6792)
- [Mann-Whitney U test — Wikipedia](https://en.wikipedia.org/wiki/Mann%E2%80%93Whitney_U_test)

**eBPF capture pitfalls (Pitfalls 4, 12, 13):**
- [BPF ring buffer + backpressure metrics — kernel.org](https://docs.kernel.org/6.6/bpf/ringbuf.html)
- [eBPF for Low-Overhead Observability + ring-buffer trade-offs — ThinhDA](https://thinhdanggroup.github.io/ebpf-observability/)
- [Why eBPF programs fail across kernels — iximiuz](https://labs.iximiuz.com/tutorials/portable-ebpf-programs-46216e54)
- [Observing HTTP/2 with eBPF / HPACK challenges — Pixie Labs](https://blog.px.dev/ebpf-http2-tracing/)
- [HPACK Header Compression — RFC 7541](https://www.rfc-editor.org/rfc/rfc7541)
- [eBPF in Kubernetes (cgroup attribution) — Wiz](https://www.wiz.io/academy/container-security/ebpf-in-kubernetes)
- [Monitor packet drops with eBPF — PHB Crystal Ball](https://phb-crystal-ball.org/monitor-packet-drops-with-ebpf/)

**Multi-tenant RLS pitfalls (Pitfall 6):**
- [Shipping multi-tenant SaaS using Postgres RLS — thenile](https://www.thenile.dev/blog/multi-tenant-rls)
- [Multi-tenant data isolation with PostgreSQL RLS — AWS](https://aws.amazon.com/blogs/database/multi-tenant-data-isolation-with-postgresql-row-level-security/)
- [RLS: SaaS tenant isolation without query changes — MVP Factory](https://mvpfactory.io/blog/row-level-security-in-postgresql-multi-tenant-data-isolation-for-your-saas)
- [Fine-Grained Postgres permissions for multi-tenancy — Permit.io](https://www.permit.io/blog/implementing-fine-grained-postgres-permissions-for-multi-tenant-applications)

**Self-serve onboarding pitfalls (Pitfall 7):**
- [Why developers never finish your onboarding — daily.dev](https://business.daily.dev/resources/why-developers-never-finish-your-onboarding-and-how-to-fix-it/)
- [Reduce Time to Value in Onboarding — Chameleon](https://www.chameleon.io/blog/reduce-time-to-value-onboarding)
- [Why Users Drop Off During Onboarding — SaaSFactor](https://www.saasfactor.co/blogs/why-users-drop-off-during-onboarding-and-how-to-fix-it)
- [How to Launch a Self-Service Developer Platform — Moesif](https://www.moesif.com/blog/developer-platforms/self-service/How-to-Launch-a-Developer-Platform-Self-Service/)

**JWT rotation pitfalls (Pitfall 10):**
- [JWKS and Zero-Downtime Key Rotation — David Sulc](https://www.davidsulc.com/blog/jws-apis-jwks-basics)
- [JWT Key Rotation in Microservices — Pallavi Sutar / Medium](https://techblogsbypallavi.medium.com/jwts-in-microservices-how-to-rotate-keys-and-invalidate-sessions-cleanly-db30c1110fd7)
- [Token Signing Key Rotation — Curity](https://curity.io/resources/learn/token-signing-key-rotation/)
- [JWT Security: When Tokens Become Attack Vectors — Raijuna](https://www.raijuna.com/knowledge/jwt-attacks)

**CI / Check Run trust pitfalls (Pitfall 8):**
- [Flaky Tests in CI/CD — edgedelta](https://edgedelta.com/company/knowledge-center/flaky-tests-ci-cd-pipelines)
- [Best practices for creating a GitHub App — GitHub Docs](https://docs.github.com/en/apps/creating-github-apps/about-creating-github-apps/best-practices-for-creating-a-github-app)
- [Rate limits for GitHub Apps — GitHub Docs](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/rate-limits-for-github-apps)
- [Flaky Tests: The Quiet Killer of Productivity — Harness](https://www.harness.io/blog/flaky-tests-the-quiet-killer-of-productivity-in-your-ci-pipeline)

**PII / credential capture pitfalls (Pitfall 5):**
- [How to Redact PII from Any API Call — Grepture](https://grepture.com/guides/redact-pii-any-api)
- [PII Leaks From Unsecure E-Commerce APIs — Trend Micro](https://www.trendmicro.com/vinfo/us/security/news/online-privacy/pii-leaks-and-other-risks-from-unsecure-e-commerce-apis)
- [Redact Sensitive Data in the OpenTelemetry Pipeline — OneUptime](https://oneuptime.com/blog/post/2026-02-06-redact-sensitive-data-pii-opentelemetry-pipeline/view)

**Pluggable / customer-deployed storage pitfalls (Pitfall 9):**
- [SaaS deployment in customer environments — Northflank](https://northflank.com/blog/saas-deployment-in-customer-environment)
- [Data Residency for SaaS — Alation](https://www.alation.com/blog/data-residency-by-design-global-compliance/)

**Replay engine + staging pitfalls (Pitfalls 1, 11):**
- [Idempotency in Distributed Systems — Rost Glukhov](https://www.glukhov.org/app-architecture/integration-patterns/idempotency-in-distributed-systems/)
- [Idempotency and Durable Execution — Temporal](https://temporal.io/blog/idempotency-and-durable-execution)
- [How to think about durable execution — Hatchet](https://hatchet.run/blog/durable-execution)

**Project-internal sources:**
- `/Users/prathameshkulkarni/repos/validation-platform/.planning/PROJECT.md` — v1 scope, constraints, out-of-scope
- `/Users/prathameshkulkarni/repos/validation-platform/.planning/codebase/CONCERNS.md` — S1-S4, R1-R5, P1-P2, T1-T4, TG1-TG3, O1-O3 directly inform Pitfalls 4, 5, 6, 7, 10, 11, 13
- `/Users/prathameshkulkarni/repos/validation-platform/CLAUDE.md` — current capability inventory and architectural commitments

---
*Pitfalls research for: production-traffic replay + statistical verdict SaaS with eBPF capture, design-partner beta*
*Researched: 2026-05-13*
