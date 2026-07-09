---
title: Orders API
description: /api/v1/orders/submit, modify, cancel, cancel-by-client-order, and lifecycle-state.
---

Order mutation routes require `X-Client-Id` and `Idempotency-Key` headers (see [API Overview](../overview/)). Field validation happens before any durable command acceptance.

## POST /api/v1/orders/submit

```json
{
  "commandId": "uuid",
  "traceId": "uuid",
  "causationId": "uuid",
  "correlationId": "uuid",
  "actorId": "string",
  "occurredAt": "2026-07-05T14:00:00.000Z",
  "orderId": "uuid",
  "instrumentId": "uuid",
  "participantId": "uuid",
  "accountId": "uuid",
  "side": "BUY | SELL",
  "orderType": "LIMIT",
  "quantityUnits": "10",
  "limitPrice": "99.50",
  "currency": "USD",
  "timeInForce": "DAY | IOC"
}
```

`orderType` currently only accepts `LIMIT` — market orders are not yet validated at this boundary (see [Bot SDK Reference](../../arena/bot-sdk-reference/) for how the arena adapter handles this today).

## POST /api/v1/orders/modify

```json
{
  "commandId": "uuid",
  "traceId": "uuid",
  "causationId": "uuid",
  "correlationId": "uuid",
  "actorId": "string",
  "occurredAt": "2026-07-05T14:00:00.000Z",
  "orderId": "uuid",
  "quantityUnits": "5",
  "limitPrice": "100.00"
}
```

## POST /api/v1/orders/cancel

```json
{
  "commandId": "uuid",
  "traceId": "uuid",
  "causationId": "uuid",
  "correlationId": "uuid",
  "actorId": "string",
  "occurredAt": "2026-07-05T14:00:00.000Z",
  "orderId": "uuid",
  "reason": "string"
}
```

## POST /api/v1/orders/cancel-by-client-order

Slower resolver path for clients that know their own `clientOrderId` but do not have the routed venue cancel metadata. The runtime resolves `(participantId, clientOrderId)` outside the matching hot path, synthesizes the normal cancel body with `orderId`, `runId`, `venueSessionId`, and `instrumentId`, then submits it through `/api/v1/orders/cancel`.

This route is not part of the throughput target. Hot-path cancels should include routing metadata and use `/api/v1/orders/cancel` directly.

```json
{
  "commandId": "uuid",
  "traceId": "uuid",
  "causationId": "uuid",
  "correlationId": "uuid",
  "actorId": "string",
  "occurredAt": "2026-07-05T14:00:00.000Z",
  "participantId": "uuid",
  "clientOrderId": "client-order-123",
  "reason": "string"
}
```

## Response Shape

Order command routes return the same result envelope (mirrors `SubmitOrderResult` in the wire contract — see [Wire Contracts](../../schema/contracts/)):

```json
{
  "outcome": {
    "accepted": { "eventId": "...", "orderId": "...", "engineOrderId": "...", "occurredAt": "..." }
  },
  "executions": [ { "eventId": "...", "executionId": "...", "orderId": "...", "instrumentId": "...", "quantity": {"units": "..."}, "executionPrice": {"nanos": "...", "currency": "..."}, "occurredAt": "..." } ],
  "trades": [ { "eventId": "...", "tradeId": "...", "executionId": "...", "buyOrderId": "...", "sellOrderId": "...", "instrumentId": "...", "quantity": {"units": "..."}, "price": {"nanos": "...", "currency": "..."}, "occurredAt": "..." } ]
}
```

Or, on rejection, `outcome.rejected` with `{ eventId, orderId, code, reason, occurredAt }` instead of `outcome.accepted`.

## POST /api/v1/orders/lifecycle-state

Rebuilds the `runtime.order_lifecycle_state` projection (open/filled/cancelled order state) used by market-data reads. No request body fields; returns a rebuild-status payload.

## Learn More

- [API Overview](../overview/) — headers, error envelope, processing modes
- [Command Status](../commands/) — poll a command by ID after acceptance
- [Wire Contracts](../../schema/contracts/) — the underlying protobuf message shapes
