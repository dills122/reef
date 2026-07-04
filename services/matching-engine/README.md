# Matching Engine

This service is the Go-based matching and execution engine for Reef.

Current state:

- runnable HTTP service
- `GET /health`
- `POST /orders/submit`
- `POST /orders/cancel`
- `POST /orders/modify`
- gRPC server scaffold behind env flag (`MATCHING_ENGINE_ENABLE_GRPC=1`)
- opt-in engine-direct JetStream or Redpanda/Kafka-compatible command consumer behind env flag (`MATCHING_ENGINE_DIRECT_STREAM_ENABLED=1`)
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

Run with engine-direct stream ingestion enabled:

```bash
cd services/matching-engine
MATCHING_ENGINE_DIRECT_STREAM_ENABLED=1 \
STREAM_ACK_NATS_URL=nats://localhost:4222 \
STREAM_ACK_COMMAND_STREAM=REEF_COMMANDS \
STREAM_ACK_SUBJECT_PREFIX=reef.cmd.v1 \
STREAM_ACK_PARTITION_COUNT=64 \
MATCHING_ENGINE_DIRECT_STREAM_PARTITIONS=0..63 \
MATCHING_ENGINE_EVENT_STREAM=REEF_VENUE_EVENTS \
MATCHING_ENGINE_EVENT_SUBJECT_PREFIX=reef.venue.events.v1 \
GOCACHE=/tmp/reef-go-build-cache go run ./cmd/matching-engine
```

Use `STREAM_ACK_LOG_PROVIDER=redpanda` with `STREAM_ACK_KAFKA_BOOTSTRAP_SERVERS=localhost:9092` to run the same direct path against Kafka-compatible command and venue-event topics.

Engine-direct mode is the first slice of the higher-headroom venue-core path:

```text
API -> durable command stream/topic -> matching engine shard -> durable venue event batch -> command ack/offset commit
```

The implementation consumes `SubmitOrder` commands from assigned stream/topic partitions, processes them in ordered batches, publishes a `VenueEventBatch` JSON fact to the event stream/topic, then acknowledges or commits the command messages only after the event batch publish succeeds. Unsupported command types are terminated for now; cancel/modify direct-stream support and Postgres materialization are follow-up slices.

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
