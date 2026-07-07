# Settlement Exception Facts

## Purpose

Define the minimal P2 settlement fact slice before broad post-trade work resumes.

This document supports `P2_SETTLEMENT_BREAK_REPAIR` only. It is intentionally smaller than the full settlement domain in [`DATA_DOMAIN_SCHEMA_BLUEPRINT.md`](./DATA_DOMAIN_SCHEMA_BLUEPRINT.md).

## Scope

The first slice proves:

```text
trade -> obligation -> cash-leg break -> repair -> resolved
```

It does not implement allocation, confirmation, clearing, netting, account-ledger mutation, buying-power enforcement, exception UI, or broad settlement analytics.

## Storage Target

Use the `settlement` schema as the target domain boundary. If implementation starts with runtime-backed compatibility tables, names and docs must still preserve the `settlement` ownership model so the facts can move cleanly.

Facts are append-only. Derived current state is a projection.

## Required Facts

### `SettlementObligationCreated`

Creates one settlement obligation referencing one canonical venue trade.

Required fields:

- `settlementObligationId`
- `scenarioRunId`
- `correlationId`
- `causationId`
- `tradeId`
- `buyerParticipantId`
- `sellerParticipantId`
- `instrumentId`
- `quantity`
- `cashAmount`
- `currency`
- `state = OBLIGATION_CREATED`
- `occurredAt`

### `SettlementBreakOpened`

Records the cash-leg failure.

Required fields:

- `settlementBreakId`
- `settlementObligationId`
- `scenarioRunId`
- `correlationId`
- `causationId`
- `reason = CASH_LEG_FAILED`
- `state = BROKEN`
- `occurredAt`

### `SettlementRepairPosted`

Records the manual repair action.

Required fields:

- `settlementRepairId`
- `settlementBreakId`
- `settlementObligationId`
- `scenarioRunId`
- `correlationId`
- `causationId`
- `repairAction = POST_CASH_LEG_REPAIR`
- `actorType = USER`
- `actorId`
- `occurredAt`

### `SettlementResolved`

Closes the P2 chain after repair.

Required fields:

- `settlementResolutionId`
- `settlementObligationId`
- `settlementBreakId`
- `settlementRepairId`
- `scenarioRunId`
- `correlationId`
- `causationId`
- `settlementState = RESOLVED`
- `exceptionState = RESOLVED`
- `occurredAt`

## State Rules

- `SettlementBreakOpened` must reference an existing obligation.
- `SettlementRepairPosted` must reference an existing break.
- `SettlementResolved` must reference an existing repair.
- no direct `CASH_LEG_FAILED -> RESOLVED` transition is allowed without repair
- final P2 state is `RESOLVED`, not `SETTLED`
- `CLOSED` is reserved for later UI/workflow case management
- no account ledger rows are created by the first P2 slice
- settlement facts must not mutate matching history

## Assertion Source

P2 needs one narrow assertion source before it can be locked:

- a settlement assertion query
- or a test-only assertion read

The assertion source must return obligation, break, repair, and resolution facts by `scenarioRunId` and must expose enough ordering data to prove causation.

## First Implementation Tasks

1. Add `settlement` schema migration or explicitly marked runtime-compatible tables.
2. Add append-only fact writes for the four required events.
3. Add a narrow assertion read by `scenarioRunId`.
4. Add P2 live assertions under [`SCENARIO_ASSERTION_PLAN.md`](./SCENARIO_ASSERTION_PLAN.md).
5. Keep account ledger, confirmation, clearing, and exception UI out of the first slice.
