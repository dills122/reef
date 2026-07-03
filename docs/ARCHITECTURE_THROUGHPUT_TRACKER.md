# Architecture Throughput Tracker

## Purpose

Track architecture work needed to move beyond accepted-request intake toward sustained `7500` completed commands per second per runtime + engine instance, with `10000` completed commands per second as the preferred target, while preserving command capture, auditability, deterministic simulation, and zero silent loss.

Primary plan:
- [`docs/ARCHITECTURE_THROUGHPUT_PLAN.md`](./ARCHITECTURE_THROUGHPUT_PLAN.md)
- [`docs/STREAM_ACK_ARCHITECTURE_PLAN.md`](./STREAM_ACK_ARCHITECTURE_PLAN.md)
- [`docs/THROUGHPUT_SCALING_WORK_PLAN.md`](./THROUGHPUT_SCALING_WORK_PLAN.md)
- [`docs/COMMAND_LOG_PARTITIONING_PLAN.md`](./COMMAND_LOG_PARTITIONING_PLAN.md)
- [`docs/DIGITALOCEAN_STRESS_TEST_PLAN.md`](./DIGITALOCEAN_STRESS_TEST_PLAN.md)

Architecture checkpoint:
- D-037 reframes stream-ack as the high-throughput path for a deterministic simulated market venue.
- the main success metric is `completed/sec`: worker processed the command, canonical command result and venue events committed, and JetStream message acked.
- `accepted/sec` is durable ingress capacity, `projected/sec` is read-model catch-up, and `visible/sec` is UI/control-room freshness.
- the next large architectural work is role split, canonical append store, async projections, engine shards, then DigitalOcean benchmark harness.
- engine sharding should not precede canonical append/projection separation unless evidence shows the engine is the current bottleneck.

Current measured reference:
- best local ceiling probe: `2961.43 rps` total, `2919.27 rps` accepted, `98.58%` success
- profile: `capacity-baseline`, target `6500`, workers `768`, gRPC runtime-engine, runtime threads `64`, DB pool max `48`
- capacity model: all targets in this tracker are per runtime + engine instance unless explicitly labeled cluster-wide

Current loaded-stack reference from the Bot Arena planning branch:
- best current-stack ceiling probe on 2026-07-01: `2096.39 rps` total, `2070.02 rps` accepted, `98.74%` success
- profile: `capacity-baseline`, target `3000`, workers `384`, API v1 command path, non-clean local Postgres data volume
- larger target probes (`4000/448`, `5000/512`, `6500/768`) reduced accepted throughput and increased tail latency
- use this as a conservative loaded-instance planning cap until clean-stack and diagnostic runs are repeated

Clean-DB retest reference from the Bot Arena planning branch:
- pre-reset database dump: `/tmp/reef-loaded-before-clean-reset-20260701.dump` (`345MB` compressed, source DB `8431MB`)
- clean database before sweep: `8359kB`, zero live rows in hot runtime/boundary tables
- best clean ceiling probe on 2026-07-01: `2067.95 rps` total, `2042.40 rps` accepted, `98.76%` success
- profile: `capacity-baseline`, target `3000`, workers `384`, API v1 command path
- the clean sweep did not recover the older `~3k accepted rps` ceiling; after four runs the database had grown to about `6049MB`
- current planning cap remains `~2k accepted rps` per runtime + engine instance until phase timing identifies the synchronous hot-path bottleneck

Hot-path timing reference from the Bot Arena planning branch:
- matching-engine microbenchmarks are not the first-order limiter for submit-heavy traffic (`~1.2us` resting submit, `~2.8us` crossing submit)
- API v1 default `3000/384/30s` sample: `1386.53` accepted wall rps, p50 `162ms`
- default hot-path average costs: command capture reserve `5.29ms`, command capture completion `5.25ms`, idempotency save `5.15ms`, runtime operation `11.92ms`
- command capture disabled sample: `1657.40` accepted wall rps, p50 `73.87ms`; still limited by idempotency save and runtime persistence
- command capture disabled + in-memory idempotency sample: `1747.17` accepted wall rps, p50 `45.60ms`; runtime persistence remained `6.35ms avg`
- reduced boundary path at `5000/512/30s` regressed to `1476.60` accepted wall rps; runtime persistence rose to `14.58ms avg`
- diagnosis: the first-order blocker is synchronous runtime persistence/write amplification under concurrency, not accumulated DB bloat alone and not matching-engine compute

Scaling intent:
- reach stable `7500` completed commands/sec per instance before relying on horizontal scale-out.
- prefer `10000` completed commands/sec per instance through stream-ack ingress, partitioned processing, and batched canonical persistence without weakening accounting or replay.
- use per-instance throughput as the unit that cluster capacity multiplies.
- keep cluster-wide tests separate from single-instance ceiling and quality gates.
- treat accepted rps as diagnostic; completed rps, queue drain, and accepted-command accounting are the release gates.

Current captured-ack scaling reference from the Bot Arena planning branch:
- raw durable intake can exceed `7k accepted rps` locally in narrow intake benchmarks.
- async drain now avoids the earlier indexed-claim and stale-lease blockers.
- drain sweep evidence: `4` workers about `2k completed/sec`, `8` workers about `3k completed/sec`, `16` workers about `4k-4.3k completed/sec`, and `24` workers about `4.9k completed/sec` with worse persistence/complete latency.
- DB pools showed no waiter pressure, so the next likely bottleneck is write amplification across command completion and runtime persistence.

Current architecture direction:
- Postgres `captured-ack` remains a correctness baseline and local fallback, but it is not the final high-throughput design for the bot-arena target.
- The next major throughput track is `stream-ack`: durable JetStream publish ack before `202`, deterministic partition routing by run/session/instrument, partition workers that commit canonical Postgres results/events before JetStream ack, and downstream projection workers with watermarks.
- The next stream-ack target is not more command-log tuning. The phase order is role split and explicit partition ownership, canonical append store, async projections, engine shards, then a DigitalOcean benchmark harness.
- Near-term benchmarks must report attempted/sec, accepted/sec, completed/sec, projected/sec where applicable, backlog/lag, DB flush p95/p99, and replay/checksum evidence before claiming progress toward `7500-10000` completed/sec.

Latest accounting smoke:
- 2026-07-02 captured-ack smoke after run attribution, accounting telemetry, backpressure, and terminal-write batching: `DEV_STRESS_DURATION=10s`, `DEV_STRESS_RATES=200`, `DEV_STRESS_SWEEP_WORKERS=16`.
- result: `1999` accepted, `1999` terminal, `0` active, `0` accounting gap, `20/20` trace checks, `199.90 completed/sec`.
- artifact: `/tmp/reef-accounting-smoke/captured-ack-smoke-rate-200-workers-16.json`.

Latest batched-completion sweep:
- 2026-07-02 captured-ack load-tester sweep used `DEV_STRESS_DURATION=15s`, `DEV_STRESS_RATES=15000`, `DEV_STRESS_SWEEP_WORKERS=384`, precise scheduling, and async worker counts `4/8/16/24`.
- artifacts:
  - `/tmp/reef-batch-drain-sweep-4w/captured-ack-4w-rate-15000-workers-384.json`
  - `/tmp/reef-batch-drain-sweep-8w/captured-ack-8w-rate-15000-workers-384.json`
  - `/tmp/reef-batch-drain-sweep-16w/captured-ack-16w-rate-15000-workers-384.json`
  - `/tmp/reef-batch-drain-sweep-24w/captured-ack-24w-rate-15000-workers-384.json`
- the stress runner stretched to about `32s` wall time under saturation; completed-rps accounting now uses the report's actual `durationSeconds` when present instead of only the configured duration.
- accepted wall throughput ranged from about `622` to `1343 rps`; terminal wall throughput ranged from about `418` to `619 rps` during immediate accounting.
- all accepted commands eventually drained with `0` accounting gap.
- this sweep is useful for end-to-end pressure and accounting validation, but the current load tester under-drives the runtime when saturated, so it is not a clean single-instance ceiling signal.

Latest raw-intake sweep after batched completion:
- 2026-07-02 raw-intake sweep used `DEV_INTAKE_DURATION=15s`, `DEV_INTAKE_RATE=15000`, `DEV_INTAKE_WORKERS=384`, precise scheduling, and async worker counts `4/8/16/24`.
- artifacts:
  - `/tmp/reef-batch-raw-4w/intake-workers-384-rate-15000.json`
  - `/tmp/reef-batch-raw-8w/intake-workers-384-rate-15000.json`
  - `/tmp/reef-batch-raw-16w/intake-workers-384-rate-15000.json`
  - `/tmp/reef-batch-raw-24w/intake-workers-384-rate-15000.json`
- `4` workers: `42391` accepted, `2807.70 accepted rps`, p95 `167.12ms`, p99 `212.22ms`; immediate active backlog `30346`, later drained to zero with `0` gap.
- `8` workers: `39516` accepted, `2616.11 accepted rps`, p95 `180.42ms`, p99 `217.26ms`; immediate accounting showed all commands terminal with `0` gap.
- `16` workers: `34115` accepted, `2256.55 accepted rps`, p95 `207.90ms`, p99 `277.64ms`; immediate accounting showed all commands terminal with `0` gap.
- `24` workers: `41291` accepted, `2736.52 accepted rps`, p95 `172.52ms`, p99 `224.26ms`; immediate accounting showed all commands terminal with `0` gap.
- hot-path sample from the `16` worker run: `api.commandCapture.reserve` averaged `16.84ms`, `runtime.persistence.persistSubmitOutcome` averaged `5.01ms`, `async.completeBatch` averaged `52.94ms` per batch, and `async.claim` averaged `1.36ms`.
- conclusion: batched terminal writes reduced one completion mutation surface but did not recover the `7500` completed/sec target. The current loaded-stack bottleneck is back at command-log reserve/write amplification and per-command runtime persistence.
- follow-up tuning removes the retired `commands(status, received_at)` index and makes `command_work_queue` an unlogged derived active-state table. Durable accepted commands remain in logged `command_log.commands`; durable terminal outcomes remain in logged `command_log.command_results`; bootstrap reconstructs active queue rows from non-terminal commands.

Active-queue WAL tuning follow-up:
- 2026-07-02 quick comparisons used the same loaded local stack and `DEV_INTAKE_DURATION=15s`, `DEV_INTAKE_RATE=15000`, `DEV_INTAKE_WORKERS=384`, precise scheduling.
- retired status-index/default-status-only run, `4` async workers, run id `drop-legacy-status-index-4w-15000-384`: `25083` accepted, `1644.24 accepted rps`, p95 `332.98ms`, p99 `372.51ms`; `api.commandCapture.reserve` averaged `18.16ms`; eventual terminal `25083/25083`, `0` active, `0` gap.
- unlogged active queue run, `4` async workers, run id `unlogged-active-queue-4w-15000-384`: `59395` accepted, `3945.78 accepted rps`, p95 `131.15ms`, p99 `209.56ms`; immediate active backlog `11340`, eventual terminal `59395/59395`, `0` active, `0` gap; `api.commandCapture.reserve` averaged `10.62ms`.
- unlogged active queue run, `8` async workers, run id `unlogged-active-queue-8w-15000-384`: `47433` accepted, `3151.87 accepted rps`, p95 `180.13ms`, p99 `238.16ms`; immediate terminal `47433/47433`, `0` active, `0` gap; `api.commandCapture.reserve` averaged `13.41ms`.
- conclusion: removing the retired status index alone did not move the ceiling on this loaded stack. Making the active queue recoverable/unlogged materially reduced append pressure and recovered a `~3.9k accepted rps` loaded-stack point, but still falls short of the `7500` completed/sec target.

Captured-ack profile correction:
- `make dev-up-captured-ack` now defaults `EXTERNAL_API_COMMAND_CAPTURE_MODE=disabled` because `command_log.commands` is the canonical durable capture path for captured-ack.
- with legacy boundary capture still enabled, run id `reserve-buildrecord-executeupdate-4w-15000-384` accepted only `25103` commands, `1646.06 accepted rps`, p95 `332.93ms`, p99 `383.14ms`; `api.commandCapture.reserve` averaged `17.41ms`, while the new inner `api.commandLog.append` span averaged `9.76ms`.
- with legacy boundary capture disabled, `4` async workers, run id `captured-ack-no-legacy-capture-4w-15000-384`: `71559` accepted, `4757.19 accepted rps`, p95 `120.98ms`, p99 `171.11ms`; immediate active backlog `21461`, eventual terminal `71559/71559`, `0` active, `0` gap; `api.commandCapture.reserve` averaged `7.03ms`, `api.commandLog.append` averaged `7.01ms`, and `api.commandCapture.buildRecord` averaged `0.013ms`.
- with legacy boundary capture disabled, `8` async workers, run id `captured-ack-no-legacy-capture-8w-15000-384`: `64958` accepted, `4317.92 accepted rps`, p95 `127.51ms`, p99 `171.43ms`; eventual terminal `64958/64958`, `0` active, `0` gap; `api.commandCapture.reserve` averaged `7.84ms`.
- conclusion: duplicate legacy boundary capture was still present in the captured-ack dev profile and was a material loaded-stack bottleneck. Removing it improves the quick loaded-stack point to `~4.8k accepted rps`, but completed-throughput drain is still below the `7500` target.

Captured-ack idempotency lookup reduction:
- captured-ack now returns the accepted command status from `command_log` immediately after reservation and skips the synchronous `api.idempotency.find` path for new accepted commands. Duplicate replay remains command-log based before reservation.
- `4` async workers, run id `captured-ack-skip-idempotency-4w-15000-384`: `73005` accepted, `4851.68 accepted rps`, p95 `106.80ms`, p99 `161.63ms`; immediate active backlog `61702`, eventual terminal `73005/73005`, `0` active, `0` gap; `api.commandCapture.reserve` averaged `7.38ms`, `api.commandLog.append` averaged `7.36ms`, and `api.idempotency.find` no longer appeared in the hot-path report.
- `8` async workers, run id `captured-ack-skip-idempotency-8w-15000-384`: `25820` accepted, `1688.07 accepted rps`, p95 `330.82ms`, p99 `373.13ms`; immediate terminal `25820/25820`, `0` active, `0` gap; `api.commandLog.append` averaged `12.76ms`.
- conclusion: skipping the redundant idempotency lookup improves the best 4-worker intake point and p95/p99 latency, but higher async worker counts still compete with intake on the loaded local database. Current best quick loaded-stack point is `4851.68 accepted rps` with eventual drain and `0` gap.

Drain-accounted worker sweep:
- 2026-07-02 corrected captured-ack profile used `DEV_INTAKE_DURATION=15s`, `DEV_INTAKE_RATE=15000`, `DEV_INTAKE_WORKERS=384`, precise scheduling, command accounting enabled, and `DEV_INTAKE_COMMAND_DRAIN_WAIT_MS=60000`.
- artifacts:
  - `/tmp/reef-drain-sweep-4w/intake-workers-384-rate-15000.json`
  - `/tmp/reef-drain-sweep-6w/intake-workers-384-rate-15000.json`
  - `/tmp/reef-drain-sweep-8w-rerun/intake-workers-384-rate-15000.json`
  - `/tmp/reef-drain-sweep-12w/intake-workers-384-rate-15000.json`
- `4` workers: `63503` accepted, `4215.75 accepted rps`, p95 `120.05ms`, p99 `162.31ms`; terminal during load `3047`, `202.28 completed/sec`; active after load `60456`; post-load drain `60456` in `26.88s`, `2249.11/sec`; final active `0`, gap `0`.
- `6` workers: `26952` accepted, `1761.90 accepted rps`, p95 `319.87ms`, p99 `358.26ms`; terminal during load `4549`, `297.38 completed/sec`; active after load `22403`; post-load drain `22403` in `13.04s`, `1718.55/sec`; final active `0`, gap `0`.
- `8` workers rerun: `72249` accepted, `4801.95 accepted rps`, p95 `108.24ms`, p99 `152.07ms`; terminal during load `8010`, `532.38 completed/sec`; active after load `64239`; post-load drain `64239` in `17.16s`, `3743.31/sec`; final active `0`, gap `0`.
- `12` workers: `25025` accepted, `1638.22 accepted rps`, p95 `347.30ms`, p99 `400.99ms`; terminal during load `7272`, `476.05 completed/sec`; active after load `17753`; post-load drain `17753` in `6.28s`, `2825.56/sec`; final active `0`, gap `0`.
- hot-path sample from the best `8` worker rerun: `api.commandLog.append` averaged `7.57ms`, `runtime.persistence.persistSubmitOutcome` averaged `1.71ms`, `async.claim` averaged `2.04ms`, and `async.completeBatch` averaged `27.10ms` per batch.
- conclusion: the worker pool cannot be fixed by simply raising thread count. The current corrected profile can accept about `4.8k/sec` on this loaded stack, but completed-during-load throughput is only `~0.5k/sec`; the backlog drains afterward at up to `~3.7k/sec`. The next implementation target should reduce worker-side database work and/or isolate worker persistence from intake contention.
- implementation follow-up: runtime-managed Postgres access now uses named Hikari pools (`runtime`, `async-runtime`, `command-log`, `command-capture`, `idempotency`, `admin-runtime`). Captured-ack workers can execute through a separate `async-runtime` pool with `EXTERNAL_API_COMMAND_ASYNC_WORKER_DEDICATED_RUNTIME_POOL_ENABLED=true`, but the dev default is currently `false` because captured-ack intake no longer uses runtime persistence on the hot accept path. Named pools use conservative role defaults to avoid multiplying the old single-pool `RUNTIME_DB_POOL_MAX`/`MIN_IDLE` settings into too many Postgres clients.
- local A/B after compose flag propagation was fixed, both `8` workers, `DEV_INTAKE_DURATION=8s`, `DEV_INTAKE_RATE=8000`, `DEV_INTAKE_WORKERS=256`: default shared worker runtime pool accepted `31811`, `3952.42 accepted rps`, p99 `130.43ms`, post-load drain `1865.19/sec`; dedicated worker runtime pool accepted `32329`, `4017.18 accepted rps`, p99 `127.63ms`, post-load drain `1879.67/sec`; both reached final active `0` and accounting gap `0`.

## Workstream Status

| ID | Workstream | Status | Target Branch Type | Notes |
|---|---|---|---|---|
| A1 | Runtime phase timing diagnostics | In progress | feature | Internal hot-path endpoint added for boundary/engine/persistence timing |
| A2 | DB pool/write-path diagnostics in stress telemetry | Done | feature | Hikari endpoint, telemetry probe, and reusable pre/post table/checkpoint diagnostics are available |
| A3 | Command log schema and interface | Not started | feature | First DB slice to add |
| A4 | Command capture append mode | Not started | feature | Preserve 100% capture |
| A5 | Command processing mode flags | Done | feature | `sync-result`, `captured-sync-engine`, `captured-ack` |
| A6 | Async batched runtime persistence | Not started | architecture | Biggest likely throughput lever |
| A7 | Runtime event/table partitioning | Not started | architecture | Long-soak stability |
| A8 | Read-model schema/projection isolation | Not started | architecture | Remove projection writes from hot path |
| A9 | Postgres outbox publisher | Deferred | architecture | Still needed for event distribution; no longer a precondition for stream-ack ingress |
| A10 | NATS JetStream stream-ack ingress | Planned | architecture | Durable accepted-command log with publish-ack-before-202 and retained replay window |
| A11 | Physical DB split evaluation | In progress | architecture | Local stream-ack now separates boundary intake, canonical runtime facts, and submit projections across three Postgres containers |
| A12 | Boundary capture hot-path reduction | In progress | architecture | Captured-ack dev profile now disables duplicate legacy boundary command capture; command-log append remains the canonical durable capture path |
| A13 | Runtime table lifecycle/partitioning | Not started | architecture | Loaded stack has multi-GB `runtime_events` and boundary tables |
| A14 | Accepted-command write-ahead path | Done | architecture | `captured-ack` can run configurable async workers from atomically claimed command-log records |
| A15 | Command queue/result split | Done | architecture | Functional split complete; first benchmark regressed ingress, so next work must reduce write amplification |
| A16 | Stored-procedure command intake | Done | architecture | Added as opt-in `EXTERNAL_API_COMMAND_LOG_APPEND_MODE=function`; benchmark regressed reserve latency, so default remains `inline` |
| A17 | Command-log lifecycle controls | In progress | architecture | Dry-run-first terminal pruning and retention pins added; partitioning remains next |
| A18 | Command-log partitioning plan | Done | architecture | Plan chooses live lookup + partitioned archive path instead of in-place range partitioning of `commands` |
| A19 | Async queue indexed claim | Done | performance | Loaded claim query moved from command-table sort/join to `command_work_queue(status, updated_at, command_id)` index; `LIMIT 250` probe dropped from ~1416ms to ~17ms |
| A20 | Async queue lease reclaim | Done | reliability | Stale `PROCESSING` rows are reclaimable after `EXTERNAL_API_COMMAND_ASYNC_WORKER_LEASE_MS`, preventing restart-stranded commands |
| A21 | Completed-throughput target and no-loss plan | Done | planning | Active target is now `7500` completed/sec minimum, `10000` preferred, with no accepted-command accounting gaps |
| A22 | Run/session attribution for throughput runs | Done | feature | `run_id`, `run_kind`, and `scenario_id` are captured on command-log rows from stress/intake payload metadata |
| A23 | Backlog-adjusted stress/intake accounting | In progress | feature | Runtime exposes `/internal/commands/accounting`; stress and intake reports attach accepted, terminal, active, stale, completed-rps, accounting-gap, and drain-time/rate data |
| A24 | Durable-intake backpressure thresholds | In progress | reliability | Postgres captured-ack has active-depth experiments; stream-ack must combine stream, worker, DB, and projection health before acceptance |
| A25 | Batched command completion | Done | performance | Async processor flushes terminal command updates in one transaction per claimed batch; A/B showed no target recovery, so command-log reserve and runtime persistence are the next bottlenecks |
| A26 | Kubernetes lifecycle readiness | Not started | architecture | Readiness, liveness, graceful drain, lease reclaim, and per-pod metric labeling for a basic cluster |
| A27 | Bot-arena venue-path readiness | Not started | architecture | Built-in and user bots must use the same command/API path with run/bot attribution and guardrails |
| A28 | Recoverable active command queue | In progress | performance | `command_work_queue` is derived active state and can be unlogged/reconstructed while accepted commands and terminal outcomes stay durable; quick loaded-stack run recovered `3945.78 accepted rps` with eventual `0` gap |
| A29 | Stream-ack command contract and partitioning | Planned | contract | Command envelope must include run/session/instrument routing metadata and deterministic subject partitioning |
| A30 | Stream-ack idempotency guard | Planned | reliability | Scoped key plus payload hash; same body replays, different body conflicts |
| A31 | Stream partition worker and ack rule | In progress | architecture | Submit workers append canonical command results and venue events before JetStream ack; cancel/modify still need stream-worker support |
| A32 | Canonical event log and projection watermarks | In progress | architecture | Submit projection watermarks and lag are exposed through partition-owned projectors; broader leaderboard/UI projections remain follow-up |
| A33 | Stream-ack crash/replay test matrix | Planned | reliability | Publish retry, redelivery before/after DB commit, deterministic replay, projection rebuild |
| A34 | Stream-ack role split and partition ownership | Done | architecture | Local deploy-shaped profile starts separate API, worker, and projector containers; workers own explicit non-overlapping partition ranges |
| A35 | Canonical append store | Done | architecture | Submit workers append canonical command results and venue events before ack; normalized submit writes moved out of the worker path |
| A36 | Async market-simulation projections | In progress | architecture | `platform-projector-0` through `platform-projector-3` materialize normalized submit read tables from canonical facts with partition-scoped watermarks; broader order/trade/status/timeline/leaderboard/run projections remain follow-up |
| A37 | Engine shard deployment shape | Deferred | architecture | Map partition ranges to engine shards after canonical append/projection separation unless profiling proves engine bottleneck |
| A38 | DigitalOcean benchmark harness | Planned | validation | Test intent and evidence plan documented; next slice is OpenTofu + host-control scaffold for deployed API/workers/projectors/engine/NATS/Postgres evidence |

## Milestone Checklist

### M1: Observability Before Rewrite

- [ ] Add phase timing around boundary validation.
- [x] Add phase timing around idempotency lookup/save.
- [x] Add phase timing around command capture.
- [x] Add phase timing around engine round-trip.
- [x] Add phase timing around runtime persistence.
- [ ] Add phase timing around response serialization.
- [ ] Surface phase timing in stress report summary.
- [x] Add Hikari pool active/idle/wait metrics or debug endpoint.
- [x] Add DB diagnostics snapshot to `dev-stress-diagnostics` for pool and table growth.
- [x] Add checkpoint/WAL-growth evidence to stress diagnostics.
- [ ] Record whether each benchmark is clean-stack, warm-stack, or loaded-stack.
- [ ] Run baseline at `5000/512/60s`.
- [ ] Run ceiling point at `6500/768/60s`.

Exit criteria:
- A report can identify whether p99 is dominated by engine, persistence, idempotency, or runtime queueing.

### M2: Command Log Slice

- [x] Add `command_log` schema migration.
- [x] Add append-only `command_log.commands` table.
- [ ] Add minimal indexes:
  - [x] unique `(client_id, route, idempotency_key)`
  - [x] unique `command_id`
  - [x] processing index `(status, received_at)`
- [x] Add command log storage interface.
- [x] Add Postgres implementation.
- [x] Add in-memory implementation for unit tests.
- [x] Wire command capture to command log behind env flag.
- [x] Add duplicate idempotency tests.
- [x] Add restart/replay test for durable captured command.

Exit criteria:
- Captured command survives runtime restart after acknowledgment.
- Existing synchronous API behavior remains available.

### M3: Command Processing Modes

- [x] Add `EXTERNAL_API_COMMAND_PROCESSING_MODE` config.
- [x] Define response contracts for each mode.
- [x] Implement `sync-result`.
- [x] Implement `captured-sync-engine`.
- [x] Prototype `captured-ack`.
- [x] Add optional async worker for `captured-ack` command-log records.
- [x] Add atomic command-log claiming for async workers.
- [x] Add async command worker thread tuning.
- [x] Add async command queue/drain stats endpoint.
- [x] Run async worker drain sweep.
- [x] Add status lookup API for captured commands.
- [x] Add idempotency replay behavior per mode.
- [x] Add simulator config toggle for mode.

Exit criteria:
- Simulator can run both legacy synchronous and captured command modes without code changes.

Drain follow-up:

- [x] Split immutable command capture from mutable queue lease/status state.
- [x] Move terminal response payloads into a separate command result table.
- [x] Make async queue counts cheap enough for frequent telemetry.
- [x] Re-run raw intake plus drain check after the command queue/result split.
- [x] Add opt-in stored-procedure command intake for append + queue enqueue + duplicate replay.
- [x] Re-run split-schema benchmark after stored-procedure intake.
- [x] Add dry-run-first command-log terminal history prune tooling.
- [x] Add pinned run/session retention before pruning named replay/audit runs.
- [x] Add partitioning plan for command-log commands/results.
- [x] Make async queue claim use the active-queue index instead of sorting joined command history.
- [x] Add async worker lease timeout so stale `PROCESSING` rows can be reclaimed after runtime restart.
- [x] Add captured-ack dev/stress entrypoints using the tested queue settings.
- [x] Add run/session attribution to command-log intake.
- [x] Run wider `4/8/16/24` worker sweep.
- [ ] Reduce remaining split-schema write amplification.
- [x] Add completed-throughput and accounting-gap fields to stress reports.
- [x] Add command drain-rate and time-to-zero-active fields to raw intake reports.
- [x] Add durable-intake overload thresholds that reject before acceptance.
- [x] Add batched terminal result and queue-completion writes.
- [x] A/B batched terminal writes against captured-ack worker and raw-intake profiles.
- [x] Remove retired command-table status index from the hot append path.
- [x] Move active command queue to recoverable unlogged storage.
- [x] Disable duplicate legacy boundary command capture in captured-ack dev/stress profile.
- [x] Add inner reserve timing for command-record build vs command-log append.
- [x] Skip redundant idempotency lookup for new captured-ack accepted commands.
- [x] Split runtime-managed DB pools by hot-path role and add opt-in dedicated runtime persistence for captured-ack workers.
- [x] Split or slim hot command payload writes on command-log reserve.
- [x] Batch per-command runtime persistence writes for captured-ack submit workers.

Latest async drain notes:
- `4` workers drained about `~2k commands/sec`.
- `8` workers drained about `~3k commands/sec`.
- `16` workers drained about `~4k-4.3k commands/sec`.
- `24` workers drained about `~4.9k commands/sec`, but per-command persistence and complete latency worsened.
- DB pools showed no waiter pressure during the sweep, so the next likely bottleneck is per-command runtime persistence and result completion writes.

Latest batched-completion notes:
- end-to-end captured-ack load-tester runs validated eventual drain and `0` accepted-command accounting gap, but did not produce a clean ceiling because the load generator stretched under saturation.
- raw-intake runs on the same loaded stack accepted only `~2.3k-2.8k rps`, far below the earlier narrow `7k+` intake reference.
- `api.commandCapture.reserve` reached `16.84ms avg` in the `16` worker raw-intake sample, so the next high-value slice is command-log write amplification before another worker-count sweep.
- after making the active queue unlogged, the best quick loaded-stack raw-intake point improved to `3945.78 accepted rps` with eventual drain and `0` gap. The next measured blocker remains command-log reserve plus runtime persistence under higher worker counts.
- after disabling duplicate legacy boundary capture in the captured-ack profile, the best quick loaded-stack point improved again to `4757.19 accepted rps`, p99 `171.11ms`, eventual drain, and `0` gap. `api.commandLog.append` now accounts for almost all reserve latency.
- after skipping captured-ack's redundant idempotency lookup, the best quick loaded-stack point improved to `4851.68 accepted rps`, p99 `161.63ms`, eventual drain, and `0` gap. Worker-side persistence/claim pressure is now the reason higher worker counts reduce intake.
- drain-accounted sweeps show best corrected loaded-stack intake at `4801.95 accepted rps` with `8` async workers, but only `532.38 completed/sec` during load and a `64239` command post-load backlog. Final accounting still reached active `0` and gap `0`.
- named pools initially inherited the old global `RUNTIME_DB_POOL_MIN_IDLE=16` for every role and could exhaust local Postgres clients during restarts. Role-specific defaults now cap `runtime`/`async-runtime` lower and keep `command-log` as the only high-capacity pool by default.
- the valid dedicated-vs-shared worker runtime pool A/B was effectively neutral in local captured-ack mode, so the dedicated `async-runtime` pool is opt-in rather than the dev default. Remaining backlog still points at command-log append and per-command async operation/persistence cost rather than Hikari waiters.
- command payload storage now defaults to `EXTERNAL_API_COMMAND_LOG_PAYLOAD_MODE=side-table`: `command_log.commands` keeps a slim `{}` compatibility payload for new commands while the full durable request payload is written to `command_log.command_payloads` and joined for replay/claim/status reads. A quick 8-worker `8k/8s` local A/B reached `3389.44 accepted rps`, p99 `148.11ms`, `1981.06/sec` post-load drain in side-table mode versus `1964.94 accepted rps`, p99 `210.73ms`, `2302.11/sec` post-load drain in inline mode; both reached final active `0` and gap `0`. The physical storage check showed `27313/27313` slim command rows plus payload rows for the side-table run and `0` side-table payload rows for the inline run.
- captured-ack submit workers now prepare a claimed batch, persist submit outcomes in one transaction, and only then mark command-log terminal rows. The first 8-worker `8k/8s` smoke reached `3067.47 accepted rps`, p99 `162.35ms`, `951.49 completed/sec` during load, and `2750.24/sec` post-load drain with final active `0` and gap `0`. A JDBC `executeBatch` variant was tested and rejected for this local profile (`1775.38 accepted rps`, `880.56 completed/sec` during load), so the committed path uses repeated function execution inside one transaction.
- captured-ack submit workers now use one schema-owned `runtime.runtime_persist_submit_outcomes(jsonb)` call per prepared batch instead of repeated app-side single-outcome procedure calls. The first version delegated to the existing single-outcome routine; the current version performs set-based submit-result, order, execution, trade, event, and trace-sequence writes inside the routine while preserving no-complete-before-persist ordering.
- first bulk-procedure `8` worker, `8k/8s` smoke (`bulk-submit-outcomes-8w-8000-256`) accepted `25065` commands, `3110.53 accepted rps`, p99 `146.16ms`, `980.75 completed/sec` during load, and drained `17162` post-load commands in `3.88s` at `4417.50/sec`; final active `0`, gap `0`. Hot-path timings: `api.commandLog.append` averaged `10.94ms`, `runtime.persistence.persistSubmitOutcomes` averaged `104.60ms` across `109` batch calls, and `async.completeBatch` averaged `44.54ms`.
- higher-pressure bulk-procedure `8` worker, `15k/15s` run (`bulk-submit-outcomes-8w-15000-384`) accepted `36604` commands, `2419.81 accepted rps`, p99 `284.75ms`, `2135.68 completed/sec` during load, and drained the remaining `4298` commands in `2.45s` at `1755.72/sec`; final active `0`, gap `0`. Hot-path timings: `api.commandLog.append` averaged `13.15ms`, `runtime.persistence.persistSubmitOutcomes` averaged `166.79ms` across `165` batch calls, `async.completeBatch` averaged `103.78ms`, and DB pools had `0` waiters after drain.
- set-based routine before command-log FK tuning, `8` worker, `8k/8s` run (`setbased-submit-outcomes-8w-8000-256`) accepted `14490` commands, `1779.21 accepted rps`, p99 `217.03ms`, `897.83 completed/sec` during load, and `3701.91/sec` post-load drain; final active `0`, gap `0`. Runtime batch persistence improved to `77.64ms` average, but command-log append rose to `14.52ms` and intake regressed on the aged stack.
- command-log hot child-table foreign keys were then removed from `command_payloads`, `command_work_queue`, and `command_results`; prune now deletes payload, queue, result, and command rows explicitly instead of relying on cascade. Durable acceptance is still anchored by logged `command_log.commands` plus logged `command_payloads`; active queue state remains reconstructable.
- after the FK drop, `8` worker, `8k/8s` run (`fkdrop-setbased-submit-outcomes-8w-8000-256`) accepted `27063` commands, `3358.16 accepted rps`, p99 `141.01ms`, `1065.16 completed/sec` during load, and `2880.14/sec` post-load drain; final active `0`, gap `0`. Hot-path timings: `api.commandLog.append` averaged `9.64ms`, `runtime.persistence.persistSubmitOutcomes` averaged `68.45ms`, and `async.completeBatch` averaged `37.20ms`.
- after the FK drop, `8` worker, `15k/15s` run (`fkdrop-setbased-submit-outcomes-8w-15000-384`) accepted `60406` commands, `4014.61 accepted rps`, p99 `162.54ms`, `2404.21 completed/sec` during load, and drained `24231` post-load commands in `4.32s` at `5606.43/sec`; final active `0`, gap `0`. Hot-path timings: `api.commandLog.append` averaged `9.51ms`, `runtime.persistence.persistSubmitOutcomes` averaged `65.55ms`, and DB pools had `0` waiters.
- `16` workers with the same `15k/15s` shape (`fkdrop-setbased-submit-outcomes-16w-15000-384`) accepted only `34701` commands, `2302.41 accepted rps`, p99 `342.44ms`, and `2229.09 completed/sec` during load. Higher worker count kept backlog low but competed with intake; `8` workers is the better current local profile.
- conclusion: this slice improved the best post-load drain evidence to `5606/sec` and raised completed-during-load throughput to about `2404/sec`, still short of the `7500` target. The remaining blockers are command-log append latency and runtime/command-log write contention under worker load, not DB pool waiters or queue accounting.

### M4: Async Batched Runtime Persistence

- [ ] Define `RuntimePersistenceMode=sync|async-batched`.
- [ ] Add bounded queues for persistence work.
- [ ] Add batch flush by size.
- [ ] Add batch flush by interval.
- [ ] Add drain/wait test hook.
- [ ] Add graceful shutdown flush.
- [ ] Add overflow policy:
  - [ ] block
  - [ ] reject overload
  - [ ] sync fallback
- [ ] A/B test against tuned sync baseline.

Exit criteria:
- `async-batched` either improves accepted throughput by `>=25%` or materially reduces p99 at equivalent throughput.
- durable captured-ack mode reaches at least `7500` completed commands/sec or identifies the next measured bottleneck.
- no graceful-shutdown test loses accepted commands.

### M5: Partitioning And Retention

- [ ] Choose partition key for `runtime_events`.
- [ ] Choose partition key for `executions`.
- [ ] Choose partition key for `trades`.
- [ ] Add migration/bootstrap for current partition.
- [ ] Add local partition creation helper.
- [ ] Add table growth diagnostics to soak reports.
- [ ] Add retention/archive plan.

Exit criteria:
- 30-minute soak does not show table-growth-driven throughput collapse.

### M6: Read Model Isolation

- [ ] Add `read_model` schema.
- [ ] Identify query APIs that can read from projections.
- [ ] Add projection worker interface.
- [ ] Add projection lag metric.
- [ ] Add rebuild command for local dev.
- [ ] Remove projection writes from order command hot path.

Exit criteria:
- Command hot path writes only command/runtime canonical state, not UI-specific projection tables.

### M7: Stream-Ack Ingress And Partitioned Processing

- [x] Define stream-ack command envelope and protobuf/contract updates.
- [x] Define deterministic partition key and subject builder.
- [x] Add local NATS/JetStream dev profile and stream bootstrap.
- [x] Add stream health, publish-ack latency, partition lag, and oldest-age telemetry.
- [x] Implement `stream-ack` API mode behind a flag.
- [x] Add scoped idempotency guard with payload-hash conflict behavior.
- [x] Add SubmitOrder partition worker that preserves per-partition ordering.
- [x] Persist SubmitOrder canonical command result and event log before JetStream ack.
- [x] Add runtime canonical command-result and venue-event append tables for stream-ack submit outcomes.
- [x] Move normalized SubmitOrder order/execution/trade/runtime-event writes behind partition-owned projector watermarks.
- [ ] Extend partition worker processing to cancel/modify commands.
- [x] Add projection watermarks and lag snapshots for normalized submit projection.
- [ ] Add publish retry, redelivery, deterministic replay, and projection rebuild tests.

Latest stream-ack notes:
- The first single-instrument stream-ack run accepted all commands but routed through too few partitions: `5000` nominal rps accepted `100186` commands at `2441.99 accepted/sec`, while the worker completed `40056` during the step at `976.35/sec`; trace checks were `0%` because processing lagged behind accepted ingress.
- The stream-ack stress profile now uses `packages/scenario-definitions/stream-ack-submit-stress.yaml`, a submit-only 16-instrument scenario, and telemetry now captures Docker stats again.
- Isolated 16-instrument run on `REEF_COMMANDS_MULTI_16_FULL`:
  - `1000` nominal rps: `28956` accepted, `928.80 accepted/sec`, `928.80 completed/sec`, p95 `47.77ms`, p99 `62.50ms`, trace pass `100%`, worker failures `0`.
  - `2500` nominal rps: `69479` accepted, `2230.22 accepted/sec`, `1946.27 completed/sec`, p95 `136.18ms`, p99 `170.69ms`, trace pass `84%`, worker failures `0`.
  - `5000` nominal rps: `66993` accepted, `2157.24 accepted/sec`, `2003.09 completed/sec`, p95 `147.35ms`, p99 `180.99ms`, trace pass `75%`, worker failures `0`.
- The 16-instrument run used `8` active stream partitions; partition `6` was hottest at `51830` commands. Runtime peaked near `354%` CPU, Postgres near `303%` CPU, and DB pool waiters peaked at `51`, so the next bottleneck is runtime/Postgres contention plus partition skew under worker load, not JetStream publish acknowledgement.
- Stream workers now prepare fetched submit batches, persist the batch with one runtime persistence call, then ack each JetStream delivery after the batch commit path returns. The stream-ack dev profile also enables a dedicated `stream-runtime` DB pool (`max=24`, `minIdle=8`) and raises the worker batch size to `250`.
- Batch persistence validation on `REEF_COMMANDS_BATCH_FULL`:
  - `1000` nominal rps: `28464` accepted/completed, `916.12/sec`, p95 `80.53ms`, p99 `156.81ms`, trace pass `100%`, worker failures `0`.
  - `2500` nominal rps: `72598` accepted/completed, `2333.84/sec`, p95 `112.51ms`, p99 `153.21ms`, trace pass `97%`, worker failures `0`.
  - `5000` nominal rps: `77861` accepted, `77776` worker completed, `2500.20 completed/sec`, p95 `128.06ms`, p99 `182.96ms`, trace pass `95%`, worker failures `0`.
- The batch run moved the processed ceiling from roughly `2000/sec` to `2500/sec` and improved trace completion at high load, but it still exposed `stream-intake` pool waiters and partition skew. The dev profile now gives `stream-intake` its own pool (`max=32`, `minIdle=8`) and the stream-ack stress target generates a 64-instrument session config so deterministic routing has enough independent instrument keys to exercise all partitions.
- Spread-profile validation on `REEF_COMMANDS_SPREAD_FULL`:
  - `1000` nominal rps: `28887` accepted/completed, `870.39/sec`, p95 `49.66ms`, p99 `61.55ms`, trace pass `100%`, active partitions `16`, worker failures `0`.
  - `2500` nominal rps: `74187` accepted/completed, `2222.68/sec`, p95 `61.97ms`, p99 `115.59ms`, trace pass `100%`, active partitions `16`, worker failures `0`.
  - `5000` nominal rps: `104431` accepted/completed, `3106.74/sec`, p95 `101.58ms`, p99 `136.16ms`, trace pass `96%`, active partitions `16`, worker failures `0`.
- Net result: the durable stream-ack processed ceiling moved from about `2000/sec` to about `3100/sec` on the local stack while preserving `202`-after-publish and DB-before-stream-ack semantics. Remaining bottlenecks are runtime/Postgres CPU and batched persistence cost (`~28ms` average per `persistSubmitOutcomes` batch in the spread run), not JetStream publish durability. Intake-pool pressure improved materially (`maxDbPoolWaiters` dropped from `56` to `14`).
- Post-projector validation on the deploy-shaped stream-ack stack (`platform-api`, two workers, one projector) after moving normalized submit writes behind `runtime-normalized-submit`:
  - `1000` nominal rps: `28721` accepted, worker-completed, and projected, `858.46/sec`, p95 `59.14ms`, p99 `82.67ms`, trace pass `100%`, projector lag `0`.
  - `2500` nominal rps: `72937` accepted and worker-completed, `2199.58/sec`, p95 `89.41ms`, p99 `156.34ms`, trace pass `71%`; projector projected `63853` during the step at `1925.63/sec` and ended with lag `163028`.
  - `5000` nominal rps: `91706` accepted and worker-completed, `2763.98/sec`, p95 `108.29ms`, p99 `229.53ms`, trace pass `50%`; projector projected `64250` during the step at `1936.47/sec` and ended with lag `555190`.
  - workers reported `0` failures and `0` ack failures at every step; projector caught up to lag `0` after the run stopped. The current ceiling moved from worker canonical persistence to projector throughput and projection freshness.
- Projector ownership is now split across `platform-projector-0` and `platform-projector-1`, with explicit non-overlapping partition ranges and fair per-partition projection batching. Split-projector validation on `REEF_COMMANDS`:
  - `2500` nominal rps: `69179` accepted and worker-completed, `2087.99/sec`, p95 `147.26ms`, p99 `238.20ms`, trace pass `63%`; projectors projected `51802` during the step at `1563.51/sec` and ended with lag `177646`.
  - `5000` nominal rps: `88217` accepted and worker-completed, `2654.48/sec`, p95 `151.80ms`, p99 `210.00ms`, trace pass `36%`; projectors projected `52314` during the step at `1574.15/sec` and ended with lag `589132`.
  - workers again reported `0` failures, `0` ack failures, and `0` durable consumer stream lag; projectors caught up to lag `0` after the run stopped. Projector throughput remained below the previous single-projector baseline, so the next optimization target is projector persistence and hot-partition projection cost, not API intake or JetStream worker drain.
- Normalized submit projections now run against `projection-postgres` via `RUNTIME_PROJECTION_POSTGRES_JDBC_URL`, while workers append canonical facts to the primary runtime Postgres. Clean split-DB validation after resetting local Docker volumes:
  - `2500` nominal rps: `67190` accepted, worker-completed, and projected, `2011.60/sec`, p95 `163.25ms`, p99 `234.83ms`, trace pass `97%`, projector lag `0`.
  - `5000` nominal rps: `81661` accepted, worker-completed, and projected, `2442.63/sec`, p95 `130.93ms`, p99 `243.43ms`, trace pass `96%`, projector lag `0`.
  - placement check: primary runtime DB held `148851` canonical submit rows and `0` submit projection rows; projection DB held `148851` submit projection rows and `0` canonical submit rows. Max durable worker stream lag stayed `0`; max sampled projector lag was `1186` and drained by step end. The storage split materially improved projection freshness versus the shared-DB split-projector run, so continue physical store separation before deeper projector SQL tuning.
- Boundary/idempotency storage now runs against `boundary-postgres` through `RUNTIME_DB_URL`, so stream-intake rows and scoped idempotency writes are measured independently from canonical worker commits. Clean three-DB validation after resetting local Docker volumes:
  - `2500` nominal rps: `71779` accepted, worker-completed, and projected, `2152.30/sec`, p95 `132.96ms`, p99 `190.01ms`, trace pass `96%`, projector lag `0`.
  - `5000` nominal rps: `84433` accepted, worker-completed, and projected, `2540.33/sec`, p95 `121.01ms`, p99 `207.01ms`, trace pass `95%`, projector lag `0`.
  - placement check: primary runtime DB held `156212` canonical submit rows and `0` boundary intake/projection rows; boundary DB held `156212` stream-intake rows and `0` canonical/projection rows; projection DB held `156212` submit projection rows and `0` canonical/boundary rows. Max sampled DB pool waiters were `1` on stream-intake, `0` on canonical runtime, and `0` on projection; max durable worker stream lag stayed `0`, with sampled projector lag draining to `0` by step end.
  - result versus the prior projection-only DB split: the `2500` step improved from `2011.60/sec` to `2152.30/sec`, and the `5000` step improved from `2442.63/sec` to `2540.33/sec`, with lower p95/p99 and no final projection lag. Continue physical store separation and domain-specific migration ownership before deeper SQL tuning.
- Stream-ack API phase timing now resets per stress step and reports `api.streamAck.*` phases. The first measured pass showed API average time was far below client p95 but still doing avoidable work: at `5000` nominal rps, API total averaged `23.12ms`, with reserve `7.43ms`, publish ack `3.85ms`, mark-published `6.82ms`, and backpressure `4.61ms`; stream-intake pool waiters peaked at `55`.
- Stream backpressure now samples JetStream stream health for `STREAM_ACK_BACKPRESSURE_SAMPLE_MS=100` instead of calling stream management on every request. Cached-backpressure validation on the warm three-DB stack:
  - `2500` nominal rps: `71860` accepted, worker-completed, and projected, `2162.18/sec`, p50 `51.92ms`, p95 `119.46ms`, p99 `212.11ms`, trace pass `93%`, projector lag `0`.
  - `5000` nominal rps latency probe: `85253` accepted, `2550.19/sec`, p50 `83.57ms`, p95 `135.99ms`, p99 `218.68ms`, trace pass `94.5%`; immediate worker/projector samples completed/projected about `84.3k` before the sampling window closed, with projector lag reported as `0`.
  - API backpressure phase average dropped to `0.58ms` at `2500` and `0.35ms` at `5000`; publish ack averaged `2.58-2.90ms`. Max sampled stream-intake waiters dropped from `55` to `11`, while canonical runtime and projection pools stayed at `0` waiters. The remaining API cost is boundary DB reserve and mark-published, not JetStream publish acknowledgement.
- The stream-intake boundary pool is now role-owned instead of multiplied uniformly across API, workers, and projectors. `platform-api` owns a `16/64` stream-intake pool; background roles keep `0/4` compatibility pools. Warm-stack validation:
  - `2500` nominal rps: `72782` accepted, worker-completed, and projected, `2185.21/sec`, p50 `50.73ms`, p95 `109.26ms`, p99 `176.69ms`, trace pass `95%`, projector lag `0`.
  - `5000` nominal rps: `95627` accepted, `2869.86/sec`, p50 `74.37ms`, p95 `118.63ms`, p99 `197.64ms`, trace pass `93%`; immediate worker/projector samples completed/projected about `94.4k`, with `0` worker failures, `0` ack failures, and projector lag reported as `0`.
  - max sampled DB pool waiters were `0` for stream-intake, canonical runtime, and projection pools. API total averaged `11.80ms` at `2500` and `19.55ms` at `5000`; reserve and mark-published remain the dominant boundary costs, so the next deeper cut needs a durable published-metadata reconciler before moving `markPublished` off the synchronous response path.
- Stream-ack publish metadata marking introduced `STREAM_ACK_MARK_PUBLISHED_MODE=async` before the current worker-repair throughput default. In async mode, the API returns after JetStream durable publish ack and enqueues the boundary metadata update; stream workers also repair the same metadata by command id after canonical commit and before JetStream ack, NAKing for redelivery if the repair cannot be completed. Warm-stack validation:
  - `2500` nominal rps: `71365` accepted, `2149.75/sec`, p50 `47.71ms`, p95 `136.02ms`, p99 `208.96ms`, trace pass `85%`; workers completed/projected `69100`, worker failures `0`, ack failures `0`, final reported projector lag `0`.
  - `5000` nominal rps: `129942` accepted, `3899.69/sec`, p50 `53.54ms`, p95 `83.93ms`, p99 `124.79ms`, trace pass `62.5%`; workers completed `104592` and projectors projected `105099` during the step, with final reported projector lag `5759`, worker failures `0`, and ack failures `0`.
  - API enqueue-published averaged `0.019ms` at `2500` and `1.02ms` at `5000`; async flush averaged `1.42ms` and `2.20ms`. Max sampled DB pool waiters stayed `0` for stream-intake, canonical runtime, and projection. A boundary DB verification after the run showed all `968946` intake rows published with nonzero stream sequence and `0` unpublished rows.
  - result: moving post-publish metadata out of the synchronous response path raised the observed `5000` nominal acceptance point from `2869.86/sec` to `3899.69/sec` and reduced p95 from `118.63ms` to `83.93ms`. The cost moved to asynchronous drain/projection freshness, visible in lower trace pass rate and nonzero end-of-window lag at `5000`; the next slice should harden redelivery/replay tests and then raise worker/projector completed throughput rather than retuning API intake.
- Projector replay/idempotency and drain-side backpressure are now covered before the DO/OpenTofu slice. Projector tests assert that normalized submit projection rebuilds from canonical rows and a second projection pass applies `0` rows without duplicating read models. Worker/projector lag gates are configured through `STREAM_ACK_MAX_WORKER_STREAM_LAG`, `STREAM_ACK_MAX_PROJECTOR_LAG`, `STREAM_ACK_DRAIN_BACKPRESSURE_SAMPLE_MS`, and `STREAM_ACK_BACKPRESSURE_WORKER_DURABLES`; API roles sample lag in a background daemon so requests only read the latest snapshot.
  - an initial validation run proved the pre-existing stream storage gate works: the retained local `REEF_COMMANDS` stream had reached `~95%` of max bytes, so the API returned `429 STREAM_COMMAND_BACKPRESSURE`. The stream was purged before measuring the drain-gate implementation.
  - clean-stream validation with background lag sampling enabled: `2500` nominal rps accepted `74503` commands at `2245.47/sec`, p50 `46.45ms`, p95 `61.88ms`, p99 `100.16ms`, trace pass `94%`, worker/projector completed `72811`, projector lag `0`.
  - `5000` nominal rps accepted `127010` commands at `3816.61/sec`, p50 `54.92ms`, p95 `85.27ms`, p99 `124.16ms`, trace pass `64%`; workers completed `100114`, projectors projected `100207`, end-window projector lag `4716`, worker failures `0`, ack failures `0`.
  - request-path backpressure remained cheap with background sampling: `api.streamAck.backpressure` averaged `0.11ms` at `2500` and `0.31ms` at `5000`; max sampled DB pool waiters stayed `0` for stream-intake, canonical runtime, and projection. Max sampled projector lag was `5296`, below the conservative local threshold `250000`; max worker stream lag stayed `0`.
- Remaining tightening before DigitalOcean/OpenTofu infra: add broader crash/restart integration coverage around API publish-before-marker enqueue and worker/projector process restarts; then start the DO droplet IaC harness and compare local evidence against expected hardware.
- Follow-up probes that did not beat the spread-profile baseline:
  - direct JDBC submit-outcome persistence regressed the `5000` step to `2988.66/sec` and raised batch persistence cost to `~29.7ms`
  - worker batch size `500` regressed to `2822.92 completed/sec`; batch size `125` regressed to `2680.07 completed/sec`
  - `32` stream partitions regressed to `2586.44/sec`, so extra worker lanes added contention rather than throughput on the local stack
  - hot-path auth/reference lookup caching reduced those lookup timings but shifted pressure into larger/slower persistence batches; cache plus batch `250` reached `2915.98/sec`, and cache plus batch `100` reached `2876.79/sec`
  - a `256`-instrument stress shape reduced hot-partition skew but regressed to `2128.20/sec`; keep the default generated stream-ack stress shape at `64` instruments for ceiling comparisons, and use `DEV_STRESS_STREAM_ACK_INSTRUMENTS` for broader realism probes

Exit criteria:
- `202` is returned only after JetStream durable publish ack.
- JetStream is not used as the canonical venue outcome store.
- Hot single-instrument load preserves ordering while multi-instrument load uses partition concurrency.
- Crash/redelivery tests do not duplicate trades, events, or terminal command results.
- First phase reaches `5000` stream-ack accepted rps with no accepted-command gaps and visible lag.

## Performance Gates

Every throughput architecture PR should include:

- [ ] Baseline command and candidate command.
- [ ] Runtime instance count and whether the metric is per-instance or cluster-wide.
- [ ] Accepted throughput delta.
- [ ] Completed throughput delta.
- [ ] Accepted-command accounting check: accepted equals terminal plus active work, with no unexplained gap.
- [ ] Queue backlog, oldest queued age, stale lease count, and drain time after load stops.
- [ ] p95/p99 delta.
- [ ] Success-rate delta.
- [ ] Trace pass rate.
- [ ] Top reject taxonomy.
- [ ] DB diagnostics or explanation why not applicable.
- [ ] WAL/checkpoint and hot-table row-growth diagnostics for durable throughput runs.
- [ ] Rollback/toggle path.

Regression budget:
- accepted throughput drop must be `<10%` unless explicitly justified.
- completed throughput must not regress unless the PR intentionally adds correctness/backpressure and documents the tradeoff.
- p95 increase must be `<20%` unless accepted for a higher-throughput operating point.
- unexpected `5xx` or transport errors should remain near zero.
- accepted-command accounting gaps must remain `0`.

## Open Decisions

1. Should `captured-ack` grow more dedicated async processing, or remain a fallback while stream-ack is built?
- current prototype: `202 Accepted` with `commandId`, status, processing mode, and status URL
- risk: simulator and existing clients currently expect synchronous accepted/rejected result unless explicitly run in captured mode
- recommendation: keep it as fallback and A/B baseline unless a change directly supports stream-ack migration

2. Should command log live in Postgres only first, or add JetStream early?
- recommendation: add JetStream early for the bot-arena high-throughput track through `stream-ack`
- reason: accepted-command ingress needs a retained ordered log, while Postgres remains canonical for venue outcomes

3. Should physical DB split happen now?
- recommendation: no
- reason: schema isolation plus diagnostics should come first

4. What is the accepted p99 tradeoff for ceiling tests?
- current `6500/768` point improves throughput but has p99 near `485ms`
- need separate targets for stable quality runs vs ceiling probes

## Next Sprint Proposal

Target:
- completed-throughput accounting visible in captured-ack stress reports.
- durable intake rejects before acceptance when queue health says the system cannot drain safely.
- batched command completion ready for A/B testing against the current worker-drain ceiling.

Deliverables:
- run/session attribution in command-log intake and stress tooling
- accepted/completed/failed/active/stale/accounting-gap metrics in stress reports
- post-load drain measurement
- overload threshold configuration for durable intake
- batched terminal-result and queue-completion write path behind a flag
- A/B report against the current captured-ack queue profile

Definition of done:
- docs updated with metrics
- sync path remains default behavior
- no accepted-command accounting gaps in the stress report
- queue drains to zero after load stops or reports an explicit active/stale reason
- all tests pass
- stress report identifies top hot-path phase and completed-throughput bottleneck
