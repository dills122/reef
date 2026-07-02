# Architecture Throughput Tracker

## Purpose

Track architecture work needed to move beyond accepted-request intake toward sustained `7500` completed commands per second per runtime + engine instance, with `10000` completed commands per second as the preferred target, while preserving command capture, auditability, deterministic simulation, and zero silent loss.

Primary plan:
- [`docs/ARCHITECTURE_THROUGHPUT_PLAN.md`](./ARCHITECTURE_THROUGHPUT_PLAN.md)
- [`docs/THROUGHPUT_SCALING_WORK_PLAN.md`](./THROUGHPUT_SCALING_WORK_PLAN.md)
- [`docs/COMMAND_LOG_PARTITIONING_PLAN.md`](./COMMAND_LOG_PARTITIONING_PLAN.md)

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
- prefer `10000` completed commands/sec per instance if write-amplification work gets there without weakening accounting or replay.
- use per-instance throughput as the unit that cluster capacity multiplies.
- keep cluster-wide tests separate from single-instance ceiling and quality gates.
- treat accepted rps as diagnostic; completed rps, queue drain, and accepted-command accounting are the release gates.

Current captured-ack scaling reference from the Bot Arena planning branch:
- raw durable intake can exceed `7k accepted rps` locally in narrow intake benchmarks.
- async drain now avoids the earlier indexed-claim and stale-lease blockers.
- drain sweep evidence: `4` workers about `2k completed/sec`, `8` workers about `3k completed/sec`, `16` workers about `4k-4.3k completed/sec`, and `24` workers about `4.9k completed/sec` with worse persistence/complete latency.
- DB pools showed no waiter pressure, so the next likely bottleneck is write amplification across command completion and runtime persistence.

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
| A9 | Postgres outbox publisher | Not started | architecture | Precondition for NATS |
| A10 | NATS JetStream integration | Deferred | architecture | Wait until outbox is real |
| A11 | Physical DB split evaluation | Deferred | decision | Only after diagnostics prove need |
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
| A24 | Durable-intake backpressure thresholds | In progress | reliability | Opt-in active-depth and stale-processing rejection added; queue-age and drain-rate thresholds remain next |
| A25 | Batched command completion | Done | performance | Async processor flushes terminal command updates in one transaction per claimed batch; A/B showed no target recovery, so command-log reserve and runtime persistence are the next bottlenecks |
| A26 | Kubernetes lifecycle readiness | Not started | architecture | Readiness, liveness, graceful drain, lease reclaim, and per-pod metric labeling for a basic cluster |
| A27 | Bot-arena venue-path readiness | Not started | architecture | Built-in and user bots must use the same command/API path with run/bot attribution and guardrails |
| A28 | Recoverable active command queue | In progress | performance | `command_work_queue` is derived active state and can be unlogged/reconstructed while accepted commands and terminal outcomes stay durable; quick loaded-stack run recovered `3945.78 accepted rps` with eventual `0` gap |

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
- [ ] Split or slim hot command payload writes on command-log reserve.
- [ ] Batch or defer per-command runtime persistence writes.

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

### M7: Outbox And NATS

- [ ] Add outbox table/routine for runtime events.
- [ ] Commit domain state + event + outbox atomically.
- [ ] Add outbox publisher.
- [ ] Add idempotent consumer contract.
- [ ] Add NATS dev profile.
- [ ] Add replay/duplicate delivery tests.

Exit criteria:
- NATS can be enabled without replacing Postgres as canonical history.

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

1. Should `captured-ack` grow a dedicated async processor in M4, or stay intake-only until the batch writer exists?
- current prototype: `202 Accepted` with `commandId`, status, processing mode, and status URL
- risk: simulator and existing clients currently expect synchronous accepted/rejected result unless explicitly run in captured mode

2. Should command log live in Postgres only first, or add JetStream early?
- recommendation: Postgres first
- reason: command capture is canonical durability, not transient transport

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
