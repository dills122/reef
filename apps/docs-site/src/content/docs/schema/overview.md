---
title: Schema Overview
description: Schema map across API, runtime, and persistence domains.
banner:
  content: Design baseline, not locked DDL. The source blueprint tracks live migrations where schemas already exist.
---

Reef keeps storage split by responsibility. Some tables are durable facts: what was accepted, matched, settled, or rejected. Others are projections: useful views that can be rebuilt from facts. That split lets the local stack stay simple today while leaving room to separate domains later.

| Schema | Status | Owns |
|---|---|---|
| [`runtime`](../runtime-schema/) | built | canonical venue facts, command outcomes, lifecycle/order-book projections, market-data snapshots, lifecycle events, outbox |
| [`boundary`](../boundary-auth-admin-schema/) | built | API idempotency, command capture, stream intake, risk controls, circuit breakers, price collars, rejection audit |
| [`auth`](../boundary-auth-admin-schema/) | built | roles and actor-role bindings |
| [`admin`](../boundary-auth-admin-schema/) | built | policy/config operations, including post-trade profiles |
| `command_log` | built | append-only inbound command capture, active work queue, terminal results |
| `account` | [planned](../planned-schema/) | users, bots, accounts, immutable ledger entries, holds, risk limits |
| `settlement` | built, expanding | allocations, confirmations, affirmations, clearing acceptance/rejection, novation, obligations, instructions, attempts, leg outcomes, ledger proof, settlements, breaks, repairs, resolutions, and exception-queue projection; netting depth remains planned |
| `arena` | built, optional | bot registry, versions, entitlements, submission admissions, qualification/operator facts, run records/results, and enforcement events; owned by the Arena overlay |
| `stock_data` | built | per-game-seed stock snapshot facts for deterministic replay; provider calls happen once per `gameSeedId` |
| `market_data` | runtime-backed | top-of-book/depth/read slices live in `runtime`; no dedicated schema today |
| `orchestration` | built | scheduler definitions and run-state |
| `analytics` | initial slice built | simulation-run exports; broader reporting facts/views remain planned |

Arena registry, qualification, and run-record data lives outside the trading hot path on purpose. Bot metadata and leaderboards should not slow order intake or matching. See [How The Game Works](../../arena/how-the-game-works/).

## Identifier Baseline

Most live hot-path tables use `TEXT` identifiers today, with typed `_uuid`, `_num`, and `_ts` companion columns where native ordering/indexing is needed. Newer Postgres-generated identifiers use `gen_random_uuid()` on Postgres 16. UUIDv7 is a tracked future optimization, not a current requirement.

## Routine Ownership

- `runtime` routines: append event + outbox, outbox claim/publish/retry/dead-letter, lifecycle mutation commits
- `command_log` routines: append + duplicate replay, integrity audit summary
- `orchestration` routines: enqueue run, claim due run, mark success/retry/fail, heartbeat
- `analytics` routines (planned beyond exports): refresh daily facts, validation row-count checks

## Learn More

- `docs/DATA_DOMAIN_SCHEMA_BLUEPRINT.md` — full blueprint, source for this section
- `docs/TRADING_MARKET_DATA_BOUNDARIES.md` — why order-entry, market-data, account, settlement, and analytics stay separate planes
- `docs/SETTLEMENT_EXCEPTION_FACTS.md` — minimal P2 settlement fact slice
- `docs/STOCK_DATA_SEEDING_PLAN.md` — seed-time stock reference data boundary
- [Wire Contracts](../contracts/) — the protobuf shapes that produce these rows
