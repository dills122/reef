# Settlement capacity after PM-S2 — 2026-09-27

Status: decision-ready architecture recommendation. No public-read, production,
or accepted-decision change. Project owner owns any amendment to D-059.

## Decision question and boundary

What one coherent redesign should follow the failed disposable 10k/s PM-S2
diagnostic while preserving durable admission order, deterministic retained-fact
replay, scarce-account decisions, and atomic four-leg DvP?

Scope: review merged #400, PM-S2 raw evidence, D-059, and comparable primary
implementation guidance. Do not infer causality from an unmatched run, relax
settlement invariants, or treat accepted command rate as trade/ledger rate.
Stop at a testable design recommendation; do not launch another 10k tuning
ladder or change public routes from this paper.

## Evidence

| Kind | Evidence | Consequence |
| --- | --- | --- |
| Observation | [PM-S2](../THROUGHPUT_BASELINES.md#pm-s2--bounded-settlement-10k300s-stage-diagnostic-failed) accepted/direct-acked 2,999,943 commands in 300s, while canonical materialization and legacy command-status projection missed their stopped-source gates. Settlement intake/obligation/admission/execution frontiers split sharply; execution covered 61,175 source positions at 19:52:24 while admission covered 816,875. | Combined topology cannot qualify 10k. Fixing settlement alone will not promote the legacy pipeline. |
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
| **Batched admission plus coalesced ordered execution in the dedicated settlement store** | Preserve retained admission history and exact rank/digest proofs. One admission owner reserves/commits many contiguous ranks per transaction; execution combines consecutive dependent windows into a bounded deterministic batch, evaluates balances in rank order, then commits all attempts, four-leg postings, checkpoints, completion proofs, and frontiers atomically with bulk writes. Crash rolls back the whole batch. Independent disjoint components may execute concurrently. Requires a substantive worker/store rewrite and replay tests. | Recommended next implementation. It directly reduces per-window lock/round-trip overhead while keeping current ownership and replay contract. No 10k guarantee. |
| New specialized ledger engine or separate ordered-log service now | Could provide efficient native batched ledger execution. Cross-store admission/ledger atomicity, replay import, DvP modeling, operations and migration would need a new design and qualification. | Keep as a later decision if the relational batch design misses the measured gate; do not swap engines based on one unmatched diagnostic. |
| Remove global admission order and shard by account | Shares load but multi-account DvP can choose different scarce-account winners across runs or form cycles. | Reject under D-059 without a new durable arbitration proof. |

## Recommendation and proof gate

Keep D-059's retained canonical admission order. Replace per-window contention
with one **bounded batch admission owner**; replace one-window execution commits
with a deterministic ordered batch over dependent ranks. The owner should
record exact trade count, source positions, rank range, account-set digest,
and policy version before execution. The executor should evaluate each trade
against in-batch account state in retained rank order, write facts in bulk,
and atomically commit all affected account checkpoints and each partition's
progress. A replay imports the same ranks and checks every batch digest and
ledger balance. This is a redesign of the dataflow, not an index or polling
change. Start with a local Docker correctness and fixed-cohort cost spike;
promote to one reviewed implementation PR only after invariants are proven.

Measure admission ranks/s and wait time, execution windows/trades/s, four-leg
facts/s, batch size distribution, hot-account dependency depth, per-stage
frontier slope, WAL bytes/trade, and 16-partition skew. Run both the PM-S2
five-hot-account fixture and a more diverse account mix; show exact accepted
commands and trades separately. The next disposable 10k/s 300s run must
capture in-load stage samples, closed-cohort trade/ledger membership,
crash/replay, and stopped-source drain. Full-system qualification still waits
for audit, public route parity/cutover, full old-projection replacement, and
the integrated plan's matched control/treatment and warm/aged gates.

## Confidence and open questions

High confidence: current combined topology failed 10k and execution lagged
admission by a large margin. Medium confidence: hot-row and per-window
transaction costs are major contributors. Unknown: fraction attributable to
admission counter versus account dependency chain versus PostgreSQL I/O;
exact in-load trade rate and outcome distribution; how much unrelated legacy
projection pressure slowed canonical/post-trade stages. A batched prototype
with the same source cohort and direct stage timing must resolve these before
claiming a specific speedup.
