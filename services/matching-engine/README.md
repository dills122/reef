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

Run the engine-only sustained load harness:

```bash
make bench-matching-engine-load
```

The default harness run targets `10k` attempted commands/sec for `30s` against one in-process engine instance, one worker, one instrument, and the `alternating-cross` scenario. It writes artifacts under `reports/matching-engine-load/<run-id>/`:

- `summary.json`: attempted, processed, accepted, rejected, failure, execution/trade, throughput, and latency percentile summary.
- `intervals.csv`: one-second processed/accepted/rejected/execution/trade counters for sustained-rate review.
- `results.ndjson`: optional per-command result records when `--record-results` is set.

Useful variants:

```bash
# Explicit 10k/sec gate for a one-minute hot-book crossing run.
make bench-matching-engine-load ARGS="--rate 10000 --duration 60s --scenario alternating-cross --workers 1 --instruments 1 --min-processed-rate 10000"

# Stress resting-book growth and price-time insertion behavior.
make bench-matching-engine-load ARGS="--rate 10000 --duration 30s --scenario resting-book --workers 1 --instruments 1"

# Exercise submit/modify/cancel lifecycle behavior. Keep workers=1 for deterministic command order.
make bench-matching-engine-load ARGS="--rate 10000 --duration 30s --scenario lifecycle --workers 1 --instruments 1"

# Spread load across multiple books to estimate partitionable capacity.
make bench-matching-engine-load ARGS="--rate 40000 --duration 30s --scenario alternating-cross --workers 4 --instruments 4"

# Capture the first 1000 command outcomes for audit/debug inspection.
make bench-matching-engine-load ARGS="--rate 10000 --duration 10s --record-results --max-recorded-results 1000"
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
