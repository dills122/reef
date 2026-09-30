# Calcify Phase 2 architecture experiments — September 30, 2026

Recommendation for user sync: **verified-led, one-input Kafka Streams Processor,
managed RocksDB/changelog/standby state, separate demand-driven sequential source
reader**. Two-input topology remains fallback. Custom recovery control plane has
no demonstrated need. Prototype and source fixtures only; production integration
requires requested sync. Execution checkpoint lives in [WORK_PLAN](../WORK_PLAN.md).

Tool checks: Serena activation reported six configured language servers; Go semantic
queries and codebase-memory returned real source/coverage. Graph root was planning
checkout (`reef-main`, generation `2026-09-30T21:14:17Z`), not experiment worktree.
Seven cited source paths had no recorded coverage gaps; source blobs unchanged
between graph commit `540c1df1` and experiment base. No exhaustive index claim.

Base: merged planning PR [#431](https://github.com/dills122/reef/pull/431),
`79dab22bda4d89c5a75eb677094f6e999dae303d`. Phase 1 [#430](https://github.com/dills122/reef/pull/430)
retained. Accepted D-042 and rich V1 direction unchanged.
[Bounded matrix](../../scripts/experiments/calcify-phase2/MATRIX.md),
[commands and harness limits](../../scripts/experiments/calcify-phase2/README.md),
[evidence directory](../evidence/calcify-phase2/).

## Decisions and measured evidence

| Question | Result | Consequence |
| --- | --- | --- |
| Can current matching guarantee same-source-lane facts? | Baseline cross-run trade on partitions 0/1; partition-by-partition replay differs from live order | D-042 alignment remains prerequisite |
| Does minimal run scope remove fixture failure? | Scratch key/batch-scope patch: no cross-run trade, partition replay parity, five batches / 130 exact trade facts | Implement narrow alignment before resolver; prototype patch is not complete production change |
| Can verified-led source read use managed state? | Real Streams EOS restart/empty-disk/standby tests all match complete expected contexts | Choose managed one-input shape; retain source cursor and target batch inside managed state |
| Does two-input remove ordering work? | Opposite schedules produce identical contexts, but source-first retains every unverified trade batch | Additional durable backlog/frontier and source gating needed; no benefit demonstrated for first slice |
| Are faulted lanes isolated? | 1,000 bad-generation links: 100 pending locally; healthy lane emits fresh 130-context wave | Narrow consumer gate works in tested two-lane case; hard cap 200, poll cap 100 |
| Can local full-fact state meet target operation rate? | 3,000,000 local joins / 300.000055 s; one million aged rows before load | Store/codec operation cost has headroom; complete managed resolver capacity remains unqualified |

## E1 — locality, index identity, ordering

[Baseline log](../evidence/calcify-phase2/E1-baseline.log) reproduces cross-run
matching and replay divergence. Buy `run-a`, sell `run-b`, same session/instrument:
actual SHA-256 routing yields partitions 0/1 at four partitions. Live sequence
buy → sell → cancel produces cross-run trade; replay partition 0 buy/cancel before
partition 1 sell changes outcomes. These are real Go service/processor observations,
not deployed broker/API locality measurements.

[Aligned log](../evidence/calcify-phase2/E1-aligned.log) uses disposable matching
module copy. Run enters book key, command mutations, batch scope and rollback keys.
Same fixture now produces zero cross-run trades and partition replay parity.
[Actual source fixture](../evidence/calcify-phase2/source-fixture.jsonl): earlier
resting buy, partial fill, modify-generated trade, rejected submit carrying
`acceptedOrder`, then 128 repeated references in one batch. Five batches, 130
trades; complete outcomes and semantic batch checksums match fresh replay.
Production matcher files remain untouched. Compatibility query scope, snapshot,
contract and ownership/deployment coverage belong to alignment implementation.

**Index key:** task-local `(sourceGeneration, orderId)`; partition supplied by task
ownership. Prototype explicit encoding includes generation, partition and
length-prefixed order ID. No `(runId, orderId)` lookup before run is known:
`TradeCreated` and modify `CommandOutcomeFact` lack run. Two direct order-ID gets
return full immutable accepted facts; derive run from both facts and require equal
run/session/instrument, expected sides, exact order references and source lane.
Current matcher reserves IDs process-wide (`orderIndex.reserve`). This is code
behavior, not durable global uniqueness proof. Source contract must disallow
ambiguous accepted-ID reuse within generation/lane, or add explicit run/acceptance
lineage upstream. Resolver pauses on conflicting immutable acceptance instead of
choosing latest row. Same acceptance event/command/facts replay keeps earliest
exact provenance. No skinny-only index or routine backward source read.

Index only `SubmitOrder`, `status=accepted`, successful `result.accepted` and full
`acceptedOrder`. Modify success never replaces original accepted terms. Rejected
payload shape never establishes acceptance. For same outcome, original submit
acceptance logically precedes its executions/trades; define this explicitly in
contract slice. Source coordinate: generation/partition/offset/outcome ordinal,
plus acceptance event ID, command ID, batch ID/checksum; trade coordinate adds
flattened trade ordinal. Later-outcome acceptance is rejected even when batch
has already been indexed. Retain partially filled and active-run entries.

## E2 — scheduling and bounded state

[Reviewed scheduling/negative fixtures](../evidence/calcify-phase2/E2-final-all.json)
use actual Kafka Streams 4.3.1 persistent RocksDB stores and real Go fixture.
Verified-led, source-first and links-first all emit 130 byte-identical contexts;
replayed verified links emit no additional output. Five source batches read for
130 links; target decode reused across fanout. Verified-led persists one target
trade array (64,536 logical bytes for 128 trades), allowing restart within batch
without per-trade source seek. It retains no source-ahead unverified trade backlog.
One startup/recovery seek initializes separately assigned `read_committed` source
consumer; normal reads advance only toward demanded target. Source poll tail
bounded to 64 records; production also needs explicit batch/fetch byte budgets.

Two-input source-first accumulated 65,594 logical trade/batch bytes; links-first
accumulated 7,280 pending-link key/value bytes. Full-fact order state identical:
85,898 logical key/value bytes. Verifier omission prevents assuming every observed
trade will eventually receive passing link; two-input reclamation needs explicit
completed frontier. Linear extrapolation from this short-ID fanout fixture:
60 seconds at 10k trades/s could retain ~303 MB extra trade state, before storage
and replication overhead. This is a sizing calculation, not measured outage.

Prototype one-input processing can stage links durably before source is ready.
Managed checkpoint therefore distinguishes **staged verified offset** from
**completed resolved frontier**. This preserves replay rather than skipping
unresolved work. `FlowControl`, through public `KafkaClientSupplier`, pauses
faulted/behind lane on stream thread and prevents internal resume from overriding
that gate. Observed pending 100; hard ceiling 200 fails closed on overflow.
Production must restore pause from durable lane state after assignment, test
rebalance/overflow and provide explicit fault disposition. No automatic resume
from contradictory source; ordinary local disk loss uses changelog restore.

## E3/E5 — actual broker recovery and availability

Final [recovery cohort](../evidence/calcify-phase2/E3-40f507ca/results.json):

| Injected boundary | Committed output before restart | After restart and fresh source wave |
| --- | --- | --- |
| After committed output/offset | 130 | 260 exact contexts, zero duplicates |
| After local state update | 0 | 260 exact contexts, zero duplicates |
| After output forward, before commit | 0 | 260 exact contexts, zero duplicates |
| Host loss / empty local directory | 130 already durable | 260 exact contexts restored through changelog |
| Output-silent standby, active killed | 130 | 260 exact contexts after promotion |

Restart/promotion through completion of fresh wave: 8.40–10.08 seconds. These
include JVM/group startup, source append and observer work; not isolated restore
latency or large-state SLO. One-partition standby emitted no normal output while
standby. Rebalance sometimes moved active role to new instance; observer follows
actual assignment. Observed standby lag ≤1 offset, not claimed zero. Exact
post-promotion full-fact parity is stronger completion evidence than soft lag.
Real transaction initialized and sent to broker before stale producer fencing
probe; stale publisher then failed. Empty transaction alone was insufficient test.

[Availability cohort](../evidence/calcify-phase2/E5-a7fad2dd/results.json): actual
`deleteRecords` removed required initial source prefix; resolver blocked with zero
output. Same-name topic recreation changed broker topic UUID; startup comparison
against managed state blocked further output, preserving prior 130 contexts.
Production must bind registered generation to broker UUID and monitor live identity
changes, not merely compare name or trust configuration integer. With 1,000 bad
links on lane 0, lane 1 continued; 390 combined contexts matched full expected
facts, zero duplicates, observed pending max 100. Lane 1 clones fixture onto second
partition for resolver isolation; it does not attest to deployed matcher routing. This is two-lane continuity,
not arbitrary topology fault containment proof.

Run availability requires earliest retained source offset ≤ required recovery
cursor and retained verified/changelog/output history consistent with active runs.
No TTL/deletion added. Keep complete order rows through partial fills and run
activity; later reclamation requires explicit close plus resolved/verified frontier
proof. Optional archive stays optional. Node-loss test restores small replicated
state; no promise of cheap full-history replay.

## E4 — bytes, operation rate and resource limits

MacBookPro18,4, 10 logical CPUs, 64 GiB RAM, macOS 26.6.2. Java 25.0.1,
Go 1.26.5, Bun 1.3.14. Kafka Streams/clients 4.3.1, RocksDB JNI 10.1.3.
Dedicated Redpanda 26.2.3 RF1, 2 CPU / 2 GiB container; Docker 29.7.2,
image `sha256:0ceda3e98968e814705a75569063c7dc64ce74c1345681624f47bd8d6a6eb3c6`.
JVM capacity probes `-Xms256m -Xmx1g`; cache 32 MiB/store, memtable 16 MiB ×2,
WAL off. Streams experiment cache budget 8 MiB/application.

| Local-store cohort | Result | Limit |
| --- | --- | --- |
| [250k aged, one store, 20s unpaced](../evidence/calcify-phase2/E4-knee-hot.json) | 47,553 joins/s | Single thread; short operation knee |
| [250k aged, four stores, 20s unpaced](../evidence/calcify-phase2/E4-knee-spread.json) | 58,115 joins/s | Sequential stores, not parallel lane scaling |
| [1m aged, one store, paced 300s](../evidence/calcify-phase2/E4-aged-paced.json) | 3m joins, 6m new order rows, 10k joins/s pacing | No source parsing, Kafka transaction, changelog or standby |
| [1m aged, paced 30s resource probe](../evidence/calcify-phase2/E4-resource-probe.json) | 300k joins; sampled service p95 27.9 µs | Local service timing, not commitment latency |

Five-minute transient offered-minus-completed peak 165 trades; final cohort count
matches offered 3m, sampled lag returns to zero. Sampled local service p95 25.0 µs.
Disk grew 132.4 → 867.3 MB while retaining all 7m rows. Repeated metadata compresses
well: ~122.5 physical bytes/new row is **not production disk budget**. Sparse aged
lookup once/100 joins does not qualify cold/random working set. Resource probe
captured 23.22 user + 1.84 system CPU seconds over 34.99 wall seconds including
seed/flush; peak RSS 365.1 MB. macOS block I/O counters reported zero despite file
growth; cannot infer zero disk traffic. Full read/write amplification unmeasured.

Full example row JSON: **646 bytes** including immutable accepted fact, acceptance
event ID and source provenance. Full example V1 JSON: **1,985 bytes**. Production
wire must be versioned Protobuf; short IDs and narrow sample values limit estimate.
At 20k new accepted orders/s, row payload alone ~46.5 GB/hour; 10k V1/s ~71.5 GB/hour,
before keys, broker framing, dedup/cursor state, compression, replication or retention.
One standby duplicates local state; provision from logical bytes until representative
Protobuf/random-ID measurements establish physical budget.

[Managed changelog sample](../evidence/calcify-phase2/E4-changelog.json), 260 contexts:
345,987 logical changelog key/value bytes / 1,062 committed data/tombstone records;
519,538 output key/value bytes; 442,686 source key/value bytes. Changelog ~1,331
bytes/context for this high-resting-order-reuse fixture, not balanced two-new-orders
workload. RF1 wire traffic, broker disk writes and standby network bandwidth were
not measured. RF3 capacity/broker loss and large-state catch-up remain implementation
qualification gates.

Baseline comparisons: CAL-P1-L9 ~5k accepted commands/s (~2.5k trades/s) local full
Phase 1 path; L6/L8 higher-rate intake failures preserved. C5 ~10k commands/s hosted
venue-core with projections off. Reviewed original success/failure JSON, mandatory
throughput ledger, performance learnings and active projection/scaling plans.
Current microbenchmark uses ~20k synthetic row inserts/s to represent 10k pair
joins/s, not actual successful order commands. No causal cross-stage capacity claim.

## Framework guarantees versus Reef measurements

Apache documents fault-tolerant local stores backed by compacted changelogs and
restoration after machine loss: [Processor API](https://kafka.apache.org/43/streams/developer-guide/processor-api/).
Streams EOS config governs managed input/state/output; external source reads are
immutable reads, while their cursor/target/order facts are managed state. This
integration is experimentally demonstrated above, not stock Streams source guarantee.
[EOS/standby configuration](https://kafka.apache.org/43/streams/developer-guide/config-streams/)
and [warmup/restore behavior](https://kafka.apache.org/43/streams/developer-guide/running-app/)
support managed recovery choice; Reef measured small RF1 local recovery only.

[KafkaClientSupplier](https://kafka.apache.org/43/javadoc/org/apache/kafka/streams/KafkaClientSupplier.html)
permits custom clients. [Consumer flow control](https://kafka.apache.org/43/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html)
provides partition pause; pause state does not survive rebalance automatically.
Thin gate remains Reef-specific tested integration, not framework theorem.
[KafkaStreams pause API](https://kafka.apache.org/43/javadoc/org/apache/kafka/streams/KafkaStreams.html)
is instance-wide and still commits/polls; it does not supply lane-only fault policy.
[Topic retention](https://kafka.apache.org/43/generated/topic_config.html) bounds source
availability independently of consumer progress. Conservative configured retention
plus actual beginning-offset checks required; compaction cannot replace positional
venue history.

## Attempts, corrections and smallest implementation sequence

Every execution artifact retained. Raw log/patch whitespace excluded from Git
whitespace lint through local attributes; byte contents preserved. Setup failure before directory existed and
sandbox Docker denial preceded successful isolated setup. Scratch patch first failed
missing `fmt` import; [failed compile](../evidence/calcify-phase2/E1-aligned-compile-failed.log)
retained. Serena hook rejected two write commands before execution; retry succeeded.
Initial standby observer assumed new worker stayed standby and zero-offset lag;
[E3-8a967789](../evidence/calcify-phase2/E3-8a967789/) retained. Corrected role/lag
observer passed promotion; first fencing probe used empty transaction and falsely
expected remote rejection ([E3-96d266ed](../evidence/calcify-phase2/E3-96d266ed/)).
Broker-started transaction fixed test; [E3-375885a7](../evidence/calcify-phase2/E3-375885a7/)
and final E3-40f507ca pass. Manual changelog topic-name typo returned empty auto-created
topic; discarded as measurement and added existence check. Initial future-acceptance
negative fixture tested another inconsistency; replaced with actual later-outcome
acceptance. Final reason is `acceptance follows trade`. Formatting introduced no
behavior change. Review fixed pending-policy overwrite and identical-acceptance
replay, plus lane-local malformed/conflicting link handling; focused regressions pass. No historical result silently promoted.

1. Implement D-042 alignment in production, including command/batch rollback,
   compatibility/snapshot scope, lane ownership and exact source/replay fixtures.
2. Define Protobuf V1 and source acceptance/order-ID uniqueness semantics. Build
   pure resolver with exact source coordinates, full-fact rows and duplicate policy.
3. Integrate verified-only Streams topology and demand source reader; managed cursor,
   target batch, pending/completed frontiers, UUID/generation checks, bounded consumer
   gate and explicit lane fault disposition. Reuse changelog/standby recovery.
4. Qualify complete resolver at 10k **durable resolved commitments/s**, representative
   IDs/wire, hot/spread/skew/aged state, RF3/broker loss, large restore and lag headroom.
   Supply ~20k successful commands/s for two-new-order pairing, or name reuse workload.

No ledger, allocation, clearing, balances, settlement or legacy replacement in this
experiment. Remaining uncertainty concerns production wire/traffic, pause restoration
across adversarial rebalances, index-ID lifetime and large recovery objectives and zombie-worker ownership loss under network partition; none
requires choosing custom recovery control plane now. Sync before production work.
