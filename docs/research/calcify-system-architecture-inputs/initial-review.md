**I would keep Calcify’s log-first, partition-owned-state foundation. I would not start another rewrite.** But I would tighten several contracts before building the remaining financial pipeline on top of it.

The direction is promising: resolve immutable facts once, keep SQL out of that lookup path, and make recovery part of the processing model. The unresolved design problems are more specific:

**Order identity is inconsistent between the matcher and resolver; source reads can block the processing thread; retained state grows with all historical activity; and matching-lane ownership does not automatically translate into safe financial-state ownership.**

I reviewed repository snapshot **`9cb68ee7`**, including the resolver, source reader, Phase 1 pipeline, matching index, Protobuf contracts, and retained capacity evidence. This is a static architecture/code review; I did not run Reef’s tests or benchmarks.

One important correction to the existing October 1 review: some of its headline findings are already outdated. The current code has run-scoped matcher indexing, fail-closed offset-reset configuration, and startup verified/output topic identity checks. However, those changes leave some important gaps described below.  

# 1. The architectural direction I recommend

I would organize Calcify around **three different responsibilities**, without assuming each needs a separate deployed service:

| Responsibility | Authority and output |
|---|---|
| **Establish source facts** | Your existing venue log, extraction, verification, and resolved context |
| **Make financial decisions** | An ordered owner of the relevant financial state, producing durable booking/obligation outcomes |
| **Serve and distribute results** | Independent order, trade, balance, reporting, and settlement-work projections |

The important separation is this:

> **A projection should not be required to reconstruct the facts needed to make the authoritative financial decision.**

Your resolved context is a good foundation for that. Do not undo it downstream by returning to a process that queries several materialized tables to rediscover the two orders, participants, trade, and source lineage.

I would retain the current Kafka Streams resolver rather than immediately replace it with a custom runtime or a two-input join topology. Kafka Streams provides the transaction boundary connecting consumed offsets, managed-state changes, and Kafka output. It does **not** make an external database mutation or economic decision correct automatically. [Apache Kafka](https://kafka.apache.org/43/streams/core-concepts/)

The retained evidence supports further engineering rather than abandonment: one historical attempt recorded **10,160.50 resolved commitments/s**, but failed its own producer-duration gate; the other workload profiles were not subsequently qualified. That is encouraging component evidence, not a pass for your new **600-second and 900-second targets**. 

# 2. Fix these concrete implementation issues first

## A. The matcher and resolver now disagree about order identity

This is the clearest correctness problem I found.

The matcher’s index now uses:

```text
(runId, orderId)
```

But `CalcifyResolverProcessor.orderKey()` still uses:

```text
(sourceGeneration, orderId)
```

The resolver also rejects an existing accepted-order row when its immutable facts differ. Therefore, two legitimate runs reusing an order ID **and landing in the same source partition** can cause the resolver to declare an accepted-fact conflict and stop the lane. This is a false integrity fault, not evidence that either run submitted an invalid order.   

**Fixing the key alone is insufficient.**

The resolver currently looks up the two orders using the trade’s order IDs, then derives the resolved run from the accepted-order records. Its constructed trade context does not independently carry run/session scope into that lookup. The current `TradeSourceV1` and `SourceProvenanceV1` do not provide that scope either.  

My recommended correction is:

1. Derive and validate trade scope from the authoritative source outcome or route.
2. Carry that scope into the resolver’s trade context.
3. Look up accepted orders using a framed `(generation, runId, orderId)` key.
4. Migrate or rebuild the old state format explicitly.

Where existing source facts cannot establish scope unambiguously, extend the source contract first.

The regression test should force two runs with the **same buy and sell order IDs into the same broker partition**, resolve different economics correctly, then repeat after state restoration. A test that accidentally puts them in different partitions will miss the defect.

## B. “Sequential source reader” currently means blocking work on the Streams thread

`BrokerVenueSourceReader` owns a dedicated consumer, but its methods are called synchronously from the processor. When its buffer empties, it performs `beginningOffsets()` and then polls. Its consumer returns at most 16 records per poll. Source-identity validation also periodically makes a synchronous metadata request. 

The processor’s 20 ms drain deadline does not limit time spent **inside** those calls. A metadata request can exceed that budget before control returns to the loop. 

That matters because a Streams thread can execute multiple tasks. Under EOSv2’s design, tasks on a thread also share transactional producer machinery. Logical lane ownership therefore does not mean complete scheduling or failure isolation. [Apache Kafka](https://kafka.apache.org/43/streams/architecture/)

**My recommendation is to preserve the hybrid design, but make this scheduling boundary explicit.**

First instrument time spent fetching source records, checking retention/identity, decoding batches, accessing RocksDB, and waiting for output commits. The relevant input rate is **source batches per second**, not just trades per second.

Then, where measurements justify it, introduce bounded prefetch:

```text
Source-consumer owner
    → bounded, ordered source buffer
    → Streams task
    → managed state + output transaction
```

The prefetch component must never mutate Streams state or emit authoritative output. Its buffer is disposable; the managed cursor remains authoritative. On restoration or ownership change, discard stale prefetched data and resume from the managed position.

Keep fail-closed retention checks. Optimize their frequency and placement rather than simply removing them. Share or batch metadata monitoring where practical instead of creating a growing fleet of synchronous control requests.

## C. Recovery checks have improved, but bootstrap remains too permissive

`ResolverConsumerGate.seekTargets()` now rejects an existing checkpoint outside the retained range. That is good.

However, when the committed offset is absent, it explicitly seeks to **whatever beginning remains**. The same helper is used by Phase 1. A missing checkpoint is treated as a new consumer, even when the retained beginning is already beyond the required history.  

**“No checkpoint exists” does not prove “starting here preserves the required history.”**

I would require explicit bootstrap modes:

| Mode | Required evidence |
|---|---|
| **New generation** | Declared genesis positions and no conflicting prior application history |
| **Restore** | A compatible state snapshot/checkpoint with its required source positions |
| **Intentional partial subscription** | An explicit partial-history contract, never reported as complete replay |

The verified/output UUID bindings are now checked at startup. But the reader’s ongoing identity monitor checks only the source topic, and the processor’s managed identity still binds only source generation/name/UUID. The current implementation therefore does not establish a complete live namespace-continuity guarantee.   

I would bind the entire application generation—input, output, source, state namespace, schemas, and bootstrap positions—in one durable manifest. Existing state must not acquire a supposed historical topic identity merely because that topic is the first one encountered after an upgrade.

Operationally, prohibit routine delete/recreate of active topics. Periodic UUID polling is a diagnostic safeguard, not an atomic replacement protocol.

## D. The extractor’s transaction unit is still the individual source batch

The verifier now batches a poll, and the receipt worker has a batch path. But the extractor processes each source record with a separate `publishAndCheckpoint()` transaction—including zero-trade source batches. 

This makes upstream batch formation part of your throughput ceiling.

A workload producing many small or zero-trade batches can be much more expensive than another workload producing the same number of trades in large batches.

I would microbatch multiple source records into a transaction, bounded by elapsed time, source bytes, and output records. Preserve the valid prefix of each partition, advance checkpoints for zero-trade batches, and never checkpoint past a poisoned record.

This changes the **transport commit unit**, not the meaning of a trade or its commitment identity. It is a much more targeted optimization than another infrastructure pivot.

## E. The resolver retains all accepted orders and all completed commitments indefinitely

The processor permanently stores accepted-order rows and one `D:` record per completed commitment. Its bounded application cache does not bound that durable history. 

At your first target, that is **six million additional completion records in ten minutes**, plus the accepted-order index and changelog traffic.

This is not automatically a ten-minute capacity failure. It is a missing lifecycle design, and it directly affects aged-state performance and recovery.

Do not solve it with arbitrary TTLs. An old resting order is exactly the case where an apparently stale acceptance can still be required.

# 3. Make replay and state retirement explicit contracts

There are two different identities you need to preserve.

## Physical source identity versus economic identity

Your 21-byte pointer is an effective locator within a registered source generation:

```text
generation + partition + offset + ordinal
```

Keep it.

But define a separate, guaranteed **execution identity** for downstream financial effects. Do not assume a field named `tradeId` has the necessary uniqueness and replay properties without specifying and testing them.

Why the distinction matters:

A historical execution restored into a different broker namespace may acquire a different physical pointer. That must not accidentally authorize a second financial booking. Conversely, an intentional simulation fork may legitimately create a new economic history.

My proposed contract is:

```text
Source locator:
    Where the authoritative fact was recorded.

Execution identity:
    Which economic execution this represents.

Action identity:
    Which booking, settlement, correction, or reversal is being applied.
```

Policy version belongs in the decision evidence. It should not, by itself, authorize booking the same execution again.

For archived source data, choose explicitly between preserving its original coordinates through an archive-aware reader or translating new transport coordinates back to stable execution identities. A changelog that restores the resolver is not a substitute for a complete source-history replay contract.

## Replace permanent completion history with certified completed prefixes

I would move toward a completed-prefix model, but **not** simply replace `D:` records with the maximum source offset.

A source batch can contain many trades. Verification can eventually reject some. There can be duplicate inputs, and offsets are not a trade-count sequence.

The safe design needs enough coverage evidence to establish:

```text
For source prefix P:
    every extracted trade has a terminal verification disposition,
    and every passing commitment has been resolved.
```

That suggests sparse extraction/verification coverage records, active-batch ordinal tracking, and a completed frontier. Historical duplicate/conflict evidence can then be retired or archived under an explicit replay policy.

These are control records, not a reason to introduce another per-trade service. Their ordering and transaction relationship to existing fixed-width records must be specified; they cannot simply be inserted into the current 21-byte stream without versioning.

## Start with run-level retirement

For Reef, I would initially prefer a simple retirement contract:

```text
Run sealed
    → venue source complete
    → extraction and verification coverage complete
    → resolution complete
    → required downstream processing complete
    → archive/checkpoint verified
    → run-scoped active state eligible for retirement
```

This is easier to reason about than immediately inventing per-order garbage collection across fills, cancellations, amendments, and delayed verification.

Later, per-order retirement can require terminal lifecycle evidence **and** proof that no unresolved source reference can still need the acceptance.

Also budget native memory across tasks, not just the JVM heap. Kafka’s documentation distinguishes record caching from RocksDB memory and describes sharing block-cache/write-buffer budgets across stores. More partitions can multiply store resources unless you control that explicitly. [Apache Kafka](https://kafka.apache.org/43/streams/developer-guide/memory-mgmt/)

# 4. The next architecture must include a non-trade path

Your demand-driven resolver advances source history when a verified trade requires it. That is appropriate for trade resolution.

It is not sufficient to maintain a live order API.

A resting order, cancellation, expiration, or amendment can require an API update without producing any resolved trade. The resolver also deliberately retains **original immutable acceptance economics**, not an evolving order-state record.  

I would use this logical flow:

```text
Authoritative venue-event log
    │
    ├── Order-lifecycle processing
    │       └── Order status / execution reports / lifecycle views
    │
    └── Extraction → structural verification → context resolution
            │
            └── Versioned financial admission
                    │
                    └── Ordered financial-state owner
                            │
                            └── Durable financial result
                                    ├── Cash / position / obligation views
                                    ├── Clearing and settlement work
                                    └── Audit and reconciliation
```

**These are logical boundaries, not a mandate for nine services.**

Keep Phase 1 verification structural unless you intentionally provide it with the state needed for more. Moving economic checks into a pointer-only verifier without that state would recreate the source-lookup problem before the resolver.

For financial processing, combine resolved trade context with whatever versioned lifecycle and reservation facts the existing policy actually requires. Do not pretend the original accepted-order fact tells you the current reservation state after amendments.

For API consistency, attach progress tokens to views. An order view and a balance view may legitimately be at different points. A caller requiring read-after-booking should wait for the relevant financial sequence rather than receiving an undocumented mixture of versions.

# 5. The most important downstream decision: who owns financial invariants?

**A matching lane is not necessarily a safe financial partition.**

Consider this hypothetical:

```text
Account A has 100 available.

Instrument lane X processes a purchase costing 80.
Instrument lane Y processes a purchase costing 80.

Each independently observes 100.
Each processes its trade exactly once.
Together they consume 160.
```

Delivery was exactly once. The financial decision was still wrong.

Therefore, do not blindly carry `(run, session, instrument)` partitioning into balances, reservations, or settlement.

## Begin with a closed financial domain

My recommendation for the first financial implementation is **one ordered owner per closed financial domain**: all state involved in an invariant must either belong to that owner or participate in an explicitly designed coordination protocol.

For a simulation whose accounts and obligations are isolated per run, that can initially mean **one financial owner per run**. Where accounts cross runs, the domain must be larger or differently defined.

This does not mean one global worker forever. It means you first prove the simplest correct ownership model, then split domains along boundaries that preserve correctness.

Do not begin by hashing the buyer and seller to separate workers and applying their legs independently. That creates a distributed atomicity problem you have not otherwise needed.

The ordered owner should use Reef’s existing financial evaluator and policies. It should not change settlement timing, netting rules, or insufficient-funds behavior to improve the benchmark.

## Persist the ordering decision across matching lanes

A financial owner may receive trades from several instrument partitions.

The merge order can affect reservations, available balances, or retry eligibility. Consequently, its ordering decision must itself become durable.

I would feed the financial owner from a **durably sequenced admission stream**, rather than repeatedly merging source partitions according to whichever consumer happens to return first.

There are two separate replay promises:

**Recovery replay** reproduces the already-recorded admission order.

**Re-running a seeded simulation from scratch** requires a deterministic ordering rule upstream of that log; persisting one arbitrary arrival order does not make every future run choose it.

This is the same fundamental constraint used in deterministic replicated state machines: decisions must depend on ordered, reproducible inputs, rather than uncontrolled wall-clock or external state. [Aeron](https://aeron.io/docs/cluster-quickstart/replicated-state-machines/)

## My preferred commit boundary for the Calcify proof

For the next proof, I would use a **committed financial-result log as Calcify’s logical authority**, with managed financial state updated in the same Streams transaction. PostgreSQL would initially be a downstream read projection, not a second competing commit authority.

Conceptually:

```text
Consume admitted financial work
    → evaluate against owned financial state
    → produce applied / pending / exception disposition
    → update managed state
    → emit complete financial result
    → commit state, input position, and output together
```

That uses the transaction model you already adopted instead of introducing a second distributed commit mechanism. External side effects still require their own idempotent delivery and acknowledgment protocols; Kafka’s transaction does not include them. [Apache Kafka](https://kafka.apache.org/43/streams/core-concepts/)

A proposed financial-result envelope should contain the execution/action identities, financial-domain sequence, rule version, exact amounts and units, all applicable journal legs, resulting disposition, and source lineage.

**Put the complete atomic booking in one result envelope.** Do not require a consumer to reconstruct atomicity from independently delivered debit and credit messages.

An inability to book an already executed trade must produce an explicit exception or pending obligation under your policy—not silently erase the execution.

If PostgreSQL is ultimately selected as the authoritative financial store instead, move this entire boundary there: journal changes, relevant state changes, deduplication, and outbox in one database transaction. Do not leave half of the authoritative commit in Kafka and half in PostgreSQL.

Finally, distinguish booking from settlement. DTCC’s CNS model itself separates trade processing, net obligations, and settlement activity. The useful lesson is the lifecycle separation, not that Reef should duplicate DTCC’s infrastructure. [DTCC](https://www.dtcc.com/products-and-services/clearing-settlement-services/equities-clearing/cns)

# 6. Capacity: budget the complete work, not the pointer size

Your requested workloads imply:

| Qualification | Resolved trades | Successful order commands in the paired-order fixture |
|---|---:|---:|
| 10,000/s × 600 seconds | **6,000,000** | **12,000,000** |
| 7,500/s × 900 seconds | **6,750,000** | **13,500,000** |

The two-orders-per-trade relationship is specific to that fixture. One incoming order matching many resting orders changes the ratio; cancellations, rejections, and non-crossing orders increase work without increasing the trade count.

The retained full-path smoke produced a **2,588-byte resolved context**. Using that particular record size only as an illustration, 10,000/s means approximately:

```text
25.88 MB/s of logical resolved output
15.53 GB over ten minutes
46.58 GB of replicated payload at RF3
```

That excludes source batches, state changelogs, indexes, protocol overhead, downstream writes, and compression effects. It is not a measured average payload for your production workload. 

**I would not respond by stripping the context back into pointers.** Carrying the required immutable facts can be the right tradeoff. The optimization target is total decoding, state mutation, I/O, and coordination per trade—not winning a record-size contest.

Measure source batches/trade, accepted orders indexed/trade, physical changelog bytes/trade, resolved bytes/trade, transaction frequency, and cold-cache lookup cost. Do not equate every logical `store.put()` with a separate disk write: Streams caching can coalesce state-store updates. [Apache Kafka](https://kafka.apache.org/43/streams/developer-guide/memory-mgmt/)

## Design for catch-up headroom

A system whose maximum sustainable rate equals its arrival rate cannot drain a recovery backlog while new work continues arriving.

For illustration:

```text
Arrival rate:           10,000/s
Interruption:                20s
Accumulated backlog:      200,000
Recovery capacity:      12,500/s
Net backlog drain:       2,500/s
Catch-up time:                80s
```

The general relationship is:

\[
\text{catch-up time} =
\frac{\text{backlog}}{\text{processing capacity} - \text{arrival rate}}
\]

Choose a recovery objective, then derive the headroom requirement. Do not silently turn your normal-load target into a promise of uninterrupted 10k/s through every failure.

Likewise, partition count is not a sufficient capacity model. For every lane, its arrival rate must fit its sustainable processing capacity, while total broker, CPU, storage, and memory budgets also remain within capacity. Idle partitions cannot rescue an overloaded serial hot lane.

Keep routing stable within a source generation. Adding workers to redistribute existing tasks is different from changing partition routing and relocating the accepted-order history needed by future fills.

# 7. Qualification I would require

I would keep **resolver qualification** and **full-system financial qualification** separate. The former is a legitimate milestone; it must not be relabeled as the latter.

| Test family | What it must establish |
|---|---|
| **Isolated Phase 2** | Both requested durations and rates, with exact committed output reconciliation |
| **Real Phase 1 → Phase 2** | Actual extraction/verifier behavior, including small and zero-trade batches |
| **Actual ingress path** | The real API and matcher can supply the necessary workload |
| **Aged and skewed state** | Hot books, many books, cold accepted-order lookups, old resting orders, and collocated runs |
| **Recovery under load** | Owner death, standby promotion, local-state loss, broker failure, and bounded catch-up |
| **History failures** | Missing checkpoints, retention loss, topic recreation, incompatible state, and explicit refusal to continue incorrectly |

For every capacity claim, count **independently observed `read_committed` output**, not `context.forward()` calls, input staging, or process-local counters.

Track pending count **and oldest pending age**, per-lane completion progress, actual processing latency, commit visibility delay, restoration duration, and backlog slope. Keep source offsets, verified offsets, and trade counts as separate quantities.

The current processor forwards resolved output with timestamp `0`. That is not evidence of incorrect trade economics, but it means an observer cannot treat that Kafka record timestamp as a useful completion-time measurement. Add deliberate observability timestamps or tracing without contaminating the deterministic business facts. 

Freeze the candidate build, workload, hardware, broker acknowledgment policy, replication, retention, and measurement window. Preserve failed runs. A final drain must not be folded into a rate calculation that makes an overloaded processing window appear successful.

For Redpanda specifically, freeze `write.caching` and actual deployment durability settings. Replication factor three alone does not say whether acknowledgment waited for disk persistence; Redpanda documents write caching as acknowledgment after majority replication without waiting for `fsync`. [Redpanda Documentation](https://docs.redpanda.com/streaming/current/develop/produce-data/configure-producers/)

Finally, reconcile **identities and complete facts**, not just counts. For the financial proof, reconcile dispositions, journal conservation, reservations, and resulting balances against an independent replay oracle. Six million records can still be six million wrong records.

# 8. The implementation sequence I would choose

| Priority | Deliverable |
|---|---|
| **1. Repair contracts** | Run-scoped resolution with authoritative trade scope; explicit bootstrap manifest; negative recovery tests |
| **2. Remove measured scheduling overhead** | Extractor microbatching; source-reader instrumentation and bounded prefetch where justified; preserve the managed transaction boundary |
| **3. Bound the active lifetime** | Coverage/seal records, completed-prefix retirement, run-scoped cleanup, and verified archive/restore |
| **4. Qualify the current boundary** | Your exact 600-second and 900-second targets, then real ingress and recovery-under-load runs |
| **5. Build the financial vertical slice** | Durable admission order, one closed financial-domain owner, existing evaluator, complete committed financial results, independent projections |

For the financial slice, shadow the legacy path with **no duplicated financial side effects**, compare exact results, then cut over at an explicit boundary with only one authoritative writer.

## Bottom line

Calcify’s strongest idea is **assembling immutable context once and processing it through owned, recoverable state**. Keep that.

The biggest danger is reaching the end of Phase 2 and rebuilding the old coordination problem downstream: several workers, repeated SQL lookups, mutable projection dependencies, and no single place that atomically decides what happened financially.

My design recommendation is:

> **Keep the current resolver foundation. Fix identity and history continuity. Make its source-reading work bounded. Retire state through proven lifecycle boundaries. Then add a durably ordered financial owner—not another orchestration layer.**

That provides a concrete route toward your 10k/s goal while preserving the property that matters most: **after a crash, replay, or cutover, Reef still agrees about exactly which executions occurred and exactly which financial effects were applied.**