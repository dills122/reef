---
title: Settlement APIs
description: Current scenario-scoped settlement fact, obligation, and ledger proof reads.
banner:
  content: Narrow P2 and instant-post-trade slice. This is evidence-oriented post-trade functionality, not the full allocation/confirmation/clearing workflow.
---

Settlement answers the next question after a trade: did the buyer get securities, did the seller get cash, and can we prove the path taken? Reef's first slice keeps that story narrow and inspectable. Matching history stays immutable; settlement reads durable trade facts and writes its own append-only evidence.

The current implementation supports a scenario-scoped evidence path plus instant-post-trade finality proof:

- settlement obligations materialized from persisted runtime trades
- deterministic settlement instructions and attempts
- cash/security leg outcomes
- append-only ledger proof entries
- `SETTLED` facts only after both legs and ledger proof pass
- cash-leg and security-leg break facts when seeded resources are insufficient
- repair facts that unlock deterministic retry attempts
- `RESOLVED` facts for repaired exception closure

This is not the full post-trade department yet. Allocation, confirmation, affirmation, clearing, novation, netting, exception UI, and broad analytics remain planned.

## GET /api/v1/settlement/facts/{scenarioRunId}

Returns the raw evidence trail for one scenario run. P2 scenario assertions and smoke reports use it to prove the settlement story from facts, not from a hand-written report.

Current fact families include:

- resource positions used by constrained instant-mode checks
- `SettlementObligationCreated`
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
trade -> obligation -> instruction -> attempt -> leg outcomes
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

## Internal Seed And Repair Commands

These routes are local/operator tooling, not public client APIs:

| Route | Purpose |
|---|---|
| `/internal/admin/settlement/facts` | Append a scenario settlement fact bundle for smoke/evidence setup |
| `/internal/admin/settlement/obligations/materialize` | Materialize trade-to-settlement obligations for a scenario run |
| `/internal/admin/settlement/repairs/cash` | Post buyer cash resource repair plus `SettlementRepairPosted` |
| `/internal/admin/settlement/repairs/security` | Post seller security resource repair plus `SettlementRepairPosted` |

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
