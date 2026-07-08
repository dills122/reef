---
title: Trading API Overview
description: External API boundary philosophy, versioning, auth, idempotency, and rate limits.
banner:
  content: Routes below reflect the current implementation checkpoint, not a stable contract. Breaking changes are still expected pre-release.
---

The public API is the front door. Users, simulator runs, and Bot Arena traffic all come through versioned `/api/v1` routes instead of reaching into engine internals or database tables. That keeps the system inspectable: each accepted command has identity, idempotency, status, and trace data attached.

Reef exposes two product-facing API families:

- venue intake and trading information for orders, command status, participant order state, executions, trade tape, current market data, and scenario settlement evidence
- admin/data for operator-approved administration plus intraday and historical data access

Internal service/control interfaces are not product APIs. They can change faster and should sit behind gRPC/protobuf, durable messaging, or gateway-backed admin/data routes with auth, authorization, audit, and versioning. Canonical policy: `docs/API_SURFACE_POLICY.md`.

## Required Headers (Writes)

| Header | Required | Purpose |
|---|---|---|
| `X-Client-Id` | yes | Client identity; missing → `401 CLIENT_ID_REQUIRED` |
| `Idempotency-Key` | yes | Deduplication key, scoped to `(clientId, route, idempotencyKey)` |
| `Authorization` | per auth hook config | Bearer token/API key, validated by the configured auth hook |
| `X-Correlation-Id` | optional | Propagated correlation ID for tracing; generated if absent |

## What Happens Before A Write Is Accepted

Before an order can enter the venue, Reef checks whether it is well-formed, allowed for the actor, inside protective controls, and safe for the configured risk policy. Only then does it reserve idempotency and publish or capture the command durably.

A rejected command stops early and does not reserve stream intake or publish durable work. A `202 Accepted` response means durable intake acknowledged the command; it does not mean matching has finished yet.

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

The runtime can process commands in several internal modes: `sync-result` for deterministic baseline behavior, `captured-ack` as a Postgres fallback, `stream-ack` for durable stream-backed intake, and `accepted-async` for asynchronous handoff. Clients should not couple themselves to those internals. They submit, receive `200` or `202`, then use command status.

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
| [`/api/v1/settlement/facts/{scenarioRunId}`](../settlement/) | GET | Append-only settlement facts for one scenario run |
| [`/api/v1/settlement/obligations/{scenarioRunId}`](../settlement/) | GET | Current settlement obligation state projected from facts |
| [`/api/v1/settlement/ledger/{scenarioRunId}`](../settlement/) | GET | Replayable balances and settlement proof totals |
| [`/api/v1/commands/{commandId}`](../commands/) | GET | Command status lookup |

`/internal/*` routes exist only as local/migration operator tooling and diagnostics — see [Internal & Admin Routes](../internal-admin/). They are not part of the public client contract and must not be exposed raw outside private operator networks.

## Learn More

- `docs/steering/external-api-boundary.md` — full boundary steering (source for this page)
- `docs/CURRENT_STATUS.md` — which processing mode is active by default today
