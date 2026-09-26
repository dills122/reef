# SQL and data architecture audit — 2026-09-25

Status: read-only audit of `codex/sql-data-architecture-audit` at
`0d2cda3f74713023c131e02571caa181ba87e627`, created from fetched
`origin/master`. No schema, service behavior, or benchmark configuration changed.

## 1. Executive assessment

Reef's promoted durable path has useful separation: broker acknowledgement owns
`202 Accepted`; Go matching publishes a transactional venue event batch; PostgreSQL
materializes compact canonical batch/outcome rows; a separate database normally
holds normalized read models and dirty-driven lifecycle/market projections.
Per-partition ordering, canonical sequence uniqueness, semantic batch checksums,
projection batch claims, and targeted replay guards protect this boundary.
Those are material strengths, not reasons to treat every derived table as truth.

Three correctness risks need priority: settlement `ON CONFLICT DO NOTHING` can
silently accept a conflicting fact under concurrent/cross-run writes; timeline
event replay can discard a changed event ID while consuming a trace sequence;
and PostgreSQL crash truncates UNLOGGED dirty queues without an automatic
downstream rebuild before freshness is reported. Source proves these paths are
possible; this audit did not observe a production incident.

Capacity remains separate by stage. C5 proves roughly 10k commands/s for venue
core with projections disabled. C4/H4 establish roughly 2.5k/s for sustained
full-projection accounting under their recorded checks. C43 accepted and
materialized 2,992,504 commands in a 300-second 10k-offered diagnostic, but its
later projector collection was 2,967,975 with 26,029 lag and the frozen
full-pipeline freshness/cohort checker failed. Postdrain equality did not turn
that into a timed pass. The largest measured data cost is combined normalized
projection writes and their WAL/maintenance; no one index or SQL microchange is
proved to close the gate. Sources: [baseline ledger](../THROUGHPUT_BASELINES.md),
[C43 evidence](../THROUGHPUT_BASELINES.md#c43--broad-runtime-event-order-time-index-removed-full-pipeline-timed-fail),
[structural seam decision](PROJECTION_STRUCTURAL_SEAM_DECISION_2026-09-25.md).

**Scope and method.** Inspected active runtime, settlement, boundary, and
command-log migrations; Kotlin persistence and worker paths; public read routes;
current projection research; and original summarized C28–C43 plans/counters.
Arena, stock-data, analytics, auth and admin schemas received an ownership and
growth pass, not an exhaustive query-by-query plan review. Read-only local
PostgreSQL catalog and one bounded selector `EXPLAIN (ANALYZE, BUFFERS)` were
captured. Both running databases had migration `runtime/0067` installed, which
is absent from this `0066` source checkout. Their 449,954 primary outcomes and
450k-row plan are **not** a version-matched performance test. No destructive
reset, load test, or data mutation was performed. Severity below rates the
specified trigger, not an observed incident.

## 2. Critical data paths and approximate database work

| Workflow | Authority and transaction | Database work / round trips | Scale note |
| --- | --- | --- | --- |
| Direct durable order command | Boundary validates then broker acknowledges; only then API sends `202`. Go lane commits command offset with durable `VenueEventBatch`. | No canonical PostgreSQL write before acceptance in the measured no-DB direct profile; optional boundary idempotency/risk/capture stores add configuration-dependent calls. | Never replace broker durability with an earlier DB or memory acknowledgement. [Contract](../COMMAND_INTAKE_PROCESS.md) |
| Canonical materialization | Materializer fetches `read_committed` batches; one PostgreSQL transaction applies ordered batch functions; source offset acknowledgement follows commit. | [Group call](../../services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt#L3785) is one JDBC statement; [active function](../../scripts/dev/db/migrations/runtime/0064_fail_closed_batch_outcome_insert.sql#L4) inserts one batch header plus one outcome per command, with conflict/count checks. | One outcome/command plus one header/batch; source replay is idempotent while the identity remains in hot tables. |
| Canonical-to-normalized projection, separated stores | Canonical members read after projection watermark; identity claim, status/fill, timeline, dirty enqueue, watermark and claim completion commit on projection DB. | At least one watermark read, one candidate read per owned partition, then claim cleanup, claim, persistence, watermark batch and completion calls plus commit. Status/fill touches `submit_results`, accepted `orders`, `executions`, `trades`, dirty orders; timeline touches `runtime_events` and payloads. Row count varies with fills/fanout. [Kotlin path](../../services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt#L2780), [claim contract](../DECISIONS.md#d-055-retry-safe-canonical-projection-batch-claims) | Retry authority and effect rows share one projection transaction. Two database instances have no cross-store MVCC snapshot; claim/watermark protect replay rather than synchronous visibility. |
| Lifecycle and market | Dirty order lock → fresh snapshot of order, all matching executions/events → lifecycle upsert → dirty instrument; dirty instrument lock → indexed best-price lookup/level SUM → snapshot upsert/delete; each function call commits its work. | One stored-function JDBC call per batch, with nested indexed reads and row mutations. A market worker can call lifecycle first, adding a caller. [Lifecycle SQL](../../scripts/dev/db/migrations/runtime/0052_projection_dirty_serialization.sql#L213), [market SQL](../../scripts/dev/db/migrations/runtime/0057_market_data_idle_metadata.sql#L6) | Recalculation depends on per-order event history and open orders at selected price. Dirty queues coalesce repeat work, but are UNLOGGED. |
| Trade-to-settlement and operator actions | Trade/order read plus scenario fact bundle; `appendFacts` validates against existing run then commits all new fact families in one settlement transaction. Ledger balance is derived from immutable entries. | Each append runs **18** run-scoped SELECT statements, then up to 18 batched INSERT families for nonempty lists. No per-trade constant-size read. [Store](../../services/platform-runtime/src/main/kotlin/com/reef/platform/application/settlement/SettlementFactStore.kt#L841), [trade materializer](../../services/platform-runtime/src/main/kotlin/com/reef/platform/application/settlement/TradeSettlementObligationMaterializer.kt#L205) | Repeated full-run reads grow with scenario history; current simulator run sizes do not establish high-volume settlement capacity. |
| Public history reads | Derived `runtime.trades` and mixed `runtime_events` tables are queried and fully serialized when no `limit` is supplied. | `/trades` and `/events` default to unbounded ORDER BY queries; `events` also joins payload side table. [Routes](../../services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServer.kt#L1180), [SQL](../../services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt#L5042) | DB sort/fetch, JVM hydration and response bytes grow with whole history. |

For scale, C42 recorded about 19.96 million projection inserts for about 3.00
million accepted commands: **about 6.65 inserts/command** in that fixture,
before counting updates/deletes or canonical writes. At a hypothetical sustained
10k commands/s with identical mix that implies about 66.5k projection inserts/s.
C43 recorded 5,658.69 projection WAL bytes/accepted command; the same hypothetical
rate is about 56.6 MB/s, or 4.9 TB/day of projection WAL. These are linear
workload extrapolations, not a measured 24-hour run or causal estimate of a
single table. [Source](PROJECTION_STRUCTURAL_SEAM_DECISION_2026-09-25.md#evidence-boundary-and-present-path).

## 3. Critical findings

### F01 — HIGH: settlement conflict can acknowledge a fact that was not stored

**Category / status:** Correctness, transactions; confirmed code path, concurrent
incident unobserved. **Evidence:** [appendFacts](../../services/platform-runtime/src/main/kotlin/com/reef/platform/application/settlement/SettlementFactStore.kt#L841)
reads the run, validates merged facts, and commits. Each insert family uses
`ON CONFLICT` on its identity column with `DO NOTHING`, including [obligations](../../services/platform-runtime/src/main/kotlin/com/reef/platform/application/settlement/SettlementFactStore.kt#L937)
and [ledger entries](../../services/platform-runtime/src/main/kotlin/com/reef/platform/application/settlement/SettlementFactStore.kt#L1278).
PK identity is global while validation reads only `scenario_run_id`.

**Current behavior / impact:** A pre-existing same ID in another run is invisible
to validation; its insert is skipped and `appendFacts` returns the requested
bundle. Two same-run writers can also validate from snapshots before either
commits; a conflicting winner is silently accepted by the loser. API operator
paths return success using the requested bundle. Business/audit/ledger state can
therefore differ from acknowledged facts. **Trigger:** ID collision or concurrent
materialization/operator retry. **Direction / benefit:** Serialize only the
affected run/obligation, insert with `RETURNING`, and compare every skipped ID
to the stored immutable fact before success; add semantic uniqueness where one
fact per trade/attempt is required. Keep multi-table commit atomic. Removes
silent acceptance; may add narrow contention. **Confidence:** High. **Validation:**
Two-connection same-ID/different-payload and cross-run-ID tests, including
ambiguous commit; assert API response and all fact/ledger rows.

### F02 — HIGH: PostgreSQL crash can erase pending projection invalidations

**Category / status:** Recovery, projection; confirmed recovery gap by source,
crash fixture required. **Evidence:** [migration 0042](../../scripts/dev/db/migrations/runtime/0042_unlogged_projection_dirty_queues.sql)
makes both order and market dirty queues UNLOGGED. [Workers](../../services/platform-runtime/src/main/kotlin/com/reef/platform/api/OrderLifecycleProjectionWorker.kt#L42)
and [market worker](../../services/platform-runtime/src/main/kotlin/com/reef/platform/api/MarketDataProjectionWorker.kt#L29)
poll those queues; their `start()` paths do not rebuild them. Full rebuild APIs
exist but are operator calls. [0057 idle metadata](../../scripts/dev/db/migrations/runtime/0057_market_data_idle_metadata.sql#L41)
uses empty queues as one freshness premise.

**Current behavior / impact:** An unclean PostgreSQL restart truncates UNLOGGED
queues. A committed status/timeline projection and watermark can survive while
its unprocessed dirty marker does not. Incremental workers then have no work,
leaving order state/market snapshot stale until full rebuild or re-invalidation;
an empty-queue check alone cannot prove freshness. **Trigger:** PostgreSQL crash
or unclean restart with pending markers. **Direction / benefit:** Persist a
postmaster-generation/recovery epoch and gate downstream readiness after restart;
reseed dirty order/instrument work from durable projection facts/frontiers or run
verified full rebuild before serving fresh metadata. Retain UNLOGGED queues only
with tested recovery. **Confidence:** High for failure mode, Medium for deployed
impact. **Validation:** Crash projection PostgreSQL after status commit and before
lifecycle/market consume; restart; assert full-row reference equality and
freshness gate before service readiness. The command-log UNLOGGED work queue has
[bootstrap reconstruction](../../services/platform-runtime/src/main/kotlin/com/reef/platform/api/PostgresCommandLogBootstrap.kt#L215);
that precedent is absent here.

### F03 — HIGH: timeline event ID conflicts are silently skipped

**Category / status:** Correctness, ordering, audit; confirmed code path,
changed-ID fixture required. **Evidence:** Active [timeline SQL](../../scripts/dev/db/migrations/runtime/0043_runtime_event_payload_cold_table.sql#L297)
allocates legacy trace sequence values before `runtime_events` and payload
`ON CONFLICT (event_id) DO NOTHING` inserts. Direct [saveEvents](../../services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt#L4714)
does the same in a transaction. By contrast, [0059](../../scripts/dev/db/migrations/runtime/0059_trade_replay_and_parse.sql)
rejects changed submit-result and trade replays.

**Current behavior / impact:** Reusing an event ID with changed header/typed
facts/payload can leave old data in place without an error. An identical retry
of a legacy/direct event can advance its per-trace allocator even when no event
row is inserted. A later payload-side insert may also fill a previously empty
payload for an existing event ID. Mixed venue/admin event reads can report
incomplete or inconsistent audit history. **Trigger:** redelivery, ambiguous
commit retry, or ID collision. **Direction / benefit:** Validate immutable
event header, typed facts and payload on replay, reject differences, and
allocate legacy sequences only for IDs proven new within the same transaction.
Test deterministic and direct producers separately. **Confidence:** High.
**Validation:** Identical/changed retries, concurrent absent IDs, mixed traces,
missing payload, and ambiguous-commit fault tests; compare event rows and trace
sequence continuity.

### F04 — HIGH: normalized projection work misses sustained full-pipeline gate

**Category / status:** Projection, throughput; confirmed measurement, precise
root cause unresolved. **Evidence:** [C43](../THROUGHPUT_BASELINES.md#c43--broad-runtime-event-order-time-index-removed-full-pipeline-timed-fail)
left 26,029 later-collected projector lag and failed downstream freshness at
10k-offered/300s despite exact source materialization. [C42](../THROUGHPUT_BASELINES.md#c42--lock-only-dirty-conflicts-write-target-removed-full-pipeline-timed-fail)
logged about 19.96M projection inserts and 17.47GB WAL. [C30](PROJECTION_CANONICAL_SQL_SPIKE_2026-09-24.md#result-and-decision)
attributed the largest nested time/WAL to combined status/fill, but its nested
profiler perturbed workload. [Aged C32 status-only](PROJECTION_STATUS_FILL_AGED_PROFILE_2026-09-25.md)
exceeded 53k synthetic outcomes/s without full pipeline.

**Current behavior / impact:** Multiple normalized facts, payloads, indexes and
dirty work per command create WAL/I/O and concurrent stage pressure; exact
source/projected counts after drain do not bound p95/p99 visibility. **Trigger:**
current 10k full-pipeline fixture; aged/high-fill loads may alter cost.
**Direction / benefit:** Measure same-prefix stage residence plus PostgreSQL
wait/I/O and per-target WAL under a fresh matched full control. Trial one
semantics-preserving reduction in rows/bytes or lifecycle recomputation while
retaining full stage, claim and replay. Do not split workers or remove an index
on a zero-scan count alone: C31 rejected the submit-result index ablation and
C43's 2.8% WAL reduction did not pass. **Confidence:** High for gate failure,
Medium for mechanism. **Validation:** Frozen 300s C43-shaped matched
control/treatment, complete cohort and business/replay parity, in-load 5s/10s
freshness and 20% stopped-source drain margin.

### F05 — HIGH: default public history reads are unbounded

**Category / status:** SQL, data movement; confirmed query shape, load impact
not measured. **Evidence:** [routes](../../services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServer.kt#L1180)
use `queryLimit(..., 0)` and call all-row `api.trades()` / `api.events()` when
no positive limit is supplied. [SQL](../../services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt#L5042)
orders entire trades history; [event SQL](../../services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt#L5234)
orders all events and joins payloads. These calls hydrate lists and serialize
complete JSON responses.

**Current behavior / impact:** One read scales with total retained rows and
payload bytes, can sort/spill, monopolize a pool connection, and amplify JVM
memory/network cost. **Trigger:** historical growth or repeated no-limit reads;
1M commands arrive in 100 seconds at hypothetical 10k/s, while event/trade
fanout varies. **Direction / benefit:** Bounded default/max plus stable keyset
cursor and indexed order matching each route; keep explicit bulk export behind
an operational path. Removes whole-history request cost. **Confidence:** High
for query shape, Medium for observed severity. **Validation:** `EXPLAIN
(ANALYZE, BUFFERS)` at aged 10M/100M rows, concurrent read p95/p99 and response
memory, cursor completeness under concurrent inserts.

### F06 — MEDIUM: canonical hot storage duplicates retained payload bodies

**Category / status:** Storage, WAL; confirmed footprint, design change requires
replay proof. **Evidence:** [batch table](../../scripts/dev/db/migrations/runtime/0010_venue_event_batch_materialization.sql#L1)
retains full `payload_json`; [outcome table](../../scripts/dev/db/migrations/runtime/0010_venue_event_batch_materialization.sql#L22)
retains each `result_payload`. C39 recorded approximately 4.756GB of outcome
table and 0.968GB of batch table for 2.982M commands; 0064 changes parsing,
not retained shape. [Ledger](../THROUGHPUT_BASELINES.md#c39--canonical-batch-sql-index-treatment-on-clean-c38-topology-timed-fail).

**Current behavior / impact:** At C39's specific mix, roughly 1.92kB of these
two tables per command, including their indexes/TOAST. A hypothetical 10k/s
for 24h at that density is about 1.66TB of new table/index footprint before
WAL, vacuum, replicas or archive. This is an extrapolation, not observed daily
growth. **Trigger:** longer retention, higher accepted rate, larger fills.
**Direction / benefit:** Preserve immutable full replay authority, then test a
versioned compact hot locator/typed projection plus bounded full-payload
retention or verified immutable archive. Do not delete either JSON body until
checksum, old-batch compatibility, point read and replay parity hold. **Confidence:**
High for cost, Medium for safe savings. **Validation:** Bytes/command, WAL,
TOAST/index size and read plans on identical full-pipeline fixtures, plus
archive/replay and checksum fault cases.

### F07 — MEDIUM: lifecycle refresh recomputes per-order history

**Category / status:** SQL, projection; potential scaling risk, current indexed
plans partly mitigate. **Evidence:** [0052 lifecycle SQL](../../scripts/dev/db/migrations/runtime/0052_projection_dirty_serialization.sql#L247)
aggregates all `executions` for selected dirty orders and scans/order-ranks
their `runtime_events` for latest modify and terminal flags each refresh.
`0056` correctly forces custom plans after measured generic-plan regression.

**Current behavior / impact:** Work grows with each affected order's execution
and event history, even if only one new fill arrived. C32 saw p95 five and max
15 executions/order, so current fixture does not prove this dominates; C28's
materializer-to-lifecycle residence is a stronger full-pipeline lead than
isolated SQL timing. **Trigger:** deep/aged orders, repeated maker fills, skew.
**Direction / benefit:** First measure loops/buffers/rows per dirty order under
full load. Trial an isolated per-order incremental accumulator only with
same-lane or explicit dependency ordering, replay identity and full-rebuild
equivalence. Do not introduce a new hot account/order row without contention
evidence. **Confidence:** Medium. **Validation:** Aged 10M/100M event fixtures,
hot-order skew, same-prefix freshness and exact full-row comparison.

### F08 — MEDIUM: market refresh recomputes hot best-price levels

**Category / status:** SQL, contention; potential scaling risk. **Evidence:**
[0057](../../scripts/dev/db/migrations/runtime/0057_market_data_idle_metadata.sql#L59)
uses side-aware indexed best-price lookups, then aggregates all remaining orders
at that price and upserts one snapshot per instrument. Dirty instrument identity
coalesces work and bounds rows, but serializes competing refreshes for one
instrument.

**Current behavior / impact:** A deep same-price book makes each refresh scan
many open orders; price-level churn raises snapshot/dirty-row conflict rates.
This does not establish current 10k failure cause. **Trigger:** few hot
instruments or deep best levels, rather than C43's evenly spread 64-instrument
fixture. **Direction / benefit:** Compare exact best-level index plan, rows and
lock wait under skew. Consider a versioned price-level aggregate only if
incremental update, cancel/modify and replay semantics are proven and full
pipeline improves. **Confidence:** Medium. **Validation:** 80/20 and one-hot-book
loads; best-level `EXPLAIN (ANALYZE, BUFFERS)` and snapshot parity.

### F09 — MEDIUM: settlement append rereads every fact in its run

**Category / status:** Data movement, transactions; confirmed shape, workload
impact unmeasured. **Evidence:** [appendFacts](../../services/platform-runtime/src/main/kotlin/com/reef/platform/application/settlement/SettlementFactStore.kt#L846)
calls [factsByScenarioRunId](../../services/platform-runtime/src/main/kotlin/com/reef/platform/application/settlement/SettlementFactStore.kt#L883),
which performs 18 independent run queries before each append. Run/time indexes
exist, but every prior row still crosses JDBC and is revalidated.

**Current behavior / impact:** For many small append calls in one run, total
rows read are O(n²); 18 round trips also lengthen the write transaction and
hold its connection. **Trigger:** high-volume settlement replay or repeated
operator actions in a long run; small P2 scenarios are not capacity proof.
**Direction / benefit:** Retain full-bundle validation for offline proof while
using run/obligation-scoped incremental checks and DB-enforced identities for
online append; compare immutable parent facts inside same transaction. Reduces
read volume without weakening ledger balance. **Confidence:** High for shape,
Medium for load impact. **Validation:** Append 1k/10k trades in one run, count
JDBC statements, rows transferred, transaction duration, and full proof parity.

### F10 — MEDIUM: exact financial values remain text at storage boundary

**Category / status:** Schema, integrity; architectural concern. **Evidence:**
[settlement obligations](../../scripts/dev/db/migrations/settlement/0001_p2_exception_facts.sql#L6)
store `quantity`/`cash_amount` as `TEXT`; [ledger](../../scripts/dev/db/migrations/settlement/0002_instant_finality_facts.sql#L18)
stores `quantity` as `TEXT`. [Validation](../../services/platform-runtime/src/main/kotlin/com/reef/platform/application/settlement/SettlementFactStore.kt#L1970)
checks obligation amounts for nonblank but does not parse those two values
there; ledger quantities and balance equality receive stronger app checks.
Runtime orders/executions/trades use text plus NUMERIC shadow columns/triggers.

**Current behavior / impact:** DB cannot enforce decimal domain/range/scale or
text-shadow equivalence as a simple native constraint; casts and duplicate
representation add write and migration cost. No float/real/double financial
column was found in reviewed hot schemas. **Trigger:** new writer/repair path,
larger amount range or schema migration. **Direction / benefit:** Define
instrument/currency scales and bounded exact domain first; add validated typed
columns and checks, backfill with rejection ledger, dual-read parity, then
cut over. Fixed-point BIGINT is only an option with explicit tick/minor-unit
contract and overflow proof. **Confidence:** High for schema, Medium for impact.
**Validation:** Invalid/overflow/scale fixtures, text↔numeric parity scan,
ledger/replay equivalence and write-cost A/B.

## 4. Cross-cutting index, transaction and operations review

- **Index architecture:** `0061/0062` enforce and consolidate canonical
  `(partition_id, stream_sequence)` uniqueness/coverage. `0055` adds a partial
  currency access path; `0056` addresses parameter-sensitive lifecycle plans;
  `0066` removes a measured zero-scan broad event index. C38 cut projection
  `submit_results` sequential scans from 2,584 to 43 without clearing the
  source/materializer deficit. C31's time-index removal failed its predeclared
  gate despite zero observed scans. Preserve these negative experiments; make
  future index choices from matched query plans **and write/WAL cost**.
- **Query plans:** A read-only local 449,954-row primary fixture used
  `idx_canonical_command_outcomes_partition_seq` for a 500-row partition
  selector: 1.204ms execution, 13 shared hits and 104 reads. It had `0067`
  installed and selected only four scalar columns. It cannot price this
  checkout's full JSON candidate read, C43 contention or sustained capacity.
  `pg_stat_statements` was available but not installed in that local instance;
  hosted C30 nested tracking perturbed load. Measure against exact source,
  configuration and aged fixture before making a plan claim.
- **Transaction boundaries:** Canonical batch header/outcomes share a statement
  transaction; source offset ack follows commit. Projection claim/effects/
  watermark share one projection transaction. Settlement append uses one
  transaction but its pre-insert validation is not protected against another
  writer for the same run. No cross-database atomicity is assumed between
  canonical and projection stores.
- **Pools and contention:** [Hikari pools](../../services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/RuntimeDataSources.kt#L13)
  have role defaults and 2s connection timeout; [Compose](../../compose.base.yml#L178)
  defaults global max 48 with role overrides. Local PostgreSQL caps are 200
  primary/160 projection; C43 used a separately recorded 512/512 topology.
  Per-process maxima must be summed over actual API/materializer/projector
  replicas before promotion. No observed pool starvation was established here.
- **WAL/MVCC/retention:** 0065 removed repeat dirty tuple updates but C42
  still wrote 17.47GB projection WAL. Inspect WAL, dead tuples, autovacuum,
  temp files, table/index/TOAST sizes and lock waits together. Canonical,
  runtime-event, trade and command-result archive target tables exist; they
  are not proof of an active move/retention policy. If archive movement is
  enabled, validate replay/uniqueness across hot and archive: hot-only
  uniqueness does not automatically span time-partitioned archive tables.
- **Other domains:** Boundary idempotency/guardrails are configuration-dependent
  pre-acceptance reads/writes; do not add a scan to the direct ingress path.
  Command-log active queue is UNLOGGED but has explicit bootstrap reconstruction
  and integrity audit views. Arena, stock-data and analytics are separate
  persistence domains and were not shown to dominate the measured venue path.
  `account` durable balance/hold schema remains planned, so do not describe
  Reef's simulation settlement ledger as a live brokerage balance authority.

## 5. Prioritized roadmap

| Tier | Work | Exit evidence |
| --- | --- | --- |
| 1 — correctness and safety | F01 settlement conflict/serialization; F03 event replay and trace sequence; F02 dirty-queue crash recovery/readiness. | Fault/concurrent PostgreSQL tests, no silently skipped fact, exact replay, verified post-crash lifecycle/market rows before fresh reads. |
| 2 — hot path | F04 matched full-pipeline attribution and one work-reduction treatment; F05 bounded history reads. | Frozen C43-shaped gate and aged read p95/p99, full business/replay parity, no extra acceptance work. |
| 3 — scaling | F06 canonical payload footprint/retention; F07 lifecycle and F08 market under aged/skewed books; F09 settlement incremental validation; pool/WAL budget. | Bytes/command, rows/operation, locks, plan buffers, headroom and replay at 10M/100M+ rows. |
| 4 — secondary | F10 typed financial migration and domain checks; targeted archive and observability improvements. | Validated backfill, precision/overflow proof, schema parity, rollout/rollback plan. |

### Top 10 database/data bottlenecks

Ranked by correctness first, then likely tail latency and sustained capacity;
status distinguishes proof from hypothesis.

| Rank | Finding | Status | Dominant consequence |
| ---: | --- | --- | --- |
| 1 | F01 settlement silent fact conflict | Confirmed code path | Acknowledged financial/audit fact can be missing. |
| 2 | F02 UNLOGGED dirty-queue crash gap | Confirmed failure mode; crash test needed | Read models can remain stale with empty queues. |
| 3 | F03 timeline replay conflict/sequence gap | Confirmed code path | Audit facts and ordering can diverge. |
| 4 | F04 full normalized projection cost | Measured gate failure; cause unresolved | 10k full-pipeline freshness and headroom fail. |
| 5 | F05 unbounded public event/trade reads | Confirmed query shape | Whole-history DB/JVM/network cost. |
| 6 | F06 duplicated canonical payload footprint | Measured storage, candidate design | WAL/retention growth. |
| 7 | F07 lifecycle historical recomputation | Scaling risk | Aged/hot order latency variance. |
| 8 | F09 settlement full-run validation reads | Confirmed O(n²) shape | Transaction and round-trip growth. |
| 9 | F08 hot market price-level aggregation | Scaling risk | Skewed-book freshness/row contention. |
| 10 | F10 text exact-value representation | Architectural concern | Weak DB validation and dual-representation cost. |

### Highest-value experiments

1. **Correctness fixture:** concurrent settlement append with same ID and
   different payload, both same-run and cross-run; force ambiguous commit and
   assert persisted rows and API response. Run event-ID identical/changed replay
   with legacy trace allocator and direct save path in the same disposable DB.
2. **Recovery fixture:** stop PostgreSQL uncleanly after committed normalized
   projection has enqueued order/market dirt; restart without replaying source.
   Compare every lifecycle/market business field to quiesced rebuild and test
   readiness/freshness metadata before and after recovery.
3. **Full-pipeline control:** reproduce C43's source/image/fixture/settings or
   record every difference; capture same-prefix residence, `pg_stat_activity`
   waits, `pg_stat_wal`, table/index sizes, HOT/dead tuples, `pg_stat_statements`
   with measured observer cost, and low-rate `auto_explain` nested plans. Change
   one demonstrated write/recompute cost only after the control.
4. **Aged read/settlement scale:** `EXPLAIN (ANALYZE, BUFFERS)` for `/trades`,
   `/events`, lifecycle order history and market best-level SQL at 10M/100M
   rows; 1k/10k fact append calls in one settlement run. Report rows, loops,
   spills, connections, response memory and p95/p99 under read/write overlap.

No latency budget beyond recorded targets is assigned: C43 has HTTP intake
p95/p99 78.06/122.08ms, while full downstream p95 is unqualified. Report each
future stage separately rather than subtracting snapshots collected at different
times or treating conservative coverage bounds as actual per-record latency.
