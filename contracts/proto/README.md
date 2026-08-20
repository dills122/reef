# Proto Contracts

This directory now contains the first draft runtime-to-engine contract in [`order_execution.proto`](./order_execution.proto).

Current scope:

- `SubmitOrder`
- `CancelOrder`
- `ModifyOrder`
- `SubmitOrders` bidirectional submit stream
- `OrderAccepted`
- `OrderRejected`
- `ExecutionCreated`
- `TradeCreated`
- `SubmitOrderResult`

Current usage model:

- the `.proto` file is the canonical contract draft
- the Kotlin runtime and Go matching engine both use generated protobuf sources
- HTTP JSON remains as a compatibility/fallback transport with equivalent command metadata
- generated Java sources are checked in under `services/platform-runtime/src/main/java/reef/contracts/orderexecution/v1/`
- generated Go sources are checked in under `services/matching-engine/internal/transport/grpc/pb/contracts/proto/`
- lifecycle mutation messages carry the target order's routing and ownership
  claims so the engine can bind them to canonical in-memory order state

Regenerate checked-in sources from the repository root:

```bash
./scripts/generate-proto.sh
make check-proto-additive
```

Contract rules:

- include stable identifiers
- include actor, trace, causation, and correlation metadata
- preserve canonical maker/taker attribution on every execution through the
  `ExecutionCreated.liquidity_role` field
- include stream routing metadata on commands that may enter `stream-ack`
  (`runId`, `venueSessionId`, `instrumentId`, order/client-order identifiers,
  and bot attribution when present)
- include `participantId` and `accountId` on cancel/modify commands; the API
  authorizes those claims and the engine rejects them when they do not match
  the target order
- avoid floating-point price and quantity fields
- version messages deliberately

Compatibility guard:

- `make check-proto-additive` compiles baseline and current descriptor sets,
  compares messages, fields, enums, services, and methods, and verifies that
  checked-in Go and Java generated sources exactly match the contract.
- Generation is pinned to `protoc 33.2`, `protoc-gen-go v1.34.2`, and
  `protoc-gen-go-grpc 1.5.1` in CI.
- The guard compares against `PROTO_BASE_REF` when set.
- Without `PROTO_BASE_REF`, it defaults to `origin/HEAD`, then falls back to
  `origin/main` or `origin/master`.
- If no base ref or required tool is available, the guard fails closed.
