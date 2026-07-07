---
title: Trading API Overview
description: External API boundary philosophy, versioning, auth, idempotency, and rate limits.
banner:
  content: Routes below reflect the current implementation checkpoint, not a stable contract. Breaking changes are still expected pre-release.
---

External clients integrate with a stable `/api/v1` boundary, implemented today in the Kotlin platform runtime, kept separate from internal service transport (engine gRPC/HTTP, stream ingress). Internal contracts can evolve independently; the public boundary changes deliberately and stays versioned.

Reef exposes two product-facing API families:

- venue intake and trading information for orders, command status, participant order state, executions, trade tape, and current market data
- admin/data for operator-approved administration plus intraday and historical data access

Internal service/control interfaces are not product APIs. They should sit behind gRPC/protobuf, durable messaging, or a gateway-backed admin/data adapter with auth, authorization, audit, and versioning. Canonical policy: `docs/API_SURFACE_POLICY.md`.

## Required Headers (Writes)

| Header | Required | Purpose |
|---|---|---|
| `X-Client-Id` | yes | Client identity; missing → `401 CLIENT_ID_REQUIRED` |
| `Idempotency-Key` | yes | Deduplication key, scoped to `(clientId, route, idempotencyKey)` |
| `Authorization` | per auth hook config | Bearer token/API key, validated by the configured auth hook |
| `X-Correlation-Id` | optional | Propagated correlation ID for tracing; generated if absent |

## What Happens Before A Write Is Accepted

Every mutating request passes, in order: request validation (schema + semantic) → command circuit breaker check → instrument price collar check → account/bot risk pre-check → idempotency reservation → abuse-protection check → durable command capture/publish. A non-`allow` decision at any boundary stage does **not** append a command-log row, reserve stream intake, or publish a command — rejections are cheap and don't consume durable resources.

`202 Accepted` (in stream-ack/async modes) or `200 OK` (in sync-result mode) only comes back once the configured durable ingress has acknowledged the command — this contract holds regardless of internal processing mode.

## Error Envelope

```json
{
  "code": "VALIDATION_ERROR",
  "message": "...",
  "correlationId": "..."
}
```

Domain rejections (e.g. a business-rule reject) are distinguished from transport/system failures (e.g. `503` runtime unavailable) by status code and `code` field.

## Command Processing Modes

The runtime supports multiple internal processing modes behind the same external contract — `sync-result` (deterministic baseline), `captured-ack` (Postgres fallback), `stream-ack` (durable Kafka/JetStream-backed, the active high-throughput target), and `accepted-async`. Clients never need to know which mode is active; the response contract (`200`/`202` + status lookup) stays stable across modes.

## Routes At A Glance

| Route | Method | Purpose |
|---|---|---|
| [`/api/v1/orders/submit`](../orders/) | POST | Submit a new order |
| [`/api/v1/orders/modify`](../orders/) | POST | Modify quantity/price of a resting order |
| [`/api/v1/orders/cancel`](../orders/) | POST | Cancel an order |
| [`/api/v1/orders/lifecycle-state`](../orders/) | POST | Rebuild order lifecycle projection |
| [`/api/v1/orders/current`](../market-data/) | GET | Participant-scoped current own-order state |
| [`/api/v1/orders/history`](../market-data/) | GET | Participant-scoped own-order history |
| [`/api/v1/market-data/snapshots/{instrumentId}`](../market-data/) | GET | Top-of-book snapshot |
| [`/api/v1/market-data/snapshots`](../market-data/) | POST | Refresh snapshot projection |
| [`/api/v1/market-data/depth/{instrumentId}`](../market-data/) | GET | Bounded depth snapshot |
| [`/api/v1/market-data/trades/{instrumentId}`](../market-data/) | GET | Public trade tape |
| [`/api/v1/market-data/bars/{instrumentId}`](../market-data/) | GET | Intraday OHLCV bars |
| [`/api/v1/data/availability`](../market-data/) | GET | Read-surface availability and freshness inventory |
| [`/api/v1/commands/{commandId}`](../commands/) | GET | Command status lookup |

`/internal/*` routes exist only as local/migration operator tooling and diagnostics — see [Internal & Admin Routes](../internal-admin/). They are not part of the public client contract and must not be exposed raw outside private operator networks.

## Learn More

- `docs/steering/external-api-boundary.md` — full boundary steering (source for this page)
- `docs/CURRENT_STATUS.md` — which processing mode is active by default today
