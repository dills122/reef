# Proto Contracts

This directory now contains the first draft runtime-to-engine contract in [`order_execution.proto`](./order_execution.proto).

Current scope:

- `SubmitOrder`
- `CancelOrder`
- `ModifyOrder`
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

Regenerate checked-in sources from the repository root:

```bash
protoc -I . \
  --java_out=services/platform-runtime/src/main/java \
  contracts/proto/order_execution.proto

PATH=$HOME/go/bin:$PATH protoc -I . \
  --go_out=services/matching-engine/internal/transport/grpc/pb \
  --go_opt=paths=source_relative \
  --go-grpc_out=services/matching-engine/internal/transport/grpc/pb \
  --go-grpc_opt=paths=source_relative \
  contracts/proto/order_execution.proto
```

Contract rules:

- include stable identifiers
- include actor, trace, causation, and correlation metadata
- include stream routing metadata on commands that may enter `stream-ack` (`runId`, `venueSessionId`, order/client-order identifiers, and bot attribution when present)
- avoid floating-point price and quantity fields
- version messages deliberately

Compatibility guard:

- `make check-proto-additive` compares against `PROTO_BASE_REF` when set.
- Without `PROTO_BASE_REF`, it defaults to `origin/HEAD`, then falls back to
  `origin/main` or `origin/master`.
- If no base ref is available, the guard prints an explicit skip message and
  exits successfully rather than pretending a compatibility check ran.
