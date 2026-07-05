---
title: Schema Overview
description: Schema map across API, runtime, and persistence domains.
banner:
  content: Design baseline, not locked DDL. Field types/names change as implementation catches up to the blueprint.
---

Reef's schema splits into a few Postgres schemas, each with distinct ownership. Design goals: support a single Postgres deployment today, preserve a low-friction path to domain-specific DB split, keep the operational write path fast and auditable, and separate hot operational, analytics, and archive responsibilities.

| Schema | Status | Owns |
|---|---|---|
| [`runtime`](/schema/runtime-schema/) | built | canonical venue facts, command outcomes, operational projections, lifecycle events, outbox |
| [`boundary`](/schema/boundary-auth-admin-schema/) | built | API idempotency records |
| [`auth`](/schema/boundary-auth-admin-schema/) | built | roles and actor-role bindings |
| [`admin`](/schema/boundary-auth-admin-schema/) | built | policy/config/audit operations (calendars, settlement-cycle profiles, override audit) |
| `account` | [planned](/schema/planned-schema/) | users, bots, accounts, immutable ledger entries, holds, risk limits |
| `settlement` | [planned](/schema/planned-schema/) | obligations, allocations, confirmations, fulfillment, breaks, repairs |
| `market_data` | partially built | top-of-book/depth snapshots today; recent trades, bars, historical query surfaces planned |
| `orchestration` | [planned](/schema/planned-schema/) | scheduler definitions and run-state |
| `analytics` | [planned](/schema/planned-schema/) | transformed reporting/query surfaces |

Arena registry/qualification/run-record schema (bot identity, versions, approval lifecycle, operator decisions) is a separate durable arena datasource, intentionally kept out of the trading hot path — see [How The Game Works](/arena/how-the-game-works/).

## Identifier Baseline

Standardize on UUIDs for primary identifiers, generated via Postgres `gen_random_uuid()` (UUIDv4) on Postgres 16. UUIDv7 is a tracked future optimization, not a current requirement.

## Routine Ownership

- `runtime` routines: append event + outbox, outbox claim/publish/retry/dead-letter, lifecycle mutation commits
- `orchestration` routines (planned): enqueue run, claim due run, mark success/retry/fail, heartbeat
- `analytics` routines (planned): refresh daily facts, validation row-count checks

## Learn More

- `docs/DATA_DOMAIN_SCHEMA_BLUEPRINT.md` — full blueprint, source for this section
- `docs/TRADING_MARKET_DATA_BOUNDARIES.md` — why order-entry, market-data, account, settlement, and analytics stay separate planes
- [Wire Contracts](/schema/contracts/) — the protobuf shapes that produce these rows
