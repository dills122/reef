---
title: Runtime Schema
description: Canonical venue facts and operational lifecycle projections.
---

`runtime` has two distinct responsibilities: compact canonical persistence from durable `VenueEventBatch` output, and rebuildable operational projections for order/status/query surfaces. Synchronous normalized order/trade/execution/UI writes never go back into the matching-engine hot path.

## Canonical Venue Facts

**`runtime.canonical_venue_event_batches`** — compact durable materialization of matching-engine `VenueEventBatch` output: batch identity, command/event stream identity, partition/offset or sequence, payload checksum, payload format/version, command count, replay-safe payload facts.

**`runtime.canonical_command_outcomes`** — command-level lookup/replay rows projected from canonical event batches: command id, command type, result status, reject code, order/instrument identifiers, stream sequence/offset, result payload, payload hash. Backs the [Command Status API](../../api/commands/).

## Operational Order Lifecycle Projections

These are query/read projections unless a decision explicitly promotes a field into canonical persistence.

**`runtime.orders`** — `order_id uuid pk`, `engine_order_id text unique`, `participant_id uuid`, `account_id uuid`, `instrument_id uuid`, `side text`, `order_type text`, `quantity numeric(20,0)`, `limit_price numeric(20,8)`, `currency char(3)`, `time_in_force text`, `status text`, `accepted_at timestamptz`, `updated_at timestamptz`.

**`runtime.executions`** — `execution_id uuid pk`, `event_id uuid unique`, `order_id uuid`, `instrument_id uuid`, `quantity numeric(20,0)`, `execution_price numeric(20,8)`, `currency char(3)`, `occurred_at timestamptz`.

**`runtime.trades`** — `trade_id uuid pk`, `event_id uuid unique`, `execution_id uuid`, `buy_order_id uuid`, `sell_order_id uuid`, `instrument_id uuid`, `quantity numeric(20,0)`, `price numeric(20,8)`, `currency char(3)`, `occurred_at timestamptz`.

Current read APIs use these runtime facts/projections for public trade tape, intraday bars, top-of-book/depth, and participant-scoped own-order reads. Dedicated `market_data` schema objects remain a planned extraction target.

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

**`runtime.runtime_events`** — `event_id uuid pk`, `event_type text`, `order_id uuid`, `trace_id uuid`, `causation_id uuid`, `correlation_id uuid`, `actor_id text`, `producer text`, `schema_version text`, `sequence_number bigint`, `payload_json jsonb`, `occurred_at timestamptz`, `created_at timestamptz`. Indexes: `(occurred_at)`, `(trace_id, sequence_number)`, `(order_id, occurred_at)`.

**`runtime.event_outbox`** — `outbox_id bigserial pk`, `event_id uuid unique`, `stream text`, `subject text`, `payload_json jsonb`, `status text check ('pending','published','retry_wait','dead_letter')`, `attempt_count int`, `next_attempt_at timestamptz`, `last_error text`, `created_at timestamptz`, `published_at timestamptz`. Index: `(status, next_attempt_at)`.

## Learn More

- `docs/DATA_DOMAIN_SCHEMA_BLUEPRINT.md` — full blueprint (source for this page)
- [API Orders](../../api/orders/) — the request/response contract these tables back
- [Wire Contracts](../contracts/) — protobuf shapes materialized into these tables
