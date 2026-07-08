# Settlement Exception Facts

## Purpose

Define the minimal P2 settlement fact slice before broad post-trade work resumes.

Historical note: this document describes the original P2-only minimal slice (`trade -> obligation -> cash-leg break -> repair -> resolved`, no ledger mutation). Post-trade work has since expanded under `D-050` — obligation materialization and real append-only ledger postings are implemented, with obligation and ledger query surfaces shipped. See [`SETTLEMENT_CLEARING_STRATEGY.md`](./SETTLEMENT_CLEARING_STRATEGY.md) for the current, complete settlement picture; treat the P2-scoped restrictions below as historical scope for that original slice, not current limits on the platform.

This document supports `P2_SETTLEMENT_BREAK_REPAIR` only. It is intentionally smaller than the full settlement domain in [`DATA_DOMAIN_SCHEMA_BLUEPRINT.md`](./DATA_DOMAIN_SCHEMA_BLUEPRINT.md).

## Scope

The first slice proves:

```text
trade -> obligation -> cash-leg break -> repair -> resolved
```

It did not implement allocation, confirmation, clearing, netting, account-ledger mutation, buying-power enforcement, exception UI, or broad settlement analytics. Account-ledger mutation is no longer accurate as a current limitation — see the historical note above and [`SETTLEMENT_CLEARING_STRATEGY.md`](./SETTLEMENT_CLEARING_STRATEGY.md) for what has since shipped. Allocation, confirmation, clearing, netting, buying-power enforcement, exception UI, and broad settlement analytics remain not implemented.

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
- historical: the original P2 slice created no account ledger rows. This is no longer current — `D-050` authorized real ledger mutation, and obligation materialization now writes append-only ledger entries (buyer cash debit, seller cash credit, seller security debit, buyer security credit) for settled instant-post-trade obligations. See [`SETTLEMENT_CLEARING_STRATEGY.md`](./SETTLEMENT_CLEARING_STRATEGY.md#obligation-materialization).
- settlement facts must not mutate matching history

## Assertion Source

P2 uses one narrow assertion source before it can be locked:

- `GET /api/v1/settlement/facts/{scenarioRunId}` returns obligation, break, repair, and resolution facts for the run
- `POST /internal/admin/settlement/facts` appends the same fact bundle for local/smoke seeding and evidence setup
- `scenario-smoke --settlement-facts-report` remains an artifact fallback for offline test evidence

The assertion source must return obligation, break, repair, and resolution facts by `scenarioRunId` and must expose enough ordering data to prove causation.

Historical note: the assertion surface above was the complete P2-era API surface. It is no longer the complete settlement API surface. Real ledger mutation has since shipped, and the runtime now also exposes `GET /api/v1/settlement/obligations/{scenarioRunId}` (obligation state projected from facts) and `GET /api/v1/settlement/ledger/{scenarioRunId}` (replayable participant/account/asset balances plus per-settlement ledger proof totals), alongside `POST /internal/admin/settlement/obligations/materialize`, `POST /internal/admin/settlement/repairs/cash`, and `POST /internal/admin/settlement/repairs/security`. See [`SETTLEMENT_CLEARING_STRATEGY.md`](./SETTLEMENT_CLEARING_STRATEGY.md#obligation-materialization) for the current, complete API surface and route list.

## First Implementation Tasks

1. Add `settlement` schema migration or explicitly marked runtime-compatible tables.
2. Add append-only fact writes for the four required events.
3. Add a narrow assertion read by `scenarioRunId`.
4. Wire P2 live assertions to the settlement fact read by default, with artifact fallback for offline tests.
5. Keep account ledger, confirmation, clearing, and exception UI out of the first slice.
