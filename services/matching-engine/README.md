# Matching Engine

This service is the Go-based matching and execution engine for Reef.

Current state:

- runnable HTTP service
- `GET /health`
- `POST /orders/submit`
- `POST /orders/cancel`
- `POST /orders/modify`
- gRPC server scaffold behind env flag (`MATCHING_ENGINE_ENABLE_GRPC=1`)
- hidden-book matching behavior with price-time ordering
- partial-fill and multi-match behavior
- engine-side order state for rest/fill/cancel/modify paths
- tested with `go test ./...`

Run locally:

```bash
cd services/matching-engine
GOCACHE=/tmp/reef-go-build-cache go run ./cmd/matching-engine
```

Run with gRPC scaffold enabled:

```bash
cd services/matching-engine
MATCHING_ENGINE_ENABLE_GRPC=1 MATCHING_ENGINE_GRPC_ADDR=:9081 GOCACHE=/tmp/reef-go-build-cache go run ./cmd/matching-engine
```

Example request:

```bash
curl -X POST http://localhost:8081/orders/submit \
  -H 'content-type: application/json' \
  -d '{
    "commandId":"cmd-1",
    "traceId":"trace-1",
    "causationId":"cause-1",
    "correlationId":"corr-1",
    "actorId":"trader-1",
    "occurredAt":"2026-03-14T18:00:00Z",
    "orderId":"ord-1",
    "instrumentId":"AAPL",
    "participantId":"participant-1",
    "accountId":"account-1",
    "side":"BUY",
    "orderType":"LIMIT",
    "quantityUnits":"100",
    "limitPrice":"150250000000",
    "currency":"USD",
    "timeInForce":"DAY"
  }'
```

Build guidance:

- follow [`../../docs/steering/architecture.md`](../../docs/steering/architecture.md)
- follow [`../../docs/steering/go.md`](../../docs/steering/go.md)
- keep transport adapters thin and matching logic deterministic
