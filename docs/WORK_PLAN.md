# Reef Work Plan

## Purpose

This is Reef's single active execution ladder. It stays short and links to the
documents that own detailed contracts, evidence, and sprint tasking.

Read [`CURRENT_STATUS.md`](./CURRENT_STATUS.md) first for the implementation
snapshot and verified performance claims.

Last aligned: 2026-09-04 against `master` at `cebbffc1`; hosted release gates
were not re-run during this documentation check.

Source/test/artifact reconciliation:
[`IMPLEMENTATION_STATUS_AUDIT_2026-09-04.md`](./IMPLEMENTATION_STATUS_AUDIT_2026-09-04.md).
Items below distinguish missing implementation from evidence not found in the
audited checkout; missing local reports do not prove a run never happened.

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
  [`REEF_BOT_ARENA_SEPARATION_PROMOTION.md`](./REEF_BOT_ARENA_SEPARATION_PROMOTION.md).
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
[`IMPLEMENTATION_STATUS_AUDIT_2026-09-04.md`](./IMPLEMENTATION_STATUS_AUDIT_2026-09-04.md).
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
