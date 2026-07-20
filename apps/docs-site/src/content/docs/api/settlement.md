---
title: Settlement APIs
description: Scenario-scoped post-trade facts, obligations, exceptions, ledger, proof, and score reads.
banner:
  content: Evidence-oriented lifecycle slice. Allocation through novation and settlement finality are implemented; broad netting and operator UX remain later work.
---

Settlement answers the next question after a trade: did the buyer get securities, did the seller get cash, and can we prove the path taken? Reef's first slice keeps that story narrow and inspectable. Matching history stays immutable; settlement reads durable trade facts and writes its own append-only evidence.

The current implementation supports a scenario-scoped evidence path plus instant-post-trade finality proof:

- settlement obligations materialized from persisted runtime trades
- allocation, confirmation, and affirmation facts
- clearing submission plus acceptance/rejection and novation facts
- deterministic settlement instructions and attempts
- cash/security leg outcomes
- append-only ledger proof entries
- `SETTLED` facts only after both legs and ledger proof pass
- cash-leg and security-leg break facts when seeded resources are insufficient
- repair facts that unlock deterministic retry attempts
- `RESOLVED` facts for repaired exception closure
- scenario-scoped exception queue projection over clearing rejects and settlement breaks

This is not the full post-trade department yet. Richer netting, operator case management/UI, external-party workflows, and broad analytics remain planned.

## GET /api/v1/settlement/facts/{scenarioRunId}

Returns the raw evidence trail for one scenario run. P2 scenario assertions and smoke reports use it to prove the settlement story from facts, not from a hand-written report.

Current fact families include:

- resource positions used by constrained instant-mode checks
- `SettlementObligationCreated`
- `SettlementAllocationProposed`
- `SettlementConfirmationGenerated`
- `SettlementAffirmationAccepted`
- `SettlementClearingSubmitted`
- `SettlementClearingAccepted` or `SettlementClearingRejected`
- `SettlementNovationRecorded` after clearing acceptance
- `SettlementInstructionCreated`
- `SettlementAttemptStarted`
- settlement leg outcomes
- settlement ledger entries
- `SettlementSettled`
- `SettlementBreakOpened`
- `SettlementRepairPosted`
- `SettlementResolved`

The response carries ordering and causation data so the chain can be checked:

```text
trade -> obligation -> allocation -> confirmation -> affirmation
  -> clearing -> novation -> instruction -> attempt -> leg outcomes
  -> ledger proof -> SETTLED
```

Failure and repair path:

```text
trade -> obligation -> failed leg -> break -> repair -> retry -> SETTLED -> RESOLVED
```

## GET /api/v1/settlement/obligations/{scenarioRunId}

Returns current obligation state for one scenario run, projected from the fact trail.

Use this when a report needs the current state of each obligation instead of every raw event. The first materializer is gross-per-trade; deterministic micro-batch netting is still planned.

## GET /api/v1/settlement/ledger/{scenarioRunId}

Returns replayable participant/account/asset balances plus per-settlement proof totals derived from append-only ledger facts. It is meant to answer: "show me the entries that prove this settlement balanced."

Instant-post-trade happy-path settlement writes four proof entries per settled trade:

- buyer cash debit
- seller cash credit
- seller security debit
- buyer security credit

Settlement proof is valid only when cash debits equal cash credits, security debits equal security credits, both leg outcomes succeeded, and `SettlementSettled` is present. No balanced proof, no finality claim.

## GET /api/v1/settlement/exceptions/{scenarioRunId}

Returns the scenario-scoped exception queue projected from clearing rejections
and settlement breaks. The view includes open/resolved counts plus exception
type, severity, owner role, required action, and repair action where applicable.
It is an operator-readable projection over append-only facts, not a mutation of
matching or settlement history.

## GET /api/v1/settlement/proof/{scenarioRunId}

Returns one replay proof with trade, obligation, attempt, and ledger identifiers; final balances; settlement proof rows; profile/policy evidence; fact counts; causation-gap checks; proof status (`CLEAN` or `GAPPED`); and a deterministic checksum.

Use it when a scenario assertion needs a compact answer to whether obligations have valid leg outcomes, balanced ledger proof, and final settlement facts.

## GET /api/v1/settlement/score/{scenarioRunId}

Returns participant scoring inputs from the same durable facts: settled balances, pending value, haircut-adjusted pending value, blocked unsettled value, fail counts, aged-fail counts, repair-pending counts, and penalty points.

Query params: optional `asOf`, optional `agedFailAfterSeconds`.

Use it for reports that need a single settlement-quality summary rather than raw facts.

## Operator Seed And Repair Commands

Use the authenticated, authorized `/admin/v1` gateway for operator workflows.
Equivalent `/internal/admin/...` handlers remain local/migration adapters and
must not be exposed raw.

| Gateway route | Purpose |
|---|---|
| `/admin/v1/settlement/facts` | Append a scenario settlement fact bundle for smoke/evidence setup |
| `/admin/v1/settlement/obligations/materialize` | Materialize trade-to-post-trade lifecycle facts for a scenario run |
| `/admin/v1/settlement/repairs/cash` | Post buyer cash resource repair plus `SettlementRepairPosted` |
| `/admin/v1/settlement/repairs/security` | Post seller security resource repair plus `SettlementRepairPosted` |
| `/admin/v1/settlement/force-settle` | Force settlement finality for controlled repair/evidence paths |
| `/admin/v1/settlement/reverse-ledger-entry` | Append compensating reversal evidence for a ledger entry |

## Profile Behavior

Post-trade profile resolution is active:

1. scenario/run override
2. venue/session override
3. platform default/admin profile
4. hard default `ops-realistic-v1`

`instant-post-trade-v1` gives fast simulation feedback while keeping lifecycle events, leg outcomes, ledger proof, and failure injection. `ops-realistic-v1` materializes obligations but leaves later lifecycle steps for future explicit workflows.

## Learn More

- `docs/SETTLEMENT_EXCEPTION_FACTS.md` - P2 settlement fact slice
- `docs/SETTLEMENT_CLEARING_STRATEGY.md` - target settlement/clearing profiles and implementation direction
- [Schema Overview](../../schema/overview/) - settlement schema ownership
- [Runtime Schema](../../schema/runtime-schema/) - runtime trade facts consumed by settlement
