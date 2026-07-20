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
  -> matching engine atomically publishes VenueEventBatch and commits command offset
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
- engine transaction commit returned an ambiguous result, so the shard fails closed and a fenced replacement replays to the broker's committed boundary
- materializer has not yet consumed the event batch
- materializer crashed before committing its event-batch offset, so event-batch redelivery will happen and canonical inserts must be idempotent

Required guarantee:

- accepted command remains durable until the matching-engine handoff is durable
- command offset and durable `VenueEventBatch` publication share one Kafka transaction in the Redpanda path
- matching shards hold a static Kafka ownership-group lease for their configured partition set; a replacement with the same stable shard id fences the old process
- startup reconstructs in-memory books by replaying the complete retained command prefix to each committed transaction boundary and refuses truncated or gapped history
- event materializers use `isolation.level=read_committed`, so aborted event batches are invisible
- event-batch materializer offset commits only after compact canonical Postgres rows commit
- venue event batches use a versioned semantic full-body checksum; two different
  command outcomes may never share one batch or command identity
- a materializer poll may commit several batch headers/outcome groups in one
  Postgres transaction and then commit the highest contiguous event offset once
  per assigned partition
- redelivery is normal recovery behavior
- deterministic IDs and uniqueness constraints prevent duplicate trades, executions, lifecycle events, and terminal outcomes

After load stops and the configured drain window passes, accepted commands must equal published, direct-acked, and terminal canonical outcomes exactly. Both undershoot and overshoot fail the run: undershoot indicates missing/backlogged work; overshoot indicates duplicate accounting or work. Diagnose the difference as broker backlog, engine redelivery, event materializer lag, poison command handling, or duplicate publication/materialization.

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

## Profile Classes And Gates

Use named profiles so benchmark claims state what path they prove.

| Profile class | Purpose | Gate posture | Minimum result |
| --- | --- | --- | --- |
| `nodb-ceiling` | API, durable-log producer, and direct matching-engine headroom without DB materialization | warning/diagnostic, not release evidence | high-limit probes such as `15k-20k/sec` for `30-90s`; no crashes, bounded memory, no direct ack gaps when broker-backed |
| `direct-materializer-short` | local durable direct path gate | required before remote promotion | `10k/sec` for `60s`, drain within `120s`, final accepted/materialized/projected gap `0` |
| `direct-materializer-mixed` | real-world mixed order-flow gate | required before treating the path as bot/simulation-ready | mixed submit/modify/cancel, hot/cold instruments, stale lifecycle commands, business rejects as terminal outcomes, `2k-5k/sec` for `10-15m`, final lag `0` |
| `simulation-soak` | expected bot/simulator run shape | required before public arena-style runs | `15m-1h`, lower workload-specific throughput, same accounting and replay gates |
| `do-direct-materializer-2000` | first remote durable promotion tier | blocking | `2000` completed/materialized commands/sec for `5m`, accepted within `5%` of materialized unless intentional pre-acceptance `429` |
| `do-direct-materializer-5000` | second remote durable promotion tier | blocking after `2000` tier | `5000` completed/materialized commands/sec for `5m`, same safety gates |
| `do-direct-materializer-7500` | minimum target remote promotion tier | blocking after `5000` tier | `7500` completed/materialized commands/sec for `5m`, same safety gates |

Latency gates:

- local healthy target: p95 `<75ms`, p99 `<200ms`
- DO healthy target: p95 `<150ms`, p99 `<300ms`
- p99 `>500ms` fails promotion unless the run is explicitly marked exploratory before it starts

Lag gates:

- direct worker lag drains to `0`
- materializer lag drains to `0`
- projection lag drains to `0` after the configured drain window
- `venue-core` mode may ignore projection lag for intake backpressure during the run, but final promotion still requires projection lag `0` after drain

Trace gates:

- trace checks are diagnostic for high-rate submit-only runs unless the profile explicitly makes trace projection freshness part of the gate
- accepted/materialized accounting and replay/checksum checks are hard gates for every durable profile

## Local Promotion Gates

Before any long remote soak, pass the gates below in order:

1. Profile guard.
   - active profile validates before startup
   - no no-op publisher in durable-acceptance claims
   - bounded in-memory intake retention enabled for no-DB ceiling profiles
   - provider, stream/topic, partition count, worker/projector/materializer role, and backpressure policy recorded in artifacts

2. Correctness smoke.
   - one command reaches durable publish ack, direct engine consume, durable `VenueEventBatch`, canonical materialization, projection, and status lookup
   - materializer and projector counters show `failed=0`, `ackFailed=0`, `unsupported=0`
   - order read-model reconstruction proves `acceptedOrder` facts are sufficient without depending on API command payload joins except as compatibility fallback

3. Replay/checksum gate.
   - venue event batch replay is idempotent
   - command counts match accepted/materialized counts after drain
   - payload checksum and command outcome payload hash checks pass
   - stream gaps, overlaps, duplicate replay inserts, missing canonical rows, and extra canonical rows are all `0`

4. Short load gate.
   - local target: `10k/sec` for `60s` where the active profile is intended to hit that rate; otherwise record the profile-specific target explicitly before the run
   - unexpected `5xx` is `0`
   - direct engine failed/ack-failed/unsupported counters are `0`
   - materializer failed/ack-failed/unsupported counters are `0`
   - final accepted/materialized accounting gap is `0`
   - worker and materializer lag drain to `0` within the configured drain window, or the run fails

## Failure Matrix

Required before promoting the path:

| Case | Required result |
| --- | --- |
| duplicate idempotency key with same payload | returns prior accepted command reference |
| duplicate idempotency key with different payload | returns `409` and publishes no new command |
| publish timeout | returns `503` before durable acceptance |
| broker unavailable | returns `503` before durable acceptance |
| producer in-flight saturation | returns `429` before durable acceptance |
| cancel without routing metadata | returns `400` and performs no hot-path lookup |
| API exits after publish ack before marker update | retry/status can recover from durable stream reference; worker repair completes marker |
| engine exits before event-batch publish | command offset remains uncommitted and redelivery produces one outcome |
| engine transaction aborts after event send | event batch is invisible, command offset remains uncommitted, and book mutations roll back |
| engine transaction commit is ambiguous | shard fails closed; static-owner replacement fences it and replays to the committed boundary |
| overlapping matching owner starts | ownership group assigns each partition once; replacement with the same stable shard id fences the old process |
| materializer exits after Postgres commit before event offset commit | event-batch redelivery is idempotent and inserts `0` duplicate canonical rows |
| projector exits mid-batch | replay advances watermark without duplicate read-model rows |
| poison command exhausts retry policy | durable terminal `FAILED` outcome materializes and closes accounting |
| drain check finds accepted/materialized gap | run fails; gap must be classified as broker backlog, engine redelivery, materializer lag, or poison handling |

## Crash/Restart Runbook

Run crash/restart tests locally before promoting any profile to DigitalOcean. The first pass should use explicit named kill points or deterministic test hooks, not random chaos. Randomized chaos can come after the named cases are boring.

Each case must produce one assertion report with:

- profile name
- provider and stream/topic names
- kill point id
- submitted command count
- accepted command count
- event batches published
- command offsets committed
- canonical outcomes materialized
- projections applied
- restart count
- final worker/materializer/projector lag
- replay/checksum result
- artifact paths

Required first cases:

| Test id | Kill point | Expected recovery | Required evidence |
| --- | --- | --- | --- |
| `api-publish-ack-before-marker` | API exits after durable publish ack before boundary marker/status update | retry/status repair recovers from durable stream reference | no duplicate command, stable command status, final accepted/materialized/projected gap `0` |
| `engine-before-event-batch-publish` | matching engine exits after consuming command before publishing `VenueEventBatch` | command offset remains uncommitted and broker redelivers | one deterministic terminal outcome, no missing outcome, command offset commits only after event-batch durability |
| `engine-transaction-abort-after-send` | matching engine sends `VenueEventBatch` then aborts before transactional offset commit | broker hides the aborted batch and redelivers the command | no aborted batch reaches the materializer, book rollback succeeds, final accepted/materialized gap `0` |
| `engine-transaction-commit-ambiguous` | transaction commit response is lost or indeterminate | shard fails closed; replacement acquires the stable ownership lease and replays to the committed boundary | one visible batch/offset result, matching checksum equals replay checksum |
| `materializer-after-postgres-before-event-offset` | materializer commits compact canonical Postgres rows then exits before event-batch offset commit | event batch redelivers and canonical inserts are idempotent | duplicate canonical inserts `0`, missing rows `0`, extra rows `0` |
| `projector-mid-batch` | projector exits after partially applying a batch before watermark commit | projector replays and advances watermark | duplicate read-model rows `0`, final projected gap `0`, watermark monotonic |
| `poison-command-terminal-failed` | command repeatedly fails deterministic validation/processing after durable acceptance | retry policy exhausts and emits durable terminal `FAILED` | command offset commits after terminal failure, materialized outcome is `FAILED`, drain accounting closes |

Pass criteria for every case:

- public `202` is returned only after durable ingress acknowledgement
- accepted commands have one terminal canonical outcome after drain
- final accepted/materialized/projected gap is `0`
- worker/materializer/projector lag drains to `0` inside the configured drain window
- replay/checksum reports `0` gaps, overlaps, duplicate inserts, payload mismatches, missing rows, and extra rows
- any failure is classified before rerun as broker backlog, redelivery/idempotency, materializer lag, projector lag, poison handling, or profile misconfiguration

## Implementation Slices

Implement the matrix by subsystem so each change has clear ownership and evidence:

1. API intake and publish marker.
   - covers duplicate same-payload replay, duplicate different-payload conflict, publish timeout, broker unavailable, producer saturation, hot cancel metadata rejection, and API crash after publish ack before marker update
   - expected evidence: boundary/API tests plus one local smoke proving worker repair or status recovery from durable stream reference

2. Matching-engine direct consumer.
   - covers engine crash before transaction commit, event send followed by transaction abort, ambiguous commit, ownership replacement, event-batch publisher failure, and poison command terminal failure
   - expected evidence: broker-backed tests proving atomic event/offset visibility, aborted-batch invisibility, fenced restart recovery, and one deterministic outcome

3. Venue event materializer.
   - covers materializer crash after compact canonical Postgres commit before event-batch offset commit
   - expected evidence: materializer tests proving redelivery inserts `0` duplicate canonical rows and closes accepted/materialized accounting

4. Projection and replay.
   - covers projector crash mid-batch, projection watermark replay, and replay/checksum verification
   - expected evidence: projection idempotency tests and `make dev-venue-event-replay-check` with `0` gaps, overlaps, duplicate inserts, payload mismatches, missing rows, or extra rows

5. Promotion harness.
   - covers profile validation, short local durable gate, pre-DO admission, report validation, artifact fetch, and safe destroy behavior
   - expected evidence: scripts fail closed when gates are missing or counters are unsafe

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
