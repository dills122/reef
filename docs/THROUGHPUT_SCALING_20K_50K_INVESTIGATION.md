# Throughput Scaling Investigation: 20k To 50k Commands/Sec

Status: exploratory recommendation, not an accepted architecture decision
Date: 2026-07-19
Baseline commit: `53c93af09f99e5da06eda77221b81fc981f1811c`

## Purpose

Assess how Reef can move from its proven `10k commands/sec` durable venue-core
gate to:

1. a first `20k commands/sec` promotion tier;
2. an aggregate `30k-50k commands/sec` target;
3. without weakening ordering, durability, deterministic replay, idempotency,
   auditability, or accepted-command accounting.

This document reviews current docs and implementation, compares the design with
primary external references, identifies proof gaps, and proposes an experiment
ladder. It does not accept a new architecture or authorize a throughput claim.

## Executive Finding

Keep the macro architecture:

```text
public command boundary
  -> durable partitioned command log
  -> one matching writer per book/lane
  -> durable venue event batches
  -> compact canonical materialization
  -> independent replayable projections
```

This shape is sound. Reef should not replace Go, Redpanda/Kafka-compatible logs,
or Postgres wholesale to reach the next tier.

Two pivots were identified; the first is now implemented and the second remains
measurement-driven work before a promoted `20k+` claim:

1. Implemented: matching-engine event publication and consumed command-offset
   commit now share one Kafka transaction, downstream consumers use
   `read_committed`, and static shard ownership plus committed-prefix recovery
   replace the former unsafe publish-then-commit window.
2. Reduce canonical and projection write amplification before `30k-50k`.
   Current measured Postgres WAL per command extrapolates to storage rates that
   will dominate the system long before matching does.

Likely outcomes:

- `20k` venue-core aggregate throughput should be reachable within the current
  architecture family, first on a fixed 16-partition shape and then on isolated
  infrastructure if single-host resource contention appears.
- `30k-50k` aggregate throughput requires multiple matching-engine shard owners,
  multiple materializers, production-shaped Redpanda replication, run-aware
  retention, and a smaller Postgres hot-row footprint.
- `50k` on one hot book is a different target. Current local evidence proves one
  hot book to `30k/sec`; preserving price-time order keeps that book single-writer.
  `50k` aggregate should be proved across multiple independently owned books.
- Full projection freshness is not currently a `10k` capability. It passed at
  `5k/60s`; `10k` evidence intentionally had `projected=0`. Venue-core,
  command-status, market-data, timeline, and analytics targets must remain
  separate.

## What The Current 10k Gate Proves

The July 12 c-16 DigitalOcean gate proved, across two five-minute samples:

- about `10k/sec` attempted, accepted, direct-acked, and materialized;
- `100%` HTTP success;
- zero accepted/direct-acked/materialized gaps;
- zero final materializer lag;
- average p95 `28.25ms` and p99 `57.21ms`;
- mixed lifecycle traffic over 64 instruments with a nominal
  submit/modify/cancel mix of `68/24/8`;
- all 16 direct-stream partitions active.

See [Performance Learnings](./PERFORMANCE_LEARNINGS.md),
[DigitalOcean Stress Test Plan](./DIGITALOCEAN_STRESS_TEST_PLAN.md), and
[Command Intake Process](./COMMAND_INTAKE_PROCESS.md).

The gate is strong venue-core evidence. Its boundary must remain explicit:

- projections were disabled;
- command and stream idempotency stores were bounded in-memory stores;
- the in-memory stream-intake cap was `100,000` entries;
- matching terminal-order retention was capped at `250,000` records;
- Redpanda ran as one local Compose broker;
- command and event topics are created with replication factor `1`;
- load generation, API, broker, engine, materializers, and Postgres shared one
  c-16 host;
- no integrated shard snapshot/restore/fencing path is promoted yet.

Therefore the gate proves a safe deterministic benchmark profile under its
documented failures, not node-loss tolerance, multi-host HA, durable API
idempotency, or full read-model freshness.

## Current Implementation Read

### Strong Parts

1. Routing preserves matching order.
   - `runId + venueSessionId + instrumentId` selects a deterministic partition.
   - submit, cancel, and modify for one book share one ordered lane.
2. Matching state is correctly local.
   - one in-memory price-level/FIFO book owns hot mutations;
   - direct order-id indexing keeps cancel/modify bounded;
   - no synchronous database read sits in matching.
3. Partition work is parallel without making one book concurrent.
   - one Go processor goroutine owns each configured partition;
   - one engine process can own many logical partitions;
   - static non-overlapping ranges can scale across engine processes.
4. Durable handoffs are explicit.
   - API returns `202` after producer acknowledgement;
   - engine publishes `VenueEventBatch` before committing command offsets;
   - materializer writes Postgres before committing event-topic offsets.
5. Rollback and replay receive first-class treatment.
   - failed event publication rolls back lazily journaled book/order mutations;
   - canonical inserts are idempotent;
   - crash and replay gates cover several important failure points.
6. Engine capacity has headroom.
   - repository evidence: `30k/sec` for one hot book and `100k/sec` across ten
     books in focused local probes;
   - fresh Apple M1 Max microbenchmarks at this commit:
     - resting submit: `1165 ns/op`, `640 B/op`, `8 allocs/op`;
     - match against resting: `1873 ns/op`, `1255 B/op`, `18 allocs/op`;
     - modify: `934 ns/op`, `239 B/op`, `5 allocs/op`;
     - many-instrument parallel submit: `536 ns/op`, `628 B/op`, `9 allocs/op`.

### Scaling And Correctness Gaps

#### 1. Event publication and input-offset commit were not atomic — corrected

The audited engine flow was:

```text
mutate book
  -> synchronously publish VenueEventBatch
  -> separately commit consumed command offset
```

If publication succeeds and offset commit is lost, the same command is
redelivered against already-mutated state. Current tests intentionally show a
redelivered submit becoming `DUPLICATE_ORDER_ID` and publishing another batch.
Both attempts use the same batch id because batch identity derives from shard,
partition, and input sequence range.

The current `payloadChecksum` does not hash the full outcome payload. It hashes
input sequence, command id, and command payload hash. An accepted first batch
and a rejected redelivery can therefore have:

- same batch id;
- same checksum;
- different result bodies.

Postgres keeps the first batch id/checksum and treats the second as an
idempotent replay, so accepted-command accounting can still close. The event log
nevertheless contains conflicting semantic bodies under one identity/checksum.
That is unsafe as a recovery and audit ledger.

The implemented correction is:

- one transactional producer identity per stable engine shard/consumer owner;
- begin transaction;
- process ordered command batch with existing rollback journal;
- publish event batch;
- add consumed offsets to the transaction;
- commit transaction;
- on abort, roll back engine state and reset consumer position;
- materializers consume with `isolation.level=read_committed`;
- checksum the complete canonical batch body using stable encoding.

Apache Kafka documents this exact consume-transform-produce pattern: output
records and consumer offsets commit atomically. Redpanda supports compatible
transaction APIs and exactly-once stream processing. See
[Kafka transaction design](https://kafka.apache.org/41/design/design/#semantics_transactions)
and [Redpanda transactions](https://docs.redpanda.com/streaming/current/develop/transactions/).

Pinned Sarama `v1.43.2` exposes `BeginTxn`, `CommitTxn`, `AbortTxn`, and
`AddOffsetsToTxn`, but not the newer group-metadata fencing API. Reef therefore
uses transactions for data/offset atomicity and a separate static Kafka
ownership group for process fencing. Broker-backed Redpanda tests cover commit,
abort invisibility, retry, offset position, and restart checksum equality.

#### 2. Ownership and recovery baseline is safe; snapshot acceleration remains

Static partition lists now participate in a custom Kafka ownership assignment.
Stable shard membership fences a same-id replacement, overlap cannot assign one
partition twice, and the replacement reconstructs state to committed transaction
boundaries before processing. Recovery requires complete command history from
offset zero and fails closed on truncation or gaps.

Before live migration and bounded-time recovery promotion, still require:

- durable routing epoch and partition-owner metadata;
- persisted routing and owner epochs beyond the current static shard identity;
- pause/drain at sequence `N`;
- snapshot plus replay from `N+1`;
- full-book checksum comparison;
- explicit resume on new owner;
- crash proof before and after transaction commit.

Keep one writer per book. External LMAX experience supports the single-writer
principle and performance-test-driven evolution; it does not justify concurrent
mutation inside one order book. See
[The LMAX Architecture](https://martinfowler.com/articles/lmax.html).

#### 3. Current broker durability is benchmark-shaped

`acks=all` against a replication-factor-1 topic means one broker acknowledgement.
It is not broker-loss tolerance. Redpanda production guidance calls for topic
replication factor at least 3; Kafka transactional guidance also recommends
three brokers for production defaults.

Promotion profiles above `10k` should distinguish:

- `RF1 diagnostic`: cheapest ceiling isolation;
- `RF3 promotion`: three brokers, replication factor 3, minimum in-sync replica
  policy, broker-loss test, and full throughput in degraded state.

See [Redpanda production readiness](https://docs.redpanda.com/streaming/current/deploy/redpanda/kubernetes/k-production-readiness/),
[Kafka producer configuration](https://kafka.apache.org/40/configuration/producer-configs/),
and [Redpanda sizing guidance](https://docs.redpanda.com/streaming/current/deploy/redpanda/manual/sizing/).

#### 4. Benchmark idempotency is not an operational multi-API design

Bounded in-memory intake is appropriate for a ceiling profile, but a `100,000`
entry cap represents about:

- 10 seconds at `10k/sec`;
- 5 seconds at `20k/sec`;
- 2 seconds at `50k/sec`.

It also does not survive API restart or coordinate multiple API replicas.

Keep two separately named gates:

- bounded-memory venue-core ceiling;
- durable-idempotency operational gate using the accepted
  `clientId + route + idempotencyKey` contract.

Do not call the latter `20k` until the durable store is measured at that rate.
First benchmark a compact, hash-partitioned Postgres reservation table with no
full payload and no synchronous post-publish rewrite. If it cannot reach the
target, make a separate decision for a partitioned idempotency service/state
store; do not silently weaken duplicate/conflict behavior.

#### 5. Canonical Postgres duplication becomes dominant

At the patched `5k` projection run, measured write rates were approximately:

- canonical runtime Postgres: `2.43KB WAL/command`;
- full projection Postgres: `6.48KB WAL/command`;
- full projection temp work: `5.59GB` per 60-second `5k` run.

Linear extrapolation is not a capacity claim, but shows the risk:

- at `20k`, canonical WAL alone is about `48.6MB/sec`;
- at `50k`, canonical WAL alone is about `121.5MB/sec`;
- at `50k`, full-projection WAL would be about `324MB/sec`;
- `50k` sustained creates `4.32 billion` command outcomes/day before fills,
  events, indexes, or projections.

Current canonical storage duplicates outcome data in:

- retained Redpanda event-batch payload;
- `canonical_venue_event_batches.payload_json`;
- one `canonical_command_outcomes` row with `result_payload` per command.

Recommended sequence:

1. Measure bytes/command by field and index, not only table total.
2. Keep batch identity, routing, sequence range, count, format/version, checksum,
   and compact command lookup in hot Postgres.
3. Store a full outcome body once in the durable event log/archive; retain a
   bounded recent Postgres payload window only if query SLOs require it.
4. Partition hot/terminal facts by run plus bounded time/range so a completed
   run can detach and archive without bulk delete/vacuum.
5. Use multi-row prepared batches or `COPY` into staging plus one idempotent
   merge only after an A/B proves lower WAL/CPU while preserving batch atomicity.

PostgreSQL documents fast partition detach/archive and recommends `COPY` for
bulk loading. See [table partitioning](https://www.postgresql.org/docs/current/ddl-partitioning.html),
[COPY](https://www.postgresql.org/docs/current/sql-copy.html), and
[bulk population guidance](https://www.postgresql.org/docs/current/populate.html).

Moving full durable payload out of Postgres changes the audit/query storage
contract and requires an explicit architecture decision. Redpanda Tiered
Storage can retain old log segments in object storage, but it is licensed and
must be evaluated against a Reef-owned immutable archive path. See
[Redpanda Tiered Storage](https://docs.redpanda.com/streaming/current/manage/tiered-storage/).

#### 6. Materialization commits too narrowly

Each event-batch delivery currently performs:

1. one JSON parse;
2. one SQL function call/transaction;
3. one Kafka `commitSync` for the advanced partition offset.

Four materializer instances spread partitions through one consumer group, which
is a good scaling seam. Next optimization should preserve that seam while
processing one source poll as a database batch:

- decode all fetched event batches;
- preserve order within each partition;
- insert multiple batch headers/outcome groups in one Postgres transaction;
- commit the highest contiguous offset per assigned partition once after DB
  commit;
- keep idempotent replay for a DB-commit/offset-commit failure.

This should be measured before adding more materializer processes. More writers
can increase index/WAL contention when write shape is unchanged.

#### 7. JSON should be measured as a throughput tax

Commands and venue event batches cross Kafka as JSON. Materialization reparses
the event batch into Kotlin objects, serializes it back to JSON, and lets
Postgres parse JSONB again.

Before `30k`, compare:

- current JSON/LZ4 baseline;
- versioned Protobuf event-batch records using existing contract governance;
- typed multi-row Postgres parameters or binary `COPY` staging.

Redpanda notes that JSON carries schema/verbosity overhead and that batching,
compression, `batch.size`, and `linger.ms` materially affect producer
throughput. See [producer tuning](https://docs.redpanda.com/streaming/current/develop/produce-data/configure-producers/).

Do not change codecs without replay compatibility, versioned envelopes, checksum
stability, and mixed-version tests.

## Recommended Target Shape

```text
load generators on separate hosts
  -> API replicas
       validation/auth/risk/rate/idempotency
       bounded async Kafka producer, acks=all
  -> 3+ Redpanda brokers, RF3 promotion topics
       32-64 logical command partitions
       matching-sensitive key remains run/session/instrument
  -> 2-4 matching-engine shards
       static fenced ownership ranges
       one writer per book
       transactional event publish + input-offset commit
       snapshot/replay/checksum recovery
  -> venue-event topic, read_committed
  -> materializer consumer group
       multi-batch Postgres transaction
       compact hot command index + batch metadata
       run/time partition lifecycle
  -> independent projection stages/databases
       command status/lifecycle: tight freshness
       market data: separate maintained state
       timeline/analytics: looser freshness/archive
```

Start with 16 partitions for the first `20k` ceiling so the comparison changes
one variable. Move to 32 or 64 only when per-partition lag or engine ownership
shows the need. Adding partitions does not redistribute existing records, so
use new run-specific topics and record the routing epoch when partition count
changes.

## Promotion Ladder

### Gate 0: Close Current 10k Evidence

Run existing `soak-15m` tier before raising the target:

- mixed lifecycle, even distribution;
- warm and aged state;
- zero accepted/direct-acked/materialized gap;
- bounded lag area and zero final lag;
- p95 <= `100ms`, p99 <= `200ms`;
- WAL/table/index/temp bytes per command;
- producer batch fill, request latency, queue time, retries, throttling;
- materializer event batches/sec, outcomes/batch, DB commits/sec;
- no restarts or OOM;
- replay/checksum pass.

Add a full-body event-batch checksum and the publish/offset failure test before
using the event topic as a recovery ledger.

### Gate 1: Find 16-Partition Knee

Run identical 60-second samples at:

```text
10k, 12.5k, 15k, 17.5k, 20k, 25k
```

Run each stage independently:

1. no-op API ceiling;
2. durable command publish only;
3. direct engine consume/event publish without Postgres;
4. canonical materializer;
5. command-status projection;
6. full projection.

Use c-16 first. Split load generation to a second host when load-generator CPU,
network, or scheduling affects the result. Repeat the decisive knee on c-32 to
separate architectural limits from shared-host resource saturation.

Do not tune several dimensions in one run. Record one hypothesis per A/B.

### Gate 2: Promote 20k Venue-Core

Promotion shape:

- `20k/sec`, 60 seconds, three samples;
- then `20k/sec`, five minutes, two samples;
- then one 15-minute aged-state sample;
- mixed lifecycle and even partition distribution;
- separate hot-book/skew sample;
- zero accounting gaps and final lag;
- p95 <= `100ms`, p99 <= `200ms` initially;
- no hidden post-run drain beyond explicit SLO;
- complete DB/Kafka/CPU/disk/network artifacts;
- transactional engine failure matrix green;
- RF1 diagnostic and RF3 promotion results labeled separately.

### Gate 3: 30k Aggregate

Only after `20k` is boring:

- 32 logical partitions on new run-specific topics;
- two fenced matching-engine shard owners;
- materializer ownership aligned with measured partition load;
- three-broker RF3 Redpanda deployment;
- dedicated load-generator host;
- dedicated canonical Postgres host/storage;
- compact canonical row A/B complete;
- five-minute mixed, skewed, and one-hot-book runs.

One-hot-book pass criteria may remain below aggregate target. Report it as the
single-lane ceiling, not an aggregate failure.

### Gate 4: 50k Aggregate

Require structural headroom, not a one-minute spike:

- 64 logical partitions unless 32-partition evidence is comfortably sufficient;
- 2-4 matching-engine shards with recovery/fencing proof;
- broker loss during load at RF3;
- materializer restart and Postgres fail/recovery tests;
- full archive/retention lifecycle for a 15-minute run (`45M` commands);
- `50k/sec` for five minutes before 15-minute promotion;
- venue-core, command-status, market-data, timeline, and analytics freshness
  reported independently.

Do not require full timeline/analytics freshness to call venue-core `50k`.
Do require canonical outcome closure, replay integrity, and stated
command-status SLO.

## Workload Matrix

Every tier needs at least these labeled shapes:

1. Even mixed lifecycle: 64+ books, `68/24/8` submit/modify/cancel.
2. One hot book: establishes real single-lane ceiling.
3. Skewed books: 80% traffic on 20% of books.
4. Deep resting book: tests memory/index/scan behavior.
5. High fill fanout: tests event size and Postgres rows/command.
6. Warm/aged run: retained engine state, broker segments, and database indexes.
7. Duplicate/conflict storm: tests durable idempotency, not only matching.
8. Failure run: broker, engine, materializer, projector, and DB restart points.

Report commands/sec and bytes/sec. One command can create very different event,
fill, WAL, and projection work depending on workload.

## Decision Triggers

Use evidence, not target enthusiasm:

- If no-op API cannot sustain `25k+`, optimize or replicate API before broker
  work. Do not bypass public paths for the promoted scenario.
- If durable publish is the knee, tune producer batches/linger/compression from
  measured batch-fill and request metrics, then split brokers/partitions.
- If one hot book is the knee, isolate it to one lane/shard. Do not add
  concurrent writers to the book.
- If matching aggregate is the knee, add fenced engine shard owners over
  non-overlapping partition ranges.
- If canonical WAL or checkpoint pressure is the knee, reduce duplicated
  payload/index bytes and batch materializer commits before adding writers.
- If full projection is the knee, preserve freshness classes and split storage;
  do not put projection writes back into acceptance or matching.
- If compact canonical Postgres still cannot sustain `20k`, open a new decision
  on retained event log/object archive as the full canonical payload store and
  Postgres as compact hot index/query state.

## Immediate Work Order

Implementation status is tracked in
[Throughput Scaling Implementation Plan](./THROUGHPUT_SCALING_IMPLEMENTATION_PLAN.md).

1. **Implemented:** add a reproducible `20k` ceiling wrapper and exact artifact
   accounting contract by extending the named materializer gate.
2. **Implemented:** add event-batch semantic full-body checksum regression tests
   with a fixed Go/Kotlin cross-language vector.
3. **Implemented; remote failure matrix pending:** Kafka transactional consume-
   process-produce, rollback/reset, static ownership fencing, `read_committed`,
   and complete-log restart recovery.
4. **Ready to run:** run c-16 stage-isolation sweep through `25k` with a separate load generator
   when needed.
5. **Instrumentation/run pending:** measure canonical field/index bytes and materializer commits per second.
6. **Implemented, A/B pending:** batch fetched event deliveries into one Postgres
   statement/transaction and one contiguous offset commit per partition.
7. Close `10k soak-15m`, then promote `20k short` and `20k soak-5m`.
8. Only then add RF3/multi-engine infrastructure and pursue `30k-50k`.

## July 19 Local Soak Audit

A post-hardening local `10k`, `1024`-worker, `15m` run on a `10`-CPU,
`12.5GB` Docker allocation proved exact lifecycle closure but missed the c-16
promotion SLO: `9,756.53/sec`, p95 `170.90ms`, p99 `305.36ms`. All `8,781,297`
accepted commands were processed, published, direct-acked, and materialized;
all failure counters and final lag were zero. Sixteen partitions were active
with only `1.0054` max/min skew.

The evidence sharpens three fault lines:

1. Durable Kafka publish dominates API time (`13.91ms` average publish ack;
   `12.49ms` delegate ack), not API validation or materializer CPU.
2. Canonical persistence remains byte-heavy: `18.95GB` WAL (`2,158B/command`),
   `14.28GB` outcome-table growth, and `2.96GB` batch-table growth.
3. The workload generated pathological active-book state. Prices were random at
   nanodollar granularity, and modify traffic used a global cross-instrument
   range. `STK001` ended with `31,548` live orders on `31,548` price levels;
   matcher memory exceeded `4GB`.

The third item is a measurement defect for the normal promotion gate, not a
reason to weaken matching semantics. Materializer sessions now declare a
`$0.01` tick and modify traffic preserves instrument price context. The
unquantized shape remains valuable as the separately labeled deep-level/worst-
case workload in the matrix. Run a clean short comparison before the next c-16
soak; do not compare the corrected workload to the prior result without calling
out the state-shape change.

The correction was then verified locally. A clean `60s` sample reached
`9,998.04/sec`; a warm/aged `15m` sample reached `9,999.49/sec`. Both had exact
accepted/published/acked/materialized closure, zero failures, zero final lag,
and all 16 partitions active. The aged sample produced p95 `135.25ms` and p99
`213.47ms`, so this smaller host passed rate but not the c-16 latency gate.

The aged final `STK001` shape was `34,462` live orders on `160` levels and
matcher RSS was `4.14GB`. This proves the tick fix, but also proves that the
`68/24/8` mix grows live inventory continuously. Split future evidence into:

- steady-state/rate-isolation: bounded live-order inventory and level count;
- aged/deep-book: declared order/level growth, RSS slope, and single-book cost.

Canonical storage was unchanged by the workload correction: `2,147B` WAL per
command, `14.58GB` outcome-table growth, and `2.96GB` batch-table growth. The
run also recorded `136,026` WAL-buffer-full events, `2.83GB` temp bytes, and a
`381s` WAL checkpoint. These are concrete inputs to the compact-row/payload-
retention A/B, not permission to weaken synchronous commit or audit closure.

## Verification Performed For This Investigation

The completed implementation verification includes full Go and Kotlin suites,
Go race/vet checks, Node 22 harness and migration tests, plus disposable
Redpanda broker tests for commit, abort visibility, offset position, recovery,
and static-owner replacement fencing. Exact commands and evidence are recorded
in the companion implementation plan and PR handoff.

Focused benchmarks:

```text
go test ./internal/app -run '^$' \
  -bench 'Benchmark(SubmitOrderResting|SubmitOrderMatchAgainstResting|ModifyOrder|SubmitOrderManyInstrumentsParallel)$' \
  -benchmem -benchtime=2s
```

Result: pass; measurements recorded above. These are local component
microbenchmarks, not end-to-end capacity claims.

The first hardening tranche was subsequently implemented and locally tested;
see the companion implementation plan for code scope, test evidence, remaining
blockers, and the exact promotion order. No `20k` claim is made by those changes.
