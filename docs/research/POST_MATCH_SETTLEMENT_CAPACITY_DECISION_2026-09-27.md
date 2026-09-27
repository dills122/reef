# Settlement capacity after PM-S2 — 2026-09-27

Status: **recommendation suspended after PM-S2 forensic review**. PM-S2 does
not isolate a settlement algorithm limit or justify implementing batching next.
No public-read, production, or accepted-decision change. Project owner owns
any amendment to D-059.

## Decision question and boundary

What does the failed disposable 10k/s PM-S2 diagnostic actually establish,
and what evidence must precede a settlement redesign while preserving durable
admission order, deterministic retained-fact replay, scarce-account decisions,
and atomic four-leg DvP?

Scope: review merged #400, PM-S2 raw evidence, D-059, and comparable primary
implementation guidance. Do not infer causality from an unmatched run, relax
settlement invariants, or treat accepted command rate as trade/ledger rate.
Stop at a testable attribution plan; do not launch another 10k tuning ladder
or change public routes from this paper.

## Evidence

| Kind | Evidence | Consequence |
| --- | --- | --- |
| Observation | [PM-S2](../THROUGHPUT_BASELINES.md#pm-s2--bounded-settlement-10k300s-stage-diagnostic-failed) accepted/direct-acked 2,999,943 commands in 300s, while canonical materialization and legacy command-status projection missed their stopped-source gates. Settlement intake/obligation/admission/execution frontiers split sharply; execution covered 61,175 source positions at 19:52:24 while admission covered 816,875. | Combined topology cannot qualify 10k. Fixing settlement alone will not promote the legacy pipeline. |
| Observation | PM-S2 used four canonical materializers and four command-status projector owners. F02 used six and sixteen respectively. PM-S2 also enabled live, market, intake, obligation, admission, and execution workers inside those same four projector JVMs, on the same c-32 host as all five PostgreSQL services. The post-match databases were separate, but compute and host I/O were not. | F02/C43 are not a matched before/after architecture comparison. PM-S2's projection regression cannot be attributed to settlement design or solved by batching from this evidence. |
| Observation | At the post-run SQL snapshot, there were 20,852 admission-counter updates and 1,673 execution completion inserts; dependency-completion query ran 44,374 times. The five-actor fixture shares cash accounts across 64 instruments. Execution reads and checks one admitted window per partition, then only proceeds when recorded predecessors complete. | An account dependency chain and repeated blocked-window checks are plausible execution bottlenecks. These aggregates do not reveal in-load rank critical path, useful execution time, or exact trades/s. Admission batching by itself has no demonstrated ability to close the execution gap. |
| Observation | The downstream diagnostic sampler collected 300 in-load samples but zero *qualified* samples: the lifecycle and market probes were online, but caller instrumentation was disabled. Source coverage errors logged by live/intake workers later logged recovery; transition logged no failure. | This run cannot support a downstream latency claim. Recovered coverage misses deserve timing/retry instrumentation, but are not evidence of a permanent stall. |
| Observation | At 19:56:02, settlement table statistics showed about 1.27m trade-intake inserts, 20,747 admission windows, 71,539 attempts and 286,156 ledger inserts. `pg_stat_statements` recorded 20,852 updates of the single admission-counter row, mean 112.23ms/call; the sampled activity had ten active transaction-ID lock waits. | Rank assignment is contended and each completed execution window carries high transaction overhead. Statistics are post-run aggregates, not per-trade latency or a controlled attribution. |
| Observation | Settlement intake/obligation/receipt/order-directory tables together occupied roughly 7GB at the diagnostic snapshot. Both isolated post-trade PostgreSQL services logged frequent WAL checkpoints at default WAL sizing. | Write shape and database configuration both need an explicit budget in the next experiment. A WAL setting change alone cannot close the execution gap demonstrated by frontiers. |
| Documented fact | [PostgreSQL row-lock rules](https://www.postgresql.org/docs/16/explicit-locking.html) make concurrent updates to the same row wait for the holder's transaction. [PostgreSQL statistics](https://www.postgresql.org/docs/16/pgstatstatements.html) define `total_exec_time` as cumulative execution time, and [activity wait events](https://www.postgresql.org/docs/16/monitoring-stats.html) describe `transactionid` as a wait for another transaction. | The counter and hot-account writes can serialize concurrent windows; summed SQL time must not be read as elapsed wall time. |
| Documented fact | [PostgreSQL WAL guidance](https://www.postgresql.org/docs/16/wal-configuration.html) explains that frequent checkpoints add data-page and WAL work. [Bulk-loading guidance](https://www.postgresql.org/docs/16/populate.html) recommends batching rows rather than individual inserts for bulk paths. | Batch persistence is a plausible work-reduction direction; its effect on this workload remains unmeasured. |
| Documented fact | [TigerBeetle architecture](https://github.com/tigerbeetle/tigerbeetle/blob/main/docs/ARCHITECTURE.md) records order before deterministic execution, notes hot-account contention, and amortizes work across large transfer batches. [Modern Treasury concurrency guidance](https://docs.moderntreasury.com/ledgers/docs/handle-concurrency) distinguishes balance-conditioned hot-account writes from asynchronous/batched unconditional entries. | Similar ledger implementers treat hot-account conditional work as serial and batch around it. Neither product's throughput is a Reef capacity measurement. |

The accepted [arbitration spike](POST_MATCH_ACCOUNT_ARBITRATION_SPIKE_2026-09-27.md)
already rejected timestamp order, `nextval` as replay proof, and unordered
account lanes. PM-S2 does not overturn that correctness reasoning.

## Options against the same criteria

| Option | Correctness and delivery cost | Capacity judgment |
| --- | --- | --- |
| Raise WAL limits and tune current per-window SQL | Preserves D-059 and is easy to undo. | Useful for fair database settings, but leaves one counter update and one account-sensitive transaction per small window. Do not make it the main architecture path. |
| Batched admission plus coalesced ordered execution in the dedicated settlement store | Preserve retained admission history and exact rank/digest proofs. One admission owner reserves/commits many contiguous ranks per transaction; execution combines consecutive dependent windows into a bounded deterministic batch, evaluates balances in rank order, then commits all attempts, four-leg postings, checkpoints, completion proofs, and frontiers atomically with bulk writes. Crash rolls back the whole batch. Independent disjoint components may execute concurrently. Requires a substantive worker/store rewrite and replay tests. | Plausible candidate only. PM-S2 has not separated transaction overhead from deployment, dependency, and host contention. Do not implement it as the next committed architecture change yet. |
| New specialized ledger engine or separate ordered-log service now | Could provide efficient native batched ledger execution. Cross-store admission/ledger atomicity, replay import, DvP modeling, operations and migration would need a new design and qualification. | Do not swap engines based on one unmatched diagnostic. Reconsider only after the dominant cost is measured. |
| Remove global admission order and shard by account | Shares load but multi-account DvP can choose different scarce-account winners across runs or form cycles. | Reject under D-059 without a new durable arbitration proof. |

## Corrected recommendation and proof gate

Keep D-059's retained canonical admission order. First separate post-match
worker processes from command-status projection and restore a matched owner
topology. Run a controlled disposable-droplet pair with identical source,
fixture, six materializers, sixteen projector owners, PostgreSQL settings,
observer, and host budget: control with post-match workers disabled; treatment
with dedicated post-match workers enabled. If the treatment materially slows
canonical or legacy projection, measure CPU, I/O, WAL, database waits, and
per-stage write amplification before changing settlement logic. This pair
attributes *incremental integrated cost*, not settlement's absolute capacity.

For settlement, add in-load counters/timings for source trades/s; admission
windows/ranks/s and counter wait; ready versus blocked execution windows,
dependency depth and critical path; execution trades/s and transaction time;
four-leg facts/s; WAL bytes/trade; and per-partition frontiers. Prove exact
closed-cohort trade/ledger membership and stopped-source drain. Run the
five-actor hot-account fixture and a more diverse account mix. A fixed-cohort
local Docker replay/cost test can compare current per-window execution with
coalescing *after* the blocked versus executing time is known. Only select
batching if it removes a measured dominant cost while preserving rank/digest,
balance, DvP, atomicity, and crash/replay proofs. Full-system qualification
still waits for audit, public route parity/cutover, full old-projection
replacement, and the integrated plan's warm/aged gates.

## Confidence and open questions

High confidence: current combined topology failed 10k; workers were colocated
with fewer projector/materializer owners than the prior F02 run; execution
lagged admission by a large margin. Medium confidence: the global counter is
contended and account dependencies constrain execution. Unknown: how much
each factor contributes; whether the host's shared I/O/CPU budget or legacy
projection load dominates; exact in-load trade rate and outcome distribution.
No batching speedup, architecture regression magnitude, or settlement capacity
claim is established by PM-S2.
