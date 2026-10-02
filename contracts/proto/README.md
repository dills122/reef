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

Calcify Phase 2 contract: [`calcify.proto`](./calcify.proto) defines
`MatchContextResolvedV1` with complete immutable trade, both accepted-order facts,
full acceptance events, command identities and exact source provenance. Generated
Java lives under `reef/contracts/calcify/v1`; Go retains existing `orderv1` package
because generation writes all schemas into one Go directory. Java drift guard
covers all contract packages.

Order-index identity is lane-local `(sourceGeneration, orderId)`. Within that lane,
new acceptance of a reused ID is a conflict, even after terminal matcher retention;
upstream callers must issue distinct IDs for the generation. Identical fact,
acceptance and command replay preserves earliest provenance. Never overwrite
original acceptance with modify economics. Both sides must share run/session/
instrument, source generation/topic UUID/lane and currency. Acceptance in same
outcome logically precedes its trades; later outcomes do not. Commitment ordinal
counts every nested trade across ordered outcomes, including modify outcomes.
Source generation must remain bound to registered broker topic UUID.

Matching snapshot/retention compatibility (2026-10-01): terminal matcher retention
is per exact `(runId, venueSessionId, instrumentId)` book, independently of
Calcify's no-reuse acceptance identity. Matcher reservations and rollback preimages
use `(runId, orderId)`; different runs may independently reuse an order ID.
Reservations remain held through provisional evictions until publication commits
or rolls back.
Snapshot V4 carries checksum-covered `book-scoped-v1` policy and exact retention
limit; restore rejects changed limits and old snapshots with enabled retention.
See [matching-engine recovery contract](../../services/matching-engine/README.md#terminal-retention-and-recovery-compatibility).
