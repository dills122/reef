# Persistence Hot Path Ablation - 2026-07-05

## Scope

Goal: isolate persistence-mode slowdown around stream-direct venue-event materialization and downstream projections.

Test profile:

- `make dev-stress-venue-event-materializer`
- `make dev-ablation-ladder`
- `DEV_STRESS_MODE=strict-lifecycle`
- `DEV_STRESS_PROFILE=capacity-heavy`
- `STREAM_ACK_PARTITION_COUNT=16`
- `VENUE_EVENT_MATERIALIZER_BATCH_SIZE=1000`
- matching engine direct stream enabled
- API hot path uses in-memory intake/idempotency/capture stores
- materializer and projector roles use Postgres persistence

## Fixes Made

- Added durable-canonical materializer accounting via `materializedOutcomes`.
- Added stress guardrails and report fields for durable canonical completion.
- Added venue-event materializer stress and ablation ladder scripts.
- Added `STREAM_ACK_PROJECTOR_INCLUDE_FILLS` to compare lightweight lifecycle projection against execution/trade fill projection.
- Added SQL projection support for executions/trades carried in venue-event result payloads.
- Fixed projector roles in Docker Compose to use `RUNTIME_PERSISTENCE=postgres` during no-op API hot-path profiles.
- Fixed split-store command-outcome projector SQL qualification; unqualified `command_id` failed when joining `command_log.command_payloads`.
- Moved projector sampling after materializer drain so projection measurements include rows created during drain.
- Added optional scaled materializer services and stress defaults so the durable-canonical path can consume Kafka venue-event batches with four materializer instances.
- Added materializer stress cleanup for disabled worker/projector JVMs before the load window.
- Added optional matching-engine terminal order retention for high-volume stress profiles; default engine behavior remains unlimited retention.

## 180s Durable Canonical Evidence

Artifact root: `/tmp/reef-ablation-ladder-final`

Clean 180s ablation after projector fixes, using one materializer:

| Rung | Accepted/sec | Durable-completed/sec | Completion gap | Projected/sec | Projection lag | p95 ms | WAL bytes/cmd | Commits/cmd |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| canonical append only | 9762.69 | 9437.65 | 58517 | 0.00 | 0 | 67.31 | 1846.92 | 0.01 |
| lightweight projection | 9999.87 | 9152.70 | 152489 | 701.41 | 0 | 62.50 | 1848.03 | 0.16 |
| full fill projection | 9999.83 | 9273.59 | 130722 | 1075.43 | 0 | 66.32 | 1846.57 | 0.15 |

This run proves the projector path is live and drains to zero lag after the SQL/persistence fixes. It also shows the single materializer service does not reliably close durable-canonical completion at 10k accepted/sec within the 60s drain window.

Hot write growth is dominated by `runtime.canonical_command_outcomes`; earlier 10k runs showed about 3.33GB WAL and about 1.85KB WAL per accepted command.

## Scaled Materializer Validation

Artifact roots:

- `/tmp/reef-materializer-scaled-60s`
- `/tmp/reef-materializer-scaled-180s`
- `/tmp/reef-materializer-scaled-15k-5m`

The scaled profile runs four Kafka materializer consumers in the same consumer group and aggregates their stats in the stress harness.

| Duration | Accepted/sec | Accepted | Durable materialized | Completion gap | p95 ms | Failures |
|---|---:|---:|---:|---:|---:|---:|
| 60s | 9878.30 | 592890 | 592890 | 0 | 84.47 | 0 |
| 180s | 9981.76 | 1797094 | 1797094 | 0 | 82.38 | 0 |

This closes the durable-canonical gap for accepted commands on the validated runs. The 180s run was against the already-running stack rather than a fresh volume reset, so it should be treated as throughput validation, not a clean ablation comparison.

Initial 15k exploratory run:

- duration: 300s
- workers: 512
- offered throughput: 15000.46 rps
- accepted throughput: 3731.53 rps
- success rate: 24.88%
- accepted: 1119426
- durable materialized: 1119700
- materializer completion gap for accepted work: 0
- p95: 113.90ms
- p99: 474.55ms
- result: failed success-rate guardrail; `reef-platform-api` exited `137`, causing connection refused errors.

The first 15k run showed whole-stack memory pressure. `reef-platform-api` was killed by Docker because idle worker/projector JVMs were also running in the stress stack.

Second 15k run stopped idle background services, which kept the API alive but exposed matching-engine memory growth from retaining every terminal order record. `reef-matching-engine` exited `137` near the end of the run.

Final 15k run after optional terminal order retention:

- duration: 300s
- workers: 512
- offered target: 15000 rps
- completed/accepted throughput: 11529.54 rps
- success rate: 100.00%
- accepted: 3459165
- stream-direct acked: 3459165
- durable materialized: 3794115
- durable gap: 0
- p95: 105.01ms
- p99: 168.39ms
- API status after run: healthy
- matching-engine status after run: healthy
- matching-engine memory ended near 1001MiB

The final run is a real 5-minute 15k offered-load test. The system did not process the full offered schedule; the client completed about 11.5k accepted/sec with 100% success. Materializer output exceeded accepted commands because it also drained backlog from earlier failed attempts.

## Projector Validation

Artifact root: `/tmp/reef-projector-validate`

Targeted validation after projector fixes:

- Targeted 30s run proved projector liveness after the `command_id` SQL qualification fix.
- Clean final ablation then showed both lightweight and full projector rungs with `projectionLagAfter=0`.
- Projector projected/sec is low in the clean ladder because projection measurement starts after materializer drain; the important correctness guardrail is zero projection lag.

## Current Read

- API/engine ingress and direct stream path are not the current limiter at about 10k rps in this profile.
- One materializer is not enough for durable-canonical zero-gap at 10k sustained load on this machine/profile.
- Four materializers close the durable-canonical gap for accepted commands in 60s and 180s validation runs.
- A final 15k/5m offered-load run stays healthy after stopping idle background services and enabling bounded terminal order retention; completed accepted throughput is about 11.5k/sec with zero durable gap.
- Projector persistence and fill-depth toggling now work; both lightweight and full projection rungs drain to zero lag.
- Main write amplification source remains compact canonical command outcome storage.
- Remaining cleanup before PR: run a fresh full ablation with the scaled materializer profile if exact projection-depth cost is needed as release evidence.

## Verification

- `./gradlew test --tests com.reef.platform.infrastructure.persistence.InMemoryRuntimePersistenceTest --tests com.reef.platform.infrastructure.persistence.PostgresVenueEventBatchMaterializationIntegrationTest --tests com.reef.platform.api.PlatformApiTest`
- `node --check scripts/dev/stress.mjs`
- `node --check scripts/dev/venue-event-materializer-stress.mjs`
- `node --check scripts/dev/ablation-ladder.mjs`
- `git diff --check`
