# Post-Trade Lifecycle Sprint

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
- settlement instruction and attempt facts
- cash/security leg outcomes
- append-only ledger proof for settled instant obligations
- cash-leg and security-leg failure paths when resources are constrained
- repair commands, force-settle, reverse-ledger, and required operator reasons
- public settlement facts, obligations, ledger, proof, and score reads
- P2 settlement assertion evidence for break/repair and settled-chain reads

Main gaps:

- no clearing or novation facts in the current instant chain
- no operator exception queue read model
- no affirmation timeout or clearing reject scenario proof
- no netting batch model beyond gross-per-trade settlement
- post-trade current state is still more proof/report oriented than operator workflow oriented
- docs still carry stale P2-only language in some active planning paths

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

## Suggested PR Slices

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

Immediate implementation target after this documentation branch merges:

- add settlement fact types:
  - `SettlementClearingSubmitted`
  - `SettlementClearingAccepted`
  - `SettlementClearingRejected`
  - `SettlementNovationRecorded`
- extend the `instant-post-trade-v1` materializer so the happy path emits:

```text
allocation
  -> confirmation
  -> affirmation
  -> clearing submitted
  -> clearing accepted
  -> novation recorded
  -> settlement instruction
  -> settlement attempt
  -> leg outcomes
  -> ledger proof
  -> SETTLED
```

- preserve `ops-realistic-v1` as pending workflow behavior; it must not
  accidentally same-tick settle just because the instant profile does.
- add a clearing-reject branch as a fact shape and test fixture, but keep full
  clearing-reject repair workflow for the exception-queue PR unless it stays
  small.
- update settlement proof/facts reads so clearing and novation identifiers are
  visible in the same evidence chain as allocation, confirmation, affirmation,
  instruction, attempt, ledger entries, and finality.
- add focused tests:
  - instant happy path includes clearing and novation before instruction.
  - ops-realistic profile produces pending post-trade state and no fake
    `SETTLED` fact.
  - repeat materialization remains idempotent.
  - P2 settled-chain assertions either still pass or have a documented
    successor assertion that includes clearing/novation.

PR 2 non-goals:

- no exception queue projection yet
- no admin UI
- no netting implementation
- no full CCP/CNS model
- no production external message schema

### PR 3: Exception Queue Projection

- add queue projection/read API
- cover cash/security failure and repair closure
- add operator metadata assertions

### PR 4: Scenario Evidence

- update P2 or add P3 post-trade scenario assertions
- generate reports for happy path and failure/repair paths
- update `CURRENT_STATUS.md` with evidence

## Effort Estimate

- backend facts/API/tests only: one strong week
- with admin UI read surfaces: two weeks
- with netting implementation beyond preview: three weeks
