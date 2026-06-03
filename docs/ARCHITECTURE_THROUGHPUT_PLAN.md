# Architecture Throughput Plan

## Purpose

Define the next architecture improvements after one local runtime + engine instance reached roughly `3k rps` with `98%+` success in capacity-baseline simulation.

The goal is to move from tuned synchronous throughput to a production-shaped write architecture that can sustain `5k` accepted requests per second per runtime instance, preserve command capture guarantees, and keep DB growth from degrading the environment over time.

## Current Baseline

Latest high-throughput local profile:

- runtime -> engine transport: gRPC
- runtime HTTP threads: `64`
- runtime DB pool max: `48`
- simulator profile: `capacity-baseline`
- best measured point: `6500` target / `768` workers / `60s`
- throughput: `2961.43 rps`
- accepted throughput: `2919.27 rps`
- success rate: `98.58%`
- p95 / p99 latency: `325.39ms / 484.59ms`
- trace checks: `100/100`

Interpretation:
- The current stack is healthy enough that the next gains should target architecture, not only tuning.
- The write path is likely dominated by synchronous boundary capture, idempotency, runtime persistence, and event/table growth.
- Further worker/rate increases already show diminishing returns and worse p99.

## Per-Instance Scaling Model

The planning target is `5k` accepted requests per second for each runtime + engine instance.

This matters because horizontal scaling should multiply a strong node, not compensate for a weak one. A scaled cluster with `N` runtime instances should be planned as roughly `N * per-instance-capacity`, with overhead reserved for routing, partition ownership, database contention, and failover.

Implications:
- performance reports must state whether throughput is per instance or cluster-wide.
- single-instance tuning remains the primary benchmark until the `5k` target is stable.
- horizontal scaling work should not hide hot-path bottlenecks in command capture, idempotency, engine calls, or runtime persistence.
- DB slice decisions should be evaluated against per-instance pressure first, then aggregate cluster pressure.

## Guiding Constraints

1. Preserve simulation realism.
- Simulators continue to use the same public command/API paths as real clients.

2. Preserve command capture.
- No client command should disappear after the runtime acknowledges it.
- Any async design must define exactly where durable capture happens.

3. Preserve deterministic testability.
- Every new async/batched path needs a sync/test mode or explicit drain controls for deterministic integration tests.

4. Keep Postgres canonical.
- Postgres remains the canonical lifecycle/event store.
- Event transport can be introduced later, but it is not the sole source of truth.

5. Make every performance change measurable.
- Each architecture slice needs before/after accepted throughput, p95/p99, top errors, and DB diagnostics.

## Target Architecture

```text
client / simulator
  -> external API boundary
  -> durable command log append
  -> fast accepted/correlated response or synchronous result mode
  -> command processor
  -> matching engine
  -> canonical runtime persistence
  -> outbox/event distribution
  -> read-model projection
```

The critical shift is separating durable command intake from heavier downstream persistence and projection work.

## DB Slice Model

Keep one physical Postgres instance for now, but treat schemas as extraction-ready slices:

| Slice | Schema | Purpose | Write Pattern | Extraction Priority |
|---|---|---|---|---|
| API boundary | `boundary` | idempotency, API policy, abuse state | small key lookups/upserts | medium |
| Command log | `command_log` | immutable inbound commands | append-only, minimal indexes | high |
| Runtime write model | `runtime` | orders, executions, trades, events | transactional domain writes | high |
| Read models | `read_model` | UI/query projections | async upserts/rebuilds | medium |
| Reference/admin | `admin`, `auth` | users, roles, instruments, accounts | low-volume durable writes | low |
| Analytics | `analytics` | transformed metrics and reports | async/batch | low |
| Orchestration | `orchestration` | scheduled jobs, retries, run state | claim/update with SKIP LOCKED | medium |

Physical DB split should wait until diagnostics prove schema-level isolation is not enough.

## Priority 1: Instrument The Write Path

### Objective

Prove where time is spent before doing invasive refactors.

### Add Metrics

- boundary validation latency
- idempotency lookup latency
- command capture latency
- engine round-trip latency
- runtime persistence latency
- command/result response serialization latency
- DB pool active/idle/wait counters
- command-log backlog depth once introduced
- async writer queue depth and flush latency once introduced

### Acceptance Criteria

- Stress reports can show phase timing summary.
- A `5000/512/60s` and `6500/768/60s` run include runtime phase diagnostics.
- We can distinguish runtime CPU, engine latency, DB latency, and simulator pressure.

## Priority 2: Command Log Slice

### Objective

Create an append-only durable command intake slice that satisfies command capture without coupling every request to the full runtime persistence path.

### Design

- New schema: `command_log`
- New table: `command_log.commands`
- Minimal write-path fields:
  - `command_id`
  - `client_id`
  - `route`
  - `idempotency_key`
  - `trace_id`
  - `correlation_id`
  - `actor_id`
  - `command_type`
  - `received_at`
  - `payload_json`
  - `status`
  - `attempt_count`
  - `last_error`
- Indexes:
  - unique `(client_id, route, idempotency_key)`
  - unique `command_id`
  - processing index on `(status, received_at)`
- No cross-schema foreign keys.

### Runtime Modes

1. `sync-result`
- Current behavior shape.
- Request waits for engine and persistence result.
- Good for compatibility and deterministic tests.

2. `captured-ack`
- Request returns after durable command capture.
- Processor completes command asynchronously.
- Good for high-throughput intake.

3. `captured-sync-engine`
- Request captures command durably, executes engine synchronously, defers non-critical projection/fanout.
- Good transitional mode.

### Acceptance Criteria

- Command capture survives runtime restart after acknowledgment.
- Idempotency replay returns the same command status/result contract.
- Duplicate idempotency keys do not create duplicate commands.
- Existing simulator can run against `sync-result` unchanged.

## Priority 3: Async Runtime Persistence Batch Writer

### Objective

Reduce per-command DB round trips and transaction overhead for high-volume runtime persistence.

### Design

- Add a `RuntimePersistenceMode`:
  - `sync`
  - `async-batched`
- Add bounded in-memory queues for:
  - submit results
  - orders
  - executions
  - trades
  - runtime events
- Flush policy:
  - max records per batch
  - max flush interval
  - max queue depth
- Backpressure:
  - if queue is full, either block, reject overload, or fall back to sync based on mode.
- Drain controls:
  - test hook to wait until queue is empty
  - shutdown hook to flush before exit

### Acceptance Criteria

- `sync` mode remains deterministic and passes current tests.
- `async-batched` mode improves accepted throughput by at least `25%` over tuned sync baseline or materially reduces p99 at similar throughput.
- Queue overflow behavior is explicit and tested.
- No accepted command result is lost in graceful shutdown tests.

## Priority 4: Runtime Event Partitioning And Retention

### Objective

Prevent long soaks from degrading write performance through unbounded hot table growth.

### Design

- Partition high-volume append tables by time or scenario run:
  - `runtime_events`
  - `executions`
  - `trades`
  - command log table if volume warrants it
- Add retention/archive jobs later under `orchestration`.
- Keep hot indexes minimal.

### Acceptance Criteria

- A 30-minute soak does not show progressive throughput collapse caused by table growth.
- DB diagnostics include table size, estimated row counts, WAL/checkpoint pressure, and partition distribution.
- Partition creation is automated for local dev reset.

## Priority 5: Read Model Isolation

### Objective

Move query/UI projection writes out of the command request path.

### Design

- New schema: `read_model`
- Projection worker consumes runtime events/outbox.
- Query APIs read from projection tables where suitable.
- Write model stays authoritative.

### Acceptance Criteria

- No read-model writes occur in the order command hot path.
- Projection lag is observable.
- Read model can be rebuilt from runtime state/events in local dev.

## Priority 6: Async Backbone

### Objective

Introduce NATS JetStream when internal queues and DB-backed processing need distribution or replay windows.

### Design

- Postgres outbox remains canonical.
- Publisher reads outbox and publishes to NATS.
- Consumers are idempotent by `event_id`.

### Acceptance Criteria

- Postgres state/event transaction commits atomically with outbox row.
- Publisher can resume after restart without event loss.
- Duplicate delivery does not duplicate business effects.

## Target Milestones

| Milestone | Goal | Expected Impact |
|---|---|---|
| M1 diagnostics | phase timing + pool/writer visibility | know bottleneck with evidence |
| M2 command log | durable append-only intake slice | safer async boundary |
| M3 batched persistence | reduce write round trips | `25-100%+` possible |
| M4 partition/retention | stabilize long soaks | lower degradation risk |
| M5 read-model async | reduce hot-path write amplification | improved p99 and growth control |
| M6 outbox/NATS | distribution without losing canonical Postgres | scalable async workflows |

## Target Metrics

Near target:
- `4k rps` accepted throughput per runtime instance
- success rate `>= 95%`
- trace pass `>= 99%`
- p99 under `750ms` in local high-load profile

Preferred target:
- `5k rps` accepted throughput per runtime instance
- success rate `>= 98%`
- trace pass `>= 99%`
- p99 under `500ms` in local high-load profile

Longer-term stretch:
- `8k-10k rps` accepted throughput per runtime instance after async command pipeline and partitioned persistence
- requires deeper command processing and write-model redesign

## Decision Gates

1. Do not physically split databases until schema-level diagnostics show contention that cannot be fixed with schema/index/partition changes.
2. Do not make `captured-ack` the default until the product/API semantics for asynchronous command completion are explicit.
3. Do not introduce NATS before Postgres outbox and idempotent consumers are designed.
4. Do not remove synchronous mode; it remains required for deterministic tests and compatibility.
5. Do not add indexes to hot write tables without benchmark evidence.

## First Sprint Recommendation

Build M1 and prepare M2:

1. Add runtime phase timing instrumentation.
2. Add DB pool diagnostics to stress telemetry.
3. Add `command_log` schema migration and storage interface.
4. Implement command capture append path behind a mode flag.
5. Run A/B:
   - current tuned sync
   - command-log append + sync result
   - command-log append + captured ack prototype if API contract is acceptable

This gives evidence before committing to the larger async persistence rewrite.
