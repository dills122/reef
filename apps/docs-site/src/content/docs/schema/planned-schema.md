---
title: Planned Schema
description: account and future analytics/archive surfaces that remain design targets.
banner:
  content: Mostly design baseline. Live runtime, settlement, arena, stock-data, orchestration, analytics export, and market-data projection tables are covered in the source blueprint.
---

These sections are candidate table lists or future expansion areas from the data blueprint. Names and columns will shift during implementation.

## Account Schema (Planned)

Owns users, bots, accounts, credits, holds, and risk controls. Current balances should derive from immutable ledger entries plus open holds/reservations.

Candidate tables: `account.accounts`, `account.bots`, `account.bot_configs`, `account.ledger_entries`, `account.balance_snapshots`, `account.order_holds`, `account.risk_limits`, `account.bot_state_changes`.

Intake risk checks use a hot, bounded account/risk view before durable order acceptance; settlement performs final enforcement from matched facts and can block fulfillment, create exceptions, or disable bots.

## Settlement Expansion

The `settlement` schema is no longer purely planned: obligation, instruction, attempt, break, resource-position, resource-fail, and security-repair facts exist in migrations. This page only tracks broader future expansion beyond the current P2/instant-finality/resource-fail slices.

Future candidate areas: allocation, confirmation, clearing-specific fulfillment steps, richer exception queues, and operator-facing exception UI.

The current implementation still must not mutate matching history. It consumes canonical venue facts and creates post-trade facts downstream.

## Market Data Extraction

There is no dedicated `market_data` schema today. The current runtime-backed slice covers order lifecycle, top-of-book snapshots, bounded depth, public trade tape, participant-scoped own-order reads, and data availability — see [Market Data API](../../api/market-data/).

If a future extraction is justified, candidate tables remain `market_data.book_snapshots`, `market_data.depth_snapshots`, `market_data.recent_trades`, `market_data.intraday_bars`, `market_data.historical_bars`, and `market_data.feed_watermarks`.

## Analytics Expansion

`analytics.simulation_run_exports` exists today. The daily derived facts and views below remain planned.

Daily derived tables: `analytics.trade_fact_daily` (partitioned by `market_date`), `analytics.order_lifecycle_fact_daily`, `analytics.event_counts_daily`, `analytics.exception_fact_daily`.

Suggested views: `analytics.vw_intraday_tps`, `analytics.vw_order_to_trade_latency`, `analytics.vw_event_backlog_health`.

## Orchestration Expansion

`orchestration.scheduled_jobs`, `orchestration.job_runs`, and `orchestration.job_artifacts` exist today. Future work should add jobs only through that scheduler/job-runner boundary.

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
- Consolidation path for legacy canonical venue tables versus `runtime.canonical_command_outcomes`
- UUIDv7 migration trigger and rollout plan

## Learn More

- `docs/DATA_DOMAIN_SCHEMA_BLUEPRINT.md` — full blueprint (source for this page)
- `docs/SETTLEMENT_EXCEPTION_FACTS.md` — scoped P2 settlement facts before broader post-trade expansion
- [Overview](../overview/) — what's built vs. planned across all schemas
