# Architecture Throughput Tracker

## Purpose

Track architecture work needed to move beyond the current tuned `~3k rps` single-instance local throughput profile toward sustained `5k` accepted requests per second per runtime instance while preserving command capture, auditability, and deterministic simulation.

Primary plan:
- [`docs/ARCHITECTURE_THROUGHPUT_PLAN.md`](./ARCHITECTURE_THROUGHPUT_PLAN.md)

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
- reach stable `5k` accepted rps per instance before relying on horizontal scale-out.
- use per-instance throughput as the unit that cluster capacity multiplies.
- keep cluster-wide tests separate from single-instance ceiling and quality gates.

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
| A12 | Boundary capture hot-path reduction | In progress | architecture | `captured-ack` now avoids the separate idempotency write for accepted responses |
| A13 | Runtime table lifecycle/partitioning | Not started | architecture | Loaded stack has multi-GB `runtime_events` and boundary tables |
| A14 | Accepted-command write-ahead path | Done | architecture | `captured-ack` can run configurable async workers from atomically claimed command-log records |
| A15 | Command queue/result split | Done | architecture | Functional split complete; first benchmark regressed ingress, so next work must reduce write amplification |
| A16 | Stored-procedure command intake | Done | architecture | Added as opt-in `EXTERNAL_API_COMMAND_LOG_APPEND_MODE=function`; benchmark regressed reserve latency, so default remains `inline` |

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
- [ ] Reduce remaining split-schema write amplification before wider `4/8/16` worker sweep.

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
- [ ] p95/p99 delta.
- [ ] Success-rate delta.
- [ ] Trace pass rate.
- [ ] Top reject taxonomy.
- [ ] DB diagnostics or explanation why not applicable.
- [ ] Rollback/toggle path.

Regression budget:
- accepted throughput drop must be `<10%` unless explicitly justified.
- p95 increase must be `<20%` unless accepted for a higher-throughput operating point.
- unexpected `5xx` or transport errors should remain near zero.

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
- M1 complete
- M2 schema/interface complete
- no default behavior change yet

Deliverables:
- runtime phase timing diagnostics
- DB pool telemetry in stress reports
- `command_log` schema/table migration
- command log interface with tests
- A/B baseline report using current tuned profile

Definition of done:
- docs updated with metrics
- sync path remains default behavior
- all tests pass
- stress report identifies top hot-path phase
