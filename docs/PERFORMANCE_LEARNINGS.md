# Reef Performance Learnings

## Purpose

Capture practical speed and impact lessons so performance stays a design constraint during normal delivery, not a late-stage recovery effort.

## Core Learnings

1. Measure accepted business throughput, not just raw request throughput.
2. Separate business rejections from infrastructure failures before diagnosing performance.
3. Run isolated benchmark sessions for baseline numbers; do not run parallel load tests when establishing capacity.
4. Use connection pooling by default for any persistent store path.
5. Batch lifecycle/event writes on hot paths to reduce round-trips.
6. Avoid aggregate scans (`MAX(...)`) in write-heavy paths; prefer sequence counters or append-safe allocators.
7. Keep benchmark modes explicit:
   - `capacity` mode to stress infra limits
   - `strict-lifecycle` mode to stress correctness and state-machine behavior
8. Always compare clean-reset and aged-state runs. Short tests can look healthy while persistence pressure is building.

## Aged-State Soak Learnings (May 26, 2026)

From a 30-minute fixed-load soak (`capacity-baseline`, `capacity-heavy`, `2500 rps`, `workers=128`):

1. Runtime sustained ~`2412 rps` total and ~`2179 rps` accepted with `p95 ~59ms`.
2. Infra remained stable (no transport failure burst), but Postgres showed heavy WAL/checkpoint pressure:
   - frequent WAL-triggered checkpoints (~every 35-40s)
   - large write amplification in `buffers_backend` and `buffers_alloc`
3. Runtime domain tables grew rapidly (GB-scale in a single soak):
   - `runtime_events`, `executions`, `trades`, `submit_results`, `orders`

Immediate implications:

1. Throughput target is reachable, but long-run stability depends on datastore lifecycle controls.
2. Postgres WAL/checkpoint tuning and data retention/partitioning are not optional follow-up items.

## DigitalOcean Stream-Ack Soak Learnings (July 3, 2026)

From a clean single-droplet DO soak (`stream-ack`, `7500 rps`, `workers=384`, `5m`):

1. The current shape is not a healthy `7500 rps` soak configuration:
   - `867415` attempted
   - `388260` accepted, about `1281 accepted/sec`
   - `310807` worker completed, about `1025 completed/sec`
   - `268782` projected, about `887 projection work items/sec`
2. Protective `429` backpressure worked, but the reject taxonomy showed downstream drain pressure rather than API instability:
   - projector lag backpressure dominated
   - worker stream lag backpressure followed
   - worker failed and ack-failed counters stayed clean
3. Accepted/sec alone is the wrong success target. The next milestone is `2000 completed/sec` sustained for at least `5m`, with accepted throughput within `5-10%` of completed throughput and a clean post-run drain.
4. Projection/UI freshness must be measured separately from venue-core capacity:
   - `control-room-fresh` mode may reject on projection lag
   - `venue-core` mode should report projection lag without letting it define canonical command capacity
5. Runtime and projection Postgres both showed heavy CPU/write pressure. Future runs must report rows/command, WAL bytes/command, commits/command, and partition skew before scaling workers or projectors.

Immediate implications:

1. Do not keep rerunning `7500/384` at the same config.
2. Run venue-core canonical ablations until `2000 completed/sec` is stable for a `5m` soak, then promote to `5000/sec`, then `7500/sec`.
3. Run projector catch-up and hot-partition versus even-distribution ablations before broad scaling.
4. Treat projection write amplification and partition skew as first-class bottleneck suspects.
5. Do not accept configured-rate results as benchmark evidence unless actual attempted and accepted RPS meet the active target; the July 3 follow-up runs under-delivered actual load even when response failures were clean.

Current fix batch:

- Use the deploy-shaped stream-ack profile with `64` partitions, `4` worker processes, and `4` projector processes before the next `2k/sec` soak.
- Keep `venue-core` as the default DO drain-backpressure policy so projection lag is measured but does not throttle canonical command acceptance.
- Gate DO reports on actual attempted and accepted RPS, defaulting to `2000/sec` for the current milestone.

## Stream-Ack Sunset Checkpoint (July 3, 2026)

The `64`-partition, `4`-worker, `4`-projector, venue-core soak did not recover the target and should be treated as a stop point for this architecture shape, not as a prompt for more small tuning.

Clean DO run: `reports/do-benchmark/do-benchmark-20260703T195008Z`

- configured load: `4000 rps`, `768` load-generator workers, `5m`
- actual attempted/accepted: `1636.20 rps`, `496128` accepted, `0` HTTP failures
- worker completed: `1486.67 rps`
- projected: `819.98 rps`, ending projection lag `202156`
- API phase average: `19.52ms`, including `7.77ms` reserve, `6.66ms` publish ack, and `4.13ms` backpressure
- runtime Postgres: about `2.18KB` WAL per accepted command and `4.42` commits per worker-completed command
- projection Postgres: about `6.24KB` WAL per accepted command, with `runtime_events`, `executions`, `trades`, `submit_results`, `orders`, and trace tables dominating write amplification

Conclusion: this stack preserved correctness semantics better than the older path, but it is not a credible base for the `2k/sec` with headroom target. Further work should pivot to a new design rather than continue incremental stream-ack/Postgres projection tuning.

## Matching/Runtime No-DB Checkpoint (July 3, 2026)

After isolating DB writes with `RUNTIME_PERSISTENCE=noop`, the matching engine itself is no longer the current limiter for multi-instrument submit-only runs.

Engine-only evidence:

- `reports/matching-engine-load/resting-15k-30s-heap/summary.json`
- scenario: `resting-book`, `16` instruments, `15000 rps`, `30s`
- result: `450000` processed, `15000.17/sec`, `0` failures, `p95=4us`, `p99=7us`

Full no-DB runtime evidence:

- `/tmp/reef-runtime-nodb-grpc-stream-multi-15k-30s-heap/runtime-nodb-grpc-stream-multi-15k-30s-heap-rate-15000-workers-512.json`
- config: `ENGINE_TRANSPORT=grpc-stream`, `16` stream lanes, `512` load workers, `30s`
- result: `12269.28/sec`, `100%` success, `p95=80.52ms`, `p99=103.15ms`, `runtime.engine.submit avg=4.30ms`
- raising `PLATFORM_HTTP_THREADS` from `64` to `80` did not materially improve throughput and increased engine wait to about `5.09ms`

Accepted-async no-DB evidence:

- `/tmp/reef-runtime-nodb-accepted-async-15k-30s/runtime-nodb-accepted-async-15k-30s-rate-15000-workers-512.json`
- config: `EXTERNAL_API_COMMAND_PROCESSING_MODE=accepted-async`, `ENGINE_TRANSPORT=grpc-stream`, `16` stream lanes, `512` load workers, `30s`
- result: `10033.86/sec`, `100%` success, all responses `202`, `p95=91.48ms`, `p99=116.96ms`, `api.mutation.total avg=0.267ms`
- worker telemetry showed the first one-at-a-time lane worker shape accepted faster than it drained: `received=280170`, `completed=71691`, `queued=208469`, `backpressured=0`
- an oversized pipelined worker window (`EXTERNAL_API_ACCEPTED_ASYNC_IN_FLIGHT_PER_LANE=256`) raised acceptance to about `12042.35/sec` in a 15s run, but inflated `runtime.engine.submit avg` to `65.23ms`; this is stream flooding, not matching-engine compute
- after Compose was updated to pass accepted-async tuning into the container, 15s `15000 rps` sweeps showed:
  - window `16`: `8726.23/sec`, `p95=106.88ms`, `runtime.engine.submit avg=16.68ms`
  - window `64`: `10323.06/sec`, `p95=92.91ms`, `runtime.engine.submit avg=37.66ms`
  - window `128`: `10319.32/sec`, `p95=91.67ms`, `runtime.engine.submit avg=72.13ms`
- raising load workers from `512` to `1024` at the selected `64` window reduced throughput to `8320.41/sec` and raised `p95` to `236.84ms`, so the remaining accepted-async ceiling is not simply too few load-generator workers
- default accepted-async in-flight is therefore `64` per lane for the current no-DB isolation path; `128` did not improve accept throughput and materially worsened engine wait

Netty hot-path no-DB evidence:

- `/tmp/reef-runtime-nodb-netty-accepted-async-w64-15k-30s/runtime-nodb-netty-accepted-async-w64-15k-30s-rate-15000-workers-512.json`
- config: `PLATFORM_HTTP_SERVER=netty`, `EXTERNAL_API_COMMAND_PROCESSING_MODE=accepted-async`, `EXTERNAL_API_ACCEPTED_ASYNC_IN_FLIGHT_PER_LANE=64`, `ENGINE_TRANSPORT=grpc-stream`, `16` stream lanes, `512` load workers, `30s`
- result: `449926` successful `202` responses, `14996.88/sec`, `100%` success, `p50=3.02ms`, `p95=20.55ms`, `p99=46.57ms`
- schedule target was `450000`; the run missed only `74` completions with no failures or generated-load drops
- hot-path telemetry: `api.mutation.total avg=0.04ms`, `runtime.engine.submit avg=7.20ms`
- a shorter 15s confirmation also hit the target: `14987.25/sec`, `100%` success, `p95=61.79ms`, `p99=113.62ms`

Coroutine accepted-async drain follow-up:

- after the 5m DO soak showed accepted-async lane queues filling under `20k/sec`, the in-memory no-DB accepted-async drain was moved from hand-rolled blocking lane threads to `kotlinx.coroutines` bounded channels with per-lane drain counters
- the gRPC stream submit client now waits for `ClientCallStreamObserver.isReady()` / `setOnReadyHandler(...)` before `onNext`, so runtime-to-engine streaming respects gRPC outbound flow-control instead of relying only on an application semaphore
- local smoke: `/tmp/reef-runtime-nodb-coroutines-smoke/runtime-nodb-coroutines-smoke-rate-5000-workers-256.json`
- config: `PLATFORM_HTTP_SERVER=netty`, `EXTERNAL_API_COMMAND_PROCESSING_MODE=accepted-async`, `EXTERNAL_API_ACCEPTED_ASYNC_IN_FLIGHT_PER_LANE=64`, `ENGINE_TRANSPORT=grpc-stream`, `16` stream lanes, `5000 rps`, `256` load workers, `10s`
- result: `49955` successful `202` responses, `4995.28/sec`, `100%` success, `p95=8.81ms`, `p99=32.67ms`, `runtime.engine.submit avg=5.78ms`
- local 16-instrument confirmation: `/tmp/reef-runtime-nodb-coroutines-15k-30s-multi/runtime-nodb-coroutines-15k-30s-multi-rate-15000-workers-512.json`
- result: `449927` successful `202` responses, `14997.03/sec`, `100%` success, `p95=34.42ms`, `p99=76.77ms`, `runtime.engine.submit avg=10.21ms`
- DO 20k/sec 5m follow-up: `reports/do-benchmark/do-nodb-coroutines-20k-5m-20260703T234000Z`
- result: `4063814` responses, `2199946` successful `202`, `1863868` `429 COMMAND_INTAKE_BACKPRESSURE`, `13471.31/sec` total, `7292.70/sec` accepted, `54.14%` success, `p95=111.81ms`, `p99=899.59ms`, `runtime.engine.submit avg=149.40ms`
- post-run live stats showed `956751` still queued, `640` processing, and `1242545` completed; the 10 active instrument lanes were still near capacity, confirming the remaining sustained-load ceiling is the runtime-to-engine async drain, not the HTTP intake path
- the first on-ready gRPC send pump plus deterministic lane scoring held a local 15k/sec 30s pass at `EXTERNAL_API_ACCEPTED_ASYNC_IN_FLIGHT_PER_LANE=64`, but overfilled the runtime-to-engine interface: `14990.24/sec`, `100%` success, `p95=66.28ms`, `p99=124.70ms`, `runtime.engine.submit avg=58.94ms`, ending telemetry `activeLaneCount=14`, `queued=76828`, `maxLaneDepth=28792`
- lowering the same pump and lane-scored path to `EXTERNAL_API_ACCEPTED_ASYNC_IN_FLIGHT_PER_LANE=32` kept the local 15k/sec target clean without queue growth: `/tmp/reef-runtime-nodb-pump-lanes-if32-15k-30s-multi/runtime-nodb-pump-lanes-if32-15k-30s-multi-rate-15000-workers-512.json`, `14995.92/sec`, `100%` success, `p95=23.99ms`, `p99=55.75ms`, `runtime.engine.submit avg=10.07ms`, ending telemetry `activeLaneCount=14`, `queued=24`, `maxLaneDepth=6`
- DO 20k/sec 5m retry with the same `32` window did not recover the sustained overload case: `reports/do-benchmark/do-nodb-pump-lanes-if32-20k-5m-20260704T0005Z`, `3960276` responses, `2369760` successful `202`, `1590516` `429 COMMAND_INTAKE_BACKPRESSURE`, `13185.83/sec` total, `7890.17/sec` accepted, `59.84%` success, `p95=121.27ms`, `p99=921.01ms`, `runtime.engine.submit avg=125.34ms`
- DO post-run diagnostics showed all 14 active accepted-async lanes still nearly full (`queued=1370394`, `maxLaneDepth=97968`, `processing=413`, `completed=998939`) while Docker stats showed `reef-platform-api` saturated at about `749%` CPU and `4.35GiB` memory, with `reef-matching-engine` near idle (`~1%` CPU). The long-soak failure is now platform-runtime async queue/transport pressure under sustained backlog, not matching-engine compute.
- the accepted-async default in-flight window is therefore `32` per lane for the no-DB isolation path; `64` is still valid as an explicit stress setting, but it now represents intentional queue pressure rather than the baseline

Immediate implications:

1. Keep the purpose-built price-time book direction. The original heap-backed structure was enough to avoid sorted-slice insertion collapse, and the current ordered price-level/FIFO queue structure preserves that benefit while making cancel/modify direct unlink operations explicit.
2. Do not keep tuning JDK `HttpServer` thread count as the main path to `15k/sec`; the Netty hot-path adapter removed the previous local front-door ceiling for accepted-async no-DB runs.
3. Accepted-async plus Netty proves the API hot path can accept submit commands at the `15k/sec` target on this workstation when DB writes are excluded. This is still not durable acceptance and must not be confused with the production ingress target.
4. Keep accepted-async worker in-flight bounded. The Compose/runtime defaults now expose `EXTERNAL_API_ACCEPTED_ASYNC_IN_FLIGHT_PER_LANE=32`; tune from there with telemetry instead of using unbounded or very large stream windows.
5. A flagged Netty hot-path adapter now exists behind `PLATFORM_HTTP_SERVER=netty` to isolate the JDK `HttpServer` boundary. Treat it as a benchmark adapter until the measured no-DB evidence justifies expanding its route surface.

## Hot Book Sharding Checkpoint (July 4, 2026)

After replacing the matching-engine heap side queues with a shard-ready in-memory book built from ordered price levels, FIFO queues per price, and direct order-id unlinking, local engine-only stress runs showed the matching hot book has materially more headroom than the current durable-ingress path.

Implementation shape:

- `services/matching-engine/internal/book` owns the in-memory book structure.
- `github.com/tidwall/btree` is used only as an ordered price-level index.
- matching semantics, order lifecycle, event IDs, and replay expectations remain Reef-owned.
- one mutable book remains single-writer; scale comes from sharding by deterministic book/partition ownership.

Single hot-book evidence:

- `reports/matching-engine-load/matching-engine-load-20260704T165356Z`: `alternating-cross`, `1` worker, `1` instrument, `15000 rps`, `30s`, `450000` processed, `0` failures, `p95=5us`, `p99=11us`
- `reports/matching-engine-load/matching-engine-load-20260704T165436Z`: `alternating-cross`, `1` worker, `1` instrument, `20000 rps`, `30s`, `600000` processed, `0` failures, `p95=5us`, `p99=10us`
- `reports/matching-engine-load/matching-engine-load-20260704T165513Z`: `alternating-cross`, `1` worker, `1` instrument, `30000 rps`, `30s`, `900000` processed, `0` failures, `p95=5us`, `p99=10us`
- `reports/matching-engine-load/matching-engine-load-20260704T165828Z`: `resting-book`, `1` worker, `1` instrument, `20000 rps`, `30s`, `600000` processed, `0` failures, `p95=4us`, `p99=8us`
- `reports/matching-engine-load/matching-engine-load-20260704T171126Z`: `deep-lifecycle`, `1` worker, `1` instrument, `20000 rps`, `30s`, `600000` processed, `0` failures, `p95=3us`, `p99=6us`

Partitionable multi-book evidence:

- `reports/matching-engine-load/matching-engine-load-20260704T165555Z`: `alternating-cross`, `6` workers, `6` instruments, `60000 rps`, `30s`, `1800000` processed, `0` failures, `p95=4us`, `p99=10us`
- `reports/matching-engine-load/matching-engine-load-20260704T165710Z`: `alternating-cross`, `8` workers, `8` instruments, `80000 rps`, `30s`, `2400000` processed, `0` failures, `p95=4us`, `p99=21us`
- `reports/matching-engine-load/matching-engine-load-20260704T165748Z`: `alternating-cross`, `10` workers, `10` instruments, `100000 rps`, `30s`, `3000000` processed, `0` failures, `p95=4us`, `p99=15us`

Additional guardrail checks:

- `reports/matching-engine-load/matching-engine-load-20260704T164924Z`: documented `10000/sec` one-minute single-book gate passed with `600000` processed and `0` failures.
- `reports/matching-engine-load/matching-engine-load-20260704T165031Z`: `10000/sec` single-book resting growth passed with `300000` processed and `0` failures.
- `reports/matching-engine-load/matching-engine-load-20260704T165108Z`: `10000/sec` lifecycle submit/modify/cancel mix passed with `300000` processed and `0` failures.
- `reports/matching-engine-load/matching-engine-load-20260704T165147Z`: `40000/sec`, `4` workers, `4` instruments partitionability check passed with `1200000` processed and `0` failures.

Conclusion:

1. The matching-engine book is not the active limiter for current durable-ingress and persistence work.
2. The sharding model is validated at the engine-only layer: one hot book is clean through `30000/sec`, and multi-book partitionable throughput is clean through `100000/sec` locally.
3. Capacity claims must still distinguish engine-only matching from durable end-to-end throughput. The next durable path work should focus on command-log/event-batch publication, snapshot/replay checksums, and compact canonical materialization.
4. Longer soaks should still be run before treating these numbers as stable operating targets, especially for deep book depth, mixed cancel/modify, and event-batch publication.

## Stream-Ack Broker A/B And Engine Boundary Checkpoint (July 4, 2026)

Local 90-second stream-ack sweeps compared Redpanda and JetStream with `64` partitions, `4` workers, `4` projectors, submit-only traffic, and worker drain telemetry enabled.

Redpanda evidence:

- artifact: `/tmp/reef-stream-ack-redpanda-stress-90s-20260704T011000Z`
- `1000 rps` target: `993.13` accepted/sec, `100%` success, `p95=69.95ms`, worker drain clean
- `2500 rps` target: `2301.89` accepted/sec, `100%` success, `p95=147.08ms`, `failedDelta=20`, no ack failures, drained
- `5000 rps` target: `2292.43` accepted/sec, `100%` success, `p95=175.21ms`, worker drain clean

JetStream evidence:

- artifact: `/tmp/reef-stream-ack-jetstream-stress-90s-20260704T011700Z`
- `1000 rps` target: `999.46` accepted/sec, `100%` success, `p95=50.93ms`, worker drain clean
- `2500 rps` target: `2488.00` accepted/sec, `100%` success, `p95=67.89ms`, worker drain clean
- `5000 rps` target: `3496.86` accepted/sec, `100%` API success, `p95=111.70ms`, but worker drain was not clean:
  - `failedDelta=8200`
  - `completedDelta=279662` versus `314942` accepted
  - last worker error: `engine gRPC SubmitOrder failed with UNAVAILABLE: io exception`

Conclusion:

1. JetStream is reasonable to keep for the next phase; the current evidence does not justify broker churn.
2. The active failure point is the worker-to-matching-engine hot boundary under sustained stream pressure.
3. `202` response success is insufficient benchmark evidence for stream-backed modes. Reports must fail on async worker failures, ack failures, unresolved redelivery, accepted/completed drain gaps, and meaningful partition lag.
4. Per-command unary gRPC from generic workers to the engine should not be treated as the final high-throughput matching architecture.
5. The target path is engine-shard command consumption by assigned partition, ordered batch processing, canonical event-batch publish, then command ack.

## Direct No-DB Command Stream Publish Ceiling (July 4, 2026)

The first DO direct no-DB stream run (`reports/do-benchmark/do-stream-direct-nodb-20260704T021237Z`) kept the matching-engine direct consumer clean but flattened above the 5k target:

- `5000` target: `4997.28/sec` accepted and direct-acked, zero failures
- `10000` target: `5769.90/sec` accepted and direct-acked, zero failures
- `15000` target: `5569.42/sec` accepted and direct-acked, zero failures
- `20000` target: `5430.75/sec` accepted and direct-acked, zero failures

The direct engine path matched every accepted command (`ackedDelta == totalSuccess`) with no NAKs, terms, unsupported commands, or direct failures. The hot phase was API durable publish acknowledgement (`api.streamAck.publishAck` about `5.5ms-5.9ms` average at the higher target rates), with API CPU hotter than matching-engine CPU.

Raw JetStream publish checks on the same DO host showed the broker was not the inherent ceiling for this payload shape:

- synchronous single-client JetStream publish, `768 B` messages: about `8627 msg/sec`
- async publish batch `500`, one client: about `131782 msg/sec`
- async publish batch `500`, eight clients: about `177480 msg/sec`

This points to Reef's API publisher/front-door call pattern rather than JetStream capacity. Follow-up DO probes did not show a simple configuration fix:

- per-request JNATS async publish futures (`STREAM_ACK_PUBLISH_MODE=async`) stayed in the same band: best `5950.53/sec` with `256` load workers
- raising load workers to `1024` moved only to `6165.87/sec` and increased client p50 latency to `163ms-171ms`
- raising JDK HTTP worker threads to `256` reached `6205.04/sec`, but server-side `api.streamAck.totalAvg` jumped to `35.21ms` and `api.streamAck.publishAck` to `17.57ms`

The next implementation should not be more thread-count tuning. Build a dedicated bounded publish pipeline/front-door path that preserves `202` only after durable ack, exposes per-lane queue depth/in-flight/ack latency, and prevents request threads from becoming the concurrency-control mechanism.

The follow-up implementation moved stream-ack submit intake onto the Netty hot path and added `STREAM_ACK_PUBLISH_PIPELINE_ENABLED=true`, which wraps the configured durable publisher in one bounded lane per command partition. The no-DB direct profile now enables this by default with `PLATFORM_HTTP_SERVER=netty`, `STREAM_ACK_PUBLISH_PIPELINE_QUEUE_CAPACITY=8192`, and `STREAM_ACK_PUBLISH_PIPELINE_MAX_IN_FLIGHT_PER_LANE=256`. The next benchmark should compare this path against the July 4 ceiling above and watch `/internal/stream-ack/health` for `publishQueueDepth`, `publishInFlight`, and `publishLaneCount` to distinguish load-generator limits, publish-lane saturation, and matching-engine drain limits.

The first DO confirmation after enabling the publish pipeline held the same clean direct-engine behavior for a 3-minute `10000 rps` no-DB run, but throughput stayed near `7884/sec` with zero failures. Stream health samples showed low queue depth and no direct-engine drain problem, while `api.streamAck.publishAck` averaged about `19ms`. Follow-up telemetry now splits the publish pipeline into queue wait, in-flight slot wait, delegate durable ack, total pipeline time, and per-lane counters so the next run can distinguish JetStream ack latency, lane skew, and load-generator scheduling pressure without broad architecture churn.

Focused telemetry run: `reports/do-benchmark/do-stream-direct-nodb-pipeline-telemetry-10k-90s-20260704T031924Z`

- target: `10000 rps`, `256` workers, `90s`
- result: `704182` accepted/direct-acked, `7822.54/sec`, `0` failures, all `202`
- latency: `p50=26.71ms`, `p95=71.69ms`, `p99=122.42ms`
- direct engine: fetched/processed/published/acked all `704182`; no nacks, terms, unsupported commands, or direct failures during the run
- publish pipeline: `queueWaitAvg=0.61ms`, `slotWaitAvg=0.003ms`, `delegateAckAvg=18.54ms`, `totalAvg=19.15ms`
- last health sample: queue depth `5`, in-flight `104` of `16384`, `0` publish failures/rejections
- conclusion: the current 10k no-DB ceiling is not the matching engine, publish queue capacity, or per-lane in-flight cap. It is dominated by the JetStream durable publish ack path plus API-side publish overhead.

Redpanda async-producer check through the DB-backed stream-ack path: `reports/do-benchmark/do-stream-ack-redpanda-async-producer-2500-5000-10000-90s-20260704T033618Z`

- targets: `2500`, `5000`, and `10000 rps`, `256` workers, `90s` each
- accepted throughput flattened at about `789/sec`, `1390/sec`, and `1422/sec`
- failures: `0`; worker completed and projector projected exactly matched accepted commands with ending lag `0`
- Kafka publish ack averaged about `8ms-10ms`, materially lower than the direct JetStream run's `18.54ms` delegate ack average
- API stream-ack total still averaged about `42ms-78ms`, with DB reserve/backpressure dominating once publish ack improved
- runtime Postgres still wrote about `2304` WAL bytes per accepted command and about `5.87` commits per accepted/worker-completed command
- conclusion: Redpanda/Kafka publish ack improved, but the DB-backed path remains dominated by command-reserve/canonical-write overhead. The useful next benchmark is an apples-to-apples direct no-DB Redpanda path where the matching engine consumes Kafka-compatible command partitions directly.

Implementation response:

- promote the Kafka-compatible producer path from synchronous `send().get(...)` to async `send(record, callback)` completion
- keep `202` gated on the broker ack callback
- preserve explicit command partition routing and Kafka `(partition, offset)` sequence encoding
- enable `acks=all`, idempotence, bounded application in-flight work, batching, and compression through documented runtime knobs
- add matching-engine direct Kafka-compatible consumption for the no-DB path: assigned command topic partitions -> ordered submit batches -> durable venue event topic batches -> offset commit after event publication
- test Redpanda/Kafka direct no-DB as the next durable ingress candidate before spending more time on JetStream thread/queue tuning

Final pause checkpoint: `reports/do-benchmark/do-stream-direct-redpanda-nodb-10k-90s-20260704T040907Z`

- target: `10000 rps`, `256` workers, `90s`
- result: `689900` accepted/direct-acked, `7664.39/sec`, `0` failures, all `202`
- latency: `p50=24.95ms`, `p95=82.29ms`, `p99=126.94ms`
- direct engine: fetched/processed/published/acked all `689900`; no nacks, terms, unsupported commands, or direct failures
- publish pipeline: `queueWaitAvg=0.74ms`, `slotWaitAvg=0.00ms`, `delegateAckAvg=14.66ms`, `totalAvg=15.41ms`
- comparison: Redpanda direct no-DB is functionally clean but did not materially beat the previous JetStream direct no-DB `10k` telemetry point (`7822.54/sec`, delegate ack `18.54ms`)
- conclusion: with DB removed and the engine consuming the durable log directly, the remaining ceiling is still the API/durable-log publish-ack boundary plus front-door scheduling overhead, not matching-engine compute or downstream drain

Local app-side publish batch probe:

- implementation: optional partition-lane batching behind `STREAM_ACK_PUBLISH_PIPELINE_BATCH_SIZE` and `STREAM_ACK_PUBLISH_PIPELINE_BATCH_LINGER_MS`; `202` remains gated on each durable broker ack
- batch run: `/tmp/reef-stream-direct-redpanda-batch-quick`, `10000 rps`, `256` workers, `30s`, batch size `32`, linger `2ms`
- batch result: `259651` accepted/direct-acked, `8642.55/sec`, `0` failures, `p95=63.04ms`, `p99=92.45ms`; publish pipeline `queueWaitAvg=3.06ms`, `delegateAckAvg=10.39ms`, `totalAvg=13.45ms`
- no-batch control: `/tmp/reef-stream-direct-redpanda-nobatch-control`, same target/workers/duration, batch size `1`, linger `0ms`
- no-batch result: `300001` accepted/direct-acked, `9995.62/sec`, `0` failures, `p95=32.29ms`, `p99=55.08ms`; publish pipeline `queueWaitAvg=0.29ms`, `delegateAckAvg=4.73ms`, `totalAvg=5.03ms`
- conclusion: shallow app-side lane batching with linger is not the next bottleneck fix. It adds queue wait and reduces local throughput versus the current no-batch async pipeline. Keep the batch knobs experimental/default-off; future headroom work should focus on producer/front-door configuration and longer soak behavior, or on a deeper ingestion protocol change rather than adding linger to the current request path.

Local stream-ingress prototype:

- implementation: opt-in long-lived TCP line ingress behind `STREAM_INGRESS_ENABLED=1` and `DEV_STRESS_TRANSPORT=stream`; each frame carries the existing submit JSON payload and the server reuses the stream-ack validation and durable publish path, so `202` is still gated on the broker ack
- local Redpanda direct no-DB profile now defaults to `STREAM_ACK_PARTITION_COUNT=16` and `MATCHING_ENGINE_DIRECT_STREAM_PARTITIONS=0..15` to fit a single retained local broker; higher partition counts remain explicit overrides for provisioned environments
- setup lesson: stale benchmark topics filled Redpanda's local partition memory cap (`1060` existing replicas vs `1048` cap), causing matching-engine startup to fail while creating event topics. Delete old benchmark topics or reset the Redpanda volume before fresh high-partition sweeps.
- smoke run: `/tmp/reef-stream-ingress-smoke5`, `1000 rps`, `128` workers, `10s`, Redpanda direct no-DB, stream transport
- smoke result: `9990` accepted/direct-acked, `998.77/sec`, `0` failures, `p95=10.96ms`, `p99=37.29ms`
- 10k run: `/tmp/reef-stream-ingress-10k-local`, `10000 rps`, `256` workers, `30s`
- 10k result: `299950` accepted/direct-acked, `9996.01/sec`, `0` failures, `p95=20.02ms`, `p99=36.98ms`; publish pipeline `queueWaitAvg=0.25ms`, `delegateAckAvg=3.06ms`, `totalAvg=3.31ms`
- above-floor run: `/tmp/reef-stream-ingress-10500-local`, `10500 rps`, `256` workers, `30s`
- above-floor result: `314951` accepted/direct-acked, `10495.92/sec`, `0` failures, `p95=19.77ms`, `p99=34.90ms`; publish pipeline `queueWaitAvg=0.23ms`, `delegateAckAvg=3.36ms`, `totalAvg=3.60ms`
- first 5-minute soak: `/tmp/reef-stream-ingress-10500-5min-local`, `10500 rps`, `256` workers, `300s`, direct-engine batch size `1000`
- first 5-minute result: `3145952` accepted, `10462.32/sec`, `0` API failures, but failed the direct-engine guardrail with `failedDelta=48` and `nackedDelta=43276`
- first 5-minute failure cause: event batches exceeded Sarama's default `Producer.MaxMessageBytes` (`1048576` bytes), e.g. `Attempt to produce message larger than configured Producer.MaxMessageBytes`
- corrected 5-minute soak: `/tmp/reef-stream-ingress-10500-5min-batch500-local`, `10500 rps`, `256` workers, `300s`, direct-engine batch size `500`
- corrected 5-minute result: `3145900` accepted/direct-acked, `10467.14/sec`, `0` failures, `0` NAKs, `p95=26.61ms`, `p99=53.17ms`; publish pipeline `queueWaitAvg=0.28ms`, `delegateAckAvg=3.34ms`, `totalAvg=3.62ms`
- conclusion: removing per-command HTTP request overhead with a long-lived stream ingress gets the no-DB Redpanda direct-engine path over the `10k/sec` single-instance target locally, including a clean 5-minute soak when direct-engine event batches are capped at `500` commands. The next promotion gate is a longer DO soak, followed by venue event batch materialization into compact Postgres canonical rows rather than a return to runtime workers calling the engine. Protobuf/framed command payloads or additional producer/front-door tuning remain follow-up headroom options if needed.

## Stream-Ack Post-Soak Optimization Priorities

This section is retained as historical context from before the sunset checkpoint above. Do not treat it as the active delivery path.

The macro architecture is now in the right family: durable stream ingress, ordered partition workers, canonical facts, and async projections. The remaining performance risk is that the hot path still performs too much database and projection work per command.

Highest-value fixes after the next ablations:

1. Collapse canonical writes while preserving replay and audit:
   - consider compact command/event records or worker-batch event records
   - preserve partition sequence ranges, stream sequence ranges, command counts, event counts, payload format, checksum, and command lookup
   - reduce rows/command, commits/command, WAL bytes/command, and hot indexes
2. Make projectors cheaper:
   - coalesce repeated aggregate updates inside a batch
   - write final current state once per batch
   - move nonessential timeline/search/report writes to slower rebuildable jobs
   - use staging/merge paths and unlogged caches only for rebuildable projection data
3. Treat partition skew as domain signal:
   - even-distribution and hot-book tests measure different capacities
   - more partitions do not fix one legitimately hot instrument that must preserve order
4. Tune configuration only with measurement:
   - NATS pull batch, `MaxAckPending`, worker fetch loop, DB batch size, and pool sizes should move together
   - raising pending limits or pool sizes can hide overload if the DB write path remains the limiter
5. Provision for practical headroom:
   - `2-3x` subsystem headroom over the current target is acceptable when cost and complexity are reasonable
   - avoid `10x` cost/complexity jumps or broad brute-force scaling that hides avoidable write amplification
6. Avoid barely-stable milestones:
   - a `2000 completed/sec` run with saturated CPU/IO, growing lag, slow drain, or no credible path to `5000/sec` is not a capacity win
   - prefer bottleneck-removing changes and practical overcapacity over small parameter nudges that only make one run pass
7. Keep the hard pivot explicit:
   - JetStream as the canonical event log and Postgres as projection/query storage is a reserve option only if compact canonical Postgres append remains the ceiling
   - adopting that option would require a new architecture decision, retention/replay/checksum requirements, and an updated audit/query story

First batch fix applied before the next DO soak:

1. Remove duplicate API-side publish-marker DB pressure from the stream-ack throughput profile.
2. Batch worker publish-marker repair before JetStream ack.
3. Stop duplicating submit lifecycle events into per-event canonical rows in throughput mode; keep the full outcome payload in canonical command results.
4. Avoid nonessential canonical query indexes in throughput mode.
5. Raise worker/projector batch and ack-pending headroom together so the next run tests a meaningfully different drain shape.

## Runtime Library Investigation Priorities

Before swapping libraries, benchmark candidates against Reef's actual command, persistence, and simulator workloads.

Priority order:

1. Runtime DB write path and batching:
   - pgjdbc + HikariCP prepared batches
   - explicit multi-row inserts
   - `reWriteBatchedInserts`
   - `CopyManager`/`COPY` only for append-only bulk, archive, report, and replay-load paths
2. Runtime JSON parser/serializer:
   - current baseline
   - `kotlinx.serialization` as the first typed default candidate
   - DSL-JSON only for ultra-hot DTO spikes after validation behavior is stable
3. Runtime HTTP boundary stack:
   - current JDK `HttpServer`
   - Ktor Netty
   - Vert.x Web
4. Go simulator and fallback codecs:
   - standard library baseline
   - `goccy/go-json`, `sonic`, `segmentio/encoding/json`, `easyjson`
   - tuned `net/http` versus `fasthttp`
   - `klauspost/compress` for archive/report compression

Reference plan:
- [`docs/PERFORMANCE_LIBRARY_INVESTIGATION.md`](./PERFORMANCE_LIBRARY_INVESTIGATION.md)

## Industry Patterns To Apply

Use these patterns as implementation priorities for sustained high-throughput operation:

1. Keep hot write paths single-purpose and append-friendly; defer non-critical fan-out work asynchronously.
2. Partition and age off high-volume runtime tables so long soaks do not force full-table growth in the hot path.
3. Tune checkpoint/WAL behavior for sustained write workloads, then validate with soak diagnostics (not just short burst tests).
4. Add explicit ingress protection (rate limits/circuit breakers) so malformed or abusive client traffic cannot starve valid flow.
5. Use disciplined retry/backoff behavior to avoid synchronized retry storms under partial failure.

Reef mapping (near-term):

1. Keep `dev-stress` as the primary harness, and run with DB diagnostics enabled before long soak campaigns.
2. Prioritize runtime event lifecycle controls (partitioning/retention/archival) as the first DB durability milestone.
3. Treat `checkpoints_req` growth and rapid table-size expansion as release-blocking signals for sustained-rate goals.

## Performance Budgets

Use these as initial guardrails for simulator/dev-env iteration:

1. Accepted throughput regression budget: no more than 10% drop versus latest baseline.
2. p95 latency regression budget: no more than 20% increase versus latest baseline.
3. Infra failure budget: unexpected `5xx` responses must remain near zero under normal baseline load.
4. Trace integrity budget: trace checks should pass at 99%+ in standard benchmark runs.

## Benchmark Discipline

For performance-sensitive changes, record:

1. Full benchmark command and mode.
2. Test window and date.
3. Throughput (`total` and `accepted`).
4. Latency (`p50`, `p95`, `p99`).
5. Top errors and top reject reasons.
6. Runtime and datastore utilization snapshot.

Store results in `docs/DEV_STRESS_BASELINE_*.md` and link the latest run from sprint trackers.

## PR Performance Checklist

Include this in PR descriptions for runtime/engine/simulator/dev-env changes:

- [ ] Baseline command(s) included
- [ ] Before/after throughput reported (`total` + `accepted`)
- [ ] Before/after p95 latency reported
- [ ] Top errors and reject reasons reported
- [ ] Any new tunables documented (env vars, defaults, expected range)
- [ ] Risk notes added (what could regress under different workloads)

## Operational Defaults

1. Optimize for deterministic reproducibility first, then for peak throughput.
2. Treat performance regressions as test failures when they exceed the budgets above.
3. Add lightweight phase timing (`validate`, `engine`, `persist`) in hot paths before deeper refactors.
4. Prefer reversible tuning flags over one-way hardcoded changes.

## Cross-References

- Steering index: [`docs/steering/README.md`](./steering/README.md)
- Architecture steering: [`docs/steering/architecture.md`](./steering/architecture.md)
- Work plan: [`docs/WORK_PLAN.md`](./WORK_PLAN.md)
- Performance library investigation: [`docs/PERFORMANCE_LIBRARY_INVESTIGATION.md`](./PERFORMANCE_LIBRARY_INVESTIGATION.md)
- Current stress baseline: [`docs/DEV_STRESS_BASELINE_2026-05-23.md`](./DEV_STRESS_BASELINE_2026-05-23.md)
