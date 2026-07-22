# Post-Trade Lifecycle Sprint

Status: core lifecycle V1 implementation landed in PR #223; remaining work is
live evidence and a separately scoped operator-workflow follow-up.

## Purpose

This sprint brings Reef's post-trade surface closer to the maturity of the venue
core without trying to build a full clearinghouse. The target is a credible,
operator-visible equity post-trade lifecycle that remains deterministic,
replayable, and policy-versioned.

Bot Arena may use the accelerated `instant-post-trade-v1` profile, but this
sprint is Reef platform work. Bot Arena is a consumer of Reef's simulation
environment, not the owner of settlement semantics.

## Goal

Reef can explain each matched trade from canonical venue trade through
allocation, confirmation, affirmation, clearing, novation, settlement attempt,
ledger finality, failure, repair, and exception closure.

## Current Starting Point

Implemented foundation:

- trade-to-settlement obligation materialization from canonical runtime trades
- `instant-post-trade-v1` profile resolution and evidence fields
- minimal auto allocation, confirmation, and affirmation facts
- clearing submission/acceptance/rejection and novation facts
- settlement instruction and attempt facts
- cash/security leg outcomes
- append-only ledger proof for settled instant obligations
- cash-leg and security-leg failure paths when resources are constrained
- repair commands, force-settle, reverse-ledger, and required operator reasons
- public settlement facts, obligations, ledger, proof, and score reads
- operator-readable exception queue projection over settlement breaks and
  clearing rejections
- P2 settlement assertion evidence for break/repair and settled-chain reads

Main gaps:

- no promoted live security-fail/repair or `ops-realistic-v1` pending report;
  focused implementation tests exist, but the active evidence bundle is still
  incomplete
- no affirmation-timeout live proof or clearing-reject repair workflow proof
- no netting batch model beyond gross-per-trade settlement
- post-trade current state is still more proof/report oriented than operator workflow oriented

## Sprint Scope

### 1. Product Boundary Cleanup

Make docs explicit:

- Reef is the institutional equity-market simulation platform.
- Bot Arena is a bot trading competition/game product that uses Reef as its
  simulation environment.
- Bot Arena docs can reference Reef post-trade profiles, but core settlement
  docs should not depend on Bot Arena.

Acceptance:

- `CURRENT_STATUS.md`, `WORK_PLAN.md`, and Bot Arena docs describe this split.
- post-trade work remains under Reef platform planning.

### 2. Post-Trade Lifecycle V1

Extend the current instant chain:

```text
TradeCreated
  -> SettlementAllocationProposed
  -> SettlementConfirmationGenerated
  -> SettlementAffirmationAccepted
  -> ClearingSubmitted
  -> ClearingAccepted or ClearingRejected
  -> NovationRecorded
  -> SettlementObligationCreated
  -> SettlementInstructionCreated
  -> SettlementAttemptStarted
  -> leg outcomes
  -> ledger proof
  -> SETTLED or FAILED
```

Rules:

- `instant-post-trade-v1` may auto-clear and auto-novate, but must emit facts.
- `ops-realistic-v1` may stop at pending workflow states instead of fake finality.
- `SETTLED` stays reserved for financial finality after leg and ledger proof.
- `RESOLVED` stays reserved for exception or case closure.

Acceptance:

- happy-path instant settlement proves clearing and novation before instruction.
- ops-realistic profile does not silently settle trades.
- existing P2 assertions still pass or have documented successor assertions.

### 3. Exception Queue V1

Add an operator-readable exception queue projection from settlement facts.

Fields:

- `scenarioRunId`
- `settlementBreakId`
- `settlementObligationId`
- `tradeId`
- `reason`
- `severity`
- `ownerRole`
- `state`
- `openedAt`
- `lastUpdatedAt`
- `repairAction`
- `resolvedAt`
- `actorId`
- `correlationId`

Initial reasons:

- `CASH_LEG_FAILED`
- `SECURITY_LEG_FAILED`
- `AFFIRMATION_TIMEOUT`
- `CLEARING_REJECT`

Acceptance:

- cash fail and security fail both appear in the queue.
- repair closes the queue item through append-only facts.
- operator mutations require actor, reason, timestamp, and correlation metadata.

### 4. Netting Batch Seed

Define deterministic netting before full implementation.

First model:

- group by participant, instrument, currency, settlement date, and policy version
- preserve gross trade references
- expose gross amount, net amount, compression ratio, and settlement date
- do not replace gross settlement until tested

Acceptance:

- doc/model contract exists.
- optional read-only preview exists if small enough for this sprint.
- no settlement finality depends on unfinished netting.

### 5. Scenario Evidence

Add or update live assertion reports for:

- instant happy path: settled chain with clearing and novation
- cash failure: break, queue, repair, retry, ledger proof, resolved
- security failure: break, queue, repair, retry, ledger proof, resolved
- ops-realistic pending: no fake same-tick finality

Acceptance:

- each report links command status, canonical trade, settlement facts, ledger proof,
  and replay/checksum evidence where available.
- failures classify missing command completion, projection freshness, replay,
  or settlement causation separately.

## Non-Goals

- full CCP/CNS clearing implementation
- real ISO 20022, FIX, or SWIFT message generation
- production custody, corporate actions, or margin
- full netted obligation settlement
- broad admin UI buildout unless a separate UI slice consumes the new reads
- projection `10k` freshness scaling

## Implementation Record And Next Slice

The original PR sequence below produced the lifecycle V1 implementation in PR
#223. Treat PRs 1-3 as delivered and PR 4 as partially delivered; do not reopen
their initial-model work as the current backlog.

### PR 1: Documentation And Contracts

- product boundary cleanup
- lifecycle V1 contract
- exception queue contract
- netting seed contract
- active/historical doc cleanup

### PR 2: Clearing And Novation Facts

- add fact types
- extend materializer chain
- add happy-path and ops-realistic tests

Implementation result: the instant profile now emits clearing and novation
before settlement instruction; `ops-realistic-v1` remains pending; clearing
reject facts and the exception projection exist; facts/proof reads expose the
chain; and focused idempotency, pending-state, queue, and causation tests pass.
Netting, a broad admin UI, full CCP/CNS behavior, and production external
message schemas remain out of scope.

### PR 3: Exception Queue Projection

- add queue projection/read API
- cover cash/security failure and repair closure
- add operator metadata assertions

### PR 4: Scenario Evidence

- update P2 or add P3 post-trade scenario assertions
- generate reports for happy path and failure/repair paths
- update `CURRENT_STATUS.md` with evidence

Current next slice:

- record live security-fail/repair and `ops-realistic-v1` pending assertion
  reports with replay/checksum evidence where available
- use those reports to scope one operator workflow improvement, rather than
  starting a broad post-trade UI
- treat affirmation timeout, clearing-reject repair, and deterministic netting
  as separate follow-ups unless one is explicitly selected

## Historical Effort Estimate

- backend facts/API/tests only: one strong week
- with admin UI read surfaces: two weeks
- with netting implementation beyond preview: three weeks
