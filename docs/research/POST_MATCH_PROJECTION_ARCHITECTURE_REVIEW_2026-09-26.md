# Post-match projection architecture review — 2026-09-26

Status: research recommendation for architecture owner; no design approval, code
change, deployment, or new benchmark. Reviewed `fb6dedfcf4cb0a82cb1d302ada28e9de1bb283b1`
against the supplied analysis, current source, retained C28–C43 evidence, the
[structural seam decision](PROJECTION_STRUCTURAL_SEAM_DECISION_2026-09-25.md),
and primary external sources listed below. Decision question: which post-match
work should become incremental or independently owned before Reef attempts to
qualify sustained 10k commands/s through all required reads?

## Executive decision

Trial an **ordered, incremental order-state maintainer in the existing
Kotlin/PostgreSQL projection boundary**. Derive explicit effects for every
affected order, including a resting maker, and maintain isolated shadow state
with an effects-plus-checkpoint transaction. Keep the current full projector and
audit history as the comparison authority. Prove business and replay parity
first; then replace the old lifecycle work for one matched, full-pipeline
capacity treatment. This tests whether removing historical recomputation
actually improves in-load freshness without prematurely adding a new service.

In parallel, design a **bounded trade-to-settlement processor** with immutable
facts and atomic accounting transitions. It needs a separate trade/account
workload and gate; command-rate evidence does not measure settlement capacity.
Audit persistence, market aggregation, and physical database separation remain
conditional follow-ups. This sequence agrees with the [existing structural seam
decision](PROJECTION_STRUCTURAL_SEAM_DECISION_2026-09-25.md#decision); this
review adds cross-checks and external implementation constraints rather than
reopening its worker-split decision.

## What the supplied analysis gets right, and what requires qualification

| Finding | Cross-check | Disposition |
| --- | --- | --- |
| Settlement is downstream of normalized trades/orders/events, rather than the gate through which lifecycle and market projections flow. | [Trade materializer](../../services/platform-runtime/src/main/kotlin/com/reef/platform/application/settlement/TradeSettlementObligationMaterializer.kt#L36) reads runtime trades and accepted orders; lifecycle SQL and market worker form a separate branch. | Confirmed current topology. Keep settlement out of explanations of C43 projection lag. |
| A canonical projection claim, normalized effects, watermark, and completion share a transaction. | [Separated-store Kotlin flow](../../services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt#L2780) and [D-055](../DECISIONS.md#d-055-retry-safe-canonical-projection-batch-claims). | Preserve this recovery invariant under any new stage. |
| Lifecycle recomputes from historical executions and event rows for dirty orders. | [Active lifecycle function](../../scripts/dev/db/migrations/runtime/0052_projection_dirty_serialization.sql#L213) aggregates `runtime.executions` and reads `runtime.runtime_events` for modification/cancel/reject. | Best first work-reduction hypothesis. A standalone status worker using today's SQL would still depend on timeline completion. |
| Projection write amplification is material. | [C42–C43 ledger](../THROUGHPUT_BASELINES.md#c42--lock-only-dirty-conflicts-write-target-removed-full-pipeline-timed-fail): about 19.96M C42 projection inserts for about 3M commands; C43 about 5,659 projection WAL bytes per accepted command and a failed frozen gate. | Confirmed fixture-specific cost. Extrapolated 56.6 MB/s at 10k/s is **not** a measured long-run rate or removable cost. No single row/index/SQL statement is yet proven to be dominant. |
| Whole-run settlement reads will age poorly. | [Materializer](../../services/platform-runtime/src/main/kotlin/com/reef/platform/application/settlement/TradeSettlementObligationMaterializer.kt#L36) loads existing run facts and an all-events map; [appendFacts](../../services/platform-runtime/src/main/kotlin/com/reef/platform/application/settlement/SettlementFactStore.kt#L873) reads run facts again to validate before insert. | Confirmed algorithmic risk, not measured settlement bottleneck. Bound reads by changed trade/obligation/account, then benchmark trade and ledger work independently. |
| Dirty queues can disappear on PostgreSQL crash. | [Migration 0042](../../scripts/dev/db/migrations/runtime/0042_unlogged_projection_dirty_queues.sql) marks both queues UNLOGGED; worker `start()` paths poll without reconstruct-before-ready. [PostgreSQL](https://www.postgresql.org/docs/18/sql-createtable.html) specifies crash truncation. | Recovery proof remains open. Empty queues cannot alone establish freshness after unclean restart. This is a recovery gate, not an observed lost-data incident. |
| `10k/s` full system is unqualified. | [C5](../THROUGHPUT_BASELINES.md#c5--current10k-venue-core-baseline-two-samples) excludes projections. [C28](../THROUGHPUT_BASELINES.md#c28--same-prefix-marker-timing-full-projection-10k-diagnostic) failed conservative downstream freshness. [C43](../THROUGHPUT_BASELINES.md#c43--broad-runtime-event-order-time-index-removed-full-pipeline-timed-fail) materialized 2,992,504 in the offered 300s run, but later projector collection had 26,029 lag and the frozen gate failed. | Confirmed. C43 stage snapshots are not simultaneous; postdrain equality cannot be promoted to sustained capacity. The precise resource ceiling is still unknown. |

The [September 25 SQL audit](SQL_DATA_ARCHITECTURE_AUDIT_2026-09-25.md)
predates several source fixes. Current [migration 0068](../../scripts/dev/db/migrations/runtime/0068_event_replay_conflicts.sql) addresses event replay conflicts; `appendFacts` now verifies uncertain inserts against persisted facts; public `/trades` and `/events` routes call bounded recent reads. Retain their regression tests and fault gates, but do not present the audit's original F01/F03/F05 descriptions as unremediated current code. The UNLOGGED queue recovery question and scaling hypotheses remain.

## External implementation evidence and limits

These references show useful ownership contracts. They do **not** establish
Reef's throughput or reveal competitors' internal database layouts.

1. [LMAX's architecture account](https://martinfowler.com/articles/lmax.html),
   based on interviews with its implementers, separates ordered in-memory
   business processing from input journaling/replication and outbound
   publication. It also reports a tested actor prototype where queue overhead
   dominated. **Inference for Reef:** retain deterministic matching order and
   measure each post-match stage before adding service queues. LMAX's design
   does not imply Reef needs Disruptor or the same persistence choices.
2. [Nasdaq OUCH](https://nasdaqtrader.com/content/technicalsupport/specifications/TradingProducts/Ouch5.0.pdf)
   defines participant order acceptance, replacement, execution, and cancel
   messages; [TotalView-ITCH](https://nasdaqtrader.com/content/technicalsupport/specifications/dataproducts/NQTVITCHSpecification.pdf)
   defines a sequenced market-data feed. [Coinbase's drop-copy
   contract](https://docs.cdp.coinbase.com/exchange/fix-api/drop-copy) separately
   exposes participant execution updates. **Inference:** private order/execution,
   public market, and audit/history need explicit sequencing, authorization,
   recovery, and freshness contracts. Public interface specs do not prove an
   internal microservice topology.
3. [DTCC CNS](https://www.dtcc.com/products-and-services/clearing-settlement-services/equities-clearing/cns)
   describes netted obligations; [DTC settlement](https://www.dtcc.com/products-and-services/clearing-settlement-services/equities-settlement)
   describes transfer of securities and cash after trading and clearing.
   **Inference:** trade execution, obligation creation, and final settlement
   must remain distinguishable in Reef's instant and realistic profiles. This
   aligns with [Reef post-match standards](../POST_MATCH_STANDARDS.md) and does
   not endorse asynchronous debit/credit halves.
4. [Kafka Streams state stores](https://kafka.apache.org/43/streams/developer-guide/processor-api/)
   provide partition-local persisted state and changelog recovery. Kafka's
   [consumer documentation](https://kafka.apache.org/41/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html)
   specifically recommends storing relational output and consumer position in
   one database transaction for atomicity. **Inference:** Kafka Streams is a
   plausible later stateful-processing candidate, but broker exactly-once
   semantics do not make a separate PostgreSQL write atomic. Reef must retain
   effect identity, contiguous progress, and retry recovery. The canonical
   PostgreSQL outcome versus broker-batch input authority also needs an explicit
   contract before such a move.
5. Incremental SQL is real but not a drop-in ledger or lifecycle authority.
   [Feldera's documented connector matrix](https://docs.feldera.com/pipelines/fault-tolerance/)
   lists PostgreSQL CDC as checkpoint/at-least-once capable but without
   exactly-once fault tolerance, and its direct PostgreSQL connector without
   those recovery modes. [Materialize's PostgreSQL source
   documentation](https://materialize.com/docs/sql/create-source/postgres-v2/)
   documents WAL retention and replication-slot/timeline failure modes.
   **Inference:** trial such systems only on a representative read projection
   after source, sink, replay, retention, and recovery gates are specified;
   do not move accounting invariants on vendor throughput claims.

## Option comparison

| Option | Possible capacity mechanism | Main risk and next gate |
| --- | --- | --- |
| Incremental order state in existing projection PostgreSQL | Removes repeated history aggregation; can coalesce current-state writes. | Hot maker rows, ordering, and replay can add contention. Prove effect/field parity, then a matched full-pipeline treatment. |
| Fewer normalized writes in existing full projector | Directly lowers observed row/WAL cost. | Removing a row or payload can break audit, direct-event reads, or replay. Prove reconstruction and retention before measuring bytes and freshness. |
| Independently checkpointed operational and audit stages in one instance | Can shorten time to operational visibility after historical dependency is removed. | Same total writes and shared I/O may worsen aggregate pressure; both stages and mixed-source audit reads must pass. |
| Kafka Streams stateful maintainer | Partition-local state and changelog recovery can move current-state computation away from SQL scans. | Canonical-input authority, repartitioning of maker effects, external PostgreSQL commit, restore time, and read serving need explicit proof. Prototype only if one-store treatment lacks headroom. |
| Incremental SQL engine for selected reads | Change-driven aggregation may help market/history queries. | Connector/checkpoint and sink guarantees differ; test only with representative read, crash, and retention fixtures. No ledger substitution. |
| Separate audit PostgreSQL instance | Adds independent physical write/I/O capacity after semantic split. | Higher resource/operations cost and cross-store read consistency. Compare fixed total resources and larger-resource configurations separately. |

## Target contracts and first experiment

Keep the existing durable ingress acknowledgement, Go matching lanes, durable
`VenueEventBatch`, canonical PostgreSQL outcome boundary, and full audit source.
Define a versioned **ordered order-effect** record with source batch ID,
partition, contiguous sequence, effect ordinal, schema version, venue session,
instrument, event/command IDs, both affected order IDs, and exact transition
payload. Order effects must derive from immutable canonical facts, not from
event timestamps or current read-model rows. An execution on a resting maker
must advance that maker's state even when another command caused the trade.

For each candidate batch: derive and validate all effects; apply them in source
order; retain every execution/audit fact; coalesce only final current-state
writes where equivalent; atomically commit shadow state, dedupe identities,
and the stage frontier. Record causal coverage per affected order. A partition
minimum or a taker command frontier alone is insufficient to claim that a
maker order or mixed order/event response is current. Quarantine semantic
conflicts and do not advance over sequence gaps. Compare every business field,
including numeric quantities and terminal states, to a quiesced full rebuild.

**Experiment sequence:**

1. Prove effect extraction and cross-partition/lane causality on submit,
   modify, cancel, reject, multi-fill, maker/taker, duplicate, ambiguous-commit,
   and ownership-change fixtures. Include crash/restart with pending dirty
   markers and generation-bound readiness.
2. Run an isolated shadow for parity, including aged history and skewed hot
   orders. Its extra writes make this a correctness run, not the final capacity
   comparison.
3. Freeze a fresh C43-shaped full-pipeline control. In a separate treatment,
   remove the superseded lifecycle recomputation work, rather than adding
   shadow work on top. Compare identical source mix, infrastructure, read load,
   collection barriers, and source code differences. Measure rows/WAL per
   command, lock waits, CPU/I/O, batch residence, queue/backlog trend, and
   same-cohort actual own-order/market visibility. If the control attributes
   little residence or contention to lifecycle, test a proven-safe normalized
   write reduction first instead of assuming the lifecycle change closes the
   gate.
4. Promote only after complete replay/business parity; conservative in-load
   lifecycle/market freshness; no upward backlog trend; crash recovery;
   and the existing separate three-run stopped-source drain headroom gate.
   A stopped-source drain is not proof of concurrent reserve. Preserve every
   failed run in the [throughput ledger](../THROUGHPUT_BASELINES.md).

If the treatment still fails, use the attribution to choose one next lever:
less normalized audit representation on the operational commit, independent
audit checkpointing, or separate physical I/O. A split is admissible only after
current state no longer reads audit timeline rows, both venue and direct-event
audit completeness can be proved, and each required stage meets its own gate.
The same PostgreSQL instance provides no automatic write-capacity gain.

## Settlement workstream and decision gate

Design bounded materialization around a durable trade/obligation cursor and
per-obligation state. Read only affected trade, counterparties, required
resource/account state, and relevant prior obligation facts. Append immutable
facts and complete accounting legs in one transaction with semantic conflict
checks and stage progress. Preserve full-run reconstruction as an offline
reconciliation proof. Keep settled balances, pending obligations,
reservations, and available risk capacity distinct; a lagging display view
cannot become pre-trade authority. Do not choose instrument sharding for
ledger writes before testing accounts that trade across instruments.

Gate with trades/s and facts/ledger entries per trade, not commands/s. Test
growing run history, repeated hot accounts, cross-instrument accounts, netting
windows, failed legs, retries, repairs, and concurrent projection/API load.
Unknown: whether settlement should share the projection instance, and what
bounded state/index shape meets aged-state targets. No settlement capacity
claim follows from C43.

## Decision ownership and remaining unknowns

Architecture owner should approve the ordered-effect contract and the
freshness/retention semantics before an ADR or public API change. No new
component or vendor is selected here. Material unknowns: exact C43 dominant
wait/resource under a fresh matched run; maker-side dependency mapping across
partitions; crash recovery for UNLOGGED queues; whether all historical venue
event fields are reconstructible from retained canonical payloads; direct
admin/protective-event authority and merged ordering; and settlement workload
capacity. Evidence that an incremental treatment fails full-pipeline capacity
or increases hot-order lock contention would change the first-choice design.
