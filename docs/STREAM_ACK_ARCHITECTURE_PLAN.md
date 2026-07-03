# Stream-Ack Architecture Plan

## Purpose

Define the high-throughput command path Reef should move toward for bot-arena and simulator scale.

The current Postgres `captured-ack` path is useful as a correctness baseline and fallback, but the measured local ceiling is still below the required `7500-10000` completed commands/sec per runtime + engine instance. More small tuning on the same command-log hot path is unlikely to close the gap.

The target architecture uses JetStream as a durable, ordered ingress log and Postgres as the canonical venue outcome/event store.

## Target Guarantees

- minimum `7500` completed commands/sec per runtime + engine instance
- preferred `10000` completed commands/sec per runtime + engine instance
- no silent drops after accepted response
- bounded backlog during load
- visible lag, oldest unprocessed age, and drain rate
- deterministic replay from accepted commands plus canonical venue events
- projections can lag without blocking order processing
- overload rejects before durable acceptance

Accepted throughput remains diagnostic. Release readiness depends on completed throughput, terminal accounting, bounded lag, and deterministic replay.

## Architecture Shape

```text
client / simulator / arena bot
  -> external API boundary
  -> validation, auth, idempotency guard, risk/rate gate
  -> JetStream durable publish ack
  -> 202 Accepted with command reference
  -> partition worker
  -> matching engine / venue application boundary
  -> canonical command result + event log transaction in Postgres
  -> JetStream ack
  -> projection workers
  -> metrics, leaderboards, replay indexes, UI read models
```

JetStream is the durable accepted-command log. Postgres remains the canonical record of what the venue decided.

## Stream Configuration Guardrails

Do not model accepted commands as a transient queue.

Required command stream posture:

- retained log with explicit replay window
- file storage
- bounded stream size with reject/discard-new behavior when full
- durable publish acknowledgments required for acceptance
- explicit stream version and subject contract
- no `202 Accepted` until publish ack succeeds
- publish failure, timeout, or overload returns `503` or `429`
- consumer acknowledgments happen only after canonical DB facts are durable

Do not use JetStream WorkQueue retention for the accepted-command stream. WorkQueue semantics are too queue-shaped for the audit/replay guarantee needed here.

## Deterministic Partitioning

All commands that mutate the same order book must route to the same partition lane.

Subject shape:

```text
reef.cmd.v1.pXX.<venueSessionId>.<instrumentId>.<commandType>
```

Partition function:

```text
partition = hash(venueSessionId + instrumentId) % partitionCount
```

For isolated simulator or arena runs:

```text
partition = hash(runId + venueSessionId + instrumentId) % partitionCount
```

Submit, cancel, and modify commands must carry enough routing metadata to avoid a synchronous database lookup on the hot path:

- `runId`
- `venueSessionId`
- `instrumentId`
- `commandType`
- `clientOrderId` or `orderId`
- `idempotencyKey`
- `actorId`
- `botId` and `botVersion` when arena-originated
- `traceId`, `correlationId`, and `causationId`

Cancel/modify commands without the routing key are rejected at the boundary or sent through a slower explicit lookup path that is not part of the throughput target.

## Idempotency Protocol

JetStream message deduplication is not sufficient business idempotency.

Use a hybrid model:

- `Nats-Msg-Id` is the scoped idempotency key.
- a small idempotency guard/projection records `scope`, `idempotency_key`, `payload_hash`, `command_id`, `stream_sequence`, and `first_seen_at`.
- same key and same payload hash returns the prior accepted command reference.
- same key and different payload hash returns `409`.
- stream redelivery is processed idempotently by `command_id` and canonical event IDs.

This guard can live in Postgres first. If it becomes the acceptance bottleneck, it should be narrowed or partitioned before changing semantics.

## Worker Ack Rule

Partition workers acknowledge JetStream only after canonical facts are durable.

Processing sequence:

1. consume command from assigned partition
2. validate lease/ownership and idempotency state
3. execute matching/venue command
4. persist command result and canonical venue events in one DB transaction
5. ack JetStream message
6. let projection workers process downstream events independently

Crash cases must be safe:

- crash before DB commit: JetStream redelivers and the command is processed once to a terminal result.
- crash after DB commit but before JetStream ack: redelivery observes existing canonical result/events and only acks; no duplicate trades, events, or terminal results.
- projection worker crash: command processing continues; projection lag is visible.

## Canonical Event Log And Projections

Split the data model into three responsibilities:

| Layer | Purpose | Notes |
|---|---|---|
| JetStream command stream | what Reef accepted | retained, partitioned, replayable ingress log |
| Postgres canonical event/result log | what the venue decided | authoritative lifecycle, orders, executions, trades, rejects |
| Projections | how users query/score/observe | rebuildable read models, metrics, leaderboards, UI state |

Do not put leaderboard, UI read-model, or analytics writes on the command completion hot path.

Initial projection tables/counters should include:

- `run_metrics_1s`
- `partition_metrics_1s`
- `bot_metrics_1s`
- `symbol_metrics_1s`
- `projection_watermarks`
- `stream_lag_snapshots`

## Backpressure Inputs

Backpressure must reject before durable acceptance when the system cannot safely drain.

Inputs:

- JetStream publish ack p95/p99
- JetStream storage utilization
- total stream pending
- per-partition pending
- oldest unprocessed age
- worker heartbeat freshness
- redelivery count
- DB canonical flush p95/p99
- DB commit error rate
- canonical event-log lag
- projection lag
- dead-letter count

Backpressure decisions should be recorded with reason codes so stress reports can distinguish capacity rejection from business rejection.

## Required Failure And Replay Tests

1. duplicate submit with same key/body returns the same command reference
2. duplicate submit with same key/different body returns `409`
3. API publishes then crashes before response; retry does not duplicate the command
4. worker processes engine result but crashes before DB commit; redelivery creates one final lifecycle result
5. worker commits DB batch but crashes before JetStream ack; redelivery does not duplicate trades/events
6. cancel/modify after fill returns a business reject
7. cancel/modify routes without synchronous DB lookup when routing metadata is present
8. hot single-instrument load preserves partition ordering
9. multi-instrument load uses partitions concurrently
10. active backpressure rejects before durable acceptance
11. stopped projection worker does not stop processing and exposes lag
12. replay from command stream produces a deterministic checksum
13. projection rebuild from canonical event log matches current read state

## Milestones

| Phase | Target | Gate |
|---|---|---|
| 1 | `5000` stream-ack accepted rps | durable publish ack, no accepted-command gaps, lag visible |
| 2 | `5000` processed rps | bounded backlog, stable canonical DB flush |
| 3 | `5000` projected rps | projections keep up or expose bounded lag |
| 4 | `10000` stream-ack accepted rps | no loss, no gaps, deterministic command replay |
| 5 | `10000` processed rps | sustained completed throughput with bounded backlog |
| 6 | `25000` cluster rps | batching, partition scale, and projection isolation proven |

The current Postgres `captured-ack` path should remain available for local fallback and comparison, but it should not be treated as the final throughput architecture for the bot-arena target.

## Implementation Work Plan

1. Contract and configuration
- define command envelope fields in protobuf/contracts
- define partition key and subject builder
- add stream version and partition-count configuration
- update simulator/arena command generation to include routing metadata

2. Local JetStream profile
- add NATS/JetStream to the local dev profile
- create command stream bootstrap script
- expose stream health and lag diagnostics
- add publish-ack latency telemetry

3. Stream-ack API mode
- add `stream-ack` processing mode beside `sync-result` and `captured-ack`
- validate command, idempotency key, routing metadata, and visible actor context
- publish to JetStream and return `202` only after publish ack
- return `429`/`503` before acceptance when stream health fails

4. Idempotency guard
- add scoped idempotency projection
- store payload hash, command id, stream sequence, and first-seen timestamp
- implement same-key/same-hash replay and same-key/different-hash conflict
- add crash/retry tests around publish acknowledgment

5. Partition worker
- consume assigned subject partitions
- preserve ordering per partition
- execute existing venue command path
- write canonical command result and event log in one DB transaction
- ack JetStream after commit only
- add redelivery idempotency tests

6. Canonical event log
- formalize event IDs and command-result linkage
- keep lifecycle events separate from projections
- support deterministic replay checksums
- support projection rebuild from canonical events

7. Projection isolation
- add projection worker and watermarks
- move leaderboard/metrics/UI reads to projection tables
- expose projection lag in stress reports and control-room views

8. Backpressure and operations
- combine stream, worker, DB, and projection health into explicit overload decisions
- add Kubernetes readiness/liveness/drain behavior for partition ownership
- add dead-letter handling and operator remediation path

## Current-App Changes Needed

- keep the existing synchronous mode for correctness tests
- keep Postgres `captured-ack` as a local fallback and A/B baseline
- stop spending large effort on further command-log micro-tuning unless it directly supports the stream-ack migration
- shift throughput work from one hot Postgres command queue to partitioned stream ingress plus canonical batched DB commits
- isolate arena metadata and leaderboard projections from the trading hot path
- add contract-first routing metadata before public bot traffic depends on cancel/modify behavior

## Implementation Checkpoint

The first stream-ack slice is implemented behind `EXTERNAL_API_COMMAND_PROCESSING_MODE=stream-ack`:

- command metadata now has additive routing fields for `runId`, `venueSessionId`, `clientOrderId`, `botId`, and `botVersion`
- the API validates stream routing metadata before publish
- subjects are built as `reef.cmd.v1.pXX.<venueSessionId>.<instrumentId>.<commandType>`
- the partition key is `runId + venueSessionId + instrumentId`
- `boundary.stream_command_intake` stores scoped idempotency keys, payload hashes, command references, subjects, partitions, and stream sequences
- same key and same payload replays the accepted stream reference
- same key and different payload returns `409`
- `make dev-up-stream-ack` starts the local JetStream profile and bootstraps the retained command stream

The partition worker, stream lag telemetry, redelivery-safe canonical DB commit path, and replay checksum tests remain follow-up work.
