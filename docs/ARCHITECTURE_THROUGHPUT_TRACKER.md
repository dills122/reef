# Architecture Throughput Tracker

## Purpose

Track architecture work needed to move beyond the current tuned `~3k rps` local throughput profile toward sustained `4k-5k+` accepted throughput while preserving command capture, auditability, and deterministic simulation.

Primary plan:
- [`docs/ARCHITECTURE_THROUGHPUT_PLAN.md`](./ARCHITECTURE_THROUGHPUT_PLAN.md)

Current measured reference:
- best local ceiling probe: `2961.43 rps` total, `2919.27 rps` accepted, `98.58%` success
- profile: `capacity-baseline`, target `6500`, workers `768`, gRPC runtime-engine, runtime threads `64`, DB pool max `48`

## Workstream Status

| ID | Workstream | Status | Target Branch Type | Notes |
|---|---|---|---|---|
| A1 | Runtime phase timing diagnostics | Not started | feature | Must land before major rewrites |
| A2 | DB pool/write-path diagnostics in stress telemetry | Not started | feature | Needed for bottleneck proof |
| A3 | Command log schema and interface | Not started | feature | First DB slice to add |
| A4 | Command capture append mode | Not started | feature | Preserve 100% capture |
| A5 | Command processing mode flags | Not started | feature | `sync-result`, `captured-sync-engine`, `captured-ack` |
| A6 | Async batched runtime persistence | Not started | architecture | Biggest likely throughput lever |
| A7 | Runtime event/table partitioning | Not started | architecture | Long-soak stability |
| A8 | Read-model schema/projection isolation | Not started | architecture | Remove projection writes from hot path |
| A9 | Postgres outbox publisher | Not started | architecture | Precondition for NATS |
| A10 | NATS JetStream integration | Deferred | architecture | Wait until outbox is real |
| A11 | Physical DB split evaluation | Deferred | decision | Only after diagnostics prove need |

## Milestone Checklist

### M1: Observability Before Rewrite

- [ ] Add phase timing around boundary validation.
- [ ] Add phase timing around idempotency lookup/save.
- [ ] Add phase timing around command capture.
- [ ] Add phase timing around engine round-trip.
- [ ] Add phase timing around runtime persistence.
- [ ] Add phase timing around response serialization.
- [ ] Surface phase timing in stress report summary.
- [ ] Add Hikari pool active/idle/wait metrics or debug endpoint.
- [ ] Add DB diagnostics snapshot to `dev-stress-diagnostics` for pool and table growth.
- [ ] Run baseline at `5000/512/60s`.
- [ ] Run ceiling point at `6500/768/60s`.

Exit criteria:
- A report can identify whether p99 is dominated by engine, persistence, idempotency, or runtime queueing.

### M2: Command Log Slice

- [ ] Add `command_log` schema migration.
- [ ] Add append-only `command_log.commands` table.
- [ ] Add minimal indexes:
  - [ ] unique `(client_id, route, idempotency_key)`
  - [ ] unique `command_id`
  - [ ] processing index `(status, received_at)`
- [ ] Add command log storage interface.
- [ ] Add Postgres implementation.
- [ ] Add in-memory implementation for unit tests.
- [ ] Wire command capture to command log behind env flag.
- [ ] Add duplicate idempotency tests.
- [ ] Add restart/replay test for durable captured command.

Exit criteria:
- Captured command survives runtime restart after acknowledgment.
- Existing synchronous API behavior remains available.

### M3: Command Processing Modes

- [ ] Define response contracts for each mode.
- [ ] Implement `sync-result`.
- [ ] Implement `captured-sync-engine`.
- [ ] Prototype `captured-ack`.
- [ ] Add status lookup API for captured commands.
- [ ] Add idempotency replay behavior per mode.
- [ ] Add simulator config toggle for mode.

Exit criteria:
- Simulator can run both legacy synchronous and captured command modes without code changes.

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

1. What should `/api/v1` return in `captured-ack` mode?
- candidate: `202 Accepted` with `commandId`, `traceId`, and status URL
- risk: simulator and existing clients currently expect synchronous accepted/rejected result

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
