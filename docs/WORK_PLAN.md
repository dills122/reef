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
The Calcify program section was re-aligned on 2026-10-16 against `master` at `b289c8186` plus #574; non-Calcify sections remain as of 2026-09-04 and need the owner's pass.

Source/test/artifact reconciliation:
[`IMPLEMENTATION_STATUS_AUDIT_2026-09-04.md`](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/archive/IMPLEMENTATION_STATUS_AUDIT_2026-09-04.md).
Items below distinguish missing implementation from evidence not found in the
audited checkout; missing local reports do not prove a run never happened.

## Calcify program — current state and next actions (October 16, 2026)

Single living status and plan: [program status and sprint plan](work/CALCIFY_PROGRAM_STATUS_AND_SPRINT_PLAN_2026-10-11.md)
(section 0 is the October 16 update). Dated checkpoint history, including the October 11 to 13 sections that used to sit here,
is in the [Calcify history log](https://github.com/dills122/reef-records/blob/f3c9941d5cd35df81b32970e9873ad8ca9f86391/records/reef/docs/work/CALCIFY_HISTORY_LOG.md). Keep entries here short; put evidence in `docs/evidence/` and
analysis in the plan. Delivery goal (owner): **10,000 booked trades/s sustained for 15 to 30 minutes**; 15 to 20k/s is later work.

| Track | State | Next bounded action |
| --- | --- | --- |
| Capacity path (finance lanes, projection; the 10k goal) | Built end to end and merged (#553 to #573); bounded lane memory on by default (#574). One hot lane projects 9.9 to 10.0k trades/s on an EPYC 9555P (16.5k with the opt-in `capture-settle-v1` mode); a 15 minute soak at 10k trades/s (9M trades) passes with integrity ok on a laptop and on the EPYC droplet. Finance lane and projection only | Full-chain 15 minute run on target hardware (owner go and cost); wire the trie snapshot into clean restarts; kill-mid-transaction broker test |
| Capture path (Phase 1/2) | Merged, opt-in. D7 10,425/s fresh-state; aged no-restart control 10.3k full contexts/s passes; resolver cold restart 8.1k/s fails the 10k gate | Warm-restart proof, then incremental keyed capture reducer |
| Finite P3 O1/O2/O3 | O1 and O2 merged after independent review (O2 live evidence for G1-G8/G10 inherited, not replayed); O3a ledger, wire contract, finance runtime and role split merged; binding v2 spec an unaccepted draft | One approved O2 live replay on master; O3b design and adapter; binding v3 |
| Arena goalpost and credit | Proposals only (10k/s through the API, settlement lags; session lengths now 15 to 30 minutes; receivable credit with reference proofs) | Calibrate and adopt by owner decision; no reservation code before |

Authority: relational financial authority (D009) stays accepted; no Calcify financial authority, credit or capacity claim
exists. Hosted CI is Linux/Node 22/Bun 1.3.14; the finite O2 harness is macOS-local (`make test-finite-o2-harness`).

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
  history [`CALCIFY_HISTORY_LOG.md`](https://github.com/dills122/reef-records/blob/f3c9941d5cd35df81b32970e9873ad8ca9f86391/records/reef/docs/work/CALCIFY_HISTORY_LOG.md)
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
| Calcify O2 evidence debt | Open | One approved live replay of G1-G8/G10 on master; merge Records PR11/PR12 (merge-commit); role split signed October 13, 2026 ([packet](work/CALCIFY_FINITE_P3_ROLE_SIGNOFF.md)). |
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
