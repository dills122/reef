# Reef Work Plan

## Purpose

This is Reef's single repository execution ladder. It links to the documents
that own detailed contracts, evidence, and sprint tasking. Its September 4
alignment is a checkpoint, not a live claim about later branches or runs;
check source and newer evidence before reporting any item as current.

Historical [CURRENT_STATUS.md](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/CURRENT_STATUS.md) records September 4 implementation
snapshot and scoped performance claims. Use newer source and evidence for current claims.

Last aligned: 2026-09-04 against `master` at `cebbffc1`; hosted release gates
were not re-run during this documentation check.
Calcify entries were re-aligned on 2026-10-11 against `master` at `778ae5da8` (see the
Calcify program section); non-Calcify sections remain as of 2026-09-04.

Source/test/artifact reconciliation:
[`IMPLEMENTATION_STATUS_AUDIT_2026-09-04.md`](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/archive/IMPLEMENTATION_STATUS_AUDIT_2026-09-04.md).
Items below distinguish missing implementation from evidence not found in the
audited checkout; missing local reports do not prove a run never happened.

## Calcify program — current state and next actions (October 11, 2026)

Single living status and plan: [program status and sprint plan](work/CALCIFY_PROGRAM_STATUS_AND_SPRINT_PLAN_2026-10-11.md).
Dated checkpoint history moved to [Calcify history log](work/CALCIFY_HISTORY_LOG.md). Keep entries here short; put evidence
in `docs/evidence/` and analysis in the plan.

| Track | State | Next bounded action |
| --- | --- | --- |
| Capture path (Phase 1/2) | Merged, opt-in. D7 10,425/s fresh-state; aged no-restart control 10.3k full contexts/s passes; resolver cold restart 8.1k/s fails the 10k gate | Warm-restart proof, then incremental keyed capture reducer |
| Finite P3 O1/O2 | Merged after independent review; O2 live evidence for G1-G8/G10 is inherited, not replayed | One approved live replay on master; Records PR11/PR12; role sign-off |
| Finite P3 O3 | Packet decided (D1-D7); O3a hardened and merged (PR493/494), wire contract merged, binding v2 spec merged as an unaccepted draft (PR495), option-B ordering spike go (PR496) | Final O3a review (3 of 3) and v2 spec review (4 of 4); fix findings; then O3b design and adapter |
| Arena goalpost and credit | Research and proposals only (10k/s through the API, settlement lags; receivable credit) | Calibrate and adopt by owner decision; no reservation code before |

Authority: relational financial authority (D009) stays accepted; no Calcify financial authority, credit or capacity claim
exists. Hosted CI is Linux/Node 22/Bun 1.3.14; the finite O2 harness is macOS-local (`make test-finite-o2-harness`).

## Calcify single-server pressure test — October 11, 2026

Research baseline `b9a8756e8d0efc5e8b665a5c62da1ab064d01539` includes merged
O1/PR485; earlier delivery statements below describe their dated checkpoint.
O2 was excluded from measured baseline; PR488 subsequently merged at `85115ac80`
on October11 19:45UTC and was integrated for research publication. Earlier proof
results do not qualify or review that newer finite path.
Owner initially confirmed one server, larger size allowed with evidence, and
hard10k/s capture/context gate with settlement allowed to lag. Later clarification
extends headline boundary through user-facing trading API; see goalpost update below.

[Architecture pressure test](research/CALCIFY_SINGLE_SERVER_ARCHITECTURE_REVIEW_2026-10-11.md)
retains deterministic owner/log candidate and recommends incremental keyed state,
separate correctness/capacity proofs, explicit identity/retention/backlog budgets
and co-resident qualification. Five offline kernel runs and isolated encoder
comparison pass scoped correctness/parity checks; arithmetic model checks5239
cases. [Verification](evidence/calcify-architecture-pressure-test/verification.json)
records all attempts and limits. No new broker/SQL/capture qualification, production
change, authority adoption or O1 review-counter reset.

Follow-up [aged contention proof](research/CALCIFY_AGED_CONTENTION_PROOF_2026-10-11.md)
completed300s on local10CPU/~15.6GiB Docker VM. Capture verified10,172.16/s lower
bound; full contexts8,106.99–8,145.11/s: **10k gate fails**. Final3,063,383 exact
contexts and independent366,625-trade synthetic financial/SQL replay audit pass.
Financial total includes post-load work; capture-to-financial integration remains
open. Cold resolver recovery, retained identities and co-resident resource cost
now constrain candidate. [Compact receipt](evidence/calcify-aged-contention/verification.json)
and [retention](records/2026-10-11-calcify-aged-contention-retention.json) preserve
failed first attempt, correction and completed target miss; owned services stopped,
volumes retained. No production behavior or authority change.

Matched [no-cold-restart control](research/CALCIFY_MATCHED_CONTROL_2026-10-11.md)
now passes300s capture gate:10,321.21–10,349.52 full contexts/s; aged60–300s lower
10,295.95/s. Final3,131,951 exact contexts and361,075 independent synthetic
financial/SQL trades pass audits; latter includes post-load work. Same runtime
images/resources and normalized workload settings; financial crash/replay remains.
First-minute ranges overlap, but control already leads before180s; no exact causal
attribution. Post180 resolved bounds10,411.41–10,571.55/s versus treatment
5,538.68–5,705.76/s support prioritizing recovery. Resolver container never restarts.
Control did more timed financial work; matched policy is not identical achieved
activity. [Comparison](evidence/calcify-matched-control/comparison.json) owns bounds.

Next proof: finite O2 remains separate; test restart with compatible retained
resolver state directory at same age and financial/SQL workload. Freeze recovery
interruption/freshness/drain allowance; instrument restoration, GC/compilation,
disk latency and all process resources. If warm restart fails, isolate one reserve,
parallelism or standby change before hardware decision. Current host demonstrates
steady aged capture, not cold recovery or production financial integration.
Matching sampled4.71GiB; identity lifetime budget remains open. Further full runs
need disk headroom above8GB reserve; no retained proof state deleted. Larger single
server allowed with measured justification. Historical originals unchanged.

Goalpost research follow-up: owner defines10k as real user/bot orders matched,
processed through Calcify and visible as completed trades awaiting settlement.
[Proposed arena contract](work/CALCIFY_ARENA_THROUGHPUT_CONTRACT.md) defines booked
trading state, settlement clocks, API visibility, session cuts and stage diagnosis.
Recommended initial p99 visibility1s, eligible settlement5s, close30s; numeric
targets await adoption. Owner selected immediate reuse of booked unsettled proceeds
under explicit risk/credit limits for first arena profile. [Credit proposal](work/CALCIFY_ARENA_CREDIT_POLICY.md) now defines account25%/pool10%
limits, eligibility and failure rules; calibration and runtime implementation remain open. Booking and
credit accounting belong inside10k gate; existing no-credit fixture is insufficient.
Arithmetic shows9k/s
settlement behind10k/s arrivals needs400s cleanup after60m if service stays9k/s.
Long sessions therefore require near-arrival-rate settlement plus catch-up reserve.
Primary sources and current API/code checked; no new throughput run or runtime
change. Capture-only pass remains scoped; integrated product gate unqualified.
Next integrated work: calibrate proposed caps and freeze bundle/policy, instrument real order-to-API slice,
then paired-order rate/duration ladder and recovery. Retained-state capture
control remains useful component proof; it cannot qualify enlarged API boundary.
Retention no-op: active proposal adds goalposts; prior scoped reports remain evidence.

Credit follow-up:34/34 reference tests pass, including676 ordered competing-order
schedules and20 actual SIGKILL cutpoints. Drawn plus undrawn exposure reserves a
prefunded pool; failure freezes new admission while preserving matched commitments.
Review tightened original-age retention and committed-head binding; hash chain
alone cannot detect a removed tail. Single-owner model only, no production credit,
distributed race, host-loss or10k API qualification. [Verification](evidence/calcify-arena-credit/verification.json)
and [policy](work/CALCIFY_ARENA_CREDIT_POLICY.md) retain current decisions; full proof
joins RecordsPR12. Session delivery bundles initial review, kernel/queue probes,
aged treatment/control, arena goalpost and credit proof on existing feature branch.

## Calcify finite P3 O3 — October 11, 2026

O2 is merged (PR488). O3 (financial admission) has a [packet](work/CALCIFY_FINITE_P3_O3_PACKET.md) with owner decisions D1-D7 recorded
(D4: operator control actions; D7: option B, order established in the financial processor's own history, which needs a new
binding schema version before O3b) and a merged first module, **O3a** (PR491): a pure, typed, unreserved gross DvP ledger under
`com.reef.platform.calcify.finance` with 14 tests. O3a was merged before review. Review 1 returned **Ready with non-blocking follow-ups**; review 2 returned **Not ready** because `restore`
could adopt a forged but consistent state. Both are closed in PR493/494: per-class budgets (refusals neither recorded nor counted), every
decision commits to the state it produced (`restore(checkpoint, trustedHead)` refuses anything else), settle requires operator
authority, an independent-encoder golden set, a protobuf wire contract (`calcify_finance.proto`), a 5,000-schedule differential oracle and a
72-mutant check with every mutant killed. Review 3 of 3 and the binding v2 spec review are pending.
It is unwired, implies no financial authority and no capacity. The O2 follow-up role definition is a
[draft](work/CALCIFY_FINITE_P3_DB_ROLES.md) with an observed privilege matrix awaiting operator sign-off.

## Calcify finite P3 O2 — October 11, 2026

Publication update: [O2 PR488](https://github.com/dills122/reef/pull/488) merged
October11 19:45UTC at `85115ac80`. Following verification and open follow-ups
retain their pre-merge evidence scope; this research session did not rerun O2.

O1 is merged (PR485, `b9a8756e8`). O2 (durable finite ingress, binding, stop/drain,
same-binding restore) was implemented on `claude/calcify-o2-finish`; it is
Codex's October 10 work, preserved unmodified at `wip/calcify-o2-codex-snapshot-2026-10-11`, plus
corrections below. Defaults stay off and live activation requires the explicit O2 opening; no
financial authority, production adoption or capacity claim follows.

- Independent review 1 of 3: **Not ready**. F2 (finite matcher inherited ambient compression):
  fixed with a finite-only `CompressionNone` pin and tests; ordinary mode unchanged. F1 (G9, full
  managed-state transport): `FiniteG9BrokerTransportTest` writes the declared 6,307,840-byte
  state through the production managed store and `liveProperties`, reads it back from the
  changelog, and restores it after local-state loss on a disposable Redpanda. Transport only —
  synthetic bytes, unauthenticated RF1 broker, one run.
- Re-verified October 11: Go build/vet/test/race, Kotlin `Finite*` including the 10 Postgres-backed
  binding-store and 11 ingress tests, proto regeneration, retention, and local replay of the CI
  steps O2 touches (CI breakers fixed: migration-list test, harness-test wiring as a local make target, Go coverage gate exclusion for the fixture-only probe).
  Results, commands and limits: [O2 verification record](evidence/calcify-finite-p3/2026-10-11-o2-verification.md).
- Independent reviews: instance 2 and 3 of 3 both **Ready with non-blocking follow-ups** (no P0-P2; review budget exhausted).
  Instance 2's P2-1 (NUL in payload fenced the run uncharged) is fixed with unit and Postgres-backed tests.
- Open: G1–G8 and G10 live
  evidence is still Codex's recorded claims (ledger under the snapshot's
  `.planning/calcify-p3-o2-2026-10-10/`); Postgres role grants used in tests were inferred and
  need an operator-reviewed definition; hosted CI run. O3b/O4/O5 components and fixtures are built and tested ahead of O2 acceptance (see `docs/work/CALCIFY_FINITE_P3_S2A_RESULT.md`); their *acceptance* stays gated on accepted O2.
- Retention: the earlier hour-by-hour O2 checkpoints formerly in this section are preserved in git
  history (snapshot commit `4c5a1f6011dadf2e1bdcd29e67a626e742d781d6`). Records archive
  publication of O2 evidence has **not** been done; retention is incomplete.
- Test infrastructure: per [delivery policy](ENGINEERING_DELIVERY_POLICY.md#test-infrastructure-must-serve-delivery),
  leave the usable harness alone. G9 transport is proven without further harness work; the G9 matrix
  sub-clauses on pinned images, fresh build hashes and resource observations within the lease remain
  Codex-recorded, not re-evidenced here.

## CI dependency throttling recovery (2026-10-02)

Master `2af704d7` CI recovered on [failed-job rerun](https://github.com/dills122/reef/actions/runs/37090920609/attempts/2).
Initial stock-data Docker build failed on Maven Central HTTP 429 after green
PR CI; required gate correctly failed. `codex/ci-maven-rate-limit` adds
three-attempt bounded Docker retries for this exact dependency failure, with
regressions wired into CI and local tooling. [CI Operations](CI_OPERATIONS.md#workflow-maintenance-rules)
owns cause, source runs, protection check and retry limits.

Local verification: exact Node CI suite passes 104 tests, zero failures/skips;
local dev-tooling target, observed-failure classifier check, actionlint,
script-surface check, offline Records retention and diff whitespace pass.
Sandbox blocked localhost test listener on first full-suite attempt; same
suite passed with localhost access. Hosted Docker builds remain PR CI checks.

Completion pass: CI owner docs and board updated; aggregate gate and delivery
invariants preserved. No runtime/API/event/storage/scenario changes, so contracts,
architecture overviews and boundary steering remain applicable. No standalone
record or retained evidence bundle superseded; archive pass is a no-op.

## Idempotency renewal and run-detail fixes (2026-10-02 branch)

`codex/idempotency-renewal-run-detail`, based on `76872e9d`, addresses #442
and #456. Expired boundary result rows renew atomically without cleanup;
live results and TTLs remain immutable under concurrent writers. In-memory
expiry/renewal follows the same rule, including stale-reader safety. Capture
and command-log reservation lifecycles remain separate. Arena run detail now
publishes only the current request and invalidates pending work on navigation,
empty selection and unmount. Both focused regressions are wired into CI.

Local verification: Java 21 focused `ExternalApiBoundaryTest`,
`InMemoryIdempotencyStoreTest` and `PostgresIdempotencyStoreIntegrationTest`
pass **38 tests, zero skipped/failures**, using isolated PostgreSQL 16 on
localhost:55442 with `RUNTIME_DB_*_TEST` configured. Deferred UI race/lifecycle
checks, Svelte check (zero errors/warnings), guarded static build, CI workflow
guard, actionlint, Node script-surface check, diff whitespace and offline
Records retention pass. Browser-render and full-stack qualification not run.

Completion pass: boundary storage and Arena owner docs updated; D-016, API
steering and overview invariants reviewed. No route/schema/event changes or
new architecture decision; general guidance remains applicable. No standalone
topic record/evidence replaced, so archive pass is a no-op; maintained doc
versions remain in Git history.

Independent review instance 1 of 3: **Ready**, no actionable findings. Reviewer
verified frozen file hashes, reran UI/CI/build/retention checks and inspected
prior zero-skipped 38-test Kotlin XML; no fresh DB or mounted-browser rerun.

## Records separation (2026-10-02)

Reef Records bootstrap and archive import landed in [Records PR #2](https://github.com/dills122/reef-records/pull/2). 386 historical files (39.36 MiB) preserved byte-for-byte at pinned archive commit; Reef cleanup on `codex/extract-historical-records` retains 89 current evidence files plus active code/docs and required fixtures. [Retention policy](RECORDS_RETENTION.md) and relocation inventory own selection and lookup. Local archive hashes, retained checksums, active links and five retention failure-path tests pass; no new runtime or performance qualification claimed.

## Records CI follow-up (2026-10-02)

PR #467 script-surface gate flagged retained `scripts/dev/projection-dirty-crash-test.mjs` after its only research-doc reference moved to Records. Current migration runbook now documents executable harness, prerequisites, isolation and limits, with archived F02 evidence link. Script-surface check passes; exact Node dev-tooling CI suite passes 87 tests. Retention pass: current runbook updated; no evidence bundle replaced or additional historical import needed. Later master merge `9b2a4861` conflicted only at work-board insertion; both current Calcify checkpoint and Records sections preserved. One new upstream link repaired to pinned Records; 228 newly merged evidence companions added to retained completeness inventory. Retention check, script-surface and exact 98-test Node CI suite pass after merge.

## Ongoing records guidance (2026-10-02)

Feature/fix/refactor completion now requires implementation/tests/contracts/docs/latest relevant evidence, affected guidance/overview review, then supersession/archive pass. AGENTS, contributor/PR guidance, delivery policy and AI reading path point to [required completion pass](RECORDS_RETENTION.md#required-completion-pass). Local context serves current planning, session/ramp-up and system/design; historical lookup uses Records originals/corrections with source/commit/scope citations. Retention pass for this guidance-only update: touched docs remain current normative owners; no new run evidence, completed plan or replaced bundle, so no additional archive import. Existing archived bytes and retained evidence pass unchanged.

## Source Of Truth

- Command and acceptance semantics:
  [`COMMAND_INTAKE_PROCESS.md`](./COMMAND_INTAKE_PROCESS.md)
- API/control-plane boundary:
  [`API_SURFACE_POLICY.md`](./API_SURFACE_POLICY.md)
- CI merge and scheduled-check operations:
  [`CI_OPERATIONS.md`](./CI_OPERATIONS.md)
- Active throughput handoff:
  [`THROUGHPUT_SCALING_IMPLEMENTATION_PLAN.md`](./THROUGHPUT_SCALING_IMPLEMENTATION_PLAN.md#pause--resume-handoff)
- Projection scaling:
  [`PROJECTION_THROUGHPUT_SCALING_PLAN.md`](./PROJECTION_THROUGHPUT_SCALING_PLAN.md)
- Scenario contracts and assertions:
  [`SCENARIO_CONTRACTS.md`](./SCENARIO_CONTRACTS.md) and
  [`SCENARIO_ASSERTION_PLAN.md`](./SCENARIO_ASSERTION_PLAN.md)
- Post-trade model and remaining hardening:
  [`SETTLEMENT_CLEARING_STRATEGY.md`](./SETTLEMENT_CLEARING_STRATEGY.md) and
  [`POST_TRADE_LIFECYCLE_SPRINT.md`](./POST_TRADE_LIFECYCLE_SPRINT.md)
- Arena preview implementation and release gate:
  [`BOT_ARENA_INVITE_PREVIEW_SPRINT.md`](./BOT_ARENA_INVITE_PREVIEW_SPRINT.md)
  and [`BOT_ARENA_RELEASE_READINESS.md`](./BOT_ARENA_RELEASE_READINESS.md)
- Calcify program state, decisions and sprints:
  [`CALCIFY_PROGRAM_STATUS_AND_SPRINT_PLAN_2026-10-11.md`](./work/CALCIFY_PROGRAM_STATUS_AND_SPRINT_PLAN_2026-10-11.md);
  proposed architecture [`CALCIFY_SYSTEM_ARCHITECTURE_RFC.md`](./work/CALCIFY_SYSTEM_ARCHITECTURE_RFC.md);
  finite contract [`CALCIFY_FINITE_P3_SOURCE_CONTRACT.md`](./work/CALCIFY_FINITE_P3_SOURCE_CONTRACT.md);
  goalpost [`CALCIFY_ARENA_THROUGHPUT_CONTRACT.md`](./work/CALCIFY_ARENA_THROUGHPUT_CONTRACT.md);
  history [`CALCIFY_HISTORY_LOG.md`](./work/CALCIFY_HISTORY_LOG.md)
- Documentation lifecycle:
  [`DOCUMENTATION_CLEANUP_PLAN.md`](./DOCUMENTATION_CLEANUP_PLAN.md)

Historical plans and dated reports remain evidence, not parallel execution
ladders.

## Planning Posture

Reef remains a simulation-first institutional trading venue and post-trade
platform. Correctness, durable acceptance, deterministic ordering, replay,
auditability, and idempotency are never traded for a higher throughput number.

Reef and Bot Arena are separate product surfaces. Reef owns venue and
post-trade behavior. Arena consumes Reef contracts through its optional
artifact and Compose overlay; Reef-only builds, routes, migrations, storage,
and readiness remain independent.

## Promoted Baseline

- The Redpanda/Kafka-compatible direct path is the canonical venue-core shape:
  durable publish acknowledgement, matching-engine partition consume,
  transactional venue-event publication and command-offset commit,
  `read_committed` canonical materialization, then asynchronous projections.
- The verified venue-core ceiling remains `10k commands/sec`. The corrected
  local `15m` run closed `8,999,955` commands at `9,999.49/sec` with no final
  gap; it did not justify a `20k` claim.
- Full projection passed historical `5k/60s` gates, but the August sustained
  baseline is `2.5k/5m`. The `5k/5m` run failed freshness with `757,955`
  watermark lag despite exact intake and canonical materialization; see
  [`PROJECTION_THROUGHPUT_SCALING_PLAN.md`](./PROJECTION_THROUGHPUT_SCALING_PLAN.md).
- P1 hidden-cross and P2 settlement-break/repair scenarios have local public
  readback plus replay/checksum evidence.
- Reef/Arena artifact, route, persistence, Compose, failure-isolation, and P1
  equivalence gates are promoted in
  [`REEF_BOT_ARENA_SEPARATION_PROMOTION.md`](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/archive/REEF_BOT_ARENA_SEPARATION_PROMOTION.md).
- Fork admission, SHA-bound maintainer approval, and external-account
  onboarding are complete through the July 23 `noodle-invite-smoke` test and
  follow-up fixes. The completion record lives in
  [`BOT_ARENA_RELEASE_READINESS.md`](./BOT_ARENA_RELEASE_READINESS.md#admission-and-onboarding-completion).
  Hosted game evidence and open-intake release requirements remain separate.

## Recent Implementation Checkpoint

- PR #337 (`a38489a9`) implemented the named architecture-review code items:
  canonical cancel/modify routing and ownership checks (`ARCH-IR-01/02`),
  deterministic malformed-timestamp rejection (`ARCH-IR-05`), and descriptor
  compatibility plus generated-source drift checks (`ARCH-IR-08`). Their
  regression tests remain required; these are no longer initial implementation
  tasks. This checkpoint does not claim a separate review sign-off.
- PR #341 (`29fb9993`) landed fail-closed stress evidence, projection phase and
  statement instrumentation, fixed-backlog drain tooling, unsafe stage
  configuration guards, and the one-maintainer remote gate configuration.
  August 21 remote short runs exercised that topology: `2.5k` passes the
  current checker; `5k` fails downstream maintainer drain despite exact
  canonical/projected counts. See the audit's recovered evidence.
- PR #349 (`16d0022c`) landed CI reliability and scheduled-check hardening.
  Treat subsequent failures and required-check rollout as CI operations under
  [`CI_OPERATIONS.md`](./CI_OPERATIONS.md), not an unfinished feature sprint.

## Work Board

This is the repository work-board view of the execution ladder below. Status
describes the remaining task, not whether the whole subsystem exists.

| Work | Status | Next bounded action |
| --- | --- | --- |
| Calcify finite P3 O3 (financial admission) | Packet decided; O3a hardened (72/72 mutants killed, 5,000-schedule oracle, state-committing chain), wire contract and ordering spike merged; binding v2 spec is a draft under review | Close final O3a and spec reviews; accept the spec; then O3b adapter. |
| Calcify O2 evidence debt | Open | One approved live replay of G1-G8/G10 on master; merge Records PR11/PR12 (merge-commit); operator sign-off of the role split. |
| Calcify 10k arena capacity | Research; recovery gate open | Warm-restart proof at aged state; incremental keyed capture reducer with differential oracle; credit calibration and owner decision. |
| Settlement read visibility/authorization | Contract and code work | Define run/participant/operator visibility; enforce and test both adapters. |
| Projection `5k` downstream drain | Failed evidence gate | Diagnose final lifecycle/market maintainer work; prove drain before sustained promotion. |
| Bounded-state workload | Implemented, unmerged | Reconcile `codex/throughput-state-shape-control`; smoke all three shapes. |
| Arena multi-seed/hosted games | Evidence to locate or produce | Complete missing campaign/rehearsal records; admission, roster and scoring code already exist. |
| Post-trade scenario hardening | Evidence to locate or produce | Record live security-repair/realistic-pending reports; behavior already implemented/tested. |
| Operational readiness and service identity | Partial implementation | Extend existing config/health/TLS foundations with operational and peer-identity proof. |
| Compact canonical storage | Deferred until state-shape gate | Run measured storage A/B with retained audit/replay proof. |
| CI, onboarding, mutation/protobuf hardening | Delivered; regression maintenance | Preserve gates; do not reopen initial implementation. |

Evidence and source/test mapping:
[`IMPLEMENTATION_STATUS_AUDIT_2026-09-04.md`](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/archive/IMPLEMENTATION_STATUS_AUDIT_2026-09-04.md).
Supplemental UI and simulator backlogs are candidate catalogs, not competing
priority ladders; reconcile individual candidates before scheduling them.

## Active Execution Ladder

1. Advance Calcify through the correctness ladder and the capacity track.
   - Follow [the program plan](work/CALCIFY_PROGRAM_STATUS_AND_SPRINT_PLAN_2026-10-11.md): Sprint 1 pays evidence
     debt, reviews and hardens O3a, specifies binding v2 and proves option-B ordering determinism, and runs the warm-restart proof.
   - Keep finite correctness acceptance separate from rate qualification; never scale the finite single-state image.
   - No financial-authority, credit or capacity claim without its own evidence and owner decision.

2. Complete the remaining invite-only game-preview evidence.
   - Preserve the completed external admission/onboarding test as regression
     evidence; do not repeat its implementation or initial proof as backlog.
   - Use the implemented and tested `T-72h` / `T-48h` / `T-24h` eligibility,
     roster lock, and `T-30m`/`T0` run binding in recorded preview evidence.
   - Locate or record the remaining multi-seed and hosted preview runs with
     immutable roster, policy, seed, artifact, replay, accounting, and scoring
     evidence. Existing July 14 hosted scoring proof is already recorded.
   - Execution-role propagation and evidence isolation are corrected and the
     fresh local three-policy matrix passes with 30 scoped fills per policy,
     complete reconciliation, zero accounting gap, and no unspecified roles.
     Repeat this evidence on the promoted hosted Arena profile; retain the
     crossed-book warning until market-data reads are venue-session scoped.
   - Do not advertise open or self-service submissions before the release
     matrix is green.

3. Finish the API/control-plane hardening backlog.
   - Participant order/current/history/fill reads and command client/participant
     checks already have implementation and negative tests; preserve them.
   - Next code slice: define run/participant/operator visibility for the six
     `/api/v1/settlement/*/{scenarioRunId}` read families in the API surface
     policy, then add allowed/denied tests and enforcement in both HTTP
     adapters. These routes currently authenticate/rate-limit, but do not pass
     a principal into the run-level read gateway. Do not silently choose a new
     visibility policy or repeat the existing order-authorization work.
   - Keep hosted, CI, and operator callers off raw `/internal/*` HTTP. The
     current [`INTERNAL_HTTP_CALLER_INVENTORY.md`](./INTERNAL_HTTP_CALLER_INVENTORY.md)
     has no hosted migration candidate; retain local diagnostic callers as
     loopback-only tooling and treat new remote callers as regressions.
   - Extend existing TLS/mesh client configuration to explicit peer/service
     identity and deployment proof. Standard engine gRPC health already exists.
   - Deepen existing enabled-role readiness beyond configuration checks;
     lifecycle/market-data readiness currently reports `true` when enabled.
   - Keep `/api/v1` and `/admin/v1` as the only externally reachable HTTP
     product families.

4. Resume venue-core scaling only from the recorded pause handoff.
   - Reconcile the existing local `codex/throughput-state-shape-control`
     implementation (`f00dd590`) with current `master` before adding another
     bounded-state implementation. It is not merged; its plan still requires
     local three-shape smoke and hosted promotion evidence.
   - First prove bounded working-set/state-shape behavior, including live-order
     retention and terminal-order cleanup.
   - Then run the compact canonical storage A/B and measure WAL/table bytes per
     command.
   - Preserve transactional command/event handoff, static ownership fencing,
     semantic checksums, full-log recovery, and materializer idempotency.
   - Raise the verified ceiling only after short and soak gates close with zero
     accepted/direct-acked/materialized gaps.

5. Reduce projection write amplification.
   - Reuse the completed August 21 instrumented one-maintainer short comparison.
     Close the `5k` downstream-drain failure, then run the bounded memory/batch
     matrix and sustained gates from the projection plan. Do not infer
     downstream freshness from canonical/projected count equality alone.
   - Keep the command-status write subset and full timeline projection stages
     measurable independently, but do not claim independent lifecycle
     freshness until cancel/modify event dependencies are explicit.
   - Reduce `runtime_events`, lifecycle/fill, WAL, tuple, and temp-file pressure
     before longer `5k` soaks or higher projection rates.
   - Keep projection freshness claims separate from venue-core acceptance and
     canonical materialization claims.

6. Harden the implemented post-trade lifecycle.
   - Preserve the allocation, confirmation, affirmation, clearing, novation,
     obligation, instruction, attempt, leg, ledger, break, repair, resolution,
     exception-queue, proof, and score fact chain.
   - Security-fail/repair and `ops-realistic-v1` pending behavior already have
     implementation and passing tests. Locate or record their remaining live
     scenario reports, then define one operator workflow slice from those
     results. Missing reports are evidence work, not missing lifecycle code.
   - Keep deterministic netting as a separately scoped contract/preview; do
     not broaden this checkpoint into a clearinghouse build or mutate matching
     history.

7. Keep documentation synchronized as behavior lands.
   - Update contracts, internal docs, public docs/API pages, and README in the
     same change when routes, commands, deployment shape, or release claims
     change.
   - Move superseded plans to `docs/archive/`; never delete decision,
     benchmark, security, or replay evidence.

## Non-Goals At This Checkpoint

- No `20k` or higher venue-core claim from short, no-op, accepted-only, or
  unmaterialized evidence.
- No UI/control-room freshness requirement folded into the `202 Accepted`
  contract.
- No raw `/internal/*` route presented as a public, partner, bot, SDK, or stable
  operator API.
- No open Bot Arena intake before remaining hosted-preview and open-intake
  requirements pass; admission/onboarding is already complete.
- No Arena implementation dependency in Reef-only artifacts or deployment.
- No broad clearinghouse build before the current post-trade facts and operator
  paths are hardened.
- No Calcify financial-authority, credit-reservation or 10k-through-the-API claim from model, fixture or research evidence;
  relational authority (D009) stays accepted until a separate adoption decision.
- No use of finite Calcify artifacts (single serialized state image, finite binding, finite budgets) as a production scale template.

## Definition Of Done For Active Work

- focused tests cover the changed behavior and failure modes
- contracts and documentation change with behavior
- replay, idempotency, ordering, and audit evidence remain intact
- performance claims name attempted, accepted, direct-acked, materialized, and
  projected stages separately
- artifacts and run identifiers are recorded for promoted evidence
- Reef-only and Arena-enabled boundaries remain independently testable

## Records second pass — October 2, 2026

Default `master` synced to `76872e9db249152e0329393d73c4a2f850ea2b93` before this pass. Completed R08 records, closed campaign scratch plans and superseded handoff/pilot evidence moved into [published Records PR #4](https://github.com/dills122/reef-records/pull/4). [Inventory](records/2026-10-02-second-pass-migration.json) pins verified originals. Latest R08 verification rehomed locally; current Calcify owner docs, open gates and evidence retained. CI Operations owns current pilot guidance. No runtime behavior or qualification changed.
