# Throughput Scaling Work Plan

## Purpose

Define the execution plan for making Reef ready for bot-arena traffic, high-throughput simulation runs, and an initial Kubernetes deployment without weakening correctness, replay, or audit guarantees.

This plan supersedes the earlier `5k accepted rps` target for the active bot-arena scaling track. The current goal is completed lifecycle throughput: commands accepted by the runtime must be durably captured, processed to a terminal state, and accounted for without silent drops.

## Non-Negotiable Goals

1. No accepted command is silently lost.
- If the runtime returns an accepted response, the command must have a durable command-log record.
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

These targets apply to the durable `captured-ack` venue path, not the stripped-down capacity-baseline mode. `sync-result` remains the correctness and compatibility mode.

## Benchmark Modes

| Mode | Purpose | Gate Role |
|---|---|---|
| `sync-result` | deterministic compatibility and strict request/result behavior | correctness gate |
| `captured-sync-engine` | transitional capture plus synchronous execution shape | contract comparison |
| `captured-ack` | durable accepted response with async command processing | primary high-throughput gate |
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

Interpretation:
- Reef is not blocked on the matching engine for this traffic shape.
- The API can accept near the minimum target in narrow intake tests, but the full accepted-to-terminal lifecycle cannot yet drain at the required rate.
- The next work should reduce write amplification and add stronger accounting/backpressure before relying on more workers or pods.

## Work Plan

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

### P2: Reduce Command Intake Write Cost

Objective: keep durable acceptance fast without duplicating hot-path writes.

Deliverables:
- keep `command_log.commands` narrow as the command-ID and idempotency anchor.
- split bulky request payloads out of the hot command index.
- keep active queue rows small and active-only.
- benchmark inline append versus function append only under the completed-throughput gate.

Exit criteria:
- durable intake remains above the minimum target without increasing accounting gaps or queue instability.

### P3: Batch Command Completion

Objective: reduce per-command result and queue-completion write overhead.

Deliverables:
- batch terminal result inserts/upserts.
- batch active queue deletes or terminal transitions.
- keep completion idempotent so retries cannot duplicate terminal results.
- expose batch size, flush interval, flush latency, and failed-batch metrics.

Exit criteria:
- completed throughput improves materially without weakening status lookup or retry semantics.

### P4: Batch Runtime Persistence

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
- introduce outbox/projection workers only after canonical write timing is explicit.
- add projection lag and rebuild controls.
- keep status APIs compatible while implementation moves behind projections.

Exit criteria:
- command hot path writes canonical command/runtime state only.
- projection lag is observable and does not affect command correctness.

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
- define config profiles for `sync-result`, `captured-ack`, and capacity diagnostics.
- add readiness checks that consider database health and intake safety.
- add liveness checks that do not kill pods during normal drain.
- implement graceful shutdown: stop accepting, finish or release leased work, flush batches, then exit.
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

The next implementation slice should be:

1. Add run/session attribution to command-log intake and stress tooling.
2. Add backlog-adjusted accounting metrics to captured-ack stress reports.
3. Add no-loss validation that fails a stress run on accepted-command gaps.
4. Add explicit backlog and lease-age thresholds for durable intake backpressure.
5. Implement and benchmark batched command completion.

Only after that slice should Reef treat more async workers, Kubernetes replicas, or bot traffic as capacity improvements.
