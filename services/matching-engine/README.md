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
- shard-local in-memory hot book using ordered price levels, FIFO queues per price, and direct order-id unlinking
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

The implementation consumes `SubmitOrder`, `ModifyOrder`, and `CancelOrder` commands from assigned stream/topic partitions, processes them in ordered batches, publishes a `VenueEventBatch` JSON fact to the event stream/topic, then acknowledges or commits the command messages only after the event batch publish succeeds. Unsupported command types are terminated with durable failed outcomes. Postgres materialization is handled by the platform materializer profile, not by the matching-engine hot path.

Hot book ownership is shard-local. The command router must send all submit/cancel/modify commands for a `runId + venueSessionId + instrumentId` book key to the same durable partition and matching-engine shard owner. The book itself is in-memory Go state; recovery is planned as snapshot plus durable command/event replay with checksum verification. See [`../../docs/HOT_BOOK_SHARDING_PLAN.md`](../../docs/HOT_BOOK_SHARDING_PLAN.md).

### Terminal retention and recovery compatibility

`MATCHING_ENGINE_TERMINAL_ORDER_RETENTION_LIMIT=0` preserves all terminal
records. Positive `N` keeps at most `N` terminal records **per exact
`(runId, venueSessionId, instrumentId)` book**. Active orders remain retained.
Terminal event time, then order ID, defines retention rank. An unrelated book
cannot change a repeated cancel/modify outcome by evicting this book's history.
A terminal record evicted by its own book still yields `NOT_FOUND`; retained
cancelled/filled records retain existing `INVALID_STATE` semantics.

Positive retention bounds terminal records by `N × number of retained books`,
not by `N` across the engine. Neither book count nor active order count is
bounded by this option. The materializer stress default `250000` across 64
books permits up to 16 million terminal records per engine; size heap/headroom
for the actual owned books before running that profile. This change has no
new throughput qualification.

Retention applies at each terminal transition, independent of publication
batch size. Failed publication rolls back per-transition heap deltas and
original order records; work/memory depends on touched batch entries, with
`O(log N)` heap work per transition and no whole-lane copy. The shard-global
order-ID reservation remains held through provisional eviction. Only the
same owning batch can reuse that ID before commit, including another book
owned by that batch; another batch receives `DUPLICATE_ORDER_ID` until eviction
commits. This preserves the
existing engine-wide order-ID namespace and does not change Calcify's stronger
no-new-acceptance-of-reused-ID contract.

Callers must serialize all mutations of one book, including the full interval
from `BeginBatch` through durable publish and `Commit`/`Rollback`. Unrelated
books may run concurrently. An indeterminate publish must keep that lane
fenced; rollback after a possibly durable outcome is unsafe.

New snapshots use `matching-service-snapshot-v4`, with checksum-covered
`terminalRetentionPolicy=book-scoped-v1` and exact `terminalRetentionLimit`.
Restore requires the same configured limit, including zero. V3/V2/legacy
snapshots remain readable with retention disabled, subject to existing scope
checks. They cannot resume enabled retention: metadata cannot prove that the
old global queue never lost another book's terminal facts. Rebuild from
retained canonical commands to establish the new policy; do not infer missing
facts from a checksum or rewrite old metadata to V4. Reading an old snapshot
with retention disabled preserves its recorded state; it does not recover
previously evicted history.

Old binaries reject V4 snapshots. Preserve old checkpoints and canonical
replay coverage before upgrade; binary rollback needs a compatible old
checkpoint plus replay, not a V4 snapshot. This replay-policy change cannot
repair canonical outcomes already published under the old policy; qualify a
new run/source-generation boundary before relying on changed historical
outcomes. See [fix evidence](../../docs/evidence/terminal-retention-replay-fix/README.md).

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

# Exercise cancel/modify direct unlinking after a deeper resting-book warmup.
make bench-matching-engine-load ARGS="--rate 20000 --duration 30s --scenario deep-lifecycle --workers 1 --instruments 1"

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
- track engine realism and hardening work in [`../../docs/MATCHING_ENGINE_HARDENING_RESEARCH.md`](../../docs/MATCHING_ENGINE_HARDENING_RESEARCH.md)
