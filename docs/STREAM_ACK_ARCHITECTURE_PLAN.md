# Stream-Ack Architecture Plan

## Purpose

Define the high-throughput command path Reef should move toward for bot-arena and simulator scale.

The current Postgres `captured-ack` path is useful as a correctness baseline and fallback, but the measured local ceiling is still below the required `7500-10000` completed commands/sec per runtime + engine instance. More small tuning on the same command-log hot path is unlikely to close the gap.

The target architecture uses JetStream as a durable, ordered ingress log and Postgres as the canonical venue outcome/event store.

## Product Framing

Reef's high-throughput path is a deterministic simulated market venue. It should borrow the shape of real market infrastructure for command durability, same-book ordering, matching correctness, audit, replay, and projections, while staying local-first and game/simulator oriented.

This means the core stream-ack path must answer two questions for every accepted command:

- what did Reef durably accept?
- what did the simulated venue decide?

Everything else is downstream observation, scoring, or operator experience.

Target shape:

```text
bot / simulator / manual client
  -> API command boundary
  -> durable accepted-command stream
  -> deterministic partition lane
  -> matching / lifecycle decision
  -> canonical append-only facts
  -> async projections
  -> UI, run state, reports, leaderboards, replay indexes
```

Avoid designing the throughput path as:

```text
request
  -> synchronously mutate every normalized table and read model
  -> update UI/control-room state
  -> treat DB row state as workflow engine, audit log, and projection
```

The synchronous path can remain as a correctness/debug fallback. It is not the target architecture for bot-arena or simulator-scale throughput.

## Research Basis

The architecture uses official vendor guidance as constraints, not as a copy of any one product:

- [NATS JetStream streams](https://docs.nats.io/nats-concepts/jetstream/streams) are message stores; JetStream publish calls can acknowledge successful storage, streams support retention/discard policy, and `Nats-Msg-Id` deduplication is a transport aid rather than a complete business-idempotency model.
- [NATS JetStream consumers](https://docs.nats.io/nats-concepts/jetstream/consumers) track delivery and acknowledgements, can redeliver unacknowledged messages, and pull consumers are recommended by NATS for new projects when scalability, flow control, or error handling matter.
- [NATS subject mapping and partitioning](https://docs.nats.io/nats-concepts/subject_mapping) supports deterministic token partitioning, including composite partition keys; Reef uses that pattern so same-book lifecycle commands stay in one ordered lane.
- [PostgreSQL bulk-loading guidance](https://www.postgresql.org/docs/current/populate.html) favors fewer commits and bulk-oriented writes for large row volumes; Reef applies that as batched canonical append persistence rather than per-projection hot-path mutation.
- [PostgreSQL table partitioning guidance](https://www.postgresql.org/docs/current/ddl-partitioning.html) supports splitting large logical tables into smaller physical pieces when query, update, bulk load, or retention patterns justify it; Reef should partition canonical/run artifacts by run/session/time only when access patterns justify the operational cost.

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

## Throughput Metric Taxonomy

Use these definitions in stress reports, docs, and release gates:

| Metric | Definition | Release meaning |
|---|---|---|
| `attempted/sec` | client attempted requests per second | load-generator pressure only |
| `accepted/sec` | API returned `202` after durable JetStream publish acknowledgement | durable ingress capacity |
| `completed/sec` | worker processed command, canonical result/events committed, and JetStream message acked | primary venue throughput target |
| `projected/sec` | async projections caught up from canonical facts | read-model and scoring capacity |
| `visible/sec` | projected state visible to UI/control-room users | operator experience capacity |

`completed/sec` does not require synchronous updates to order blotters, trade grids, trace timelines, leaderboard counters, run summaries, search indexes, or UI read models. Those are projection requirements with explicit lag and watermark evidence.

Backpressure must look at completed throughput, oldest unprocessed age, and lag growth. A system accepting `10000/sec` while completing `3000/sec` with unbounded backlog is overloaded, not successful.

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

In stream-ack mode, canonical append-only facts become the completion boundary. Normalized order, execution, trade, status, trace, leaderboard, report, and UI tables are projections unless a future decision explicitly promotes a field back into the canonical completion transaction.

Implementation note: `runtime.canonical_command_results` and `runtime.canonical_venue_events` now exist as the append-only stream-ack outcome store. Workers append canonical submit outcomes before JetStream ack. `platform-projector-0` through `platform-projector-3` apply canonical submit outcomes into the existing normalized order/execution/trade/runtime-event read tables for explicit non-overlapping partition ranges and advance `runtime.projection_watermarks` so lag is visible.

Post-DO soak direction: the canonical completion boundary stays in Postgres for now, but the write shape should become more compact if measurements show row/commit/WAL amplification is the limiter. Acceptable next shapes include:

- one command-result row plus one canonical event row that contains a compact event array for the command
- one canonical event-batch row per worker batch with partition sequence range, stream sequence range, command count, event count, payload format, checksum, and creation time
- minimal hot indexes on canonical append tables, with richer query shapes rebuilt by projections

Any compact batch format must preserve deterministic replay, command/result lookup, partition ordering, duplicate suppression, and checksum/audit evidence. It must not make normalized read tables part of the stream-ack completion boundary again.

Minimum canonical result direction:

```text
canonical_command_results
  run_id
  venue_session_id
  partition_id
  partition_seq
  stream_name
  stream_seq
  command_id
  idempotency_key
  payload_hash
  instrument_id
  command_type
  result_status
  reject_code
  accepted_at
  completed_at
  engine_shard_id
```

Minimum canonical event direction:

```text
canonical_venue_events
  run_id
  venue_session_id
  partition_id
  partition_seq
  event_seq
  command_id
  event_id
  event_type
  aggregate_type
  aggregate_id
  instrument_id
  deterministic_event_index
  payload
  emitted_at
```

Initial uniqueness rules:

```text
unique(run_id, command_id)
unique(run_id, partition_id, partition_seq)
unique(run_id, event_id)
unique(run_id, command_id, deterministic_event_index)
```

These names are schema direction, not final migration DDL. The implementation should choose final names/types in the data-platform and Kotlin runtime slices.

Initial projection tables/counters should include:

- `run_metrics_1s`
- `partition_metrics_1s`
- `bot_metrics_1s`
- `symbol_metrics_1s`
- `projection_watermarks`
- `stream_lag_snapshots`

Projection optimization direction:

- coalesce repeated updates for the same order or aggregate inside one projection batch
- write final current state once per batch where possible
- write metrics into time buckets instead of per-event counters
- keep detailed timeline/search/audit conveniences on slower rebuildable paths when they are not required for live control-room freshness
- use staging tables plus batch merge/upsert paths where they reduce per-row overhead
- consider unlogged tables only for rebuildable projection caches, never for canonical command results or canonical venue facts

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
| 0 | architecture decision checkpoint | D-037 locks market-simulation framing, metric definitions, phase order, partition/idempotency rules, and crash/replay tests |
| 1 | role split and partition ownership | API, worker, and projector runtime roles only; workers own explicit non-overlapping partition ranges |
| 2 | canonical append store | workers commit canonical command results and venue events before stream ack; normalized tables are not completion requirements |
| 3 | async projection system | order/trade/status/timeline/leaderboard/run projections have watermarks, lag metrics, and rebuild path |
| 4 | engine shards | partition ranges map to engine shards after canonical persistence no longer hides engine parallelism |
| 5 | DigitalOcean benchmark harness | deployed API/workers/engine/NATS/Postgres with external load generator and accepted/completed/projected/replay evidence |
| 6 | post-soak write-collapse pass | rows/command, WAL bytes/command, commits/command, and partition skew evidence drives compact canonical batches and projection write reduction |

The current Postgres `captured-ack` path should remain available for local fallback and comparison, but it should not be treated as the final throughput architecture for the bot-arena target.

## Implementation Work Plan

0. Architecture decision checkpoint
- record D-037 as the stream-ack canonical append and projection split
- define accepted/completed/projected/visible metrics
- define the canonical append boundary and projection ownership
- require crash/redelivery/replay tests before throughput claims

1. Role split and partition ownership
- add runtime modes for API, worker, and projector local operation
- run API without stream workers in deployable throughput profiles
- run workers with explicit partition range ownership
- keep local and deploy-shaped runtime processes separated; do not add an all-in-one fallback mode

2. Contract and configuration
- define command envelope fields in protobuf/contracts
- define partition key and subject builder
- add stream version and partition-count configuration
- update simulator/arena command generation to include routing metadata

3. Local JetStream profile
- add NATS/JetStream to the local dev profile
- create command stream bootstrap script
- expose stream health and lag diagnostics
- add publish-ack latency telemetry

4. Stream-ack API mode
- add `stream-ack` processing mode beside `sync-result` and `captured-ack`
- validate command, idempotency key, routing metadata, and visible actor context
- publish to JetStream and return `202` only after publish ack
- return `429`/`503` before acceptance when stream health fails

5. Idempotency guard
- add scoped idempotency projection
- store payload hash, command id, stream sequence, and first-seen timestamp
- implement same-key/same-hash replay and same-key/different-hash conflict
- add crash/retry tests around publish acknowledgment

6. Canonical append store and partition worker
- consume assigned subject partitions
- preserve ordering per partition
- execute existing venue command path
- write canonical command result and event log in one DB transaction
- ack JetStream after commit only
- add redelivery idempotency tests
- initial submit slice exists: workers batch append canonical submit outcomes before ack and retain normalized writes until projector ownership is separated

7. Canonical replay support
- formalize event IDs and command-result linkage
- keep lifecycle events separate from projections
- support deterministic replay checksums
- support projection rebuild from canonical events

8. Projection isolation
- add projection worker and watermarks
- move leaderboard/metrics/UI reads to projection tables
- expose projection lag in stress reports and control-room views
- initial submit projector exists: `runtime-normalized-submit` materializes normalized submit read tables from canonical command results outside the worker ack path

9. Engine shard split
- map partition ranges to engine shard IDs
- preserve same-book ordering across shard assignment
- measure shard distribution and hot-partition behavior
- do this after canonical append/projection split unless profiling proves the engine is the next bottleneck

10. Backpressure and operations
- combine stream, worker, DB, and projection health into explicit overload decisions
- add Kubernetes readiness/liveness/drain behavior for partition ownership
- add dead-letter handling and operator remediation path

11. Post-soak write-collapse pass
- run venue-core canonical ablations before broad scaling
- add projector-only catch-up benchmark against a fixed canonical backlog
- compare even-distribution and hot-book workloads explicitly
- collapse canonical event writes only after rows/command, WAL bytes/command, commits/command, and replay requirements are measured
- batch or stream worker-to-engine interactions only when the DB write shape no longer hides engine overhead
- keep a JetStream canonical event-log pivot as a separate future decision, not an implicit implementation detail

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
- `/internal/stream-ack/health` reports command stream availability, stream bytes/messages, storage utilization, and publish-ack latency watermarks
- the API rejects before publish when the command stream is unavailable or stream storage utilization exceeds the configured threshold
- stream-ack dev mode enables pull workers for all partitions by default
- `/internal/stream-ack/worker/stats` reports worker fetch, completion, failure, ack-failure, unsupported-command, empty-poll, per-partition, local in-flight, JetStream consumer pending, ack-pending, redelivery, ack-floor, delivered-sequence, and stream-lag counters
- the first worker path processes `SubmitOrder` subjects sequentially per partition, persists fetched submit outcomes in a batch, and acknowledges JetStream deliveries only after the DB commit path returns
- `make dev-stress-stream-ack` runs a submit-only multi-instrument stream-ack stress profile and attaches stream-worker deltas to each report

Cancel/modify stream processing, partition lag telemetry, oldest-unprocessed age, projection watermarks, and replay checksum tests remain follow-up work.
