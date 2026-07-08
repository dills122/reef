---
title: Schema Overview
description: Schema map across API, runtime, and persistence domains.
banner:
  content: Design baseline, not locked DDL. Field types/names change as implementation catches up to the blueprint.
---

Reef keeps storage split by responsibility. Some tables are durable facts: what was accepted, matched, settled, or rejected. Others are projections: useful views that can be rebuilt from facts. That split lets the local stack stay simple today while leaving room to separate domains later.

| Schema | Status | Owns |
|---|---|---|
| [`runtime`](../runtime-schema/) | built | canonical venue facts, command outcomes, operational projections, lifecycle events, outbox |
| [`boundary`](../boundary-auth-admin-schema/) | built | API idempotency records |
| [`auth`](../boundary-auth-admin-schema/) | built | roles and actor-role bindings |
| [`admin`](../boundary-auth-admin-schema/) | built | policy/config/audit operations (calendars, post-trade profiles, override audit) |
| `account` | [planned](../planned-schema/) | users, bots, accounts, immutable ledger entries, holds, risk limits |
| `settlement` | first slice built | scenario-scoped obligations, instructions, attempts, leg outcomes, ledger proof, settlements, breaks, repairs, resolutions; full allocation/confirmation/clearing/netting remains planned |
| `market_data` | partially built | top-of-book/depth snapshots plus public trades/bars through runtime-backed reads; dedicated market-data schema remains planned |
| `stock_data` | built service schema | seed snapshot batches for game/simulation creation; provider calls happen once per `gameSeedId` |
| `orchestration` | [planned](../planned-schema/) | scheduler definitions and run-state |
| `analytics` | [planned](../planned-schema/) | transformed reporting/query surfaces |

Arena registry, qualification, and run-record data lives outside the trading hot path on purpose. Bot metadata and leaderboards should not slow order intake or matching. See [How The Game Works](../../arena/how-the-game-works/).

## Identifier Baseline

Primary identifiers use UUIDs generated with Postgres `gen_random_uuid()` on Postgres 16. UUIDv7 remains a future optimization, not a current requirement.

## Routine Ownership

- `runtime` routines: append event + outbox, outbox claim/publish/retry/dead-letter, lifecycle mutation commits
- `orchestration` routines (planned): enqueue run, claim due run, mark success/retry/fail, heartbeat
- `analytics` routines (planned): refresh daily facts, validation row-count checks

## Learn More

- `docs/DATA_DOMAIN_SCHEMA_BLUEPRINT.md` — full blueprint, source for this section
- `docs/TRADING_MARKET_DATA_BOUNDARIES.md` — why order-entry, market-data, account, settlement, and analytics stay separate planes
- `docs/SETTLEMENT_EXCEPTION_FACTS.md` — minimal P2 settlement fact slice
- `docs/STOCK_DATA_SEEDING_PLAN.md` — seed-time stock reference data boundary
- [Wire Contracts](../contracts/) — the protobuf shapes that produce these rows
