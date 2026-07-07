# Post-Arena Platform Finalization Plan

## Purpose

Capture the future platform-shape work to revisit after the bot arena, backbone services, and run-plane infrastructure are stood up and tested.

This is not the current execution checkpoint. The active checkpoint remains durable direct path hardening for the venue core and the bot-arena/backbone rollout described in [`WORK_PLAN.md`](./WORK_PLAN.md), [`BOT_ARENA_PLAN.md`](./BOT_ARENA_PLAN.md), and [`SYSTEM_BACKBONE_SERVICES.md`](./SYSTEM_BACKBONE_SERVICES.md).

This plan exists so the long-term venue architecture is not lost while near-term work focuses on arena infrastructure, simulator topology, and backbone operations.

## Timing

Reopen this plan when these conditions are true:

- the arena control plane can create, run, score, and archive local/built-in bot runs through the normal command path
- backbone services have durable admin and analytics storage separated from run-plane runtime storage
- the run plane can be provisioned, exercised, exported, and torn down repeatably
- the direct durable path has local smoke, replay/checksum, and at least one remote soak with clean accepted/materialized accounting
- bot/user read surfaces have enough live proof for arena bots to run without fixture-only assumptions

Until then, keep this as a future plan and avoid broad platform rewrites.

## Current State To Build From

Reef already has most of the technical seams needed for the future shape:

- `stream-ack` command intake can publish to JetStream or Redpanda/Kafka-compatible durable logs.
- command envelopes require routing metadata and derive a deterministic partition from `runId + venueSessionId + instrumentId`.
- the Redpanda/Kafka producer uses explicit partitions, durable acknowledgements, idempotent producer mode, bounded in-flight work, batching, and compression.
- the matching engine has a direct stream-consumer path behind `MATCHING_ENGINE_DIRECT_STREAM_ENABLED`.
- matching-engine direct consumers can publish durable `VenueEventBatch` records before command offsets are committed.
- platform materializers can consume venue event batches and commit compact canonical Postgres rows before event-batch offsets are committed.
- projection/read-model workers are already separate enough to keep UI, leaderboard, and market-data freshness out of the command completion boundary.

The remaining work is mostly promotion, operational hardening, and finalizing ownership boundaries, not inventing a new architecture.

## Target Platform Shape

The long-term venue-core path should be:

```text
client / simulator / bot
  -> public API boundary
  -> validation, auth, risk, rate, and scoped idempotency guard
  -> durable command-log publish acknowledgement
  -> 202 Accepted with provider-neutral command reference
  -> matching-engine shard consumes owned command partitions
  -> shard-local in-memory order book
  -> durable VenueEventBatch publication
  -> command offset commit
  -> async compact canonical Postgres materialization
  -> async projections, market data, timelines, leaderboards, analytics, and UI
```

The key property is that durable command acceptance, matching completion, canonical materialization, and projection visibility are separate lifecycle stages with separate metrics.

## Partitioning Model

Use deterministic partitioning by matching conflict domain:

```text
partitionKey = runId + "|" + venueSessionId + "|" + instrumentId
partition = hash(partitionKey) % partitionCount
```

Required invariant:

- `SubmitOrder`, `CancelOrder`, and `ModifyOrder` for the same run/session/instrument must route to the same ordered lane.

Do not use generic queue load balancing for matching-sensitive commands. It can improve worker utilization, but it can also move related commands to different consumers and break same-book ordering.

For later scale, introduce a logical-lane layer before physical shard migration:

```text
logicalLane = hash(runId, venueSessionId, instrumentId) % logicalLaneCount
physicalPartition = laneAssignment[logicalLane]
engineShard = shardAssignment[physicalPartition]
```

This lets Reef change shard ownership deliberately without changing the public routing contract.

## Dormant Integration Posture

The path can exist before it becomes the default.

Recommended near-term posture:

- keep `sync-result` as the deterministic correctness mode
- keep Postgres `captured-ack` as fallback/A-B baseline
- keep JetStream as fallback/comparison
- keep Redpanda/Kafka-compatible direct stream as opt-in experimental/promotion path
- keep direct matching-engine stream consumption disabled by default outside named profiles
- keep materializer roles explicit and separately observable
- require profile validation so incompatible worker/direct-consumer combinations cannot accidentally process the same command stream

The goal is to make the future path easy to run and measure without requiring every local development task to use it.

## Rollout Phases

### Phase 0: Freeze The Contract

Document and test the command intake contract as a platform boundary:

- stable public `202 Accepted` response
- provider-neutral command status lookup
- scoped idempotency: `clientId + route + idempotencyKey`
- same idempotency scope and same payload hash returns the prior accepted command reference
- same idempotency scope and different payload hash returns `409`
- hot cancel/modify commands must carry routing metadata, not perform hot-path DB lookup
- provider details such as partition and offset remain diagnostic metadata

Exit criteria:

- command contract tests cover submit and cancel first
- modify follows the same metadata/routing shape even if it is not in the first hot gate
- docs and status APIs distinguish accepted, engine-processed, materialized, projected, and visible

### Phase 1: Named Local Profile

Create one boring local command for the future path, separate from current default dev setup:

```text
API stream-ack Redpanda
  -> matching-engine direct consume
  -> Redpanda VenueEventBatch topic
  -> platform materializer
  -> compact canonical rows
  -> projection/replay proof
```

This can be a make target or script wrapper around the existing compose profiles.

Exit criteria:

- one command starts the stack
- one smoke proves accepted command -> event batch -> canonical outcome -> projection watermark
- the profile uses isolated stream/topic names by default for smoke runs
- docs explain which legacy workers must be off when direct engine consumption is on

### Phase 2: Failure Matrix

Add crash/restart tests before using throughput numbers as evidence:

- API publishes then exits before local published-marker update
- broker publish timeout rejects before acceptance
- matching engine exits before event-batch publish
- matching engine publishes event batch then exits before command offset commit
- event-batch publish fails and command offset remains uncommitted
- materializer commits canonical rows then exits before event-batch offset commit
- projector exits mid-batch and replays idempotently
- poison command eventually publishes/materializes terminal `FAILED` outcome

Exit criteria:

- every accepted command is terminal or visible as durable pending work after recovery
- replay/checksum tooling reports no command count, payload hash, stream sequence, or projection watermark mismatch
- no duplicate trades, executions, lifecycle events, command outcomes, or order rows appear after redelivery

### Phase 3: Operational Ownership

Make partition and shard ownership explicit:

- checked-in local shard map for 64 partitions
- environment-driven override for experiments
- metrics by partition and shard
- clear startup validation for overlapping partition ownership
- drain/fencing plan before any future live reassignment
- routing epoch field reserved before dynamic migration is attempted

Exit criteria:

- logs and metrics can answer which engine owns a command partition
- a bad profile with two owners for the same partition fails fast
- hot partitions are visible as routing skew, not hidden in aggregate throughput

### Phase 4: Materialization And Projection Finalization

Keep the matching hot path compact and make Postgres the materialized canonical/query store:

- durable venue event batches are the matching-engine recovery handoff
- compact canonical Postgres rows preserve batch id, shard id, partition id, stream/topic names, first/last command sequence, command count, payload checksum, format/version, and replay-safe payload
- command outcome rows support provider-neutral status lookup and idempotent materializer replay
- normalized orders, executions, trades, timelines, market data, leaderboards, and UI rows stay projections unless a future decision promotes a field into compact canonical storage

Exit criteria:

- materializer can be restarted safely under load
- replay from stored event batches is deterministic
- projections can be rebuilt without changing canonical outcomes
- status lookup remains useful while projections lag

### Phase 5: Throughput Promotion Ladder

Only after the local failure matrix is clean, promote through measured tiers:

| Tier | Requirement |
|---|---|
| Local smoke | one run, no accepted/materialized gap, replay clean |
| Local short soak | `1000-2500/sec`, mixed submit/cancel/modify where supported, clean drain |
| Local pressure | `5000/sec`, bounded partition lag, materializer catches up |
| Remote gate 1 | `2000 completed/sec` for at least `5m`, clean artifacts |
| Remote gate 2 | `5000 completed/sec` for at least `5m`, clean artifacts |
| Remote gate 3 | `7500 completed/sec` for at least `10-15m`, clean drain |
| Future target | `100k/sec` aggregate sustained for `15-60m`, across enough independent books/partitions |

Do not treat raw accepted/sec as success. Required evidence:

- attempted/sec
- accepted/sec after durable publish ack
- engine processed/sec
- event batches published/sec
- command offsets committed/sec
- materialized/sec
- projected/sec
- oldest unprocessed age
- per-partition lag and skew
- p95/p99 publish ack and event-publish latency
- accepted/materialized/projected accounting after drain
- replay/checksum artifact paths

### Phase 6: Scale-Out And Recovery

After the single-shard direct path is proven:

- split engine shards by partition ranges
- run multiple materializers in a single consumer group where provider semantics support it
- separate canonical Postgres pressure from projection/read-model pressure
- add shard-local book snapshots as recovery accelerators, not source of truth
- require snapshot + durable command/event replay + checksum verification before claiming recovery support

Exit criteria:

- cluster capacity is reported as aggregate and per-instance capacity
- failover preserves same-book ordering
- one hot book is reported separately from even-distribution traffic
- snapshot recovery never bypasses replay/audit verification

## 100k/sec Interpretation

The `100k/sec` long-term target should mean aggregate venue command throughput across many independent books and partitions.

It should not imply that one mutable order book can be safely processed by many concurrent writers. A single hot instrument/session has a serial matching decision point if price-time priority and deterministic replay are preserved.

Use two labeled benchmark classes:

- even-distribution benchmark: many instruments/sessions, measures partitionable aggregate throughput
- hot-book benchmark: one or a few instruments dominate, measures single-book/shard limits and routing skew behavior

Both are useful. They answer different capacity questions.

## Known Gaps

Before this can become the default platform shape:

- direct Redpanda/Kafka profile needs a clearer one-command local runbook
- profile validation must prevent legacy stream workers and direct engine consumers from double-consuming the same stream
- topic/stream creation remains local/dev shaped and needs production-like replication/retention settings for remote evidence
- routing epochs and partition migration are not implemented
- shard-local snapshot format and checksum inputs are not finalized
- idempotency/intake storage pressure still needs measurement under high accepted throughput
- materializer and projection lag need promotion-grade dashboards or machine-readable reports
- poison command retry/terminal failure policy needs full accounting tests
- public status and diagnostics need to remain provider-neutral while preserving partition/offset evidence for operators

## Research Basis

The plan follows common stream-processing constraints rather than treating the broker as magic scale-out:

- Kafka topics scale by partitions; events with the same key route to the same partition and consumers read each partition in write order: [Confluent Kafka introduction](https://docs.confluent.io/kafka/introduction.html).
- Redpanda producer configuration supports explicit keys/partitions, `acks=all`, idempotence, batching, compression, and bounded in-flight behavior: [Redpanda producer docs](https://docs.redpanda.com/streaming/current/develop/produce-data/configure-producers/).
- NATS documents deterministic subject partitioning as the way to preserve ordered processing per subject while scaling consumers/streams: [NATS subject mapping and partitioning](https://docs.nats.io/nats-concepts/subject_mapping).
- Kafka consumer offsets are the durable resume point for a consumer group; committing before durable business handoff risks lost work: [Confluent consumer offsets](https://docs.confluent.io/kafka/design/consumer-design.html).
- PostgreSQL high-volume loading guidance favors fewer commits and bulk-oriented writes; table partitioning is useful when table size, retention, or access patterns justify the operational cost: [PostgreSQL populate](https://www.postgresql.org/docs/current/populate.html), [PostgreSQL partitioning](https://www.postgresql.org/docs/current/ddl-partitioning.html).

## Related Docs

- [`COMMAND_INTAKE_PROCESS.md`](./COMMAND_INTAKE_PROCESS.md)
- [`STREAM_ACK_ARCHITECTURE_PLAN.md`](./STREAM_ACK_ARCHITECTURE_PLAN.md)
- [`THROUGHPUT_SCALING_WORK_PLAN.md`](./THROUGHPUT_SCALING_WORK_PLAN.md)
- [`ARCHITECTURE_THROUGHPUT_PLAN.md`](./ARCHITECTURE_THROUGHPUT_PLAN.md)
- [`HOT_BOOK_SHARDING_PLAN.md`](./HOT_BOOK_SHARDING_PLAN.md)
- [`BOT_ARENA_PLAN.md`](./BOT_ARENA_PLAN.md)
- [`SYSTEM_BACKBONE_SERVICES.md`](./SYSTEM_BACKBONE_SERVICES.md)
- [`CURRENT_STATUS.md`](./CURRENT_STATUS.md)
- [`DECISIONS.md`](./DECISIONS.md)
