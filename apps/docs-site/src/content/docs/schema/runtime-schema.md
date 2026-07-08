---
title: Runtime Schema
description: Canonical venue facts and operational lifecycle projections.
---

`runtime` has three responsibilities: compact canonical persistence from durable `VenueEventBatch` output, rebuildable operational projections for order/status/query surfaces, and the runtime event backbone/outbox. Synchronous normalized order/trade/execution/UI writes never go back into the matching-engine hot path.

## Canonical Venue Facts

**`runtime.canonical_venue_event_batches`** — compact durable materialization of matching-engine `VenueEventBatch` output: batch identity, command/event stream identity, partition/offset or sequence, payload checksum, payload format/version, command count, replay-safe payload facts.

**`runtime.canonical_command_outcomes`** — command-level lookup/replay rows projected from canonical event batches: command id, command type, result status, reject code, order/instrument identifiers, stream sequence/offset, result payload, payload hash. Backs the [Command Status API](../../api/commands/).

**`runtime.canonical_command_results` / `runtime.canonical_venue_events`** — earlier append-only canonical tables used by legacy/compat append/project routines. Do not add new consumers without checking the consolidation status in the source blueprint.

## Operational Order Lifecycle Projections

These are query/read projections unless a decision explicitly promotes a field into canonical persistence. Live hot-path fields are generally stored as `TEXT` for compatibility, with typed companion columns added for native sorting/indexing.

**`runtime.orders`** — immutable accepted-order facts keyed by `order_id text`; includes engine, participant/account, instrument, side/type, quantity, limit, currency, time-in-force, accepted-at, client-order, run, and venue-session fields. Current status does not live here.

**`runtime.order_lifecycle_state`** — rebuildable current-state projection for status, original/remaining/filled quantities, last event time, and book-facing indexes.

**`runtime.market_data_snapshots`** — top-of-book snapshot projection. There is no dedicated `market_data` schema today.

**`runtime.executions`** and **`runtime.trades`** — projected execution/trade facts, including typed companions and a monotonic trade-tape cursor on trades.

**`runtime.submit_results`**, **reference tables**, and **`runtime.projection_watermarks`** support submit outcome lookup, reference data, and projector progress.

Current read APIs use these runtime facts/projections for public trade tape, top-of-book/depth, participant-scoped own-order reads, and data availability. Dedicated `market_data` schema objects remain a possible extraction target, not a live schema.

Settlement obligation materialization reads persisted `runtime.trades` plus accepted buy/sell orders as canonical trade evidence. Settlement writes append-only post-trade facts downstream; runtime trade and matching history are not mutated.

## Settlement Fact Store

The first settlement slice is scenario-scoped and evidence-oriented. Current fact families cover:

- resource positions
- obligations
- instructions
- attempts
- leg outcomes
- ledger entries
- settlements
- breaks
- repairs
- resolutions

Public read APIs expose the append-only facts, projected obligation state, and replayable ledger proof through [Settlement APIs](../../api/settlement/). Full post-trade tables for allocation, confirmation, clearing, novation, and netting remain planned.

## Event & Outbox

**`runtime.runtime_events`** — text-keyed runtime event facts with trace/causation/correlation ids, actor, producer, schema version, sequence number, payload, text occurred-at, created-at, and typed companion columns.

**`runtime.event_outbox`** — relay rows with stream/subject/payload, `pending`/`published`/`retry_wait`/`dead_letter` status, attempt tracking, worker id, next-at, last-error, created-at, and published-at fields. Claim/publish/retry/dead-letter routines own state transitions.

## Learn More

- `docs/DATA_DOMAIN_SCHEMA_BLUEPRINT.md` — full blueprint (source for this page)
- [API Orders](../../api/orders/) — the request/response contract these tables back
- [Wire Contracts](../contracts/) — protobuf shapes materialized into these tables
