# Trading and Market Data Boundaries

## Purpose

Define the first durable boundaries between order entry, matching, canonical venue persistence, settlement, account ledgers, market data, and analytics.

The goal is to keep the order path fast and deterministic while still giving bots and users usable live state, historical data, and account controls.

## Boundary Summary

```text
Trading / Order API
  -> intake auth, idempotency, account/risk pre-check
  -> durable command log
  -> matching-engine shard
  -> hot book mutation
  -> durable VenueEventBatch
  -> canonical venue persistence
  -> operational projections

Market Data / History API
  -> market-data projections and historical stores
  -> canonical venue facts when replay or audit is required

Settlement / Fulfillment
  -> canonical venue facts
  -> settlement obligations, fulfillment state, breaks, repairs

Account / Bot Ledger
  -> users, bots, accounts, credits, holds, ledger entries, limits
  -> intake risk pre-checks and downstream settlement enforcement

Analytics
  -> mirrored canonical, settlement, and account facts
  -> reporting, bot performance, PnL, leaderboards, and audit summaries
```

## Trading / Order API

The order API is the user and bot interface for trading intent.

It owns:

- submit order
- modify order
- cancel order
- command idempotency and status
- open-order and recent-order query surfaces
- routing metadata validation for deterministic matching lanes
- intake account/risk pre-checks

The order API must not make historical market-data queries, analytics reads, or settlement reports part of the matching hot path.

Normal user and bot reads for open orders should come from operational projections built from canonical venue facts. The matching engine can expose controlled snapshots for diagnostics and reconciliation, but external clients should not depend on the engine's private hot-book structure.

## Hot Book

The hot book is matching-engine private state.

It owns:

- resting order priority
- price-time matching
- direct cancel/modify lookup
- shard-local deterministic mutation
- event-batch production after accepted command processing

It is not a user-facing database. Its durability comes from the command log, durable `VenueEventBatch` output, snapshots as recovery accelerators, and replay/checksum verification.

## Canonical Venue Persistence

Canonical venue persistence is the durable bridge between matching and every downstream system.

It owns:

- `runtime.canonical_venue_event_batches`
- `runtime.canonical_command_outcomes`
- durable command-to-batch linkage
- result status, reject codes, order identifiers, instrument identifiers, hashes, and checksums
- replay and idempotent materialization evidence

This layer records what the venue/matching system did. It is distinct from both the hot book and settlement state.

Operational order projections, market-data projections, settlement workflows, and analytics should consume canonical venue facts rather than reaching into matching-engine memory.

## Account, Bot, and Risk Ledger

Account and bot state should be its own domain, not a column set attached to orders.

It owns:

- users and actors
- bots and bot configuration
- accounts and account membership
- credits, deposits, withdrawals, and adjustments
- immutable ledger entries
- derived balances
- open-order holds and reservations
- risk limits, bot enablement state, and shutdown controls

The intake path should run an initial account/risk pre-check before durable order acceptance. That check can reject or backpressure commands before they enter the command log when the platform knows the account or bot cannot safely trade.

The settlement path should run the final enforcement check after matching facts exist. Settlement can block fulfillment, raise breaks, apply holds/adjustments, and disable a bot when debt or risk thresholds are breached.

Derived balances are projections. Financial correctness should come from append-only ledger entries plus deterministic balance reconstruction, not from mutable balance rows alone.

## Settlement / Fulfillment

Settlement and fulfillment consume canonical venue facts and create their own post-trade facts.

They own:

- trade obligations
- allocation and confirmation state
- payment/delivery lifecycle
- clearing and netting state
- settlement breaks and repairs
- exception workflows
- final account enforcement after matching

Settlement must not mutate matching history. It can create settlement facts, exceptions, holds, repairs, and account ledger entries that reference canonical venue facts.

## Market Data / History API

The market-data API is separate from the order API.

It owns:

- live-ish top-of-book and depth snapshots
- recent trades and executions
- intraday bars and aggregates
- historical market data
- replayable market-data feeds
- query-optimized time-series views

For the first slice, market-data reads should be conservative and projection-based. Prefer snapshots and bounded refresh intervals over engine-coupled real-time reads. This gives bots useful data without adding load to the matching-engine hot path.

The initial implemented snapshot is `runtime.market_data_snapshots`, kept live from `runtime.order_lifecycle_state` and exposed through `/api/v1/market-data/snapshots/{instrumentId}`. Bounded depth reads are exposed through `/api/v1/market-data/depth/{instrumentId}` and aggregate remaining open lifecycle quantity by price. Both surfaces carry source projection, lag, and refresh metadata. The optional `MARKET_DATA_PROJECTOR_ENABLED` background loop keeps top-of-book snapshots current incrementally (dirty-tracked by instrument, not a full recompute every cycle); depth still remains bounded read-time aggregation until an incremental depth projector is justified.

`/api/v1/market-data/trades/{instrumentId}` is the first "recent trades" surface: a public trade tape (price, quantity, currency, occurredAt, tradeId, monotonic `sequence` cursor), most-recent-first, bounded by `limit` with `before=<sequence>` pagination. It reads durable `runtime.trades` rows directly — no projector needed, since trade facts are already durably and idempotently written by the existing submit-outcome persist functions. Counterparty order/participant identity is deliberately excluded to match the visible-data policy below. The response includes a `meta` block identifying `runtime.trades` as the source and `durable fact rows` as the freshness model.

`/api/v1/market-data/bars/{instrumentId}` is the first "intraday bars" surface, aggregating `runtime.trades` into OHLCV buckets via Postgres `date_bin` (interval `1m`/`5m`/`15m`/`1h`, bounded by `start`/`end`), matching the Bot SDK's `BotHistoricalBarsRequestV1`/`HistoricalBarV1` contract. `/api/v1/orders/current` and `/api/v1/orders/history` add participant-scoped own-order reads (joining `runtime.orders` and `runtime.order_lifecycle_state`) — the prior only order read was an unscoped all-participants dump, which would have violated "own open orders" visibility if bots had read it directly. Both own-order endpoints accept optional `instrumentId` and `limit` query parameters so bots can keep history reads bounded at the projection boundary. These responses also include `meta` blocks so bots and users can distinguish durable-fact reads from lifecycle projection reads.

`/api/v1/data/availability` is the side API inventory for bots, users, and test harnesses. It reports each current read surface, its endpoint, source table/projection, freshness model, and the current projection lag/watermark when a projection defines freshness. This endpoint is not a production throughput claim by itself; it is the contract that tells clients which facts are direct durable rows, which facts are rebuilt projections, and where lag must be considered.

`packages/bot-sdk/src/live-client.ts` is the first Bot SDK client that calls these market-data/order-read endpoints over real HTTP rather than fixtures — previously the SDK's `marketData`/`historical`/`orders.current()`/`history()` were fixture-only in every run mode, including "hosted"/"live"; only order actions reached a real venue. It is not yet wired into the tick-runner (`runner.ts`/`strategy-runner.ts`, still fixture-only), which remains a deliberate follow-up. Venue-session-specific depth remains follow-on work.

Later, real-time feeds can be added from event streams or specialized market-data publishers. They should still consume canonical or event-stream facts, not the engine's mutable book internals directly.

## Analytics and Mirrored Settlement Data

Analytics is a downstream plane.

It owns:

- denormalized trade facts
- bot performance and PnL summaries
- mirrored settlement facts for reporting
- leaderboards
- operational metrics
- audit and compliance reports

Analytics can lag and should be rebuildable. It must not define matching correctness, account correctness, or settlement correctness.

## Projection Families

Projection is a pattern, not one database.

Reef should expect several projection families:

- operational projections for order status, open orders, runtime events, and control-room views
- market-data projections for snapshots, depth, trades, bars, and history
- settlement projections for fulfillment workflow queues and exception handling
- account projections for current balances, available buying power, holds, and bot state
- analytics projections for reporting and long-horizon queries

Each projection family should have explicit freshness expectations, lag metrics, replay behavior, and ownership.

## Open Design Decisions

1. Account/risk pre-check shape:
   - start with simple cached limits and available-credit checks
   - keep the check before durable command acceptance
   - measure it as a hot-path phase once implemented
2. Bot shutdown rules:
   - define debt thresholds, risk thresholds, and manual override rules
   - record shutdown as account/bot ledger or admin/audit facts
3. Market-data freshness tiers:
   - snapshot and bounded-refresh reads first
   - event-stream real-time feed later
   - historical query store separately from order intake
4. Full order projection:
   - either include enough original submit metadata in `VenueEventBatch`
   - or join with durable command payload storage during projection/replay
