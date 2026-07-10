# Throughput Scaling Work Plan

## Purpose

Define the execution plan for making Reef ready for bot-arena traffic, high-throughput simulation runs, and an initial Kubernetes deployment without weakening correctness, replay, or audit guarantees.

This plan supersedes the earlier `5k accepted rps` target for the active bot-arena scaling track. The current goal is completed lifecycle throughput: commands accepted by the runtime must be durably captured, processed to a terminal state, and accounted for without silent drops.

Status note (2026-07-09): this document is a phased throughput reference, not the single active execution ladder. Current sequencing lives in [`WORK_PLAN.md`](./WORK_PLAN.md#active-execution-ladder). Older P0-P8 items below remain useful for why the path changed, but items marked shipped/superseded must not be reopened as "next" work unless a later decision or `WORK_PLAN.md` reactivates them.

## Non-Negotiable Goals

1. No accepted command is silently lost.
- If the runtime returns an accepted response, the command must have a durable command-log or stream record.
- Every accepted command must reach a terminal `COMPLETED` or `FAILED` result, or remain visible as active work with lease/retry metadata.
- Overload must reject or throttle before durable acceptance when the system cannot safely process more work.

2. Completed throughput is the primary capacity metric.
- Raw HTTP success and accepted-command intake are useful diagnostics, but they are not sufficient gates for this track.
- The main score is completed commands per second with bounded backlog and a clean drain to zero after load stops.

3. Replay and audit stay first-class.
- Load tests, simulator runs, and future bot-arena runs need run/session attribution.
- Stress reports must prove the accounting invariant: accepted commands equal terminal results plus active queue work, with no unexplained gap.

4. Bots use the same venue path.
- Bot-generated orders must enter through the public command/API path.
- Bot execution, arena scheduling, and simulator traffic must not mutate venue state directly to win throughput.

5. Kubernetes multiplies a strong instance.
- Horizontal scaling should not hide per-instance write amplification or queue-drain bottlenecks.
- The first cluster profile should prove pod lifecycle, readiness, graceful drain, and lease reclaim before treating scale-out as capacity.

## Per-Instance Targets

| Target | Requirement |
|---|---|
| Minimum stable target | `7500` completed commands/sec per runtime + engine instance |
| Preferred stable target | `10000` completed commands/sec per runtime + engine instance |
| Accounting | `0` silent drops or unexplained accepted-command gaps |
| Backlog | bounded during load; drains to zero after load stops |
| Error behavior | overload is explicit rejection/throttle, not hidden loss |
| Benchmark duration | sustained `10-15m` run plus post-run drain check before treating a result as stable |

These targets apply to the durable `stream-ack` venue path, not the stripped-down capacity-baseline mode. Postgres `captured-ack` remains the local fallback and A/B baseline. `sync-result` remains the correctness and compatibility mode.

## Benchmark Modes

| Mode | Purpose | Gate Role |
|---|---|---|
| `sync-result` | deterministic compatibility and strict request/result behavior | correctness gate |
| `captured-sync-engine` | transitional capture plus synchronous execution shape | contract comparison |
| `captured-ack` | Postgres-backed durable accepted response with async command processing | local fallback and A/B baseline |
| `stream-ack` | JetStream-backed durable accepted response with partition workers | primary high-throughput gate |
| `stream-direct` / engine-direct | durable command log/topic consumed directly by matching-engine shards, followed by durable venue event batches | next venue-core hot path gate |
| capacity baseline | isolate simulator/client/runtime overhead | diagnostic only |
| raw intake benchmark | isolate durable append and API intake | diagnostic only |

The project can report accepted rps for diagnostics, but release decisions for this track use completed rps, queue drain, terminal accounting, and trace integrity.

## Current Baseline

Current evidence from the bot-arena throughput branch:

- durable raw intake can exceed `7k accepted rps` locally when the benchmark isolates command-log append from full drain pressure.
- async queue drain improved after indexed claim and worker lease reclaim.
- worker sweep showed roughly:
  - `4` workers: about `2k completed/sec`
  - `8` workers: about `3k completed/sec`
  - `16` workers: about `4k-4.3k completed/sec`
  - `24` workers: about `4.9k completed/sec`, with worse per-command persistence and completion latency
- DB pools did not show connection-wait pressure during the sweep.
- the likely remaining bottleneck is write amplification across command results, queue completion, and runtime persistence.
- later set-based runtime persistence plus command-log FK tuning stayed lossless, but still reached only about `4015 accepted rps`, `2404 completed/sec` during load, and `5606/sec` post-load drain.

Interpretation:
- Reef is not blocked on the matching engine for this traffic shape.
- The API can accept near the minimum target in narrow intake tests, but the full accepted-to-terminal lifecycle cannot yet drain at the required rate.
- The Postgres `captured-ack` path remains valuable for local fallback and measurement, but more small command-log tuning is unlikely to reach the target alone.
- JetStream stream-ack ingress, deterministic partitioning, canonical commit before stream ack, and projection isolation have landed enough to become fallback/comparison evidence. D-041 moved the active venue-core hot path to Kafka-compatible durable ingress with matching-engine direct partition consumption and durable venue event batches.
- Later July 2026 evidence moved the venue-core hot path toward direct matching-engine partition consumption and durable event-batch publication. Generic runtime workers and per-command unary engine calls remain transitional scaffolding.

## Work Plan

The P0-P8 ladder below is historical phase structure. Use it for context and gap classification; use [`WORK_PLAN.md`](./WORK_PLAN.md#active-execution-ladder) for execution order.

### P0: Accounting And Run Attribution

Objective: make every stress run prove where accepted work went.

Deliverables:
- add `run_id`, `run_kind`, and `scenario_id` to command-log intake.
- propagate run/session metadata from stress tools, simulators, and future arena schedulers.
- update stress reports with accepted, completed, failed, active received, active processing, stale processing, and accounting-gap fields.
- report drain time after load stops.
- record whether each run is clean-stack, warm-stack, or loaded-stack.

Exit criteria:
- every durable throughput report can prove accepted-command accounting.
- retained/pruned command history can be selected by run/session.

### P1: Backpressure And No-Loss Guardrails

Objective: reject before acceptance when the system cannot safely drain.

Deliverables:
- define queue depth, queue age, lease age, and drain-rate thresholds.
- add explicit overload responses for durable intake when thresholds are exceeded.
- include reject taxonomy in stress reports.
- add stale `PROCESSING` and orphaned-result checks to stress validation.

Exit criteria:
- overload does not create invisible loss.
- accepted commands remain either terminal or visible active work during and after failure tests.

### P2: Stream-Ack Command Ingress

Status: shipped, superseded by D-041. JetStream stream-ack ingress was implemented and measured, including a clean single-droplet DO soak (see "Stream-Ack Sunset Checkpoint (July 3, 2026)" in [`PERFORMANCE_LEARNINGS.md`](./PERFORMANCE_LEARNINGS.md)), but the full accepted-to-completed lifecycle did not reach the required throughput under real drain pressure. `docs/DECISIONS.md` D-041 moved the active hot-ingress target to a Kafka-compatible durable producer with matching-engine direct consumption; JetStream stream-ack remains available only as fallback/comparison, not the primary gate.

Objective (superseded): make durable acceptance a JetStream publish-ack operation with deterministic partition routing.

Deliverables:
- define command envelope fields in protobuf/contracts.
- define subject shape `reef.cmd.v1.pXX.<venueSessionId>.<instrumentId>.<commandType>`.
- route by `hash(venueSessionId + instrumentId) % partitionCount`, or include `runId` for isolated arena/simulator runs.
- add NATS/JetStream local profile and command stream bootstrap.
- return `202` only after durable publish ack.
- reject with explicit `429` or `503` before durable acceptance when stream health is unsafe.
- keep Postgres `captured-ack` available as the fallback and comparison profile.

Exit criteria:
- first phase reaches `5000` stream-ack accepted rps with no accepted-command gaps and visible stream lag.

### P3: Stream Idempotency And Partition Workers

Status: shipped, superseded by D-041. Scoped idempotency and generic partition workers calling the matching engine per command were implemented, but `docs/DECISIONS.md` D-040/D-041 confirmed this generic worker-to-engine path is transitional scaffolding, not the target high-throughput architecture. The target path now has matching-engine shards consume assigned command partitions directly and publish durable venue event batches, ack'ing only after that publication succeeds.

Objective (superseded): process accepted stream commands exactly once at the business outcome layer under at-least-once delivery.

Deliverables:
- add scoped idempotency guard with payload hash, command ID, stream sequence, and first-seen timestamp.
- replay same-key/same-body requests and reject same-key/different-body requests with `409`.
- add partition workers that preserve per-partition ordering.
- execute existing venue command path from workers.
- write canonical command result and event log in one DB transaction.
- ack JetStream only after canonical DB commit.
- handle redelivery after DB commit without duplicate trades, events, or terminal command results.

Exit criteria:
- crash/retry tests prove no duplicate venue outcomes.
- first processed phase reaches `5000` completed commands/sec with bounded backlog.

### P4: Batch Canonical Runtime Persistence

Objective: reduce runtime write-model round trips for high-volume order flow.

Deliverables:
- implement `RuntimePersistenceMode=sync|async-batched`.
- batch orders, submit results, runtime events, executions, and trades where domain semantics allow.
- add bounded queues, flush-by-size, flush-by-interval, drain hooks, and graceful shutdown flush.
- define overflow policy per mode: block, reject overload, or sync fallback.

Exit criteria:
- `async-batched` reaches at least `7500 completed/sec` or clearly identifies the next bottleneck.
- graceful shutdown tests do not lose accepted commands.

### P5: Canonical Events And Projections

Objective: remove UI/query projection writes from command processing while keeping replayable state.

Deliverables:
- keep Postgres as canonical event and lifecycle history.
- formalize canonical command-result and event-log linkage for stream workers.
- introduce projection workers only after canonical write timing is explicit.
- add projection lag and rebuild controls.
- add projection watermarks and lag snapshots for run, partition, bot, and symbol metrics.
- keep status APIs compatible while implementation moves behind projections.

Exit criteria:
- command hot path writes canonical command/runtime state only.
- projection lag is observable and does not affect command correctness.

### P5.5: Engine Shards And Hot Book Structure

Status: done locally. The Reef-owned book (`services/matching-engine/internal/book`) with ordered price levels, FIFO queues per price, and direct order-id unlinking shipped, and the "July 4, 2026 Checkpoint" in [`HOT_BOOK_SHARDING_PLAN.md`](./HOT_BOOK_SHARDING_PLAN.md) and [`PERFORMANCE_LEARNINGS.md`](./PERFORMANCE_LEARNINGS.md) shows single hot-book and multi-book partitionable throughput clearing this phase's targets; the hot book is no longer the active limiter. Snapshot/replay checksum work remains open and is tracked as its own next slice, not a blocker for this phase's exit criteria.

Objective: make matching-engine partition ownership and shard-local book state explicit before adding deeper persistence and replay work.

Deliverables:
- keep the hot book in Go memory inside the matching-engine shard that owns the command partition.
- route `runId + venueSessionId + instrumentId` to one deterministic partition and one shard owner.
- add a Reef-owned book abstraction with ordered price levels, FIFO queues per price, and an order-id index for direct cancel/modify unlinking.
- use `github.com/tidwall/btree` only as an ordered price-level index where benchmark evidence supports it.
- preserve single-writer command processing per book; parallelism comes from many books/partitions, not concurrent mutation inside one book.
- define snapshot metadata for shard-local recovery: partition, shard, stream/topic offsets, book sequence, open orders, price levels, checksum, engine version, and contract version.
- add hot-book benchmarks for deep resting books, cancel-heavy, modify-heavy, alternating-cross, hot single-instrument, and many-instrument workloads.

Exit criteria:
- existing matching behavior remains test-compatible.
- cancel/modify no longer require heap/queue scans by order ID.
- reports distinguish hot single-book capacity from multi-book partitionable capacity.
- snapshot plus replay checksum work is scoped before any production-shaped recovery claim.

### P6: Partitioning And Retention

Objective: prevent long stress runs and arena runs from degrading the hot path.

Deliverables:
- use run/session attribution for retention and diagnostics.
- split command payload/history from hot lookup tables.
- partition or archive terminal history by completion time and later by run/session.
- keep pruning dry-run-first and retention-pin aware.

Exit criteria:
- repeated high-throughput runs do not collapse from table growth.
- named replay/audit runs remain protected.

### P7: Kubernetes Foundation

Objective: prepare a basic cluster without pretending kube solves the hot path.

Deliverables:
- define config profiles for `sync-result`, `captured-ack`, `stream-ack`, and capacity diagnostics.
- add readiness checks that consider database health and intake safety.
- add liveness checks that do not kill pods during normal drain.
- implement graceful shutdown: stop accepting, finish or release leased/owned partition work, flush batches, then exit.
- document pod resource requests, limits, and required environment variables.
- add per-pod and cluster-wide metric labeling.

Exit criteria:
- rolling restart does not strand accepted commands.
- failed pod work is reclaimed through leases.
- reports distinguish per-instance throughput from cluster-wide throughput.

### P8: Bot-Arena Readiness

Objective: make bot traffic safe to attach to the venue path.

Deliverables:
- require bot traffic to include run/session/bot attribution.
- keep built-in market-maker and traffic bots on the same command/API path as user bots.
- define arena storage separate from the trading hot path for leaderboard, metadata, and replay artifacts.
- gate bot submissions through sandbox, static review, runtime limits, and circuit breakers before public use.
- add per-bot throttle and ban lists that reject before durable command acceptance when necessary.

Exit criteria:
- local bot traffic can stress the same venue path with visible attribution and no direct state mutation.
- arena metadata growth cannot slow command intake.

## Immediate Implementation Slice

The hot-book replacement slice is complete locally: `services/matching-engine/internal/book` owns ordered price levels, FIFO queues, and direct order-id unlinking, and the July 4 engine-only benchmarks show the book is no longer the active limiter.

This slice has partly landed: profile validation, local materializer smoke, event-batch replay/checksum, direct-path crash gate, and the short DigitalOcean `10k` materializer gate now exist. Remaining active work is longer remote direct-materializer soak evidence, API/control-plane hardening, and lifecycle/scenario locks as ordered in `WORK_PLAN.md`.

Historical implementation slice:

1. Keep named stream profiles honest with profile validation before throughput runs, especially no-op publisher, bounded in-memory intake retention, direct-stream, Redpanda/Kafka-compatible, and materializer profiles.
2. Add broader crash/restart integration coverage for API publish-before-marker enqueue, matching-engine direct consumption, worker repair, projector replay, and materializer offset commit.
3. Run short durable gates before long soaks: durable publish ack, direct engine consume, venue event batch publish, compact canonical materialization, projection replay/idempotency, and replay/checksum with `0` accepted/materialized gaps.
4. Promote only clean short gates to the DigitalOcean/OpenTofu harness and compare actual attempted, accepted, direct-acked, materialized, projected, p95/p99, lag, and restart behavior against local evidence.
5. Scope snapshot plus replay checksum as the next matching-engine recovery slice, not as a prerequisite for the current materializer proof.

Only after this slice should Reef treat longer DO soaks, deeper engine sharding, or production-shaped snapshot recovery as capacity improvements.
