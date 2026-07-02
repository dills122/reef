# Architecture Throughput Plan

## Purpose

Define the next architecture improvements after Reef proved that a single local runtime + engine instance can accept several thousand requests per second, but still cannot complete the durable captured-command lifecycle fast enough for bot-arena scale.

The active goal is to move from tuned synchronous throughput to a production-shaped write architecture that can sustain at least `7500` completed commands per second per runtime instance, preferably `10000`, while preserving command capture, auditability, deterministic replay, and zero silent loss of accepted commands.

Detailed execution plan:
- [`docs/THROUGHPUT_SCALING_WORK_PLAN.md`](./THROUGHPUT_SCALING_WORK_PLAN.md)

## Current Baseline

Historical high-throughput synchronous/capacity profile:

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

Current captured-ack evidence from the bot-arena scaling branch:
- raw durable intake can exceed `7k accepted rps` in narrow local benchmarks.
- async worker drain is currently below target: about `4k-4.3k completed/sec` at `16` workers and about `4.9k completed/sec` at `24` workers, with worsening persistence and completion latency.
- indexed queue claims and lease reclaim fixed correctness/drain blockers, but they did not remove the remaining write-amplification ceiling.

Interpretation:
- the next capacity gate is not whether the API can accept commands.
- the next capacity gate is whether accepted commands reach terminal state at `7500-10000 completed/sec` without unbounded backlog, unexplained gaps, or lossy overload behavior.

## Per-Instance Scaling Model

The planning target is at least `7500` completed commands per second for each runtime + engine instance, with `10000` completed commands per second as the preferred stable target.

This matters because horizontal scaling should multiply a strong node, not compensate for a weak one. A scaled cluster with `N` runtime instances should be planned as roughly `N * per-instance-capacity`, with overhead reserved for routing, partition ownership, database contention, and failover.

Implications:
- performance reports must state whether throughput is per instance or cluster-wide.
- single-instance tuning remains the primary benchmark until the `7500` completed/sec minimum target is stable.
- horizontal scaling work should not hide hot-path bottlenecks in command capture, idempotency, engine calls, or runtime persistence.
- DB slice decisions should be evaluated against per-instance pressure first, then aggregate cluster pressure.
- accepted-command intake is a diagnostic metric; completed throughput, terminal accounting, and queue drain are the release gates.

## Guiding Constraints

1. Preserve simulation realism.
- Simulators continue to use the same public command/API paths as real clients.

2. Preserve command capture.
- No client command should disappear after the runtime acknowledges it.
- Any async design must define exactly where durable capture happens.
- Any accepted command must either reach terminal `COMPLETED`/`FAILED` state or remain visible as active leased/retryable work.
- Overload must reject or throttle before durable acceptance when the system cannot safely drain more work.

3. Preserve deterministic testability.
- Every new async/batched path needs a sync/test mode or explicit drain controls for deterministic integration tests.

4. Keep Postgres canonical.
- Postgres remains the canonical lifecycle/event store.
- Event transport can be introduced later, but it is not the sole source of truth.

5. Make every performance change measurable.
- Each architecture slice needs before/after accepted throughput, p95/p99, top errors, and DB diagnostics.
- High-throughput slices also need completed throughput, queue depth, drain time, stale lease count, and accepted-command accounting gap.

6. Keep bot traffic on the venue path.
- Built-in bots, market-maker bots, simulator traffic, and future user bots must use the same command/API path.
- Bot-arena metadata, leaderboards, and replay artifacts should be isolated from the trading hot path.

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

## Priority 1A: Runtime Library Benchmark Gate

### Objective

Make the persistence sprint faster without adding speculative library churn.

### Benchmark Areas

- DB writes:
  - pgjdbc + HikariCP baseline
  - prepared batches
  - explicit multi-row inserts
  - `reWriteBatchedInserts`
  - `CopyManager`/`COPY` for append-only bulk/report/archive paths only
- JSON:
  - current parser/serializer baseline
  - `kotlinx.serialization`
  - DSL-JSON for order-command DTO spike only
- HTTP boundary:
  - current JDK `HttpServer`
  - Ktor Netty
  - Vert.x Web

### Constraints

- Do not start with R2DBC/reactive DB access. The near-term bottleneck is controlled durable batching and stable latency.
- Do not adopt a faster JSON library unless malformed-input and validation behavior remain acceptable.
- Do not optimize the HTTP stack before phase timing proves boundary/server overhead is material.

### Acceptance Criteria

- Benchmark results are recorded with the same throughput discipline as stress baselines.
- Recommendation identifies one default runtime JSON path and one default DB batching path.
- Any adopted library has rollback/config toggles or a narrow integration surface.

Reference:
- [`docs/PERFORMANCE_LIBRARY_INVESTIGATION.md`](./PERFORMANCE_LIBRARY_INVESTIGATION.md)

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
- Response contract: returns the current synchronous order result payload and replays the same payload by idempotency key.

2. `captured-ack`
- Request returns after durable command capture.
- Processor completes command asynchronously.
- Good for high-throughput intake.
- Response contract: returns HTTP `202` with `commandId`, `status`, `processingMode`, and `statusUrl`; replays the same accepted response by idempotency key.

3. `captured-sync-engine`
- Request captures command durably, executes engine synchronously, defers non-critical projection/fanout.
- Good transitional mode.
- Response contract: returns the current synchronous order result payload while command status is queryable through `/api/v1/commands/{commandId}`.

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

Minimum stable target:
- `7500` completed commands/sec per runtime + engine instance in durable `captured-ack` mode
- `0` silent drops or unexplained accepted-command gaps
- bounded queue backlog during load
- queue drains to zero after load stops
- trace pass `>= 99%`
- overload is explicit rejection/throttle, not hidden loss

Preferred target:
- `10000` completed commands/sec per runtime + engine instance in durable `captured-ack` mode
- `0` silent drops or unexplained accepted-command gaps
- sustained `10-15m` run plus clean post-run drain
- trace pass `>= 99%`
- p99 target documented per benchmark mode and not allowed to hide backlog growth

Diagnostic targets:
- raw durable intake can exceed the completed-throughput target, but it is not sufficient by itself.
- capacity-baseline mode remains useful for isolating simulator/client/runtime overhead, not for release gating.

## Decision Gates

1. Do not physically split databases until schema-level diagnostics show contention that cannot be fixed with schema/index/partition changes.
2. Do not make `captured-ack` the default until the product/API semantics for asynchronous command completion are explicit.
3. Do not introduce NATS before Postgres outbox and idempotent consumers are designed.
4. Do not remove synchronous mode; it remains required for deterministic tests and compatibility.
5. Do not add indexes to hot write tables without benchmark evidence.
6. Do not use Kubernetes scale-out to mask per-instance write amplification or accepted-command accounting gaps.

## First Sprint Recommendation

Build the first slice from the throughput scaling work plan:

1. Add run/session attribution to command-log intake.
2. Add backlog-adjusted accounting metrics to captured-ack stress reports.
3. Fail stress validation on accepted-command accounting gaps.
4. Add explicit durable-intake backpressure thresholds.
5. Implement and benchmark batched command completion.

This turns the current queue/drain findings into a measurable path toward `7500-10000` completed commands/sec without weakening no-loss guarantees.
