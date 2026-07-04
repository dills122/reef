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

The initial implemented snapshot is `runtime.market_data_snapshots`, refreshed from rebuildable `runtime.order_lifecycle_state` rows and exposed through `/api/v1/market-data/snapshots/{instrumentId}`. Bounded depth reads are exposed through `/api/v1/market-data/depth/{instrumentId}` and aggregate remaining open lifecycle quantity by price. Both surfaces carry source projection, lag, and refresh metadata. Venue-session-specific depth and fully incremental market-data projection remain follow-on work.

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
