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

Source/test/artifact reconciliation:
[`IMPLEMENTATION_STATUS_AUDIT_2026-09-04.md`](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/archive/IMPLEMENTATION_STATUS_AUDIT_2026-09-04.md).
Items below distinguish missing implementation from evidence not found in the
audited checkout; missing local reports do not prove a run never happened.

## Calcify financial experiment E0 checkpoint (2026-10-03)

Execution source `85f0ce8c`, separate from open planning PR #466 at `cda4185b`.
Java21 build and70 existing Calcify tests pass; matcher source suites pass.
Frozen20 synthetic cases/52 inputs, history/checkpoint shape, runtime jar hashes,
isolated RF3 config and measurement rules ready for E1a. E0 tool gate blocked by
Serena Kotlin initialization; Docker daemon/app unavailable, guest disk/image/topic
settings unverified. Same-run order reuse remains live-source gap; reservations
and authority cutover deferred. [Canonical sprint evidence and next E1 slice](evidence/calcify-financial-sprint1/README.md).

Independent review1 accepts scoped preparation checkpoint with P2 recorder finding.
Byte-capture correction and2 regression tests pass; review2 checks corrected branch
before PR publication. Planning PR#466 now merged; doc-only master sync leaves
original executed production-source85f0ce8c provenance intact.

Fresh independent review2of3 accepts scoped preparation checkpoint with non-blocking
follow-ups; prior recorder P2 fixed and verified, no new actionable findings.
[Report](evidence/calcify-financial-sprint1/reviews/instance-2.md) retained; E0 tool
gate and later financial/broker proofs remain pending. PR publication authorized.

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

## Calcify joined10k checkpoint (2026-10-02)

**Configuration correction:** campaign tested PostgreSQL-backed smoke ingress, not documented in-memory-intake/publish-pipeline performance shape. Intended fast-ingress+Calcify capacity remains unmeasured. Verify explicit profile/accounting before further tuning;7,708.36/s is scoped SQL-smoke result.

Two-hour frozen-base campaign fails10k accepted-order target. Best300s **7,708.36durable order ACKs/s**,1,158,676 exact resolved contexts and Phase1 counts, bounded drain, zero failures. Final higher-concurrency attempt aborts on Docker guest-disk ENOSPC. Latest-master correctness fixes integrated and separately checked; no capacity transfer. [Campaign evidence/next gates](research/CALCIFY_10K_TIMEBOX_2026-10-02.md), [handoff](work/handoffs/2026-10-02-calcify-10k-timebox.md). Restore guest storage headroom and repeated-state controls before further sustained load; durable-intake batching remains measured hypothesis. Older checkpoints below retain dated scope.

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

## Calcify Phase 2 implementation (2026-09-30 branch)

User approved [six-slice implementation plan](work/CALCIFY_PHASE2_IMPLEMENTATION.md). Active branch: `codex/calcify-phase2-implementation`, continuing preserved experiment commits. [Draft PR #433](https://github.com/dills122/reef/pull/433) records nightly checkpoint and merge blockers. D-042 run-scoped matching/V3 snapshots, full-fact Protobuf/pure resolver, managed Streams role, bounded lane faults, source identity/retention guards, and operational configuration implemented. Actual HTTP two-order smoke emits one exact context. Corrected RF3/fsync fault matrix passes nine boundaries; million-row cold recovery reaches observed RUNNING in19.97s with exact catch-up. Those broker cohorts precede final throughput changes and require unchanged-candidate repeat. Latest100k hot diagnostic reaches11.12k/s with JFR and exact full-fact parity. Latest sustained hot3.15m cohort reconciles exact at10.16k/s but producer310.73s fails frozen301s maximum; spread/skew/aged unrun. Final local platform regression/coverage passes. Review found open verified-input retention and verified/output UUID binding gaps. Work paused at [nightly handoff](https://github.com/dills122/reef-records/blob/6b838e5287c5428c914f6577ff176c1cd03c44ec/records/reef/docs/work/handoffs/2026-09-30-calcify-phase2.md); correctness fixes, unchanged-candidate fault/recovery, production-role smoke and sustained qualification remain; no full-system20k upstream capacity or production availability claim. [Implementation evidence](evidence/calcify-phase2-implementation/README.md) preserves failed attempts, durability correction and exact measurement scope.

## Calcify Phase 2 experiment checkpoint (2026-09-30 branch)

`codex/calcify-phase2-experiments`, based on merged #431, contains isolated source
fixtures, managed-runtime/fault prototypes and measured local-store diagnostics.
Recommendation: verified-led one-input Kafka Streams with managed full-fact local
state/changelog/standby and demand source reader. Scratch D-042 alignment proves
cross-run isolation/replay fixture; production matching alignment still pending.
Recovery, topic-generation/retention and two-lane fault probes passed in stated
local RF1 scope. Five-minute 10k local store/codec joins/s is not full resolver or
system capacity qualification. [Decision/evidence report](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/research/CALCIFY_PHASE2_EXPERIMENTS_2026-09-30.md)
records remaining implementation gates and reproducible commands. User sync subsequently approved implementation; see implementation checkpoint above. Legacy remains intact.

## Calcify Phase 1 checkpoint (2026-09-29 branch)

`codex/calcify-phase1` adds opt-in matching commitment extraction, stub
verification, and PostgreSQL processing receipts without switching legacy
post-matching. Local fixtures, partition-poison isolation, and receipt restart
passed on two-partition Redpanda. A later four-partition local smoke passed
PostgreSQL-backed HTTP intake through Go matching to an exact Calcify receipt;
bounded 2,000-order hot-lane local load also reconciled 1,001 trades, links,
and receipts after drain. These are diagnostics, not hosted capacity or
settlement qualification.
Measured verifier-per-link transaction backlog was corrected by per-poll
transaction batching: two repeated local 1,000-pair runs drained receipts
within 960 and 942 ms after last acceptance with exact stage counts.
Phase 1 paired load gate then passed: 1,000-pair burst plus five minutes at
100 crossing pairs/s, with 60,000 accepted load orders, 30,001 trades/links/
receipts including preflight, sampled accepted-to-receipt gap p95/peak 70
trades, and 1,923 ms final drain. Scope remains local single-lane diagnostic.
Follow-up five-minute run at 300 crossing pairs/s accepted 180,000 load
orders; 90,001 trades/links/receipts reconciled, sampled gap p95/peak was
200 trades with steady first/last half means, and final drain was 1,943 ms.
Tail stayed bounded at this tested rate; higher-rate and longer-run limits
remain unproven.
High-rate CAL-P1-L6 probes exposed a Kafka 1 MiB matching event-batch ceiling,
per-record receipt transaction/checkpoint backlog, and Bun socket saturation.
Phase 1 high-rate local setup now uses 200-command matching batches and per-poll
receipt batches. With pooled Go load, 10k/s-offered 30-second probes reached
about 7.7k accepted orders/s. First 7.5k/s-offered five-minute run accepted
7,258.61/s with exact stage counts, but gap samples were lower bounds.
Corrected 7.5k/s run accepted 6,420.72/s and failed intake gate despite exact
stage counts. Fresh 5k/s-offered five-minute run passed at 4,998.03 accepted
orders/s with 749,952 exact trades/links/receipts, conservative upper-gap
p95/peak 628/846 trades, and 1,478 ms final drain. Repeat sustained plus
short stress probes for every material post-match phase; 7.5k/s and 10k/s
local and multi-lane qualification remain open.
Exact wire contract, corrections, and limits:
[Calcify Phase 1 implementation](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/work/CALCIFY_PHASE1_IMPLEMENTATION.md).
Replay-retention window enforcement and independent source-to-link
reconciliation remain follow-ups before non-diagnostic use. Repeat
`make dev-smoke-calcify-full-path` as each post-match slice expands, adding
assertions for that slice's source identity, durable effect, and replay behavior.
After each material post-match phase change, run a fresh full-path preflight,
a 30-second 10k/s-offered probe, and a 300-second 5k/s-offered run through
`make dev-smoke-calcify-high-rate` and `make dev-soak-calcify-high-rate-load`.
Use `make dev-verify-calcify-high-rate` for exact final stage counts; record
offered/accepted rate, dropped pairs, conservative in-load receipt gap, drain,
failures, and phase-specific effects. Raise sustained target toward 7.5k/s
only after it passes with the new phase. Retain raw reports for failed runs.

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

1. Complete the remaining invite-only game-preview evidence.
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

2. Finish the API/control-plane hardening backlog.
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

3. Resume venue-core scaling only from the recorded pause handoff.
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

4. Reduce projection write amplification.
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

5. Harden the implemented post-trade lifecycle.
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

6. Keep documentation synchronized as behavior lands.
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
