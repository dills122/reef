---
title: Schema Overview
description: Schema map across API, runtime, and persistence domains.
banner:
  content: Design baseline, not locked DDL. The source blueprint tracks live migrations where schemas already exist.
---

Reef's schema splits into a few Postgres schemas, each with distinct ownership. Design goals: support a single Postgres deployment today, preserve a low-friction path to domain-specific DB split, keep the operational write path fast and auditable, and separate hot operational, analytics, and archive responsibilities.

| Schema | Status | Owns |
|---|---|---|
| [`runtime`](../runtime-schema/) | built | canonical venue facts, command outcomes, lifecycle/order-book projections, market-data snapshots, lifecycle events, outbox |
| [`boundary`](../boundary-auth-admin-schema/) | built | API idempotency, command capture, stream intake, risk controls, circuit breakers, price collars, rejection audit |
| [`auth`](../boundary-auth-admin-schema/) | built | roles and actor-role bindings |
| [`admin`](../boundary-auth-admin-schema/) | built | policy/config operations, including post-trade profiles |
| `command_log` | built | append-only inbound command capture, active work queue, terminal results |
| `account` | [planned](../planned-schema/) | users, bots, accounts, immutable ledger entries, holds, risk limits |
| `settlement` | built, expanding | obligations, instructions, attempts, breaks, resource positions/fails, repair actions |
| `arena` | built | bot registry, bot versions, qualification reports, run records/results, enforcement events |
| `stock_data` | built | per-game-seed stock snapshot facts for deterministic replay |
| `market_data` | runtime-backed | top-of-book/depth/read slices live in `runtime`; no dedicated schema today |
| `orchestration` | built | scheduler definitions and run-state |
| `analytics` | initial slice built | simulation-run exports; broader reporting facts/views remain planned |

Arena registry/qualification/run-record schema (bot identity, versions, approval lifecycle, operator decisions) is a separate durable arena datasource, intentionally kept out of the trading hot path — see [How The Game Works](../../arena/how-the-game-works/).

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
- [Wire Contracts](../contracts/) — the protobuf shapes that produce these rows
