---
title: Planned Schema
description: account, settlement, market_data, and analytics — design targets, not built yet.
banner:
  content: Nothing on this page is implemented yet. This is a design baseline for future work.
---

These schemas are candidate table lists from the data blueprint. Names and columns will shift during implementation.

## Account Schema (Planned)

Owns users, bots, accounts, credits, holds, and risk controls. Current balances should derive from immutable ledger entries plus open holds/reservations.

Candidate tables: `account.accounts`, `account.bots`, `account.bot_configs`, `account.ledger_entries`, `account.balance_snapshots`, `account.order_holds`, `account.risk_limits`, `account.bot_state_changes`.

Intake risk checks use a hot, bounded account/risk view before durable order acceptance; settlement performs final enforcement from matched facts and can block fulfillment, create exceptions, or disable bots.

## Settlement Schema (Planned)

Consumes canonical venue facts and creates post-trade facts. Must not mutate matching history.

Candidate tables: `settlement.obligations`, `settlement.allocations`, `settlement.confirmations`, `settlement.fulfillment_steps`, `settlement.breaks`, `settlement.repairs`, `settlement.exception_cases`.

## Market Data Schema (Partially Built)

A separate query/read domain from order entry. The current slice (`runtime.order_lifecycle_state`, `runtime.market_data_snapshots`) covers top-of-book and bounded depth — see [Market Data API](/api/market-data/). Remaining candidate tables: `market_data.book_snapshots`, `market_data.depth_snapshots`, `market_data.recent_trades`, `market_data.intraday_bars`, `market_data.historical_bars`, `market_data.feed_watermarks`.

## Analytics Schema (Planned)

Daily derived tables: `analytics.trade_fact_daily` (partitioned by `market_date`), `analytics.order_lifecycle_fact_daily`, `analytics.event_counts_daily`, `analytics.exception_fact_daily`.

Suggested views: `analytics.vw_intraday_tps`, `analytics.vw_order_to_trade_latency`, `analytics.vw_event_backlog_health`.

## Orchestration Schema (Planned)

`orchestration.scheduled_jobs`, `orchestration.job_runs`, `orchestration.job_artifacts`.

## Archive Layout (File Store, Planned)

```text
archive/market_date=YYYY-MM-DD/events-*.jsonl.gz
archive/market_date=YYYY-MM-DD/trades-*.jsonl.gz
archive/market_date=YYYY-MM-DD/manifest.json
```

Manifest minimum: run id, source reconciliation counts, file list, per-file row counts, SHA-256 checksums, export start/end timestamps.

## Open Questions

- Numeric precision defaults for price/quantity per instrument class
- When to introduce partitioning for `runtime_events` and `event_outbox`
- Analytics schema in same instance vs. separate Postgres instance trigger point
- UUIDv7 migration trigger and rollout plan

## Learn More

- `docs/DATA_DOMAIN_SCHEMA_BLUEPRINT.md` — full blueprint (source for this page)
- [Overview](/schema/overview/) — what's built vs. planned across all schemas
