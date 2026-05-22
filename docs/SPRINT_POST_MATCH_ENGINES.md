# Sprint Plan: Post-Match Engines Foundation

## Purpose

This sprint follows `SPRINT_COMMUNICATION_API_ADMIN.md` and establishes the first production-shaped post-match lifecycle across compare, clearing, netting, and settlement intake.
Implementation in this sprint should follow [`docs/POST_MATCH_STANDARDS.md`](./POST_MATCH_STANDARDS.md).

## Sprint Goal

Implement a deterministic, inspectable post-match pipeline where matched trades can progress through:

1. trade processing completion
2. compare and affirmation
3. clearing acceptance or rejection
4. netting into settlement-ready obligations

## Scope

### In scope

- runtime application modules for:
  - compare and affirmation engine
  - clearing engine
  - netting engine
  - settlement intake orchestration
- event and command contracts for new post-match transitions
- operations read models and queues for compare, clearing, and netting states
- simulation-mode controls for post-match timing and fault injection
- baseline settlement ledger adapter interface and default relational implementation
- role-gated command authorization across post-match engines (persona-aware workflow controls)

### Out of scope

- full external clearing/depository integrations
- full buy-in and advanced fail-market workflows
- production blockchain deployment
- complete admin HTTP surface (CLI-first remains acceptable)

## Workstreams

### Workstream A: Compare and Affirmation Engine

Deliverables:

- state machine: `booked -> compared -> affirmed` and mismatch branches
- deadlines and timeout-based mismatch opening
- role-gated actions for compare and affirmation operations
- commands:
  - `GenerateConfirmation`
  - `AffirmTrade`
  - `RejectAffirmation`
- events:
  - `TradeCompared`
  - `TradeAffirmed`
  - `TradeMismatched`

Exit criteria:

- at least one happy-path affirmed trade
- at least one mismatch path opening an actionable exception

### Workstream B: Clearing Engine

Deliverables:

- clearing submission workflow after affirmation
- acceptance/rejection model with coded reasons
- novation state handling
- role-gated clearing decisions for eligible operational personas
- commands:
  - `SubmitForClearing`
  - `AcceptClearing`
  - `RejectClearing`
- events:
  - `ClearingSubmitted`
  - `ClearingAccepted`
  - `ClearingRejected`
  - `TradeNovated`

Exit criteria:

- affirmed trade can be accepted to clearing and transition to novated
- rejected clearing case opens an exception with trace context

### Workstream C: Netting Engine

Deliverables:

- netting batch model keyed by settlement date
- netting rules by participant and instrument
- gross-to-net calculation outputs
- policy version tagging on netting results for replay and audit
- command:
  - `NetSettlementObligations`
- events:
  - `ObligationsNetted`
  - `SettlementObligationCreated`

Exit criteria:

- at least two offsetting trades produce one net obligation per side
- netting artifacts are queryable in operations read models

### Workstream D: Settlement Intake and Ledger Adapters

Deliverables:

- settlement intake command path from netting outputs
- adapter interface:
  - `RelationalLedgerAdapter` (default)
  - `EventLogLedgerAdapter` (optional path)
  - `BlockchainLedgerAdapter` (experimental stub only)
- settlement intake events and queue visibility

Exit criteria:

- net obligations become settlement obligations through adapter-driven persistence
- adapter selection is environment-configurable and covered by tests

### Workstream E: Simulation and Fault Injection

Deliverables:

- simulated post-match clock controls
- calendar-aware simulated timing (country-agnostic config, U.S. defaults)
- fault profiles for compare/clearing/netting/settlement-intake stages
- deterministic seeded replay coverage for one end-to-end scenario

Exit criteria:

- same scenario seed produces identical state transitions and event timeline

## Architecture and Environment Plan

### Production-shaped runtime mode

- realistic workflow windows (T+1 settlement default behavior)
- bounded retries and idempotency enforcement
- stricter validation and failure codes

### Simulation mode

- accelerated time windows
- deterministic failures at chosen stages
- repeatable seeded runs for scenario comparison

Mode rule:
- both modes use the same command handlers and state machines; only timing and adapter behavior differ by configuration.

## Data and Contract Plan

Required artifacts:

- protobuf contract additions for new commands and events
- runtime persistence tables for compare, clearing, netting batches, and settlement intake
- read-model tables for operational queues:
  - compare queue
  - clearing queue
  - netting queue
  - settlement intake queue

## Testing Plan

Minimum tests for sprint completion:

1. unit tests per engine state machine
2. transport-level contract tests for new commands and events
3. end-to-end deterministic test:
   - match -> compare -> affirm -> clear -> net -> settlement obligation created
4. failure-path test:
   - clearing reject opens exception and halts downstream netting for affected trade
5. adapter parity tests:
   - relational vs event-log adapter produce equivalent domain outcomes

## Risks and Mitigations

- risk: post-match modules become tightly coupled.
  mitigation: enforce module-owned state transitions and event-only handoffs.
- risk: netting complexity expands too early.
  mitigation: start with deterministic v1 netting rules and explicit exclusions.
- risk: experimental ledger work delays core delivery.
  mitigation: keep blockchain adapter as non-blocking stub in this sprint.

## Definition of Done

The sprint is done when:

1. matched trades can traverse compare, clearing, and netting into settlement intake.
2. both happy and failure paths are observable through queue/read models.
3. deterministic replay is proven for at least one end-to-end post-match scenario.
4. adapter-based settlement intake is configuration-selectable without domain code changes.
