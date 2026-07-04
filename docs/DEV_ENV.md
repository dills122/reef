# Reef Local Dev Environment

This runbook defines the Docker-first local workflow introduced by the dev-env sprint.

## Prerequisites

- Docker with Compose plugin
- `curl`
- Bun runtime
- Go toolchain (only needed for `make dev-stress`, which runs the Go load tester)

## Base workflow

From repo root:

```bash
make dev-up
make dev-smoke
```

Recommended first step for configuration:

```bash
cp .env.example .env
```

If Bun is not available locally yet, you can temporarily run with Node:

```bash
JS_RUNTIME=node make dev-up
JS_RUNTIME=node make dev-smoke
```

If default host ports are already in use, override them at runtime:

```bash
REEF_PLATFORM_API_HOST_PORT=18080 \
REEF_PLATFORM_WORKER_0_HOST_PORT=18082 \
REEF_PLATFORM_WORKER_1_HOST_PORT=18083 \
REEF_PLATFORM_PROJECTOR_0_HOST_PORT=18084 \
REEF_PLATFORM_PROJECTOR_1_HOST_PORT=18085 \
REEF_MATCHING_ENGINE_HOST_PORT=18081 \
REEF_POSTGRES_HOST_PORT=15432 \
JS_RUNTIME=node make dev-up
```

Shutdown:

```bash
make dev-down
```

Deterministic reset (down + volume wipe + rebuild + compose health wait):

```bash
make dev-reset
```

Forward-only DB migrations run automatically during `make dev-up` and `make dev-reset` after Postgres is healthy and before the full stack starts. Manual migration is still available for repair/debug:

```bash
make dev-db-migrate
```

Stack startup passes `--remove-orphans` so renamed local services, such as the retired all-in-one runtime container, do not keep stale ports or process roles alive.

Optional inline smoke during reset:

```bash
DEV_RESET_RUN_SMOKE=1 make dev-reset
```

## Postgres tuning knobs

Compose applies WAL/checkpoint defaults optimized for local soak runs:

- `REEF_PG_MAX_WAL_SIZE=16GB`
- `REEF_PG_MIN_WAL_SIZE=4GB`
- `REEF_PG_CHECKPOINT_TIMEOUT=15min`
- `REEF_PG_CHECKPOINT_COMPLETION_TARGET=0.9`
- `REEF_PG_WAL_COMPRESSION=on`
- `REEF_PG_LOG_CHECKPOINTS=on`

Override per run:

```bash
REEF_PG_MAX_WAL_SIZE=24GB \
REEF_PG_CHECKPOINT_TIMEOUT=20min \
make dev-reset
```

## Optional profiles

Enable optional services using `DEV_COMPOSE_PROFILES`.

Redis profile:

```bash
DEV_COMPOSE_PROFILES=redis make dev-up
```

Observability profile:

```bash
DEV_COMPOSE_PROFILES=observability make dev-up
```

Multiple profiles:

```bash
DEV_COMPOSE_PROFILES=redis,observability make dev-up
```

## Stress baseline entrypoint

Run stepped load profile (100 -> 200 -> 300 -> 400 rps):

```bash
make dev-stress
```

Run stress with automatic pre/post DB diagnostics capture:

```bash
make dev-stress-diagnostics
```

Run the durable captured-ack queue profile used for async drain and bot-arena capacity work:

```bash
make dev-up-captured-ack
make dev-stress-captured-ack
```

`dev-up-captured-ack` starts the separated local runtime roles (`platform-api`, `platform-worker-0`, `platform-worker-1`, `platform-projector-0`, and `platform-projector-1`) with:
- `EXTERNAL_API_COMMAND_CAPTURE_MODE=disabled`
- `EXTERNAL_API_COMMAND_LOG_MODE=postgres`
- `EXTERNAL_API_COMMAND_LOG_PAYLOAD_MODE=side-table`
- `EXTERNAL_API_COMMAND_PROCESSING_MODE=captured-ack`
- `EXTERNAL_API_COMMAND_ASYNC_WORKER_ENABLED=true`
- `EXTERNAL_API_COMMAND_ASYNC_WORKER_THREADS=4`
- `EXTERNAL_API_COMMAND_ASYNC_WORKER_BATCH_SIZE=250`
- `EXTERNAL_API_COMMAND_ASYNC_WORKER_POLL_MS=5`
- `EXTERNAL_API_COMMAND_ASYNC_WORKER_LEASE_MS=60000`
- `EXTERNAL_API_COMMAND_ASYNC_WORKER_DEDICATED_RUNTIME_POOL_ENABLED=false`

In this profile, `command_log.commands` is the canonical durable command capture path. The legacy boundary command-capture table is disabled by default to avoid duplicate hot-path writes; set `EXTERNAL_API_COMMAND_CAPTURE_MODE=postgres` explicitly when testing legacy capture behavior.

Durable captured-ack intake backpressure is enabled by default in `dev-up-captured-ack`:
- `EXTERNAL_API_COMMAND_INTAKE_MAX_ACTIVE_COMMANDS` defaults to `asyncThreads * asyncBatchSize * 2`.
- `EXTERNAL_API_COMMAND_INTAKE_MAX_ACTIVE_COMMANDS=0` disables active queue-depth rejection.
- `EXTERNAL_API_COMMAND_INTAKE_MAX_STALE_PROCESSING=0` disables stale-processing rejection.
- `EXTERNAL_API_COMMAND_INTAKE_BACKPRESSURE_SAMPLE_MS=100` caches queue-depth samples briefly so backpressure checks do not add a full queue probe to every request.
- When enabled, new commands receive `429 COMMAND_INTAKE_BACKPRESSURE` before durable acceptance; duplicate idempotency replays still return their existing command status.

Override the worker count for drain sweeps, for example:

```bash
EXTERNAL_API_COMMAND_ASYNC_WORKER_THREADS=16 make dev-up-captured-ack
```

Diagnostic artifacts are written under the stress artifact root with suffix `-diagnostics`:
- `pre-db-diagnostics.json`, `post-db-diagnostics.json`
- `pre-pg_stat_bgwriter.csv`, `post-pg_stat_bgwriter.csv`
- `pre-pg_stat_checkpointer.csv`, `post-pg_stat_checkpointer.csv` (Postgres 17+ when available)
- `pre-table-stats.csv`, `post-table-stats.csv`
- `postgres-logs.txt`

Stress telemetry also samples runtime health, hot-path timings, async command queue stats, runtime DB pool stats, engine health, and Docker container stats into `*-telemetry.ndjson`.
For stream-ack runs, worker-drain telemetry samples `platform-worker-0` through `platform-worker-3` directly from `REEF_PLATFORM_WORKER_0_HOST_PORT` through `REEF_PLATFORM_WORKER_3_HOST_PORT`; override with `DEV_STRESS_STREAM_ACK_WORKER_URLS` for custom worker layouts.

Captured-ack stress reports also attach `commandAccounting` when the runtime exposes `/internal/commands/accounting`. The accounting block records the run-scoped pre/post snapshots, accepted delta, completed/failed terminal delta, active queue depth after the step, stale processing count, completed rps, and accepted-command accounting gap. `make dev-stress-captured-ack` sets `DEV_STRESS_FAIL_ON_ACCOUNTING_GAP=1` by default.

Run the first JetStream-backed accepted-command profile:

```bash
make dev-up-stream-ack
```

`dev-up-stream-ack` starts the deploy-shaped local stack (`platform-api`, `platform-worker-0` through `platform-worker-3`, `platform-projector-0` through `platform-projector-3`, `matching-engine`, `nats`, and Postgres services), boots NATS with JetStream enabled, and creates the retained `REEF_COMMANDS` stream for `reef.cmd.v1.>` subjects. The runtime roles are configured with:
- `EXTERNAL_API_COMMAND_PROCESSING_MODE=stream-ack`
- `STREAM_ACK_LOG_PROVIDER=jetstream`
- `STREAM_ACK_NATS_URL=nats://nats:4222`
- `STREAM_ACK_COMMAND_STREAM=REEF_COMMANDS`
- `STREAM_ACK_SUBJECT_PREFIX=reef.cmd.v1`
- `STREAM_ACK_PARTITION_COUNT=64`
- `STREAM_ACK_INTAKE_STORE=postgres`
- `STREAM_ACK_MAX_STORAGE_UTILIZATION=0.95`
- `STREAM_ACK_BACKPRESSURE_SAMPLE_MS=100`
- `STREAM_ACK_MAX_WORKER_STREAM_LAG=50000`
- `STREAM_ACK_MAX_PROJECTOR_LAG=0`
- `STREAM_ACK_DRAIN_BACKPRESSURE_POLICY=venue-core`
- `STREAM_ACK_DRAIN_BACKPRESSURE_SAMPLE_MS=500`
- `STREAM_ACK_MARK_PUBLISHED_MODE=worker`
- `STREAM_ACK_MARK_PUBLISHED_WORKERS=4`
- `STREAM_ACK_MARK_PUBLISHED_QUEUE_CAPACITY=500000`
- `STREAM_ACK_WORKER_ENABLED=true`
- `STREAM_ACK_WORKER_0_PARTITIONS=0..15`
- `STREAM_ACK_WORKER_1_PARTITIONS=16..31`
- `STREAM_ACK_WORKER_2_PARTITIONS=32..47`
- `STREAM_ACK_WORKER_3_PARTITIONS=48..63`
- `STREAM_ACK_WORKER_BATCH_SIZE=1000`
- `STREAM_ACK_WORKER_DEDICATED_RUNTIME_POOL_ENABLED=true`
- `STREAM_ACK_CANONICAL_EVENT_ROWS_ENABLED=false`
- `STREAM_ACK_CANONICAL_QUERY_INDEXES_ENABLED=false`
- `STREAM_ACK_PROJECTOR_ENABLED=true`
- `STREAM_ACK_PROJECTOR_0_PARTITIONS=0..15`
- `STREAM_ACK_PROJECTOR_1_PARTITIONS=16..31`
- `STREAM_ACK_PROJECTOR_2_PARTITIONS=32..47`
- `STREAM_ACK_PROJECTOR_3_PARTITIONS=48..63`
- `STREAM_ACK_PROJECTION_NAME=runtime-normalized-submit`
- `STREAM_ACK_PROJECTOR_BATCH_SIZE=2000`
- `STREAM_ACK_PROJECTOR_POLL_MS=10`
- `RUNTIME_DB_URL=jdbc:postgresql://boundary-postgres:5432/reef?currentSchema=boundary`
- `RUNTIME_PROJECTION_POSTGRES_JDBC_URL=jdbc:postgresql://projection-postgres:5432/reef?currentSchema=runtime`
- `RUNTIME_DB_POOL_STREAM_RUNTIME_PROJECTION_MAX=24`
- `RUNTIME_DB_POOL_STREAM_RUNTIME_PROJECTION_MIN_IDLE=8`
- `RUNTIME_DB_POOL_STREAM_INTAKE_API_MAX=64`
- `RUNTIME_DB_POOL_STREAM_INTAKE_API_MIN_IDLE=16`
- `RUNTIME_DB_POOL_STREAM_INTAKE_BACKGROUND_MAX=4`
- `RUNTIME_DB_POOL_STREAM_INTAKE_BACKGROUND_MIN_IDLE=0`
- `RUNTIME_DB_POOL_STREAM_RUNTIME_MAX=24`
- `RUNTIME_DB_POOL_STREAM_RUNTIME_MIN_IDLE=8`

In this mode `platform-api` returns `202` only after JetStream publish acknowledgment. The worker containers expose health and internal metrics endpoints, but public command intake routes are not mounted in worker or projector roles. Commands must include stream routing metadata (`runId`, `venueSessionId`, `instrumentId`, `orderId`, and `commandId`); duplicate idempotency keys replay the accepted stream reference only when the payload hash matches, and return `409 IDEMPOTENCY_PAYLOAD_CONFLICT` for a different payload.

Engine-direct stream ingestion is the higher-headroom replacement path for the current generic worker -> engine RPC bridge. It is opt-in while it is being built:

```bash
MATCHING_ENGINE_DIRECT_STREAM_ENABLED=true \
STREAM_ACK_WORKER_ENABLED=false \
make dev-up-stream-ack
```

Relevant matching-engine knobs:

- `MATCHING_ENGINE_DIRECT_STREAM_ENABLED=false|true`
- `MATCHING_ENGINE_SHARD_ID=engine-0`
- `MATCHING_ENGINE_DIRECT_STREAM_PARTITIONS=0..63`
- `MATCHING_ENGINE_DIRECT_STREAM_DURABLE_PREFIX=reef-engine-direct`
- `MATCHING_ENGINE_DIRECT_STREAM_BATCH_SIZE=500`
- `MATCHING_ENGINE_DIRECT_STREAM_CONNECT_TIMEOUT_MS=60000`
- `MATCHING_ENGINE_DIRECT_STREAM_FETCH_TIMEOUT_MS=200`
- `MATCHING_ENGINE_DIRECT_STREAM_POLL_MS=5`
- `MATCHING_ENGINE_DIRECT_STREAM_ACK_WAIT_MS=60000`
- `MATCHING_ENGINE_DIRECT_STREAM_MAX_ACK_PENDING=4000`
- `MATCHING_ENGINE_EVENT_STREAM=REEF_VENUE_EVENTS`
- `MATCHING_ENGINE_EVENT_SUBJECT_PREFIX=reef.venue.events.v1`

In this mode the matching engine consumes assigned command partitions directly, processes ordered command batches, publishes `VenueEventBatch` records to JetStream, and acks command messages only after the event batch publish succeeds. Submit, cancel, and modify branches exist in the direct processor; the current stress/profile coverage is still submit-focused. Postgres materialization from venue event batches is the next persistence slice.

Run the engine-direct no-DB stress profile:

```bash
make dev-stress-stream-direct-nodb
```

This starts the stream-ack command intake profile with DB-backed hot-path persistence disabled, disables stream workers/projectors, enables `MATCHING_ENGINE_DIRECT_STREAM_ENABLED=true`, uses the Netty hot-path adapter, enables the bounded partitioned command-publish pipeline, and runs submit-only stress steps at `5000,10000,15000,20000` rps for `90s` by default. Reports are written under `/tmp/reef-stream-direct-nodb-stress`. The profile uses isolated high-capacity JetStream defaults, `STREAM_ACK_COMMAND_STREAM=REEF_DIRECT_NODB_COMMANDS_V2`, `STREAM_ACK_SUBJECT_PREFIX=reef.direct.nodb.v2.cmd.v1`, `STREAM_ACK_COMMAND_STREAM_MAX_BYTES=34359738368`, `MATCHING_ENGINE_EVENT_STREAM=REEF_DIRECT_NODB_VENUE_EVENTS_V2`, and `MATCHING_ENGINE_EVENT_SUBJECT_PREFIX=reef.direct.nodb.v2.venue.events.v1`, so retained local streams from older profiles do not overlap subjects or hit the older 1 GiB command-stream cap. Trace validation is disabled with `DEV_STRESS_TRACE_CHECK_LIMIT=0` because trace/event persistence is intentionally not part of this ceiling test.

Run the same no-DB direct profile against Redpanda/Kafka-compatible command and event topics:

```bash
STREAM_ACK_LOG_PROVIDER=redpanda make dev-stress-stream-direct-nodb
```

In this mode the API still returns `202` only after the Kafka-compatible producer receives an `acks=all` broker acknowledgment. The matching engine consumes assigned Kafka topic partitions directly, publishes `VenueEventBatch` records to the configured event topic, and commits Kafka offsets only after the event batch publish succeeds. A platform runtime can then run `PLATFORM_RUNTIME_ROLE=materializer` with `EXTERNAL_API_COMMAND_PROCESSING_MODE=stream-ack` and `STREAM_ACK_LOG_PROVIDER=redpanda` to read those event batches, commit compact canonical Postgres rows, and commit its Kafka event-topic offsets only after the Postgres materialization call returns. This is the apples-to-apples path for the durable command-log boundary plus async canonical materialization; the older `STREAM_ACK_LOG_PROVIDER=redpanda make dev-up-stream-ack` path still exercises the DB-backed stream workers.

Run the local Redpanda direct-stream materializer smoke:

```bash
make dev-smoke-venue-event-materializer
```

This starts isolated Redpanda command/event topics, the direct matching-engine consumer, and `platform-materializer`. The smoke submits one stream-ack order, waits for `runtime.canonical_command_outcomes`, and verifies `/internal/venue-event-materializer/stats` advanced. It is a correctness smoke, not a throughput claim.

For the higher-throughput front-door prototype, enable the long-lived stream transport:

```bash
STREAM_ACK_LOG_PROVIDER=redpanda \
STREAM_INGRESS_ENABLED=1 \
DEV_STRESS_TRANSPORT=stream \
DEV_STRESS_STREAM_ADDRESS=127.0.0.1:8090 \
make dev-stress-stream-direct-nodb
```

The stream ingress listener is mounted on `REEF_STREAM_INGRESS_HOST_PORT`/`STREAM_INGRESS_PORT` (`8090` by default). Frames are newline-delimited submit-command JSON payloads; the runtime validates the same command body as `/api/v1/orders/submit` and returns a newline-delimited status code after the durable command-log ack. Error frames include `status<TAB>jsonBody` for diagnostics.

When `STREAM_ACK_LOG_PROVIDER=redpanda`, the direct no-DB helper defaults local runs to `STREAM_ACK_PARTITION_COUNT=16` and `MATCHING_ENGINE_DIRECT_STREAM_PARTITIONS=0..15` so a retained single-node Redpanda broker does not exhaust its partition memory reservation. Override those values explicitly for provisioned DO or multi-broker tests. If local startup fails with `Can not increase partition count due to memory limit`, delete stale benchmark topics with `docker compose -f docker-compose.yml exec -T redpanda rpk topic delete ...` or reset the Redpanda volume before rerunning.

Each direct no-DB report includes:

- `streamDirect.delta.fetchedDelta`: commands fetched by the matching-engine direct consumers
- `streamDirect.delta.processedDelta`: submit commands applied to the matching engine
- `streamDirect.delta.publishedDelta`: command outcomes durably published in venue event batches
- `streamDirect.delta.ackedDelta`: commands acked after venue event batch publication
- `unitMetrics.directAckedCommandsPerSecond`: direct engine completion throughput
- `streamDirectPartitionSkew`: per-partition direct-engine drain distribution

Direct no-DB stress fails by default if the direct runner records failures, NAKs, unsupported/termed commands, or a post-drain accepted/acked gap.

An opt-in Redpanda/Kafka-compatible DB-backed stream-ack comparison path is available without changing the public command contract:

```bash
STREAM_ACK_LOG_PROVIDER=redpanda make dev-up-stream-ack
```

This enables the Compose `redpanda` profile and sets `STREAM_ACK_KAFKA_BOOTSTRAP_SERVERS=redpanda:9092` by default. The runtime creates or expands the Kafka topic named by `STREAM_ACK_COMMAND_STREAM` if needed, publishes with explicit partition routing, `acks=all`, idempotent producer mode, async send callbacks, bounded application in-flight work, batching, and compression. Kafka `(partition, offset)` is encoded into the canonical `stream_seq`, and workers commit Kafka offsets only after canonical submit outcomes are durable.

Kafka-compatible producer tuning is exposed with:

- `STREAM_ACK_KAFKA_PUBLISH_MAX_IN_FLIGHT`
- `STREAM_ACK_KAFKA_MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION`
- `STREAM_ACK_KAFKA_LINGER_MS`
- `STREAM_ACK_KAFKA_BATCH_SIZE`
- `STREAM_ACK_KAFKA_COMPRESSION_TYPE`
- `STREAM_ACK_KAFKA_BUFFER_MEMORY_BYTES`
- `STREAM_ACK_KAFKA_MAX_BLOCK_MS`
- `STREAM_ACK_KAFKA_REQUEST_TIMEOUT_MS`
- `STREAM_ACK_KAFKA_DELIVERY_TIMEOUT_MS`

The API container owns the larger `stream-intake` boundary DB pool because it is the only role that accepts public stream commands. Worker and projector roles keep tiny `stream-intake` pools for startup/internal compatibility while using their dedicated canonical/projection pools for drain and read-model work.

In stream-ack mode, `STREAM_ACK_MARK_PUBLISHED_MODE=worker` removes the post-publish boundary metadata update from the synchronous response path after JetStream durable publish acknowledgement. Stream workers repair the same metadata by `commandId` in batches after canonical commit and before JetStream ack, so an API crash after publish still has a durable-message repair path. Set `STREAM_ACK_MARK_PUBLISHED_MODE=sync` to force the API to update boundary metadata before returning.

The stream bootstrap is repeat-safe: if `REEF_COMMANDS` already exists, the script leaves the existing stream configuration in place.

Stream-ack health is exposed at `/internal/stream-ack/health`. The first backpressure gate rejects before publish when the command stream is unavailable or, for JetStream, when stream byte utilization meets or exceeds `STREAM_ACK_MAX_STORAGE_UTILIZATION`. API request-path backpressure checks reuse the latest stream health snapshot for `STREAM_ACK_BACKPRESSURE_SAMPLE_MS` milliseconds to avoid calling stream management on every accepted command.

JetStream command publishing supports `STREAM_ACK_PUBLISH_MODE=sync|async`. `sync` uses one synchronous durable publish call per accepted command. `async` uses JNATS async publish futures with a bounded in-flight limit from `STREAM_ACK_PUBLISH_MAX_IN_FLIGHT`, then waits for the specific command's publish ack before returning `202`.

`STREAM_ACK_PUBLISH_PIPELINE_ENABLED=true` wraps the configured stream publisher in a bounded partitioned publish pipeline. Each command partition gets a lane with queue capacity `STREAM_ACK_PUBLISH_PIPELINE_QUEUE_CAPACITY` and in-flight durable publish cap `STREAM_ACK_PUBLISH_PIPELINE_MAX_IN_FLIGHT_PER_LANE`. Netty stream-ack intake waits asynchronously for the durable publish future, so request threads no longer own JetStream concurrency. The stream-ack health response includes `publishMode`, `publishInFlight`, `publishMaxInFlight`, `publishQueueDepth`, `publishMaxQueueDepth`, `publishLaneCount`, publish accepted/completed/failed/rejected counters, phase timings for queue wait, slot wait, delegate ack, and per-lane snapshots for benchmark interpretation.

The deploy-shaped stream-ack profile enables venue-core drain-side backpressure by default. `STREAM_ACK_MAX_WORKER_STREAM_LAG` samples configured worker drain state; in JetStream mode it uses durable consumer snapshots from `STREAM_ACK_BACKPRESSURE_WORKER_DURABLES`, while Redpanda mode reports assigned-partition offset lag. When the positive threshold is reached, the API rejects before publish with `429` instead of accepting work it cannot safely drain. Projection lag is still reported, but it only gates intake when `STREAM_ACK_DRAIN_BACKPRESSURE_POLICY=control-room-fresh` and `STREAM_ACK_MAX_PROJECTOR_LAG` is positive.

Stream-ack worker stats are exposed at `/internal/stream-ack/worker/stats`. The worker consumes `SubmitOrder` commands partition-by-partition, prepares a fetched batch, appends canonical command results/events, and acknowledges the durable log only after the canonical DB commit path returns. In JetStream mode this is a message ack; in Redpanda mode this is a manual Kafka offset commit. Unsupported stream command types are terminated until cancel/modify processing is added.

Stream-ack projector status is exposed at `/internal/projector/status` on `platform-projector-0` through `platform-projector-3` (`REEF_PLATFORM_PROJECTOR_0_HOST_PORT` through `REEF_PLATFORM_PROJECTOR_3_HOST_PORT`, defaults `8084`, `8085`, `8088`, and `8089`). Projectors own explicit non-overlapping partition ranges, read canonical submit outcomes from the runtime Postgres service, update the normalized order/execution/trade/runtime-event read tables in the projection Postgres service, advance projection-local `runtime.projection_watermarks`, and report projection lag for their owned partitions. Stress reports capture all default endpoints when `DEV_STRESS_CAPTURE_STREAM_ACK_PROJECTOR=1`; stream-ack worker capture enables it by default. Override custom layouts with `DEV_STRESS_STREAM_ACK_PROJECTOR_URLS`.

Local Docker includes separate `boundary-postgres` (`REEF_BOUNDARY_POSTGRES_HOST_PORT`, default `5434`) and `projection-postgres` (`REEF_PROJECTION_POSTGRES_HOST_PORT`, default `5433`) services so command intake/idempotency and projection writes can be measured independently from canonical worker commits. Startup applies the same forward migrations to `postgres`, `boundary-postgres`, and `projection-postgres`. If a retained canonical DB is paired with a fresh projection DB, projectors will rebuild historical canonical submit outcomes before fresh stress numbers are comparable; use `make dev-reset` for clean A/B stress baselines.

The worker stats endpoint includes global counters, per-partition counters (`partitionMetrics`), and durable-log consumer snapshots (`consumerMetrics`). JetStream snapshots include pending, ack-pending, redelivery count, ack-floor sequence, delivered sequence, and stream lag. Redpanda snapshots report committed offset, end offset, and lag for the assigned partition. Local in-flight age is reported for messages fetched by a worker but not yet terminally handled.

Run the submit-only stream-ack stress profile:

```bash
make dev-stress-stream-ack
```

This starts the stream-ack stack, enables all partition workers, runs `1000,2500,5000` rps submit-only steps, writes reports under `/tmp/reef-stream-ack-stress`, and attaches stream-worker before/after global and per-partition deltas to each report. Because stream-ack completion is asynchronous after `202`, the stream-ack wrapper waits up to `DEV_STRESS_STREAM_ACK_DRAIN_WAIT_MS` before final worker sampling so `completedDelta` reflects durable worker drain instead of only commands finished during the load-generator window. `DEV_STRESS_STREAM_ACK_WORKER_PROBE_TIMEOUT_MS` controls the worker stats HTTP timeout for partitioned durable-log snapshots. Stress telemetry also samples runtime health, hot-path timings, DB pool stats, stream health, stream worker stats, engine health, and Docker container stats into `*-telemetry.ndjson`.

The stream-ack stress target generates a 64-instrument session config under `/tmp/reef-stream-ack-stress` by default so submit traffic has enough independent routing keys to exercise deterministic stream partitions. Set `DEV_STRESS_STREAM_ACK_INSTRUMENTS` to change the generated instrument count, or set `DEV_STRESS_SESSION_CONFIG` to run a fixed session file instead. For isolated reruns on a retained NATS volume, override both `STREAM_ACK_COMMAND_STREAM` and `STREAM_ACK_SUBJECT_PREFIX`; JetStream rejects streams with overlapping subject filters.

When `DEV_STRESS_CAPTURE_STREAM_ACK_WORKERS=1`, stress runs fail by default if stream workers report command-processing failures, ack failures, or a post-drain accepted/completed gap. Override only for diagnostic sweeps:

- `DEV_STRESS_FAIL_ON_STREAM_ACK_WORKER_FAILURES=0|1` toggles the guardrail; default is `1` when worker capture is enabled.
- `DEV_STRESS_MAX_STREAM_ACK_WORKER_FAILED_DELTA=0` sets the allowed worker failure delta.
- `DEV_STRESS_MAX_STREAM_ACK_WORKER_ACK_FAILED_DELTA=0` sets the allowed ack-failure delta.
- `DEV_STRESS_MAX_STREAM_ACK_COMPLETION_GAP=0` sets the allowed gap between accepted API commands and worker-completed commands after the configured drain wait.

Recommendations and KPI quality caps prefer stream-ack-clean samples, so a high-accepted run with async worker failures is not treated as the capacity setting to promote.

Tune diagnostics capture knobs (optional):
- `DEV_STRESS_CAPTURE_DB_DIAGNOSTICS=1`
- `DEV_STRESS_CAPTURE_COMMAND_ACCOUNTING=1`
- `DEV_STRESS_FAIL_ON_ACCOUNTING_GAP=0|1`
- `DEV_STRESS_RUN_ID=<stable-run-id>`
- `DEV_STRESS_RUN_KIND=stress`
- `DEV_STRESS_SCENARIO_ID=<mode-or-scenario>`
- `DEV_STRESS_DB_SERVICE=postgres`
- `DEV_STRESS_DB_USER=reef`
- `DEV_STRESS_DB_NAME=reef`
- `DEV_STRESS_DB_SCHEMAS=runtime,boundary,command_log`
- `DEV_STRESS_DB_LOG_SINCE=30m`
- `DEV_STRESS_RATE_SCHEDULE=drop|precise` controls load-tester rate scheduling (`drop` is the default; `precise` is useful for capacity sweeps with larger worker counts)
- `DEV_STRESS_RATE_QUEUE_DEPTH=<tokens>` sets the rate-token queue depth passed to the load tester; use this with `precise` to make target/scheduled/enqueued/completed gaps explicit.

Stress reports distinguish configured rate from generated and completed load. The load tester reports `loadSchedule.targetRequests`, `scheduled`, `enqueued`, `dropped`, `completed`, `scheduleDeficit`, and `completionDeficit`. Check those fields before treating throughput as a server-side capacity number.

Raw intake benchmarks accept matching run metadata:
- `DEV_INTAKE_RUN_ID=<stable-run-id>`
- `DEV_INTAKE_RUN_KIND=intake-bench`
- `DEV_INTAKE_SCENARIO_ID=raw-intake`
- `DEV_INTAKE_CAPTURE_COMMAND_ACCOUNTING=1` attaches command accounting and drain-rate data to the JSON report.
- `DEV_INTAKE_COMMAND_DRAIN_WAIT_MS=30000` controls how long the runner waits for active commands to drain after load stops.
- `DEV_INTAKE_COMMAND_DRAIN_POLL_MS=1000` controls drain polling frequency.
- `DEV_INTAKE_FAIL_ON_ACCOUNTING_GAP=1` fails the run if the final accounting snapshot reports a gap.

Run replay-pack drift validation against baseline scenario:

```bash
make dev-replay
```

Run matching-engine benchmark baseline:

```bash
make bench-matching-engine
```

Run matching-engine sustained load harness:

```bash
make bench-matching-engine-load
```

The sustained harness runs in-process against the Go engine only, so it isolates matching behavior from HTTP, runtime, queue, and database costs. Defaults target `10k/s` for `30s` and write `summary.json` plus `intervals.csv` under `reports/matching-engine-load/<run-id>/`. Pass `ARGS` to vary the load shape, for example:

```bash
make bench-matching-engine-load ARGS="--rate 10000 --duration 60s --scenario alternating-cross --workers 1 --instruments 1 --min-processed-rate 10000"
```

Run the runtime + engine no-DB benchmark profile:

```bash
make dev-stress-runtime-nodb
```

This profile is for bottleneck isolation only. It starts the normal local runtime and matching-engine path, but configures the platform request path with:

- `RUNTIME_PERSISTENCE=noop`
- `EXTERNAL_API_IDEMPOTENCY_STORE=inmemory`
- `EXTERNAL_API_COMMAND_CAPTURE_MODE=disabled`
- `EXTERNAL_API_COMMAND_LOG_MODE=disabled`
- `EXTERNAL_API_COMMAND_PROCESSING_MODE=sync-result`
- `STREAM_ACK_INTAKE_STORE=inmemory`
- `STREAM_ACK_WORKER_ENABLED=false`
- `STREAM_ACK_PROJECTOR_ENABLED=false`

`noop` runtime persistence keeps reference/auth setup data so validation and authorization still run, but it drops submit outcomes, orders, executions, trades, lifecycle events, canonical rows, and projections. The default no-DB stress wrapper disables trace checks with `DEV_STRESS_TRACE_CHECK_LIMIT=0` because trace/event persistence is intentionally not part of this ceiling test. It also defaults to `DEV_STRESS_RATE_SCHEDULE=precise` and `DEV_STRESS_RATE_QUEUE_DEPTH=200000` so generated-load accounting is visible during ceiling tests.

Transport comparison knobs:

- `ENGINE_TRANSPORT=grpc` uses unary gRPC for runtime-to-engine calls.
- `ENGINE_TRANSPORT=grpc-stream` uses experimental submit-order lanes with persistent bidirectional gRPC streams. The current proof path streams submit commands and keeps cancel/modify on unary gRPC.
- `ENGINE_GRPC_STREAM_LANES=16` sets the number of runtime submit lanes. Commands currently route by deterministic `instrumentId` lane scoring; this should evolve to `venueSessionId + instrumentId` when the runtime command model carries venue session directly.
- `ENGINE_GRPC_STREAM_QUEUE_CAPACITY=100000` sets each lane in-flight capacity.

HTTP boundary comparison knobs:

- `PLATFORM_HTTP_SERVER=jdk` keeps the default JDK `HttpServer` adapter and full local route surface.
- `PLATFORM_HTTP_SERVER=netty` enables the measured Netty hot-path adapter. This adapter intentionally covers the no-DB ceiling-test surface first: setup POSTs for reference/auth seeding, `/health`, `/api/v1/orders/submit`, `/api/v1/commands/{commandId}`, `/internal/perf/hot-path`, `/internal/perf/db-pools`, `/internal/commands/async/stats`, stream-ack/projector status probes, and abuse stats.
- `PLATFORM_NETTY_BOSS_THREADS=1`, `PLATFORM_NETTY_WORKER_THREADS=0`, and `PLATFORM_NETTY_APPLICATION_THREADS=64` tune the Netty adapter. A worker count of `0` uses Netty's default event-loop sizing, while application threads run the hot-path command handler off the IO event loop.

Accepted-async no-DB isolation knobs:

- `EXTERNAL_API_COMMAND_PROCESSING_MODE=accepted-async` makes submit-order intake return a `202` command receipt after validation, idempotency reservation, and in-memory lane enqueue. It does not provide durable acceptance and is benchmark-only unless paired with a durable ingress design.
- `EXTERNAL_API_ACCEPTED_ASYNC_LANES=16` sets in-memory worker lanes. Submit commands route by deterministic `instrumentId` lane scoring to preserve per-instrument order while avoiding obvious small-symbol-set modulo skew.
- `EXTERNAL_API_ACCEPTED_ASYNC_QUEUE_CAPACITY=100000` sets per-lane queued command capacity.
- `EXTERNAL_API_ACCEPTED_ASYNC_IN_FLIGHT_PER_LANE=32` bounds concurrent engine submissions per lane. Raising it can inflate engine wait time by flooding the gRPC stream.
- `EXTERNAL_API_ACCEPTED_ASYNC_OFFER_TIMEOUT_MS=0` keeps enqueue backpressure non-blocking; full lanes return `429 COMMAND_INTAKE_BACKPRESSURE`.
- `GET /internal/commands/async/stats` includes an `acceptedAsync` block with aggregate queued, processing, completed, failed, duplicate, and backpressure counters plus per-lane queue/drain counters for accepted-async no-DB diagnostics.

Each stress report includes `hotPathPhases.phases` from `/internal/perf/hot-path`. For no-DB sync-result runs, start with `api.mutation.total`, `api.operation`, `api.parse.submitOrder`, `runtime.submitOrder.total`, `runtime.engine.submit`, `runtime.persistence.persistSubmitOutcome`, `api.response.serializeSubmitOrder`, and `api.writeResponse`. `runtime.engine.submit` measures the platform runtime gateway call to the engine, including transport and response parsing; compare it against the matching-engine-only harness before attributing that time to matching logic itself.

Use this comparison ladder when isolating throughput collapse:

```text
matching-engine only
runtime + engine + HTTP, no DB/write-model persistence
runtime + engine + boundary DB only
runtime + engine + canonical DB only
full platform with projections
```

Run matching-engine benchmark guardrail check (CI-equivalent):

```bash
make bench-matching-engine-check
```

Run the throughput campaign (quality + capacity lanes with cap summary):

```bash
make dev-throughput-campaign
```

Run campaign with optional intentional-trip abuse lane:

```bash
DEV_CAMPAIGN_INCLUDE_ABUSE_TRIP=1 \
DEV_CAMPAIGN_ABUSE_TRIP_RATES=1200 \
DEV_CAMPAIGN_ABUSE_TRIP_WORKERS=128 \
make dev-throughput-campaign
```

Optional guardrails for intentional-trip lane (enabled by default):

- `DEV_CAMPAIGN_ENFORCE_ABUSE_TRIP_GUARDRAIL=1`
- `DEV_CAMPAIGN_ABUSE_TRIP_MIN_TRIPS=1`
- `DEV_CAMPAIGN_ABUSE_TRIP_MIN_BLOCKS=1`
- `DEV_CAMPAIGN_ABUSE_TRIP_MIN_ABUSE_BLOCKED_FAIL_PCT=1`

Run campaign with an automatic clean reset first (recommended for fair high-rate comparisons):

```bash
DEV_CAMPAIGN_RESET_STACK=1 make dev-throughput-campaign
```

Compare two throughput campaign summaries (baseline vs candidate):

```bash
DEV_CAMPAIGN_BASELINE_SUMMARY=/tmp/reef-throughput-campaign-baseline/throughput-campaign-summary.json \
DEV_CAMPAIGN_CANDIDATE_SUMMARY=/tmp/reef-throughput-campaign/throughput-campaign-summary.json \
make dev-throughput-compare
```

Run ad hoc simulator load against active dev env:

```bash
make dev-sim ARGS="--duration 30s --workers 8 --rate 100 --mode strict-lifecycle --pretty-summary"
```

Simulator mutating traffic uses `/api/v1` routes by default (idempotency + client headers enabled):
- `--use-api-v1=true` (default)
- `--client-id-prefix=sim-client` (default)
- `DEV_SIM_COMMAND_PROCESSING_MODE=sync-result|captured-sync-engine|captured-ack` recreates `platform-api` with that processing mode before running simulator traffic
- `DEV_SIM_COMMAND_LOG_MODE=postgres|inmemory|disabled` overrides simulator-triggered command-log mode; captured simulator modes default this to `postgres`

Optional abuse-breaker guardrail for `/api/v1` writes:
- `EXTERNAL_API_ABUSE_BREAKER_MODE=off|reject-rate` (default `off`)
- `EXTERNAL_API_ABUSE_BREAKER_MAX_REJECTS` (default `50`)
- `EXTERNAL_API_ABUSE_BREAKER_WINDOW_SECONDS` (default `30`)
- `EXTERNAL_API_ABUSE_BREAKER_BLOCK_SECONDS` (default `60`)
- `EXTERNAL_API_ABUSE_BREAKER_REJECT_CODES` (default `INVALID_STATE,NOT_FOUND,REFERENCE_DATA_ERROR,VALIDATION_ERROR`)
- `EXTERNAL_API_ABUSE_BREAKER_ROUTES` (default `/api/v1/orders/submit,/api/v1/orders/modify,/api/v1/orders/cancel`)
- `EXTERNAL_API_ABUSE_BREAKER_ROUTE_POLICIES` (optional `route:maxRejects/windowSeconds/blockSeconds` list)
- `EXTERNAL_API_ABUSE_BREAKER_WARN_ONLY=true|false` (default `false`)

Example enablement:

```bash
EXTERNAL_API_ABUSE_BREAKER_MODE=reject-rate \
EXTERNAL_API_ABUSE_BREAKER_MAX_REJECTS=25 \
EXTERNAL_API_ABUSE_BREAKER_WINDOW_SECONDS=30 \
EXTERNAL_API_ABUSE_BREAKER_BLOCK_SECONDS=90 \
make dev-up
```

Breaker telemetry snapshot:

```bash
curl -s http://127.0.0.1:8080/internal/boundary/abuse/stats
```

Disable boundary route usage only for legacy comparison/debug:

```bash
make dev-sim ARGS="--duration 30s --workers 8 --rate 100 --mode capacity-baseline --use-api-v1=false --pretty-summary"
```

Raw accepted-command intake benchmark:

```bash
DEV_INTAKE_DURATION=30s \
DEV_INTAKE_WORKERS=256 \
DEV_INTAKE_RATE=10000 \
DEV_INTAKE_RATE_SCHEDULE=precise \
DEV_INTAKE_ACTOR_ID_PREFIX=bot \
DEV_INTAKE_ARTIFACT_DIR=/tmp/reef-intake-$(date +%Y%m%d-%H%M%S) \
DEV_INTAKE_REPORT_OUT=/tmp/reef-intake-bench.json \
make dev-intake-bench JS_RUNTIME=node
```

Use this before architecture changes when the goal is to separate `platform-api` `/api/v1/orders/submit` intake capacity from simulator strategy/lifecycle overhead.

`dev-intake-bench` captures pre/post DB diagnostics by default and embeds a `dbDiagnostics` object into the JSON report. It also writes raw snapshots under `*-db-diagnostics-workers-<workers>-rate-<rate>/`. Disable this with `DEV_INTAKE_CAPTURE_DB_DIAGNOSTICS=0`, or adjust schemas with `DEV_INTAKE_DB_SCHEMAS=runtime,boundary,command_log`.

When command accounting is enabled, the report also includes `commandAccounting` with before/after/drained snapshots, accepted rps, completed-during-load rps, active backlog after load, final active depth, final accounting gap, drain seconds, post-load drained count, and post-load drain rps. This is the preferred local gate for captured-ack throughput work because accepted rps alone does not prove the worker queue can drain.

## Command-log lifecycle

Prune terminal command-log history with a dry-run-first dev tool:

```bash
make dev-command-log-prune
```

The default mode reports how many completed/failed command records are eligible for pruning but does not delete rows. It never selects active `command_work_queue` rows. Apply pruning explicitly:

```bash
DEV_COMMAND_LOG_PRUNE_APPLY=1 \
DEV_COMMAND_LOG_PRUNE_OLDER_THAN=24h \
make dev-command-log-prune
```

Useful knobs:
- `DEV_COMMAND_LOG_PRUNE_OLDER_THAN=24h` accepts `ms`, `s`, `m`, `h`, or `d`
- `DEV_COMMAND_LOG_PRUNE_BATCH_SIZE=50000`
- `DEV_COMMAND_LOG_PRUNE_MAX_BATCHES=100`
- `DEV_COMMAND_LOG_PRUNE_VACUUM=1`
- `DEV_COMMAND_LOG_PRUNE_CAPTURE_DB_DIAGNOSTICS=1`

Vacuum runs after applied pruning using `PARALLEL 0` to reduce Docker shared-memory pressure. If vacuum still fails locally, pruning remains applied and the report records the vacuum error; rerun later with more Docker shared memory or `DEV_COMMAND_LOG_PRUNE_VACUUM=0`.

Protect a named run/session before pruning by adding a retention pin. Intake and load-test idempotency keys start with a session prefix, so `idempotency_prefix` is the usual selector:

```bash
DEV_COMMAND_LOG_PIN_ACTION=upsert \
DEV_COMMAND_LOG_PIN_SELECTOR_TYPE=idempotency_prefix \
DEV_COMMAND_LOG_PIN_SELECTOR_VALUE=intake-1782954356175044000 \
DEV_COMMAND_LOG_PIN_REASON="keep post-prune benchmark run" \
make dev-command-log-pin
```

List pins:

```bash
make dev-command-log-pin
```

Delete a pin:

```bash
DEV_COMMAND_LOG_PIN_ACTION=delete \
DEV_COMMAND_LOG_PIN_SELECTOR_TYPE=idempotency_prefix \
DEV_COMMAND_LOG_PIN_SELECTOR_VALUE=intake-1782954356175044000 \
make dev-command-log-pin
```

Supported selectors are `command_id`, `idempotency_prefix`, `trace_id`, `correlation_id`, and `client_id`. Prune excludes pinned commands in addition to active queue rows.

For loaded local benchmark databases, use `DEV_COMMAND_LOG_PRUNE_OLDER_THAN=0s` only when all unpinned terminal command history can be discarded. Export important audit history or add retention pins before pruning.

30-minute fixed-load soak (clean reset recommended first):

```bash
DEV_STRESS_DURATION=30m \
DEV_STRESS_MODE=capacity-baseline \
DEV_STRESS_PROFILE=capacity-heavy \
DEV_STRESS_RATES=2500 \
DEV_STRESS_SWEEP_WORKERS=128 \
DEV_STRESS_TRACE_CHECK_LIMIT=500 \
DEV_STRESS_TELEMETRY_INTERVAL_MS=1000 \
DEV_STRESS_MIN_SUCCESS_RATE_PCT=0 \
DEV_STRESS_ARTIFACT_DIR=/tmp/reef-soak-30m-$(date +%Y%m%d-%H%M%S) \
DEV_STRESS_REPORT_OUT=/tmp/reef-soak-30m.json \
make dev-stress JS_RUNTIME=node
```

10-minute investigative soak with DB diagnostics (fast root-cause pass before long soak):

```bash
DEV_STRESS_DURATION=10m \
DEV_STRESS_MODE=capacity-baseline \
DEV_STRESS_PROFILE=capacity-heavy \
DEV_STRESS_RATES=2500 \
DEV_STRESS_SWEEP_WORKERS=128 \
DEV_STRESS_TRACE_CHECK_LIMIT=500 \
DEV_STRESS_TELEMETRY_INTERVAL_MS=1000 \
DEV_STRESS_MIN_SUCCESS_RATE_PCT=0 \
DEV_STRESS_ARTIFACT_DIR=/tmp/reef-soak-diagnostics-$(date +%Y%m%d-%H%M%S) \
DEV_STRESS_REPORT_OUT=/tmp/reef-soak-diagnostics.json \
make dev-stress-diagnostics JS_RUNTIME=node
```

## Admin CLI To Dev Env

Admin operations against the active runtime API:

```bash
make dev-admin CMD="instrument-upsert AAPL AAPL"
make dev-admin CMD="participant-upsert participant-1 'Participant 1'"
make dev-admin CMD="account-upsert account-1 participant-1"
make dev-admin CMD="traces trace-1-1"
```

Reports are written to `/tmp` as:
- `/tmp/reef-load-report-dev-stress-rate-100.json`
- `/tmp/reef-load-report-dev-stress-rate-200.json`
- `/tmp/reef-load-report-dev-stress-rate-300.json`
- `/tmp/reef-load-report-dev-stress-rate-400.json`

Additional stress artifacts:
- `/tmp/reef-load-report-dev-stress-telemetry.ndjson`
- `/tmp/reef-load-report-dev-stress-recommendation.json`
- `/tmp/reef-load-report-dev-stress-kpi.json`
- `/tmp/reef-load-report-dev-stress-kpi.md`

## Environment contract

Compose sets:
- runtime persistence: `RUNTIME_PERSISTENCE=postgres`
- DB bootstrap mode: `RUNTIME_DB_BOOTSTRAP_MODE=validate` (`compat` remains a local repair fallback)
- runtime DB JDBC: `RUNTIME_POSTGRES_JDBC_URL` (`currentSchema=runtime` remains configured, but runtime storage uses explicit `runtime.*` and `auth.*` names)
- boundary idempotency persistence: `EXTERNAL_API_IDEMPOTENCY_STORE=postgres`
- boundary command capture persistence: `EXTERNAL_API_COMMAND_CAPTURE_MODE=postgres`
- optional append-only command-log capture: `EXTERNAL_API_COMMAND_LOG_MODE=disabled|postgres|inmemory` (default `disabled`). Postgres command-log mode stores immutable intake rows in `command_log.commands`, durable request payloads in `command_log.command_payloads`, active worker state in `command_log.command_work_queue`, and terminal status/responses in `command_log.command_results`.
- command-log payload mode: `EXTERNAL_API_COMMAND_LOG_PAYLOAD_MODE=side-table|inline` (default `side-table`). `side-table` keeps hot command metadata rows narrow while retaining the full durable request payload for worker replay.
- runtime role: `PLATFORM_RUNTIME_ROLE=api|worker|projector|materializer`. Compose sets this per service; there is no all-in-one runtime role.
- HTTP server adapter: `PLATFORM_HTTP_SERVER=jdk|netty` (default `jdk`). `netty` is currently a hot-path benchmark adapter for submit/status/internal stats, not a full replacement for every local route.
- command processing mode: `EXTERNAL_API_COMMAND_PROCESSING_MODE=sync-result|captured-sync-engine|captured-ack|stream-ack|accepted-async` (default `sync-result`; captured modes require command-log capture, `stream-ack` requires a configured durable stream provider, and `accepted-async` is an in-memory no-DB isolation mode)
- async command worker: `EXTERNAL_API_COMMAND_ASYNC_WORKER_ENABLED=false|true` (default `false`; when `true` with `captured-ack`, queued command-log records are processed in the background)
- async command worker tuning: `EXTERNAL_API_COMMAND_ASYNC_WORKER_THREADS`, `EXTERNAL_API_COMMAND_ASYNC_WORKER_BATCH_SIZE`, `EXTERNAL_API_COMMAND_ASYNC_WORKER_POLL_MS`, and `EXTERNAL_API_COMMAND_ASYNC_WORKER_LEASE_MS`
- async command worker runtime pool: `EXTERNAL_API_COMMAND_ASYNC_WORKER_DEDICATED_RUNTIME_POOL_ENABLED=true` makes captured-ack workers execute through a separate `async-runtime` persistence pool. The dev default is `false` because captured-ack intake no longer uses runtime persistence on the hot accept path; turn it on for isolation A/B runs.
- async command worker lease: claimed `PROCESSING` rows are reclaimable after `EXTERNAL_API_COMMAND_ASYNC_WORKER_LEASE_MS`; this prevents runtime restarts from permanently stranding in-flight commands.
- async command worker stats: `GET /internal/commands/async/stats` returns worker settings, active queue status counts from `command_log.command_work_queue`, and async claim/complete/fail counters. Postgres mode does not count historical terminal results on this hot probe.
- accepted-async tuning: `EXTERNAL_API_ACCEPTED_ASYNC_LANES`, `EXTERNAL_API_ACCEPTED_ASYNC_QUEUE_CAPACITY`, `EXTERNAL_API_ACCEPTED_ASYNC_IN_FLIGHT_PER_LANE`, and `EXTERNAL_API_ACCEPTED_ASYNC_OFFER_TIMEOUT_MS` tune the in-memory no-DB accepted-async isolation path.
- DB pool stats: `GET /internal/perf/db-pools` returns Hikari pool name plus active/idle/total/waiter counts for runtime-managed pools.
- DB pool sizing: `RUNTIME_DB_POOL_MAX` and `RUNTIME_DB_POOL_MIN_IDLE` are global defaults. Named hot-path pools apply conservative role defaults so those values do not multiply directly across every pool. Override individual pools with `RUNTIME_DB_POOL_<POOL>_MAX` and `RUNTIME_DB_POOL_<POOL>_MIN_IDLE`, where `<POOL>` is the uppercase pool id with punctuation converted to underscores, such as `COMMAND_LOG` or `ASYNC_RUNTIME`.
- legacy/internal mutation routes: `PLATFORM_LEGACY_MUTATION_ROUTES_ENABLED=true` in local compose; POSTs to `/orders/*` and `/reference/*` must include `X-Reef-Internal-Route: true`
- boundary DB JDBC: `RUNTIME_DB_URL` (`currentSchema=boundary` remains configured, but boundary storage uses explicit `boundary.*` names)
- stream-ack partition workers: `platform-worker-0` through `platform-worker-3` default to four explicit non-overlapping `16`-partition ranges over the default `STREAM_ACK_PARTITION_COUNT=64`.
- venue event materializer: `PLATFORM_RUNTIME_ROLE=materializer`, `EXTERNAL_API_COMMAND_PROCESSING_MODE=stream-ack`, and `STREAM_ACK_LOG_PROVIDER=redpanda` starts the Kafka-compatible event-batch materializer. `VENUE_EVENT_MATERIALIZER_ENABLED=true` can also start it in another background-capable role for local experiments.
- venue event materializer compose service: `platform-materializer` is enabled by the `venue-event-materializer` compose profile and exposes diagnostics on `REEF_PLATFORM_MATERIALIZER_HOST_PORT` (default `8091`).
- venue event materializer tuning: `VENUE_EVENT_MATERIALIZER_TOPIC` defaults to `MATCHING_ENGINE_EVENT_STREAM` or `REEF_VENUE_EVENTS`; `VENUE_EVENT_MATERIALIZER_GROUP_ID`, `VENUE_EVENT_MATERIALIZER_BATCH_SIZE`, `VENUE_EVENT_MATERIALIZER_POLL_MS`, `VENUE_EVENT_MATERIALIZER_FETCH_TIMEOUT_MS`, and `VENUE_EVENT_MATERIALIZER_KAFKA_MAX_POLL_RECORDS` tune consumption.
- venue event materializer stats: `GET /internal/venue-event-materializer/stats` reports enabled state, role, source, batch/poll config, and materialization counters.

Postgres init creates domain schemas:
- `runtime`
- `auth`
- `admin`
- `boundary`

Runtime, auth, and boundary persistence validates migrated schema objects by default in Docker/local startup. Set `RUNTIME_DB_BOOTSTRAP_MODE=compat` only for local repair/debug if a migration path needs to be bypassed temporarily.

`make dev-up`, `make dev-reset`, and `make dev-db-migrate` apply SQL files under `scripts/dev/db/migrations/` in deterministic domain order and record checksums in `public.reef_schema_migrations`. Use `$(JS_RUNTIME) scripts/dev/db/migrate.mjs --dry-run` to validate order/checksums without touching Docker.

Schema-placement verification:
- `PostgresSchemaMigrationIntegrationTest` is opt-in.
- run it with `RUNTIME_POSTGRES_JDBC_URL_TEST=jdbc:postgresql://localhost:5432/reef`, `RUNTIME_POSTGRES_USER_TEST=reef`, and `RUNTIME_POSTGRES_PASSWORD_TEST=reef` after `make dev-up` or `make dev-db-migrate`.

`.env` support:
- all `scripts/dev/*.mjs` load `.env` and `.env.local` automatically
- explicit shell environment variables still override file-based values

Related:
- [DB split-readiness guardrails](./DB_SPLIT_READINESS.md)
- [Observability dev profile runbook](./OBSERVABILITY_DEV_PROFILE.md)
- [Simulation + app KPI matrix](./SIMULATION_KPIS.md)
