---
title: Market Data & Read APIs
description: Market-data, history, own-order, and data-availability reads.
banner:
  content: First conservative slice only. Reads are runtime-backed or projection-backed, not live matching-engine order-book streaming.
---

Market data is a read/query plane, separate from order-entry command paths. Current reads are backed by runtime facts and projections such as `runtime.order_lifecycle_state`, `runtime.market_data_snapshots`, and `runtime.trades`.

## GET /api/v1/market-data/snapshots/{instrumentId}

Returns the top-of-book snapshot: remaining open `LIMIT` quantity for the instrument.

Query params: `projectionName` (default `market-data-top-of-book`).

`404` with `{"error": "market data snapshot not found"}` if no snapshot exists yet for the instrument.

## POST /api/v1/market-data/snapshots

Triggers a refresh of the snapshot projection.

Query params: `projectionName` (default `market-data-top-of-book`), `sourceProjectionName` (default `runtime-normalized-venue-outcomes`).

An opt-in background loop (`MARKET_DATA_PROJECTOR_ENABLED=true`) also refreshes snapshots continuously and reports status at `/internal/market-data/projector/status`.

## GET /api/v1/market-data/depth/{instrumentId}

Returns bounded lifecycle-backed depth.

Query params: `levels` (default `5`), `projectionName` (default `market-data-depth`), `sourceProjectionName` (default `runtime-normalized-venue-outcomes`).

`404` with `{"error": "market data depth not found"}` if depth cannot be computed for the instrument.

## GET /api/v1/market-data/trades/{instrumentId}

Returns public trade tape rows for one instrument, most recent first. The response exposes trade id, price, quantity, currency, occurred-at time, and a monotonic sequence cursor.

The public tape deliberately excludes counterparty, order, and participant identity.

Query params: `limit` (max `500`), `before` sequence cursor.

## GET /api/v1/market-data/bars/{instrumentId}

Returns intraday OHLCV bars aggregated from durable runtime trades.

Query params: `interval` (`1m`, `5m`, `15m`, `1h`), `start`, `end`.

## GET /api/v1/orders/current

Returns participant-scoped current own-order state. Supports bounded reads for bot/user contexts.

Query params: optional `instrumentId`, optional `limit`.

## GET /api/v1/orders/history

Returns participant-scoped own-order history. Supports optional `instrumentId` and bounded `limit`.

## GET /api/v1/orders/fills

Returns participant-scoped fills by joining runtime orders and executions. Supports optional `instrumentId` and bounded `limit`.

## GET /api/v1/data/availability

Returns the current read-surface inventory: endpoint, source type, freshness model, visibility scope, required/optional filters, projection lag, and watermark where available. Scenario and bot reports use this to distinguish durable fact reads from projection freshness. The inventory now includes settlement facts as scenario evidence.

## Learn More

- [API Overview](../overview/) — boundary contract shared across all routes
- [Settlement APIs](../settlement/) — scenario settlement evidence and ledger proof reads
- `docs/TRADING_MARKET_DATA_BOUNDARIES.md` — why order-entry and market-data are kept as separate planes
