---
title: Wire Contracts (Protobuf)
description: order_execution.proto — the cross-language contract between Kotlin runtime and Go matching engine.
---

`contracts/proto/order_execution.proto` is the current versioned contract between the Kotlin platform runtime and the Go matching engine.

## Command Metadata (Shared)

Every command carries a `CommandMetadata`: `command_id`, `correlation_id`, `actor_id`, `actor_type`, `occurred_at`, `trace_id`, `causation_id`, `run_id`, `venue_session_id`, `client_order_id`, `bot_id`, `bot_version`.

## Commands

| Message | Fields |
|---|---|
| `SubmitOrder` | `metadata`, `order_id`, `instrument_id`, `participant_id`, `account_id`, `side`, `order_type`, `quantity`, `limit_price`, `time_in_force` |
| `CancelOrder` | `metadata`, `order_id`, `reason` |
| `ModifyOrder` | `metadata`, `order_id`, `quantity`, `limit_price` |

`OrderQuantity` is `{ units: string }`; `Price` is `{ nanos: string, currency: string }`.

## Result & Events

| Message | Fields |
|---|---|
| `SubmitOrderResult` | `oneof outcome { accepted: OrderAccepted, rejected: OrderRejected }`, `executions: ExecutionCreated[]`, `trades: TradeCreated[]` |
| `OrderAccepted` | `event_id`, `order_id`, `engine_order_id`, `occurred_at` |
| `OrderRejected` | `event_id`, `order_id`, `code`, `reason`, `occurred_at` |
| `ExecutionCreated` | `event_id`, `execution_id`, `order_id`, `instrument_id`, `quantity`, `execution_price`, `occurred_at` |
| `TradeCreated` | `event_id`, `trade_id`, `execution_id`, `buy_order_id`, `sell_order_id`, `instrument_id`, `quantity`, `price`, `occurred_at` |

## Service

```protobuf
service OrderExecutionService {
  rpc SubmitOrder(SubmitOrder) returns (SubmitOrderResult);
  rpc SubmitOrders(stream SubmitOrder) returns (stream SubmitOrderResult);
  rpc CancelOrder(CancelOrder) returns (SubmitOrderResult);
  rpc ModifyOrder(ModifyOrder) returns (SubmitOrderResult);
  rpc HealthCheck(HealthCheckRequest) returns (HealthCheckResponse);
}
```

## Enums

- `OrderSide`: `ORDER_SIDE_UNSPECIFIED`, `ORDER_SIDE_BUY`, `ORDER_SIDE_SELL`
- `OrderType`: `ORDER_TYPE_UNSPECIFIED`, `ORDER_TYPE_LIMIT` (market orders not yet defined at this layer)
- `TimeInForce`: `TIME_IN_FORCE_UNSPECIFIED`, `TIME_IN_FORCE_DAY`, `TIME_IN_FORCE_IOC`

Contracts are versioned and additive-first; `make check-proto-additive` verifies compatibility in CI.

## Learn More

- `contracts/proto/order_execution.proto` — the full, current source of truth
- `contracts/proto/README.md` — contract conventions
- [Orders API](../../api/orders/) — the public JSON shape this maps to/from
- [Runtime Schema](../runtime-schema/) — how these messages materialize into Postgres
