# Throughput Scaling Implementation Plan

Status: paused at a safe evidence boundary; throughput claims remain evidence-gated
Date: 2026-07-19
Companion audit: [Throughput Scaling Investigation](./THROUGHPUT_SCALING_20K_50K_INVESTIGATION.md)

## Objective

Move Reef from the tested `10k commands/sec` venue-core profile to a repeatable
`20k commands/sec` gate, then pursue `30k-50k commands/sec` aggregate capacity.
Correctness, deterministic lane ordering, durable acceptance, audit identity,
idempotent replay, and exact accounting remain hard constraints.

This plan separates code hardening from capacity proof. A completed code item is
not a promoted throughput claim until the matching remote gate and failure matrix
pass.

## Pause / Resume Handoff

This work is safe to defer after the July 19 hardening and corrected local
`10k` evidence. The established DigitalOcean c-16 `10k soak-5m` baseline remains
the promoted venue-core claim. The new local runs add correctness and diagnostic
evidence; they do not promote `20k` and do not replace the hosted result.

### Completed before pause

- Atomic Kafka event publication plus command-offset commit per deterministic
  matching lane, `read_committed` materialization, fenced static ownership, and
  fail-closed complete-log recovery.
- Stable cross-language semantic checksum, conflict rejection, exact benchmark
  accounting, poll-level materializer persistence/offset batching, and safe
  terminal-retention commit ordering.
- Named scaling gate plans for `10k` through `25k`.
- Corrected workload pricing: optional per-instrument `priceTickNanos`, a
  `$0.01` materializer tick, and instrument-aware modify prices.
- Corrected local clean `60s` and warm `15m` runs with exact
  accepted/published/acked/materialized closure and zero failures/final lag.
- Full Go/Kotlin correctness suites, Go race/vet, Node gate tests, and disposable
  Redpanda transaction/ownership integration tests.

### Deliberately not claimed

- No `20k`, `30k`, or `50k` capacity promotion.
- No c-16 result from the corrected workload; the local Docker allocation was
  `10` CPUs and `12.5GB` memory.
- No steady-state latency ceiling: the current `68/24/8` workload grows live
  order inventory throughout the run.
- No production failure-tolerance claim from the RF1 local broker.
- No compact-storage decision; full batch payload and canonical outcome rows
  still coexist in Postgres.

### First slice when work resumes

Implement benchmark state-shape control before raising the target:

1. Add a deterministic bounded-working-set mode. Each load worker owns a fixed
   live-order budget; at the budget it modifies/cancels/replaces instead of
   continuing unconstrained submits. Do not approximate this only by changing
   action percentages.
2. Capture aggregate matching state before, during, and after each run:
   live orders, price levels, orders/level, order growth/sec, level growth/sec,
   and matcher RSS. Preserve per-book hot/skew samples.
3. Add separately named workload shapes:
   - `steady-state`: bounded inventory; rate and latency isolation;
   - `growing-book`: current `68/24/8`; aged memory/index pressure;
   - `one-hot-book`: single deterministic lane ceiling.
4. Fail the steady-state gate on unbounded inventory/level slope, accounting
   gaps, nonzero terminal lag, or the existing latency/error thresholds.
5. Re-establish corrected `10k short` and `10k soak-15m` evidence before running
   the c-16 knee (`10k,12.5k,15k,17.5k,20k,25k`).

### Second slice after the real knee is measurable

Run the compact canonical storage A/B. The corrected aged run measured
`2,147B` WAL per command, `14.58GB` outcome-table growth, `2.96GB` batch-table
growth, `2.83GB` temp bytes, `136,026` WAL-buffer-full events, and a `381s`
WAL-triggered checkpoint. Measure heap/TOAST/index bytes separately and compare
typed batch parameters, staging `COPY`/merge, and storing the full body once in
the retained log/archive with compact hot Postgres lookup state. Any removal of
the full canonical payload requires an explicit audit/retention decision and
restore proof.

### Resume commands and evidence

```text
worktree: /Users/dsteele/repos/reef/.worktrees/throughput-50k-investigation
branch:   codex/throughput-50k-investigation

make do-materializer-scaling-gate ARGS=plan
go test ./...                                      # each affected Go module
./gradlew test                                     # services/platform-runtime
git diff --check
```

Local raw artifacts are intentionally outside Git and may be cleaned by the
host:

- `/tmp/reef-materializer-10k-soak-15m/` — original unquantized diagnostic;
- `/tmp/reef-materializer-10k-tick-short/` — corrected clean `60s` comparison;
- `/tmp/reef-materializer-10k-tick-soak-15m/` — corrected warm `15m` evidence.

The durable numbers and interpretation are recorded here and in
[`PERFORMANCE_LEARNINGS.md`](./PERFORMANCE_LEARNINGS.md), so loss of `/tmp`
does not erase the engineering conclusion.

### Guardrails on resume

- Do not tune through an accounting, checksum, ownership, replay, or lag error.
- Do not use the growing-book workload alone to declare aggregate rate capacity.
- Do not hide duplicated payload/index bytes with Postgres tuning before the
  storage-shape A/B.
- Do not add concurrent writers to one book; scale aggregate capacity by fenced
  non-overlapping lanes/shards.
- Require RF3 and broker-loss evidence before production-like `30k-50k` claims.

## Senior-Engineer Audit: Fault Lines

| Priority | Fault line | Risk at higher rate | Required disposition |
| --- | --- | --- | --- |
| P0 | Engine event publish and command offset commit were separate | A publish-success/offset-failure replay could mutate the book twice and emit conflicting outcomes | Implemented: Kafka transaction per lane and `read_committed`; remaining: leader/coordinator/ambiguous-commit remote matrix |
| P0 | Engine ownership/recovery was not fenced | Two owners or an unrestored owner could violate single-writer order-book semantics | Implemented: static Kafka shard lease and complete-log recovery; remaining: durable snapshot acceleration and routing-epoch live handoff |
| P0 | Event identity previously covered inputs, not complete outcomes | Different audit bodies could share batch identity/checksum | Stable semantic full-body checksum and conflict rejection |
| P1 | Materializer performed one SQL transaction and offset commit per delivery | Excess round trips, commit load, and coordinator traffic | Poll-level DB transaction and one contiguous commit per partition |
| P1 | Consumer `take(batchSize)` could discard a polled tail; rebalance maps were stale | Unprocessed records could be skipped in-process; stale offsets could cross ownership changes | Pending-record queues, rewind cleanup, rebalance state reset |
| P1 | Canonical Postgres repeats full event payload and command result bodies | WAL, index, vacuum, and storage dominate before matching | Measure bytes/command; compact hot canonical shape; bounded payload window/archive decision |
| P1 | Benchmark gates accepted counter overshoot | Duplicate work could pass a one-sided gap check | Exact accepted = published = acked = materialized gates |
| P1 | API retried a record above Kafka's idempotent producer | Application resend could create extra records beyond producer retry semantics | One application send; Kafka owns retries within delivery timeout |
| P1 | Every engine batch copied the entire terminal-retention index for rollback | O(retained terminals) allocation/copy pressure and cross-lane rollback corruption | Defer retention mutations until durable publish; commit only the successful batch delta |
| P1 | Materializer stress prices created nearly one level per live order; modifies lost instrument context | Artificial book growth consumed matcher memory/CPU and contaminated the capacity knee | Optional per-instrument tick quantization; instrument-aware modifies; keep nanos-random shape only as a labeled pathological test |
| P2 | Bounded in-memory API idempotency is restart-local | At `50k/sec`, a `100k` entry window covers roughly two seconds and does not coordinate replicas | Separate operational durable-idempotency gate from ceiling profile |
| P2 | RF1 broker profile is not failure-tolerant | `acks=all` acknowledges one broker | Keep RF1 diagnostic; require RF3 promotion and broker-loss run |
| P2 | JSON is parsed in Go, Kotlin, and Postgres | CPU, allocation, and payload bytes grow with result fanout | Measure JSON baseline; A/B Protobuf plus typed/binary persistence only with mixed-version replay proof |
| P2 | Projection writes remain large | Full projection measured about `6.48KB WAL/command` and high temp work | Preserve freshness classes; reduce no-op/index/payload writes; isolate projection storage |

## Implemented First Tranche

### Matching engine

- Terminal-retention rollback now records only batch-local terminal order ids.
- Global retention changes happen only after the venue event batch is durably
  published; a failed batch cannot restore an old global snapshot over another
  lane's successful commit.
- A concurrency regression test covers the former cross-batch corruption case.
- Venue event batches now carry `payloadChecksumAlgorithm` with
  `sha256-reef-canonical-v1` and a checksum over the complete canonical body.
  Only root `createdAt`, `payloadChecksum`, and `payloadChecksumAlgorithm` are
  excluded. Object keys are recursively sorted and scalar types/lengths are
  encoded explicitly.

### API and canonical materializer

- Kafka command publication performs one application send. Kafka's idempotent
  producer owns retriable delivery; Reef no longer resends the record above it.
- Kotlin verifies the Go semantic checksum before persistence. Legacy batches
  without an algorithm remain readable during migration; an unknown algorithm
  or body drift is retried and reported.
- Canonical command identity is strict: a second batch cannot claim an existing
  command id with another outcome. The insert-first Postgres path rolls back the
  header and rows on count, duplicate-command, or semantic conflict.
- The redundant `(event_stream, batch_id, stream_sequence)` index is removed;
  the existing unique constraint already owns the same index shape.
- One fetched materializer group is decoded, persisted through one SQL
  statement/transaction, then acknowledged with one Kafka `commitSync` map for
  all advanced partitions. On persistence failure, the group is bisected until
  the poison delivery is isolated; healthy deliveries can still commit.
- Kafka sources retain polled records that exceed a worker batch, discard only
  the affected partition tail on rewind, ignore stale replay acknowledgements,
  and clear offset/pending state on partition revoke/assignment.

### Evidence harness

- Materializer promotion checks now require exact equality among accepted,
  published, direct-acked, and canonical-materialized commands. Overshoot fails
  as duplicate work; undershoot fails as missing work.
- `make do-materializer-scaling-gate` adds named `knee`, `20k-short`, and
  `20k-soak-5m` profiles. The knee is
  `10k,12.5k,15k,17.5k,20k,25k` on the unchanged c-16/16-partition shape.
- Tests cover checksum parity across Go/Kotlin, semantic drift, retention commit
  ordering, API single-send behavior, conflict rejection, migration inventory,
  duplicate-accounting gate failure, and scaling-wrapper resolution.
- Session equities support optional `priceTickNanos`. The materializer spread
  workload uses a `10,000,000`-nanodollar (`$0.01`) tick, and modify traffic
  preserves the tracked order's instrument price band. This prevents the normal
  promotion gate from silently becoming a millions-of-price-level stress test.

### July 19 local soak evidence

The first post-hardening local `10k`, `1024`-worker, `15m` run completed with
exact closure across `8,781,297` accepted/published/acked/materialized commands
and zero processing failures. On the smaller `10`-CPU/`12.5GB` Docker host it
reached `9,756.53/sec`, p95 `170.90ms`, and p99 `305.36ms`, so it is a clean
correctness soak but not a promotion pass. All 16 partitions were active with
`1.0054` skew; final materializer lag was zero.

The run measured `2,158` WAL bytes per accepted command, `14.28GB` growth in
canonical outcome storage, and `2.96GB` in canonical batch storage. It also
revealed the price-shape defect addressed above: `STK001` alone held `31,548`
live orders on `31,548` levels, while matcher memory rose above `4GB`.

Artifacts: `/tmp/reef-materializer-10k-soak-15m/`. These local artifacts and
resource limits must not be presented as c-16 evidence.

The corrected clean `60s` comparison reached `9,998.04/sec` with exact closure,
then the corrected warm `15m` run reached `9,999.49/sec` with exact closure and
zero failures/lag. The aged run improved p95/p99 to `135.25/213.47ms`, still
outside the `100/200ms` remote gate on this smaller host. Tick cardinality
remained bounded (`160` levels for `STK001`), but its live inventory reached
`34,462` orders and matcher RSS reached `4.14GB`. The scaling ladder must
therefore report steady-state/rate-isolation and growing/deep-book shapes
separately rather than treating `68/24/8` as both.

Corrected artifacts:

- `/tmp/reef-materializer-10k-tick-short/`
- `/tmp/reef-materializer-10k-tick-soak-15m/`

The aged corrected run also measured `2,147` WAL bytes per command, `136,026`
WAL-buffer-full events, `2.83GB` temp bytes, and a `381s` WAL-triggered
checkpoint. Compact canonical storage remains the next write-amplification A/B;
Postgres tuning alone must not be used to hide duplicate payload/index bytes.

## Remaining Work Order

### Phase 1: transactional engine lane — implemented; promotion matrix pending

The Redpanda lane now uses the required atomic boundary:

Required lane state machine:

```text
resolve fenced shard/lane ownership and restored sequence
  -> fetch ordered command group
  -> apply commands with batch rollback journal
  -> begin Kafka transaction
  -> publish semantic-checksummed VenueEventBatch
  -> add highest contiguous input offsets for the consumer group
  -> commit Kafka transaction
  -> commit deferred terminal-retention delta

any failure before transaction commit
  -> abort Kafka transaction
  -> roll back book/order mutations
  -> discard deferred retention delta
  -> reset input positions to the batch start
```

Implemented details:

1. One stable transactional producer id per durable partition lane.
2. Event batch plus highest contiguous command offset commit in one transaction.
3. Abort rolls back the batch journal and rewinds input; indeterminate commit
   fails the shard closed without guessing.
4. Materializers use `isolation.level=read_committed`.
5. Sarama auto-commit is disabled; shutdown ordering avoids its disabled-auto-
   commit partition-manager deadlock.
6. A broker-backed Redpanda integration test proves committed visibility,
   aborted invisibility, retry, offset position, and restart checksum equality.

Mandatory tests:

- crash before publish (unit-covered rollback path; process-kill gate pending);
- publish call succeeds, then transaction aborts (broker-covered);
- crash during transaction commit with ambiguous client result;
- crash immediately after committed transaction;
- old static shard owner fenced by replacement (broker-covered);
- broker leader change and coordinator change;
- replay yields the same matching-state checksum and offsets (broker-covered;
  full process-kill artifact pending);
- no aborted batch is visible to a `read_committed` consumer (broker-covered).

### Phase 2: ownership and restore baseline — implemented; snapshots remain

Matching processes now join one static Kafka ownership group with stable
`shardId` membership and a custom assignment containing only their configured
partitions. Same-id replacement fences the old process; overlapping different
shards cannot both claim one partition. Startup then replays the complete
retained command prefix to each committed transaction boundary before starting
lane goroutines. Missing offset zero, any gap, or recovery timeout fails closed.

This establishes a correct source-of-truth recovery baseline but not bounded
recovery time for a very large log. Next persist
`{routingEpoch, shardId, partitionSet, ownerEpoch, snapshotSequence,
bookChecksum}` and restore snapshot plus replay from `N+1`. Live routing changes
still require explicit drain, handoff, and checksum proof under load.

### Phase 3: measure and compact canonical storage

Before changing the audit contract, capture for each workload:

- event bytes and outcome bytes per command;
- Postgres heap, TOAST, and every index byte per command;
- WAL bytes per command and per event/fill;
- DB statements, transactions, fsyncs, checkpoints, temp bytes, and CPU;
- materializer batch fill and outcomes per transaction.

Then A/B, one change at a time:

1. current JSON function versus the new poll-level transaction;
2. typed multi-row parameters versus JSONB reparse;
3. staging `COPY` plus one idempotent merge;
4. compact command lookup plus batch metadata, with the full body stored once in
   retained log/immutable archive;
5. run/time partitions with detach/archive instead of bulk deletion.

Removing full canonical payload from hot Postgres changes the storage/audit
contract. It requires an explicit decision, archive restore proof, retention
SLO, and query fallback before implementation.

### Phase 4: durable API idempotency and RF3

- Benchmark a compact hash-partitioned idempotency reservation keyed by
  `clientId + route + idempotencyKey`, storing a request hash and durable command
  identity without a synchronous post-publish rewrite.
- Run the ceiling profile and operational durable-idempotency profile separately.
- Add a three-broker RF3 profile with minimum in-sync replicas and prove normal,
  leader-change, and one-broker-loss throughput.

### Phase 5: projection and codec work

- Maintain separate SLOs for venue-core, command status/lifecycle, market data,
  timeline, and analytics.
- Reduce projection no-op updates, wide JSON rows, indexes, and temp work before
  adding projector writers.
- A/B Protobuf only after the canonical storage measurements show JSON CPU or
  network bytes are material. Require versioned envelopes, old/new mixed replay,
  semantic checksum stability, and archive readability.

## Verification Evidence At This Change

- `go test ./...`: pass.
- `go test -race ./internal/streamdirect`: pass.
- `go vet ./...`: pass.
- `./gradlew test` in `services/platform-runtime`: pass.
- Node 22 `node --test` for benchmark accounting, scaling wrapper, and migration
  inventory: `24/24` pass.
- Disposable Redpanda `v26.1.1` integration:
  `TestKafkaTransactionalLaneIntegration` and
  `TestKafkaOwnershipLeaseIntegration` pass. The tests cover transaction commit,
  abort after event send, `read_committed` invisibility, retry, broker offset,
  complete-log recovery checksum, and same-shard static-member replacement.
- `git diff --check`: pass.

The disposable broker container was removed after verification. These are
correctness results, not `20k` throughput evidence.

## Promotion Gates

No target is promoted unless all samples satisfy:

- exact `accepted = published = direct-acked = materialized`;
- zero failed, NAKed, termed, unsupported, and ack-failed deltas;
- final engine/materializer lag `0` within the declared drain SLO;
- no restarts, OOM, coordinator churn, or unclassified error;
- p95 `<=100ms`, p99 `<=200ms` for the first `20k` gate;
- all 16 partitions active with declared skew bound;
- WAL/table/index/temp bytes per command and producer/consumer batch telemetry;
- semantic replay/checksum and failure matrix green.

Execution order:

1. Re-run `10k soak-15m` with this hardening.
2. Run the c-16 knee profile; stop at the first unstable stage and classify it.
3. Run `20k-short` (`60s`, three samples), then `20k-soak-5m` (two samples),
   then one aged `15m` sample.
4. Repeat the decisive result on isolated load generation and c-32 if shared-host
   saturation is plausible.
5. Begin `30k` only after transactions, fencing/restore, RF3, and compact-storage
   A/B evidence are complete.
6. Treat `50k` as aggregate multi-book capacity; report one-hot-book capacity as
   the separate single-lane ceiling.

## Stop Conditions

Stop and diagnose rather than tune through any of these:

- accounting difference in either direction;
- two semantic bodies under one batch or command identity;
- offset progress without corresponding canonical rows;
- canonical rows from an aborted transaction;
- overlapping matching owners;
- benchmark success that depends on post-run unbounded drain;
- WAL, storage, or temp growth lacking a sustainable retention model;
- a throughput change that weakens replay, durability, or ordering.
