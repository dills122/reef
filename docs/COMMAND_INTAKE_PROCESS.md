# Command Intake Process

## Purpose

This document defines the near-term command intake contract for Reef's high-throughput venue path.

It turns the current stream-ack and direct engine-consumer direction into implementation work that can be planned, tested, and promoted without weakening durability, ordering, replay, or audit semantics.

## Current Scope

The first intake work covers:

- `SubmitOrder`
- `CancelOrder`

`ModifyOrder` keeps the same target shape but is deferred from the first implementation gate. It is not required for the next intake readiness milestone.

The hot path is for commands that already carry deterministic routing metadata. Commands that require lookup or repair belong on slower explicit paths and are not part of the throughput target.

## Target Lifecycle

```text
client / simulator / bot
  -> public API command boundary
  -> validation, authorization, risk, rate, abuse, and idempotency gates
  -> durable command-log publish with explicit partition
  -> 202 Accepted after durable publish ack
  -> matching-engine shard consumes assigned partition
  -> matching engine publishes durable VenueEventBatch
  -> matching engine commits command offset only after event-batch publish
  -> materializer consumes VenueEventBatch
  -> materializer writes compact canonical Postgres rows
  -> materializer commits event-batch offset
  -> downstream projections update read models, timelines, market data, reports, and UI
```

The public acceptance boundary is the durable command-log producer acknowledgement. A `202 Accepted` response must never be returned before the configured durable ingress provider acknowledges the command.

The command completion boundary is engine processing plus durable venue event-batch publication. Postgres materialization is the canonical query and audit surface, but it must not put synchronous normalized read-model writes back on the matching hot path.

## Provider Posture

Redpanda/Kafka-compatible ingress is the active next hot-ingress target because it gives explicit partitions, `acks=all`, batching, compression, idempotent producer settings, and direct matching-engine consumption.

JetStream remains available as fallback and comparison.

The provider abstraction should stay thin:

- publish one command envelope to one explicit partition
- return a durable publish acknowledgement
- expose provider health and lag snapshots
- carry provider-neutral envelope metadata
- allow provider-specific tuning without hiding it behind a broad generic bus abstraction

Do not build an enterprise-messaging abstraction that hides partitioning, acknowledgements, offsets, or backpressure. Those are part of the domain contract for this path.

## Command Envelope

Hot-path submit and cancel commands must include:

- `commandId`
- `idempotencyKey`
- `runId`
- `venueSessionId`
- `instrumentId`
- `orderId` for cancel, or order identifier assigned by submit path
- `clientOrderId` when supplied by the client
- `actorId`
- `clientId`
- `traceId`
- `correlationId`
- `causationId`
- `botId` and `botVersion` for bot-originated commands
- command payload hash

Partition key:

```text
hash(runId + venueSessionId + instrumentId) % partitionCount
```

All submit and cancel commands that affect the same run, venue session, and instrument must land on the same ordered partition lane.

## Cancel Policy

Hot-path cancel requires routing metadata.

If a cancel request does not include enough metadata to route to the same partition as the order, the boundary rejects it with `400`. It must not perform a synchronous DB or projection lookup on the hot path.

A slower cancel resolver can be added later:

```text
POST /api/v1/orders/cancel-by-client-order
  -> resolve clientOrderId/order context outside hot path
  -> emit normal CancelOrder with runId, venueSessionId, instrumentId, and orderId
```

The slower resolver is not part of the throughput target.

## Acceptance Response

Public `202` response:

```json
{
  "commandId": "cmd-123",
  "status": "ACCEPTED",
  "statusUrl": "/api/v1/commands/cmd-123",
  "acceptedAt": "2026-07-06T00:00:00Z",
  "processingMode": "stream-ack"
}
```

Internal and diagnostic responses may include provider metadata:

```json
{
  "provider": "redpanda",
  "stream": "REEF_COMMANDS",
  "partition": 12,
  "streamSequence": 123456
}
```

Provider metadata is useful for simulator reports, admin diagnostics, and replay checks. It should not be required by normal public clients.

## Idempotency

Idempotency scope:

```text
clientId + route + idempotencyKey
```

Payload hash remains inside the idempotency record.

Rules:

- same scope and same payload hash returns the prior accepted command reference while the idempotency record exists
- same scope and different payload hash returns `409`
- once retention expires, no idempotency guarantee remains for that key
- completion status is obtained through `/api/v1/commands/{commandId}`, not by changing duplicate-acceptance semantics

Retention should be configurable:

- bounded retention for local benchmark and no-DB ceiling profiles
- run-lifetime retention plus short grace for simulator and arena runs
- explicit retention pins for named replay or audit runs
- longer retention for operationally important command classes

The hot path should avoid writing full command payloads to Postgres before broker publish. If a Postgres idempotency guard is required for correctness in a profile, keep it as small as possible and benchmark it separately from in-memory bounded guards.

## Accepted But Not Completed

Accepted-but-not-completed means "durably accepted, outcome pending." It must never mean "maybe dropped."

Valid pending causes:

- command is in broker partition backlog and has not been consumed
- engine consumed it but failed before event-batch publication, so the command offset is uncommitted and broker redelivery will happen
- engine published the event batch but crashed before command offset commit, so redelivery must observe deterministic command/event uniqueness and avoid duplicate outcomes
- materializer has not yet consumed the event batch
- materializer crashed before committing its event-batch offset, so event-batch redelivery will happen and canonical inserts must be idempotent

Required guarantee:

- accepted command remains durable until the matching-engine handoff is durable
- command offset commits only after durable `VenueEventBatch` publication
- event-batch materializer offset commits only after compact canonical Postgres rows commit
- redelivery is normal recovery behavior
- deterministic IDs and uniqueness constraints prevent duplicate trades, executions, lifecycle events, and terminal outcomes

After load stops and the configured drain window passes, accepted commands must equal terminal canonical outcomes. Any remaining gap fails the run and must be diagnosed as broker backlog, engine redelivery, event materializer lag, or poison command handling.

Poison commands must not be silently dropped. After the configured retry policy is exhausted, the engine path must publish a durable terminal failure outcome, commit the command offset, and let materialization close the accounting gap with `FAILED`.

## Command Status

Status lookup should be provider-neutral and ordered by authority:

1. `runtime.canonical_command_outcomes`
2. durable event-batch metadata when the batch exists but compact outcome projection is still catching up
3. stream intake or broker reference for accepted-but-not-completed commands
4. legacy command-log fallback for non-hot modes

Minimum status fields:

- `commandId`
- `status`: `ACCEPTED`, `IN_FLIGHT`, `EVENT_PUBLISHED`, `COMPLETED`, `REJECTED`, `FAILED`, or `UNKNOWN`
- `resultStatus` when known
- `rejectCode` when known
- `runId`
- `venueSessionId`
- `instrumentId`
- `commandType`
- `acceptedAt`
- `completedAt` when known
- `source`: `canonical_outcome`, `event_batch`, `stream_reference`, or `command_log`

Diagnostic fields may include partition, stream sequence or offset, event batch id, materializer lag, and provider name.

## Backpressure And Errors

Use explicit response codes:

- `202`: durable command-log publish acknowledgement succeeded
- `400`: request is invalid, including missing hot-path routing metadata
- `409`: idempotency key conflict with different payload hash
- `429`: system is healthy but overloaded or backpressured
- `503`: durable acceptance cannot be proven because a dependency is unavailable, publish failed, or publish timed out

First backpressure inputs:

- producer in-flight at configured maximum
- publish queue saturation
- publish acknowledgement timeout
- broker unavailable
- command topic lag and oldest unconsumed command age
- event materializer lag and oldest unmaterialized event-batch age

In `venue-core` mode, projection lag is reported but does not reject command intake. In `control-room-fresh` mode, projection lag may reject or throttle when operator freshness is part of the workload contract.

## Readiness Gate

The first durable intake readiness gate should prove:

- `SubmitOrder` and `CancelOrder` use the same public command boundary as manual users, simulators, and bots
- `ModifyOrder` is explicitly deferred but reserved in the contract
- hot cancel without routing metadata is rejected
- API returns `202` only after durable publish acknowledgement
- duplicate same-key/same-payload replay returns the previous accepted reference
- duplicate same-key/different-payload returns `409`
- engine direct consumer owns completion for the hot path
- command offset commits only after durable venue event-batch publication
- event-batch materializer writes compact canonical rows idempotently
- status lookup shows accepted, pending, completed, rejected, and failed states without provider leakage
- benchmark reports distinguish attempted, accepted, engine fetched, event-batch published, command offset committed, materialized, projected, and visible counts
- replay/checksum tooling proves no accepted-command accounting gap after drain

Projection freshness is a separate readiness gate. Intake readiness requires canonical materialization and replay evidence, not UI or market-data freshness.

## First Proof Target

Baseline target:

```text
10k/sec for 60s locally
```

Required counters:

- attempted
- accepted
- engine fetched
- engine processed
- event-batch published
- command offset committed
- materialized canonical outcomes
- projected work items
- visible/read-model caught-up count where applicable
- broker lag
- materializer lag
- oldest unconsumed age
- oldest unmaterialized age
- p95 and p99 API latency
- publish ack p95 and p99

The target is not a release claim by itself. It is the first local gate before longer DigitalOcean soaks.

## Failure Tests

Required before promoting the path:

- duplicate idempotency key with same payload
- duplicate idempotency key with different payload
- publish timeout returns `503` before durable acceptance
- broker unavailable returns `503`
- producer in-flight saturation returns `429`
- cancel without routing metadata returns `400`
- engine crash before event-batch publish redelivers command
- engine crash after event-batch publish but before command offset commit does not duplicate outcome
- event-batch publisher failure leaves command offset uncommitted
- materializer crash before offset commit redelivers event batch idempotently
- poison command produces durable terminal `FAILED` outcome after retry policy
- drain check fails any accepted/materialized accounting gap

## Implementation Backlog

### Now

- Add or confirm `SubmitOrder` and `CancelOrder` envelope validation for hot-path routing metadata.
- Ensure hot cancel rejects missing `runId`, `venueSessionId`, `instrumentId`, and order identifier.
- Keep `ModifyOrder` out of the first gate while preserving subject/type compatibility.
- Make `202` response stable and public, with provider metadata behind diagnostics.
- Confirm idempotency scope and payload hash behavior in tests.
- Ensure stream producer profile can run Redpanda/Kafka-compatible ingress with explicit partition, `acks=all`, bounded in-flight work, batching, and compression.
- Ensure matching-engine direct consumer publishes `VenueEventBatch` before committing command offsets.
- Ensure materializer status lookup and canonical outcomes close accounting.
- Add one named intake proof script or make target that produces the required counter set.
- Add failure tests for duplicate idempotency, missing cancel metadata, publish timeout, and drain accounting.

### Next

- Add engine crash/redelivery and materializer crash/redelivery test harness.
- Add poison command terminal failure policy and tests.
- Promote local `10k/sec 60s` proof to longer DigitalOcean soak.
- Add status endpoint diagnostic fields for partition, sequence or offset, batch id, source, and lag.
- Add slow cancel resolver for client-order-only cancel flows outside the hot path.
- Revisit Postgres idempotency guard cost against bounded in-memory guard evidence.

### Defer

- `ModifyOrder` in the first intake gate.
- engine snapshots and routing epochs.
- dynamic shard migration and hot-book move handoff.
- venue-session-specific market-data depth projection.
- leaderboard, analytics, UI, and control-room freshness as command intake blockers.
- post-trade intake expansion.

## Non-Goals

- no public `202` before durable ingress acknowledgement
- no synchronous normalized read-model writes on the matching hot path
- no cancel hot-path lookup through Postgres or projections
- no treating raw accepted/sec as release readiness
- no hidden accepted/completed accounting gaps after drain
- no provider swap abstraction that hides partition, offset, or acknowledgement semantics
