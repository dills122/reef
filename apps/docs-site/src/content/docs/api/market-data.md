---
title: Market Data API
description: /api/v1/market-data/snapshots and depth — top-of-book and bounded depth reads.
banner:
  content: First conservative slice only. Backed by a lifecycle-state projection, not live matching-engine order-book streaming.
---

Market data is a read/query plane, separate from order-entry command paths. Reads are backed by `runtime.order_lifecycle_state` (rebuilds open/filled/cancelled order state) and `runtime.market_data_snapshots`.

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

## Learn More

- [API Overview](/api/overview/) — boundary contract shared across all routes
- `docs/TRADING_MARKET_DATA_BOUNDARIES.md` — why order-entry and market-data are kept as separate planes
