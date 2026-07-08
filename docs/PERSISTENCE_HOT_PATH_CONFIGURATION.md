# Persistence Hot Path Configuration

This note documents the local stream-ack, stream-direct, venue-event materializer, and projection settings used by the persistence hot-path work.

## Scenarios

### Baseline Stream Ack

Use this when testing durable command intake through the API and stream-ack workers.

```sh
make dev-up-stream-ack
make dev-stress-stream-ack
```

Default shape:

- API accepts commands and publishes to the configured stream.
- Stream-ack workers consume the command stream and persist canonical runtime results.
- Projectors can rebuild read models from canonical persistence.

Key profiles:

- `stream-ack`
- `redpanda` when `STREAM_ACK_LOG_PROVIDER=redpanda`

### Stream Direct No-DB Hot Path

Use this when isolating API publish + matching-engine direct stream consumption with Postgres removed from the command acceptance path.

```sh
make dev-up-stream-direct-nodb
make dev-stress-stream-direct-nodb
```

Default shape:

- API uses in-memory idempotency/intake/capture stores.
- API publishes commands to Redpanda/Kafka.
- Matching engine consumes the command stream directly.
- Runtime persistence is `noop` unless a background role overrides it.
- `STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES=100000` bounds the no-DB idempotency window for long ceiling tests; set it to `0` for unlimited in-memory replay retention.
- `STREAM_ACK_INMEMORY_INTAKE_SHARDS=256` shards no-DB stream-intake reservation state so the ceiling profile does not serialize all accepted commands on one in-memory monitor.
- `EXTERNAL_API_ACCEPTED_ASYNC_TERMINAL_STATUS_MAX_RECORDS=100000` bounds completed/failed status retention for the accepted-async no-DB isolation path; set it to `0` only when measuring unlimited status/idempotency heap growth.

Isolation tools:

- `make dev-stream-publish-bench` measures configured stream publisher capacity without HTTP, intake reservation, or matching-engine work.
- `STREAM_ACK_PUBLISHER=noop make dev-up-stream-direct-nodb` keeps the API stream-ack front door active but replaces durable broker publish with an immediate ack. Use only to isolate HTTP/API ceiling; it is not a durable-acceptance profile.
- `make dev-validate-stream-profile PROFILE=stream-direct-nodb` checks the intended profile environment without starting a load run. Use `PROFILE=noop-ceiling` for no-op API-front-door isolation and `PROFILE=materializer-soak` for durable canonical materializer runs.

### Venue Event Materializer

Use this for the persistence hot-path profile validated in this branch.

```sh
make dev-stress-venue-event-materializer
```

Default shape:

- API command acceptance remains no-db/in-memory.
- Matching engine consumes the command stream directly.
- Matching engine emits venue-event batches.
- Materializer services consume venue-event batches and persist canonical command outcomes to Postgres.
- Stress guardrail checks accepted commands against durable materialized outcomes.

The stress script enables:

- `redpanda`
- `venue-event-materializer`
- `venue-event-materializer-scaled`

That starts four materializer consumers by default for stress runs.

### Projection Ablation

Use this to compare projection depth after durable canonical materialization.

```sh
make dev-ablation-ladder
```

Rungs:

- `canonical-append-only`: materialize venue-event batch header and canonical command outcome rows only.
- `lightweight-outcome-projection`: project submit result, order, and lifecycle state without execution/trade fills.
- `full-order-execution-trade-projection`: project orders plus execution/trade fills.

The ladder resets Docker volumes between rungs. Use `DEV_ABLATION_DURATION`, `DEV_ABLATION_RATE`, `DEV_ABLATION_WORKERS`, and `DEV_ABLATION_ARTIFACT_DIR` to size a run.

## High-Value Commands

```sh
# 10k, 180s, scaled materializers
DEV_STRESS_DURATION=180s \
DEV_STRESS_RATES=10000 \
DEV_STRESS_SWEEP_WORKERS=384 \
DEV_STRESS_ARTIFACT_DIR=/tmp/reef-materializer-scaled-180s \
DEV_STRESS_REPORT_OUT=/tmp/reef-materializer-scaled-180s/materializer-scaled.json \
make dev-stress-venue-event-materializer

# 15k, 5 minute exploratory run
DEV_STRESS_DURATION=300s \
DEV_STRESS_RATES=15000 \
DEV_STRESS_SWEEP_WORKERS=512 \
DEV_STRESS_ARTIFACT_DIR=/tmp/reef-materializer-scaled-15k-5m \
DEV_STRESS_REPORT_OUT=/tmp/reef-materializer-scaled-15k-5m/materializer-scaled-15k.json \
make dev-stress-venue-event-materializer

# Fresh 180s projection ablation
DEV_ABLATION_DURATION=180s \
DEV_ABLATION_RATE=10000 \
DEV_ABLATION_WORKERS=384 \
DEV_ABLATION_ARTIFACT_DIR=/tmp/reef-ablation-ladder \
make dev-ablation-ladder
```

## Static Scaling Surfaces

### Command Stream Partitions

- `STREAM_ACK_PARTITION_COUNT`
- `STREAM_ACK_WORKER_0_PARTITIONS` through `STREAM_ACK_WORKER_3_PARTITIONS`
- `MATCHING_ENGINE_DIRECT_STREAM_PARTITIONS`

Current hot-path materializer profile uses 16 command partitions and matching-engine direct consumption over `0..15`.

### API Publish Pipeline

- `STREAM_ACK_PUBLISHER` (`noop` only for API isolation benchmarks)
- `STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES`
- `STREAM_ACK_INMEMORY_INTAKE_SHARDS`
- `STREAM_ACK_PUBLISH_PIPELINE_ENABLED`
- `STREAM_ACK_PUBLISH_PIPELINE_QUEUE_CAPACITY`
- `STREAM_ACK_PUBLISH_PIPELINE_MAX_IN_FLIGHT_PER_LANE`
- `STREAM_ACK_PUBLISH_PIPELINE_BATCH_SIZE`
- `STREAM_ACK_PUBLISH_PIPELINE_BATCH_LINGER_MS`
- `PLATFORM_NETTY_APPLICATION_MAX_PENDING_TASKS`
- `EXTERNAL_API_ACCEPTED_ASYNC_TERMINAL_STATUS_MAX_RECORDS`
- `STREAM_ACK_KAFKA_PUBLISH_MAX_IN_FLIGHT`
- `STREAM_ACK_KAFKA_BATCH_SIZE`
- `STREAM_ACK_KAFKA_COMPRESSION_TYPE`
- `MATCHING_ENGINE_KAFKA_COMPRESSION_TYPE`

The materializer stress profile uses the publish pipeline with lz4 Kafka compression and high per-lane in-flight capacity.
The direct no-DB Redpanda profile defaults matching-engine event publish compression to `none` because the current Sarama record batch encoder leaves compressed batch `max_timestamp` unset, which forces Redpanda to decompress produced batches for validation.

### Materializer Consumers

Single materializer profile:

- `venue-event-materializer`
- service: `platform-materializer`
- default port: `8091`

Scaled materializer profile:

- `venue-event-materializer-scaled`
- services: `platform-materializer-1`, `platform-materializer-2`, `platform-materializer-3`
- default ports: `8092`, `8093`, `8094`

All materializers use the same Kafka consumer group:

- `VENUE_EVENT_MATERIALIZER_GROUP_ID`

Each service has a distinct Kafka client id. Kafka assigns venue-event partitions across the group.

Important knobs:

- `VENUE_EVENT_MATERIALIZER_BATCH_SIZE`
- `VENUE_EVENT_MATERIALIZER_KAFKA_MAX_POLL_RECORDS`
- `VENUE_EVENT_MATERIALIZER_POLL_MS`
- `VENUE_EVENT_MATERIALIZER_FETCH_TIMEOUT_MS`
- `RUNTIME_DB_POOL_MATERIALIZER_MAX`
- `RUNTIME_DB_POOL_MATERIALIZER_MIN_IDLE`
- `DEV_STRESS_STOP_IDLE_BACKGROUND_SERVICES`

Stress stats are aggregated through:

- `DEV_STRESS_VENUE_EVENT_MATERIALIZER_URLS`

The stress script stops disabled stream workers and projectors before the load window by default. This keeps idle JVMs from consuming local Docker memory during materializer-only capacity tests.

### Projectors

Static projector services:

- `platform-projector-0` through `platform-projector-3`

Partition knobs:

- `STREAM_ACK_PROJECTOR_0_PARTITIONS` through `STREAM_ACK_PROJECTOR_3_PARTITIONS`

Projection mode knobs:

- `STREAM_ACK_PROJECTOR_ENABLED`
- `STREAM_ACK_PROJECTION_SOURCE`
- `STREAM_ACK_PROJECTOR_BATCH_SIZE`
- `STREAM_ACK_PROJECTOR_POLL_MS`
- `STREAM_ACK_PROJECTOR_INCLUDE_FILLS`

Use `STREAM_ACK_PROJECTOR_INCLUDE_FILLS=false` for lightweight lifecycle projection and `true` for full order/execution/trade projection.

### Database Pools

Shared runtime knobs:

- `RUNTIME_DB_POOL_STREAM_INTAKE_API_MAX`
- `RUNTIME_DB_POOL_STREAM_INTAKE_BACKGROUND_MAX`
- `RUNTIME_DB_POOL_STREAM_RUNTIME_MAX`
- `RUNTIME_DB_POOL_STREAM_RUNTIME_PROJECTION_MAX`
- `RUNTIME_DB_POOL_MATERIALIZER_MAX`

Postgres container knobs:

- `REEF_PG_MAX_CONNECTIONS`
- `REEF_PG_MAX_WAL_SIZE`
- `REEF_PG_MIN_WAL_SIZE`
- `REEF_PG_CHECKPOINT_TIMEOUT`
- `REEF_PG_CHECKPOINT_COMPLETION_TARGET`
- `REEF_PG_WAL_COMPRESSION`

Projection Postgres has matching `REEF_PROJECTION_PG_*` settings.

## Guardrails

The venue-event materializer stress report treats command outcomes as the durable completion gate:

- accepted command count
- materialized command outcome count
- materializer failures
- materializer ack failures

Important stress knobs:

- `DEV_STRESS_CAPTURE_VENUE_EVENT_MATERIALIZER=1`
- `DEV_STRESS_FAIL_ON_VENUE_EVENT_MATERIALIZER_FAILURES=1`
- `DEV_STRESS_VENUE_EVENT_MATERIALIZER_DRAIN_WAIT_MS`
- `DEV_STRESS_MAX_VENUE_EVENT_MATERIALIZER_COMPLETION_GAP`

Projection runs also check:

- `DEV_STRESS_CAPTURE_STREAM_ACK_PROJECTOR=1`
- `STREAM_ACK_MAX_PROJECTOR_LAG=0`

## Durable Proof Gates

No-op publisher evidence is useful only for front-door capacity. A production throughput claim needs all durable facts to line up:

- API returned `202` only after durable command append ack.
- command stream accepted count equals matching-engine direct consumed count after drain.
- matching-engine command ack happens only after `VenueEventBatch` publish succeeds.
- venue-event materializer consumes event batches and persists canonical command outcomes before committing offsets.
- canonical command outcomes match accepted commands after any intentional restart window.
- replay/projector pass can rebuild lifecycle rows without duplicates.
- failure counters stay zero: publish failed/rejected, stream-direct failed/nacked/termed/unsupported, materializer failed/ackFailed.

Short-run cleanup gates before another long soak:

1. Validate the profile with `make dev-validate-stream-profile PROFILE=materializer-soak`.
2. Run `make dev-smoke-venue-event-materializer` to prove one durable command reaches canonical outcome and compact projection replay is idempotent.
3. Run a short durable materializer stress, not a long soak, with strict drain checks and small duration such as `DEV_STRESS_DURATION=60s`.
4. Inspect report equality: accepted, stream-direct acked, and materialized outcomes should have zero unexplained gap after drain.
5. Save the long `10k+` soak for DO once local short gates are clean.

Smoke caveats:

- `make dev-smoke-venue-event-materializer` uses isolated Redpanda command/event topics per run so retained local topic backlog does not hide the command under test.
- The smoke proves durable API append, matching-engine direct consume/ack, venue-event batch publish, materializer canonical outcome persistence, target-row projection idempotency, and order read-model reconstruction.
- No-DB direct-consume projection does not depend on `command_log.command_payloads`: submit outcomes in the durable event batch carry a compact `acceptedOrder` projection fact. Set `DEV_VENUE_EVENT_MATERIALIZER_EXPECT_ORDER_ROW=0` only when intentionally debugging older payload shapes.

## Current Capacity Read

Current local evidence for durable materializer path:

- Post-`acceptedOrder` smoke: `make dev-smoke-venue-event-materializer`, smoke id `materializer-smoke-1783354591266`, `materializedDelta=1`, `projectedDelta=1`, `projectedReplayDelta=0`; replay/checksum scoped to `REEF_MATERIALIZER_SMOKE_VENUE_EVENTS_MATERIALIZER-SMOKE-1783354591266` passed with `duplicateReplayInserted=0`, no checksum/hash/count mismatches, no stream gaps/overlaps, and no projection watermark lag.
- Post-`acceptedOrder` short gate: `/tmp/reef-materializer-gate-10k-60s/materializer-10k-60s-rate-10000-workers-384.json`, `10k rps`, `60s`, `384` workers, `599950` requests, `599950` HTTP `202`, `0` failures, `9998.47 rps`, p95 `50.59ms`, p99 `89.72ms`; stream-direct acked `599950`, materializer persisted `599950`, direct/materializer failures and ack failures `0`. Replay/checksum scoped to `REEF_MATERIALIZER_STRESS_VENUE_EVENTS` passed with `599950` canonical outcomes and `duplicateReplayInserted=0`.
- One materializer does not reliably drain to zero durable-canonical gap at 10k sustained load.
- Four materializers drained accepted commands to zero durable gap at 10k for 60s and 180s validation runs.
- Initial 15k/5m attempts exposed local memory pressure: first the API was OOM-killed by idle background JVM overhead, then the matching engine was OOM-killed by unbounded terminal order retention.
- The final 15k/5m run stopped disabled background JVMs and set `MATCHING_ENGINE_TERMINAL_ORDER_RETENTION_LIMIT=250000`; API and matching engine remained healthy.
- Projector persistence and fill-depth toggles are functional; lightweight and full projection rungs reached zero projection lag after fixes.
- The current local ceiling for completed accepted throughput in this profile is about 11.5k/sec under 15k offered load.

Matching-engine terminal retention:

- `MATCHING_ENGINE_TERMINAL_ORDER_RETENTION_LIMIT=0`: default, preserve all terminal order records.
- `MATCHING_ENGINE_TERMINAL_ORDER_RETENTION_LIMIT=250000`: stress profile default, keep recent terminal order records bounded while preserving active order state.

15k/5m final result:

- artifact root: `/tmp/reef-materializer-scaled-15k-5m-retention`
- offered target: 15000 rps
- completed/accepted throughput: 11529.54 rps
- success rate: 100.00%
- accepted commands: 3459165
- stream-direct acked commands: 3459165
- durable materialized outcomes: 3794115
- durable gap: 0
- materializer failures: 0
- API status after run: healthy
- matching-engine status after run: healthy
- note: durable materialized outcomes are higher than accepted commands because this run drained backlog from earlier failed attempts.

Current local evidence for API-front-door no-op isolation:

- artifact root: `/tmp/reef-local-noop-10000-900s-intake-cap`
- profile: `STREAM_ACK_PUBLISHER=noop`, `STREAM_ACK_INTAKE_STORE=inmemory`, Netty hot-path adapter, workers/projectors disabled
- offered target: 10000 rps for 15m
- completed/accepted throughput: 9999.89 rps
- requests/success/failures: 8999952 / 8999952 / 0
- p99: 47.40ms
- API status after run: healthy, `restartCount=0`, post-run RSS about 1.17GiB

Headroom probes after the 15m soak:

- `/tmp/reef-local-noop-11000-120s-headroom`: 11000 rps for 2m, 1320000 / 1320000 / 0, p99 34.46ms
- `/tmp/reef-local-noop-12500-120s-headroom`: 12500 rps for 2m, 1499983 / 1499983 / 0, p99 34.10ms, API RSS about 1.205GiB, publish queue single digits, `restartCount=0`, `oomKilled=false`

Root-cause note:

- The failed 10k/15m no-op soak retained unbounded in-memory intake entries because Compose did not pass `STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES` into platform-runtime containers.
- The current direct no-DB wrapper sets `STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES=100000` and `STREAM_ACK_INMEMORY_INTAKE_SHARDS=256`; Compose now passes both through.
- `STREAM_ACK_PUBLISHER=noop` remains an isolation tool only. It proves API validation, intake reservation, response handling, and load-generator accounting; it does not prove durable command-log acceptance.

Verification status for the bounded-intake patch:

- `GRADLE_USER_HOME=/tmp/reef-gradle-verify-1 ./gradlew test --tests com.reef.platform.api.StreamCommandIntakeTest --tests com.reef.platform.api.PlatformHttpServerBoundaryTest.streamAckLatePublishAckAfterResponseTimeoutReplaysAcceptedReference`
- `go test ./cmd/load-tester` from `services/simulator`
