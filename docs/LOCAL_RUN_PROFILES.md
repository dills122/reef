# Local Run Profiles

This runbook names the local flows that are safe to use for demos, comparison
runs, and materializer-backed throughput work. Use it to choose the right stack,
monitor settings, and stress command before starting a run.

## Profile Matrix

| Goal | Proper entrypoint | Expected runtime roles | Materializer? | Primary evidence |
| --- | --- | --- | --- | --- |
| Local Control Room / stream-ack demo | `make dev-up-stream-ack` + `make dev-stress-stream-ack` | `platform-api`, 4 workers, 4 projectors, NATS JetStream | No | accepted/completed/projected, worker/projector lag |
| Active durable materializer path | `make dev-smoke-venue-event-materializer`, then `make dev-stress-venue-event-materializer` | API, matching engine direct consumer, Redpanda, materializer(s), projectors for read models | Yes | accepted/direct-acked/materialized gap, materializer failures, projector lag |
| Front-door / direct-ingress ceiling | `make dev-stress-stream-direct-nodb` | API, matching engine direct consumer, broker | No canonical materializer by default | accepted/direct-acked throughput and ingress latency |
| Legacy comparison | `make dev-stress-captured-ack` | API plus captured-ack async worker | No | command accounting gap and async queue health |

Do not compare these profiles as if they prove the same thing. The generic
JetStream `stream-ack` worker path is useful for local demos and regression
comparison. The active durable-canonical throughput path is Redpanda direct
stream plus venue-event materializer.

## Control Room

Start the read-only monitor:

```bash
make dev-control-room
```

Open `http://127.0.0.1:3015`.

For the default stream-ack demo, do not set materializer URLs. The stack does
not run `platform-materializer`, so an offline materializer row is not a stream
ack failure.

For materializer-backed runs, include materializer endpoints:

```bash
REEF_CONTROL_ROOM_PROFILE=materializer-soak \
REEF_CONTROL_ROOM_MATERIALIZER_URLS=http://127.0.0.1:8091,http://127.0.0.1:8092,http://127.0.0.1:8093,http://127.0.0.1:8094 \
make dev-control-room
```

When you want Control Room to show run reports, write artifacts under
`/tmp/reef-control-room/runs/<run-id>/` and set `DEV_STRESS_REPORT_OUT` inside
that directory.

## Stream-Ack Demo Flow

Use this when you want a local page showing live worker/projector throughput
and a simple submit-only stress run.

Start or refresh the stack:

```bash
PLATFORM_INTERNAL_HTTP_MODE=enabled make dev-up-stream-ack
```

Run a named 5k/sec, 60s demo and write artifacts for Control Room:

```bash
DEV_STRESS_RATES=5000 \
DEV_STRESS_DURATION=60s \
DEV_STRESS_SWEEP_WORKERS=256 \
DEV_STRESS_RATE_SCHEDULE=precise \
DEV_STRESS_RATE_QUEUE_DEPTH=300000 \
DEV_STRESS_ARTIFACT_DIR=/tmp/reef-control-room/runs/stream-ack-5k-60s \
DEV_STRESS_REPORT_OUT=/tmp/reef-control-room/runs/stream-ack-5k-60s/stream-ack-5k-60s.json \
make dev-stress-stream-ack
```

If the stack is already running and you intentionally want to avoid a stack
restart, apply the stream-ack stress profile before importing `stress.mjs`:

```bash
DEV_STRESS_RATES=5000 \
DEV_STRESS_DURATION=60s \
DEV_STRESS_SWEEP_WORKERS=256 \
DEV_STRESS_RATE_SCHEDULE=precise \
DEV_STRESS_RATE_QUEUE_DEPTH=300000 \
DEV_STRESS_ARTIFACT_DIR=/tmp/reef-control-room/runs/stream-ack-5k-60s \
DEV_STRESS_REPORT_OUT=/tmp/reef-control-room/runs/stream-ack-5k-60s/stream-ack-5k-60s.json \
bun -e "import { applyStressProfile } from './scripts/dev/lib/dev-stress-profiles.mjs'; applyStressProfile('stream-ack'); await import('./scripts/dev/stress.mjs');"
```

Avoid running `bun scripts/dev/stress.mjs` directly for stream-ack unless
`DEV_STRESS_SESSION_CONFIG` is explicitly set. The stream-ack profile generates
a spread submit session config. Without it, a run can concentrate traffic on one
partition, trigger worker backpressure, and report a high 429 failure rate that
is a bad test shape rather than a platform result.

The stress harness fails fast when `profile=stream-submit`, stream-ack worker or
projector diagnostics are enabled, and `DEV_STRESS_SESSION_CONFIG` is missing.
Use `DEV_STRESS_ALLOW_MISSING_SESSION_CONFIG=1` only for deliberate low-level
experiments where hot-partition behavior is the point of the test.

Expected stream-ack roles:

- `platform-api` serves command intake on `8080`.
- `platform-worker-0..3` consume 64 command partitions.
- `platform-projector-0..3` project normalized submit read models.
- `platform-materializer` is not expected.

Interpretation:

- `materialized=0` is normal in this profile.
- Worker/projector failures should stay `0`.
- Final worker lag and projector lag should drain to `0`.
- Backpressure rejects mean the profile accepted only the work it could safely
  drain; investigate partition spread, worker lag, and rate before treating the
  result as a capacity claim.

## Materializer Flow

Use this for the active durable-canonical path. It uses Redpanda/Kafka-compatible
command and event topics, direct matching-engine consumption, and
venue-event materializer runtime roles.

Validate profile settings:

```bash
make dev-validate-stream-profile PROFILE=materializer-soak
```

Run correctness smoke before throughput:

```bash
make dev-smoke-venue-event-materializer
```

Run a local materializer stress. Defaults are `10k/sec` for `180s`; override
rate and duration for a shorter local check:

```bash
DEV_STRESS_RATES=5000 \
DEV_STRESS_DURATION=60s \
DEV_STRESS_ARTIFACT_DIR=/tmp/reef-control-room/runs/materializer-5k-60s \
DEV_STRESS_REPORT_OUT=/tmp/reef-control-room/runs/materializer-5k-60s/materializer-5k-60s.json \
make dev-stress-venue-event-materializer
```

Expected materializer roles:

- Redpanda is running.
- Matching engine consumes command partitions directly.
- `platform-materializer` is running, with scaled materializers when the scaled
  profile is enabled.
- Generic JetStream stream workers are stopped or disabled for this run shape.

Interpretation:

- `accepted/direct-acked` gap must be `0`.
- `accepted/materialized` gap must be `0` after drain.
- Materializer `failed` and `ackFailed` deltas must be `0`.
- Projector lag should drain after materialization.
- This is the path to use before promotion gates such as `make do-materializer-10k-gate`.

## Direct No-DB Ceiling Flow

Use this only to measure API/front-door, durable broker append, and
matching-engine direct-consume headroom without Postgres canonical materializer
cost in the hot comparison.

```bash
make dev-stress-stream-direct-nodb
```

This flow intentionally disables generic stream workers/projectors and trace
validation. It is useful for finding ingress or broker ceilings, but it is not a
durable canonical materialization result.

## Supported Tuning Knobs

These are the supported local knobs for current run profiles. Prefer these over
editing Compose files or calling lower-level scripts directly.

### Shared Stress Knobs

| Knob | Applies to | Purpose |
| --- | --- | --- |
| `DEV_STRESS_RATES` | all stress wrappers | Comma-separated target rates, for example `5000` or `5000,10000`. |
| `DEV_STRESS_DURATION` | all stress wrappers | Step duration such as `60s`, `180s`, or `5m`. |
| `DEV_STRESS_SWEEP_WORKERS` | all stress wrappers | Comma-separated load-tester client worker counts. |
| `DEV_STRESS_RATE_SCHEDULE` | all stress wrappers | Use `precise` for capacity runs; default harness mode may drop scheduled work under pressure. |
| `DEV_STRESS_RATE_QUEUE_DEPTH` | all stress wrappers | Load-generator queue capacity for high-rate precise schedules. |
| `DEV_STRESS_REPEAT_SAMPLES` | all stress wrappers | Repeat each rate/worker sample for comparison. |
| `DEV_STRESS_TRACE_CHECK_LIMIT` | stream-ack and captured-ack | Number of sampled commands to validate through read/projection surfaces. |
| `DEV_STRESS_ARTIFACT_DIR` | all stress wrappers | Directory for reports, telemetry, KPI, and diagnostics. |
| `DEV_STRESS_REPORT_OUT` | all stress wrappers | Base JSON report path; stepped reports append rate/worker suffixes. |
| `DEV_STRESS_MIN_SUCCESS_RATE_PCT` | all stress wrappers | Guardrail for end-to-end success rate. |
| `DEV_STRESS_CAPTURE_DB_DIAGNOSTICS` | stress wrappers | Capture Postgres diagnostic artifacts when set to `1`. |
| `DEV_STRESS_DB_SERVICES` | DB diagnostics | Comma-separated Compose DB services to inspect, for example `postgres,boundary-postgres`. |
| `DEV_STRESS_SESSION_CONFIG` | all stress wrappers | Explicit load-tester session config. Leave unset for wrappers that generate spread configs. |
| `DEV_STRESS_ALLOW_MISSING_SESSION_CONFIG` | direct low-level experiments only | Bypasses the stream-ack session-config guardrail; do not use for normal stream-ack evidence. |
| `DEV_STRESS_TRANSPORT` | direct no-DB | `http` or `stream`; use `stream` only with stream ingress enabled. |
| `DEV_STRESS_STREAM_ADDRESS` | direct no-DB stream transport | Host and port for long-lived stream ingress, default `127.0.0.1:8090`. |

### Control Room Knobs

| Knob | Default | Purpose |
| --- | --- | --- |
| `REEF_CONTROL_ROOM_HOST` | `127.0.0.1` | Bind host. |
| `REEF_CONTROL_ROOM_PORT` | `3015` | Bind port. |
| `REEF_CONTROL_ROOM_RUNTIME_URL` | derived local runtime URL | Platform API to probe. |
| `REEF_CONTROL_ROOM_ENGINE_URL` | derived local engine URL | Matching engine URL shown in config. |
| `REEF_CONTROL_ROOM_PROFILE` | `materializer-soak` when materializer URLs are set; otherwise `stream-ack` | Expected role profile for Control Room warnings. Supported current values are `stream-ack`, `materializer-soak`, and `direct-nodb`. |
| `REEF_CONTROL_ROOM_STATE_DIR` | `/tmp/reef-control-room` | Run artifact root. |
| `REEF_CONTROL_ROOM_WORKER_URLS` | worker ports `8082,8083,8086,8087` | Stream-ack worker diagnostics. |
| `REEF_CONTROL_ROOM_PROJECTOR_URLS` | projector ports `8084,8085,8088,8089` | Projector diagnostics. |
| `REEF_CONTROL_ROOM_MATERIALIZER_URLS` | unset | Materializer diagnostics for materializer-backed profiles only. |

### Stream-Ack Stack Knobs

Use these with `make dev-up-stream-ack` or `make dev-stress-stream-ack`.

| Knob | Default in profile | Purpose |
| --- | --- | --- |
| `PLATFORM_INTERNAL_HTTP_MODE` | `enabled` for stress wrapper | Enables `/internal/*` diagnostics for local monitoring. |
| `STREAM_ACK_LOG_PROVIDER` | `jetstream` | Durable command log provider; `redpanda` is for comparison, not the default stream-ack demo. |
| `STREAM_ACK_COMMAND_STREAM` | `REEF_COMMANDS` | Command stream name. |
| `STREAM_ACK_SUBJECT_PREFIX` | `reef.cmd.v1` | Command subject prefix. |
| `STREAM_ACK_PARTITION_COUNT` | `64` | Command partition count. |
| `STREAM_ACK_INTAKE_STORE` | `postgres` | Boundary intake/idempotency store. |
| `STREAM_ACK_WORKER_ENABLED` | `true` | Enables stream workers in worker containers. |
| `STREAM_ACK_WORKER_BATCH_SIZE` | `1000` | Worker fetch/process batch size. |
| `STREAM_ACK_WORKER_MAX_ACK_PENDING` | `4000` | JetStream worker ack-pending cap. |
| `STREAM_ACK_WORKER_0_PARTITIONS` through `STREAM_ACK_WORKER_3_PARTITIONS` | non-overlapping `0..63` ranges | Worker partition ownership. |
| `STREAM_ACK_PROJECTOR_ENABLED` | `true` | Enables canonical submit projectors. |
| `STREAM_ACK_PROJECTOR_BATCH_SIZE` | `2000` | Projector batch size. |
| `STREAM_ACK_PROJECTOR_0_PARTITIONS` through `STREAM_ACK_PROJECTOR_3_PARTITIONS` | non-overlapping `0..63` ranges | Projector partition ownership. |
| `STREAM_ACK_MAX_WORKER_STREAM_LAG` | `50000` | Venue-core intake backpressure threshold. |
| `STREAM_ACK_MAX_PROJECTOR_LAG` | `0` | Projector lag reporting threshold; not the venue-core gate unless policy changes. |
| `STREAM_ACK_DRAIN_BACKPRESSURE_POLICY` | `venue-core` | Backpressure policy used by local deploy-shaped stream-ack. |
| `STREAM_ACK_MARK_PUBLISHED_MODE` | `worker` | API returns after durable publish; workers repair publish markers. |
| `RUNTIME_DB_POOL_STREAM_INTAKE_API_MAX` | `64` | API intake DB pool max. |
| `RUNTIME_DB_POOL_STREAM_RUNTIME_MAX` | `24` | Worker/runtime DB pool max. |
| `RUNTIME_DB_POOL_STREAM_RUNTIME_PROJECTION_MAX` | `24` | Projection DB pool max. |
| `DEV_STRESS_STREAM_ACK_INSTRUMENTS` | `64` | Number of generated instruments for stream-ack spread session config. |
| `DEV_STRESS_CAPTURE_STREAM_ACK_WORKERS` | `1` in stream-ack stress | Attach worker delta diagnostics. |
| `DEV_STRESS_CAPTURE_STREAM_ACK_PROJECTOR` | follows worker capture unless overridden | Attach projector delta diagnostics. |
| `DEV_STRESS_STREAM_ACK_DRAIN_WAIT_MS` | `15000` | Wait for worker drain before final report. |
| `DEV_STRESS_FAIL_ON_STREAM_ACK_WORKER_FAILURES` | `1` when worker capture is enabled | Fail reports on worker failure/ack-failure/gap guardrails. |

### Materializer Stack Knobs

Use these through `make dev-stress-venue-event-materializer`. The script sets
the required stack shape and validates `materializer-soak` before running.

| Knob | Default in profile | Purpose |
| --- | --- | --- |
| `STREAM_ACK_LOG_PROVIDER` | `redpanda` | Kafka-compatible durable command log. |
| `DEV_COMPOSE_PROFILES` | adds `redpanda,venue-event-materializer,venue-event-materializer-scaled` | Enables broker and materializer roles. |
| `STREAM_ACK_COMMAND_STREAM` | `REEF_MATERIALIZER_STRESS_COMMANDS` | Isolated materializer stress command topic/stream. |
| `STREAM_ACK_SUBJECT_PREFIX` | `reef.materializer.stress.cmd.v1` | Isolated command subject prefix. |
| `STREAM_ACK_COMMAND_STREAM_MAX_BYTES` | `34359738368` | High-capacity command log bound for stress. |
| `STREAM_ACK_PARTITION_COUNT` | `16` | Local Redpanda partition count. |
| `STREAM_ACK_INTAKE_STORE` | `inmemory` | API-front-door intake store for materializer stress. |
| `STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES` | `100000` | Bounds replay/idempotency retention. |
| `STREAM_ACK_INMEMORY_INTAKE_SHARDS` | `256` | Intake shard count. |
| `STREAM_ACK_PUBLISH_PIPELINE_ENABLED` | `true` | Enables partitioned publish pipeline. |
| `STREAM_ACK_PUBLISH_PIPELINE_QUEUE_CAPACITY` | `8192` | Publish lane queue capacity. |
| `STREAM_ACK_PUBLISH_PIPELINE_MAX_IN_FLIGHT_PER_LANE` | `256` | Durable publish concurrency per lane. |
| `STREAM_ACK_WORKER_ENABLED` | `false` | Generic stream workers are not the materializer path. |
| `MATCHING_ENGINE_DIRECT_STREAM_ENABLED` | `true` | Matching engine consumes command partitions directly. |
| `MATCHING_ENGINE_DIRECT_STREAM_PARTITIONS` | `0..15` | Engine direct-consume partition ownership. |
| `MATCHING_ENGINE_DIRECT_STREAM_BATCH_SIZE` | `500` | Engine direct-consume batch size. |
| `MATCHING_ENGINE_DIRECT_STREAM_MAX_ACK_PENDING` | `16000` | Broker ack-pending cap for engine consumer. |
| `MATCHING_ENGINE_EVENT_STREAM` | `REEF_MATERIALIZER_STRESS_VENUE_EVENTS` | Event-batch topic/stream. |
| `MATCHING_ENGINE_EVENT_SUBJECT_PREFIX` | `reef.materializer.stress.venue.events.v1` | Event-batch subject prefix. |
| `VENUE_EVENT_MATERIALIZER_ENABLED` | `true` | Enables materializer loop in materializer roles. |
| `VENUE_EVENT_MATERIALIZER_TOPIC` | event stream name | Materializer source topic/stream. |
| `VENUE_EVENT_MATERIALIZER_GROUP_ID` | `reef-venue-event-materializer-stress` | Materializer consumer group. |
| `VENUE_EVENT_MATERIALIZER_BATCH_SIZE` | `1000` | Materializer Postgres commit batch size. |
| `VENUE_EVENT_MATERIALIZER_POLL_MS` | `10` | Materializer poll interval. |
| `VENUE_EVENT_MATERIALIZER_FETCH_TIMEOUT_MS` | `200` | Materializer fetch timeout. |
| `MATCHING_ENGINE_TERMINAL_ORDER_RETENTION_LIMIT` | `250000` | Bounds terminal order retention for materializer stress. |
| `DEV_STRESS_MATERIALIZER_INSTRUMENTS` | `64` | Generated instrument count for materializer spread config. |
| `DEV_STRESS_CAPTURE_STREAM_DIRECT` | `1` | Attach direct engine consume deltas. |
| `DEV_STRESS_CAPTURE_VENUE_EVENT_MATERIALIZER` | `1` | Attach materializer deltas. |
| `DEV_STRESS_VENUE_EVENT_MATERIALIZER_URLS` | materializer ports `8091..8094` when scaled | Materializer diagnostic endpoints. |
| `DEV_STRESS_VENUE_EVENT_MATERIALIZER_DRAIN_WAIT_MS` | `60000` | Wait for materializer drain before final report. |
| `DEV_STRESS_MAX_STREAM_DIRECT_COMPLETION_GAP` | `0` | Guardrail for accepted/direct-acked gap. |
| `DEV_STRESS_MAX_VENUE_EVENT_MATERIALIZER_COMPLETION_GAP` | `0` | Guardrail for accepted/materialized gap. |
| `DEV_STRESS_STOP_IDLE_BACKGROUND_SERVICES` | `1` | Stops unused generic workers/projectors before stress. |

### Direct No-DB Knobs

Use these through `make dev-stress-stream-direct-nodb`.

| Knob | Default in profile | Purpose |
| --- | --- | --- |
| `STREAM_ACK_LOG_PROVIDER` | `jetstream`; set `redpanda` for Kafka-compatible comparison | Durable command log provider. |
| `STREAM_ACK_COMMAND_STREAM` | `REEF_DIRECT_NODB_COMMANDS_V2` | Isolated direct no-DB command stream/topic. |
| `STREAM_ACK_SUBJECT_PREFIX` | `reef.direct.nodb.v2.cmd.v1` | Isolated subject prefix. |
| `STREAM_ACK_COMMAND_STREAM_MAX_BYTES` | `34359738368` | High-capacity command log bound. |
| `STREAM_ACK_INTAKE_STORE` | `inmemory` | Front-door intake/idempotency store. |
| `STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES` | `100000` | Bounds replay/idempotency retention. |
| `STREAM_ACK_INMEMORY_INTAKE_SHARDS` | `256` | Intake shard count. |
| `STREAM_ACK_PUBLISH_PIPELINE_ENABLED` | `true` | Enables partitioned publish pipeline. |
| `STREAM_ACK_PUBLISH_PIPELINE_QUEUE_CAPACITY` | `1024` | Publish lane queue capacity. |
| `STREAM_ACK_PUBLISH_PIPELINE_MAX_IN_FLIGHT_PER_LANE` | `256` | Durable publish concurrency per lane. |
| `STREAM_ACK_WORKER_ENABLED` | `false` | Generic stream workers disabled. |
| `STREAM_ACK_PROJECTOR_ENABLED` | `false` by default | Projection disabled unless explicitly evaluating direct projection. |
| `MATCHING_ENGINE_DIRECT_STREAM_ENABLED` | `true` | Matching engine consumes commands directly. |
| `MATCHING_ENGINE_DIRECT_STREAM_BATCH_SIZE` | `500` | Engine direct-consume batch size. |
| `MATCHING_ENGINE_DIRECT_STREAM_MAX_ACK_PENDING` | `16000` | Broker ack-pending cap. |
| `STREAM_INGRESS_ENABLED` | `false` | Enable only for long-lived stream transport tests. |
| `STREAM_INGRESS_PORT` | `8090` | Local stream ingress port. |
| `DEV_STRESS_STREAM_DIRECT_INSTRUMENTS` | profile-generated default | Generated instrument count for direct spread config. |
| `DEV_STRESS_CAPTURE_STREAM_DIRECT` | `1` | Attach direct engine consume deltas. |
| `DEV_STRESS_STREAM_DIRECT_DRAIN_WAIT_MS` | `30000` | Wait for engine direct-consume drain before final report. |

### Validation And Promotion Knobs

| Knob / command | Purpose |
| --- | --- |
| `make dev-validate-stream-profile PROFILE=stream-direct-nodb` | Validates direct no-DB local profile before a run. |
| `make dev-validate-stream-profile PROFILE=noop-ceiling` | Validates no-op publisher ceiling profile; not a durability claim. |
| `make dev-validate-stream-profile PROFILE=materializer-soak` | Validates active materializer soak settings. |
| `make do-materializer-10k-gate` | Named remote/local promotion gate wrapper for the materializer path. |

Low-level env vars not listed here may still exist for tests, migrations, or
experiments, but they are not considered supported local run knobs unless a
profile wrapper prints, validates, or documents them.

## Quick Triage

If a run looks wrong:

1. Check which run is selected in Control Room. The newest run should be first,
   but clicking an older run pins that selection until refresh/reconnect.
2. Check expected roles for the profile. A missing materializer is only a bug in
   materializer-backed profiles.
3. Check whether `DEV_STRESS_SESSION_CONFIG` was generated or supplied. Missing
   stream-ack spread config can create hot-partition backpressure.
4. Check final lag after drain, not only mid-run lag.
5. Use the report JSON and KPI markdown under the artifact directory as the
   source of truth for success rate, gaps, and failure taxonomy.
