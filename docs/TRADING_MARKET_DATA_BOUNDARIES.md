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

## Post-Trade Re-Entry Criteria

Historical note: this section originally gated broad post-trade work (real account-ledger mutation, an operator exception workbench, and broad settlement analytics) behind the criteria below. Re-entry has since happened: `D-050` (accepted; see [`DECISIONS.md`](./DECISIONS.md#d-050-settlement-instant-post-trade-profile)) authorized real ledger mutation for the `instant-post-trade` profile, and obligation materialization now writes real append-only ledger entries, with obligation and ledger query surfaces shipped (see [`SETTLEMENT_CLEARING_STRATEGY.md`](./SETTLEMENT_CLEARING_STRATEGY.md#obligation-materialization)). An operator exception workbench UI and broad settlement analytics remain not built. The criteria below are kept as a historical record of what gated re-entry, not as a current restriction on ledger mutation.

Post-trade work remains gated until the venue-core path can prove causation and replay. Do not restart broad allocation, confirmation, settlement, clearing, account-ledger, or UI work until all of these are true:

1. `P1_GOLDEN_HIDDEN_CROSS_T1` is aligned with [`SCENARIO_CONTRACTS.md`](./SCENARIO_CONTRACTS.md), golden artifacts are refreshed, replay is stable, hidden-liquidity visibility assertions pass, and order lifecycle states match the contract.
2. `P2_SETTLEMENT_BREAK_REPAIR` is aligned with [`SCENARIO_CONTRACTS.md`](./SCENARIO_CONTRACTS.md) at least at the scenario-contract level, with stubbed/manual repair clearly marked and no fake account-ledger side effects.
3. The `direct-materializer-short` gate passes with final accepted/materialized/projected gap `0`, worker/materializer/projector lag draining to `0`, and replay/checksum clean.
4. Bot/user read surfaces are classified by `/api/v1/data/availability` and the read-surface inventory in this document, so scenario assertions know which reads prove durable facts versus projection freshness.
5. Any post-trade facts introduced by the first slice reference canonical venue facts and do not mutate matching history.

The first allowed post-trade slice is P2-only settlement exception facts:

- settlement obligation facts
- cash-leg break facts
- manual repair facts
- resolved/closed exception facts
- state-transition tests for `trade -> obligation -> break -> repair -> resolved`

The fact shape for this slice lives in [`SETTLEMENT_EXCEPTION_FACTS.md`](./SETTLEMENT_EXCEPTION_FACTS.md).

Explicitly out of scope for the first re-entry slice:

- full allocation workflow
- full confirmation/affirmation workflow
- clearing/netting
- ~~real account ledger mutation~~ — re-entered under `D-050`; obligation materialization now performs real append-only ledger mutation for settled instant-post-trade obligations
- buying-power/hold enforcement after match
- operator exception workbench UI — still not built
- broad settlement analytics — still not built

Those modules could start only after the P2-only facts proved causation through real canonical trade references. That happened: obligation materialization consumes canonical trades and orders, and `D-050` accepted real ledger mutation as shipped scope. Full allocation/confirmation/affirmation workflows, clearing/netting, buying-power/hold enforcement, an operator exception workbench UI, and broad settlement analytics remain future work unless separately planned.

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

The initial implemented snapshot is `runtime.market_data_snapshots`, kept live from `runtime.order_lifecycle_state` and exposed through `/api/v1/market-data/snapshots/{instrumentId}`. Bounded depth reads are exposed through `/api/v1/market-data/depth/{instrumentId}` and aggregate remaining open lifecycle quantity by price. Both surfaces carry source projection, lag, and refresh metadata. The optional `MARKET_DATA_PROJECTOR_ENABLED` background loop keeps top-of-book snapshots current incrementally (dirty-tracked by instrument, not a full recompute every cycle); depth still remains bounded read-time aggregation until an incremental depth projector is justified. Depth is currently instrument-scoped. Venue-session-specific depth should not be claimed until projected order facts persist `venueSessionId` or another durable session key that can filter lifecycle rows.

`/api/v1/market-data/trades/{instrumentId}` is the first "recent trades" surface: a public trade tape (price, quantity, currency, occurredAt, tradeId, monotonic `sequence` cursor), most-recent-first, bounded by `limit` with `before=<sequence>` pagination. It reads durable `runtime.trades` rows directly — no projector needed, since trade facts are already durably and idempotently written by the existing submit-outcome persist functions. Counterparty order/participant identity is deliberately excluded to match the visible-data policy below. The response includes a `meta` block identifying `runtime.trades` as the source and `durable fact rows` as the freshness model.

`/api/v1/market-data/bars/{instrumentId}` is the first "intraday bars" surface, aggregating `runtime.trades` into OHLCV buckets via Postgres `date_bin` (interval `1m`/`5m`/`15m`/`1h`, bounded by `start`/`end`), matching the Bot SDK's `BotHistoricalBarsRequestV1`/`HistoricalBarV1` contract. `/api/v1/orders/current` and `/api/v1/orders/history` add participant-scoped own-order reads (joining `runtime.orders` and `runtime.order_lifecycle_state`) — the prior only order read was an unscoped all-participants dump, which would have violated "own open orders" visibility if bots had read it directly. Both own-order endpoints accept optional `instrumentId` and `limit` query parameters so bots can keep history reads bounded at the projection boundary. These responses also include `meta` blocks so bots and users can distinguish durable-fact reads from lifecycle projection reads.

`/api/v1/data/availability` is the side API inventory for bots, users, and test harnesses. It reports each current read surface, its endpoint, source table/projection, freshness model, visibility scope, required/optional query filters, and the current projection lag/watermark when a projection defines freshness. This endpoint is not a production throughput claim by itself; it is the contract that tells clients which facts are direct durable rows, which facts are rebuilt projections, and where lag must be considered.

`packages/bot-sdk/src/live-client.ts` is the first Bot SDK client that calls these market-data/order-read endpoints over real HTTP rather than fixtures. `runner.ts`, `strategy-runner.ts`, and `hosted-runner.ts` accept optional `readClients`, so operators can keep deterministic fixture mode by default or inject live market-data, historical, and own-order clients for live smoke or hosted artifact runs.

Later, real-time feeds can be added from event streams or specialized market-data publishers. They should still consume canonical or event-stream facts, not the engine's mutable book internals directly.

External stock snapshots used to seed new simulation/game state are a separate
ingress concern, not part of the venue-local market-data API. The seed workflow
may call an external stock-data provider once during game creation, then must
persist the exact normalized seed snapshots for replay and audit. Simulation,
matching, historical reads, and replay must consume those persisted seed facts
instead of calling external providers. The provider selection and fallback plan
lives in [`STOCK_DATA_SEEDING_PLAN.md`](./STOCK_DATA_SEEDING_PLAN.md).

## Read Surface Inventory

`/api/v1/data/availability` is the canonical runtime inventory for bot/user read surfaces. Documentation should mirror that endpoint rather than invent a second source of truth.

| Endpoint | Audience | Source | Source type | Freshness model | Lag/watermark | Visibility | Gate status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `/api/v1/data/availability` | bot/user/test harness | runtime availability report | diagnostic/internal contract | live diagnostic snapshot | includes projection lag/watermarks where available | public diagnostic | active |
| `/api/v1/market-data/snapshots/{instrumentId}` | bot/user | `runtime.market_data_snapshots` | projection-backed read | top-of-book projection watermark | market-data projector status | public market data | active |
| `/api/v1/market-data/depth/{instrumentId}` | bot/user | `runtime.order_lifecycle_state` | projection-backed read-time aggregation | bounded aggregation over lifecycle projection | source lifecycle projection watermark | public market data | active but conservative |
| `/api/v1/market-data/trades/{instrumentId}` | bot/user | `runtime.trades` | durable fact read | durable trade rows | none beyond trade persistence completeness | public market data | active |
| `/api/v1/market-data/bars/{instrumentId}` | bot/user | `runtime.trades` | durable fact aggregation | bounded aggregation over durable trade rows | none beyond trade persistence completeness | public market data | active |
| `/api/v1/orders/current` | bot/user | `runtime.orders + runtime.order_lifecycle_state` | projection-backed participant read | dirty-tracked lifecycle projection | lifecycle projection watermark | participant own orders | active |
| `/api/v1/orders/history` | bot/user | `runtime.orders + runtime.order_lifecycle_state` | projection-backed participant read | dirty-tracked lifecycle projection | lifecycle projection watermark | participant own orders | active |
| `/api/v1/orders/fills` | bot/user | `runtime.orders + runtime.executions` | durable participant read | durable execution rows scoped by participant order ownership | none beyond execution persistence completeness | participant own orders | active |
| `/api/v1/settlement/facts/{scenarioRunId}` | user/admin/test harness | `settlement append-only fact store` | durable fact read | durable fact rows | none beyond settlement fact persistence completeness | scenario settlement evidence | active |
| `/api/v1/settlement/obligations/{scenarioRunId}` | user/admin/test harness | settlement obligation projection over append-only facts | projection-backed read | derived from durable settlement facts | none beyond settlement fact persistence completeness | scenario settlement evidence | active |
| `/api/v1/settlement/ledger/{scenarioRunId}` | user/admin/test harness | append-only settlement ledger entry facts | projection-backed read | replayable participant/account/asset balances derived from ledger facts | none beyond settlement fact persistence completeness | scenario settlement evidence | active |
| `/api/v1/settlement/proof/{scenarioRunId}` | user/admin/test harness | settlement append-only fact store + replayable ledger projection | rebuildable proof projection | rebuildable proof projection | none beyond settlement fact persistence completeness | scenario settlement evidence | active |
| `/api/v1/settlement/score/{scenarioRunId}` | user/admin/test harness | settlement append-only fact store + ledger/obligation projections | rebuildable scoring projection | rebuildable scoring projection | none beyond settlement fact persistence completeness | scenario settlement score | active |
| `/orders`, `/trades`, `/events`, `/traces` legacy/internal surfaces | admin/test | runtime tables | direct runtime-table read | current local runtime state | varies by source | internal/admin | diagnostic |
| venue-session-specific depth | bot/user | planned projected lifecycle facts with session key | not built | not available | not available | public market data | deferred |
| account balances, holds, buying power | bot/user | settlement ledger projection (`/api/v1/settlement/ledger/{scenarioRunId}`) for scenario-scoped balances; broader per-account buying-power/hold enforcement remains planned | partially built | derived from durable ledger facts for scenario-scoped balances | none beyond settlement fact persistence completeness | participant/account scope | partial |
| settlement obligations/breaks/repairs | user/admin | settlement facts/projections (`/api/v1/settlement/facts`, `/api/v1/settlement/obligations`) | built | derived from durable settlement facts | none beyond settlement fact persistence completeness | participant/admin | active |

Read source definitions:

- durable fact read: reads canonical or durable domain rows and has no projection lag contract, though it still depends on the write path being complete.
- projection-backed read: reads rebuilt state and must expose lag/watermark metadata through response `meta` or `/api/v1/data/availability`.
- direct runtime-table read: useful for local/admin diagnostics but not a long-term bot/user contract unless promoted deliberately.
- not built/planned: documented target surface with no live API guarantee.
- diagnostic/internal only: usable for operators/tests, not public product readiness evidence.

Projection lag policy:

- Bot/user reads return data with `meta` freshness/lag information by default. Stale projection data is visible, not silently presented as fresh.
- `venue-core` mode reports projection lag but does not reject order intake solely because read models are behind.
- `control-room-fresh` mode may reject or throttle workflows when projection freshness is part of the operator contract.
- A promotion gate does not pass with final projection lag remaining after the configured drain window, even if projection lag was non-gating during intake.

Current known limitations:

- Own-order reads are treated as projection-backed contract surfaces even though they currently join `runtime.orders` and `runtime.order_lifecycle_state`; future implementation should preserve projection semantics, not expose unscoped runtime tables to bots.
- Depth is active but conservative. It is instrument-scoped, aggregates remaining lifecycle quantity at read time, and must not be used for venue-session-specific depth claims until projected lifecycle facts carry a durable session key.
- Trade tape and bars are durable fact reads from `runtime.trades`; they have no projector lag, but they only prove facts that have reached trade persistence.

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

The first bot/run analytics projection is `analytics.run_bot_performance_summaries`, exposed for admin reads through `/admin/v1/analytics/run-bot-summaries` and `/internal/admin/analytics/run-bot-summaries`. It is rebuilt during simulation-run export ingestion from `analytics.simulation_run_exports.summary.botResults` plus matching `settlementScore.participants` entries when present. Rows are keyed by `(run_id, bot_id)` and contain final equity, realized PnL, max drawdown, fail counts, command counts, and the raw settlement score participant summary. This is a query-optimized, non-authoritative projection: source reports, settlement facts, arena run results, and canonical venue/settlement facts remain the audit sources. Freshness is "last export ingested"; replaying the same export idempotently upserts the same run/bot row.

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
