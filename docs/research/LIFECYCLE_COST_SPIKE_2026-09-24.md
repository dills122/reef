# Lifecycle SQL cost spike — 2026-09-24

Status: completed within15-minute timebox, started21:09:19UTC; no deployed SQL change. Decision owner: throughput workstream. Question: why did dedicated batch250 regress, and what measured cost should be targeted next?

## Result

Keep batch500. Claiming dirty rows is not the measured bottleneck. Recompute/write dominates isolated function time. Plan shape changes with batch cardinality:250 repeatedly evaluates grouped results under nested-loop joins;500 uses a mix of hash joins and a cached execution-total result that is still scanned repeatedly. Do not reintroduce generic plans: function retains0056 `force_custom_plan` throughout.

For the same1,000 orders, timing-off control ABBA gave:

| Batch | Calls | Total function time | Claim time | Recompute/write time |
|---|---:|---:|---:|---:|
| 250 | 4 | 220.813ms /195.125ms | 1.490ms /1.365ms | 196.141ms /181.335ms |
| 500 | 2 | 110.831ms /113.022ms | 0.911ms /1.005ms | 103.311ms /104.793ms |

Timings are nested/inclusive: do not sum function and child statement durations. Node-timing diagnostic first250 call had execution aggregate250loops and event aggregate250loops. First500 call instead materialized the execution aggregate result and scanned it500times; event state used a hash join. Claim scan under1ms per call; no temp spill in inspected plans. No need to assume lock contention explains this isolated difference.

## One tested candidate — rejected

Only candidate: add `AS MATERIALIZED` to bounded `execution_totals` and `order_event_state` CTEs. Function definition replacement occurred inside each transaction and rolled back. Claim locks, separate fresh-snapshot recomputation, source predicates, state formulas and dirty invalidation remained unchanged.

| Batch | Baseline time per1,000 | Candidate time per1,000 | Decision |
|---|---:|---:|---|
| 250 | 219.445 /208.588ms | 157.375 /157.326ms | Improves this arm, still slower than baseline500 |
| 500 | 117.871 /118.191ms | 154.612 /151.816ms | Regression; reject candidate |

All9candidate-experiment arms processed exactly1,000orders and matched every lifecycle business field against original state except intentionally excluded `updated_at`. Function-definition hash, complete selected lifecycle rows (including timestamps), both dirty queues and market snapshot hashes matched before/after rollback. This is scoped parity, not full-source rebuild/recovery qualification.

PostgreSQL documents that side-effect-free CTEs can be folded into their parent; `MATERIALIZED` prevents folding but can inhibit useful optimization. The mixed result here is evidence against applying it indiscriminately. [PostgreSQL16 CTE materialization](https://www.postgresql.org/docs/16/queries-with.html#QUERIES-WITH-CTE-MATERIALIZATION).

## Method and limits

Host603319154, c32, retainedC24 projection DB; all runtime writers stopped. Same source/image asC23/C24 (`a1f3ec30dfb89b844d1c7ae4094406c95c4550c00582fd2060d445dabcf82b4e`); parent source/base identity remains in [baseline ledger](../THROUGHPUT_BASELINES.md). One stable cohort: first1,000 lifecycle order IDs sorted,64instruments,5statuses. Each arm inserts dirty rows and temporarily changes `last_event_at` to force real writes; all mutations and experimental function DDL roll back.40s statement timeout,3s lock timeout,360s process ceiling.

Session-local auto_explain records nested statements, buffers/WAL, timing-off comparison arms; separate timing-on arms identify plan structure. Multiple calls share one transaction per arm, so results exclude per-call production commit/network/pool costs and do not measure production lock occupancy. Warm sequential arms and synthetic rebuilds are not live-load reproduction. Rollback preserves logical contents, not physical WAL/cache/dead-tuple state. Initial merged stdout/stderr capture interleaved JSON; original attempt preserved, repeated with separate streams, and only intact v2/candidate logs used.

Private host reproduction/evidence:
- `/home/reefbench/benchmarks/lifecycle-cost-spike-v2.py`
- `/home/reefbench/benchmarks/lifecycle-cost-spike-20260924-v2/{probe.sql,results.log,private-plan.log,aggregate.json}`
- `/home/reefbench/benchmarks/lifecycle-materialized-spike.py`
- `/home/reefbench/benchmarks/lifecycle-materialized-spike-20260924/{probe.sql,results.log,private-plan.log,aggregate.json}`

Raw plans may contain business literals and remain private. [Sanitized evidence](../evidence/lifecycle-cost-spike-2026-09-24.json) retains aggregate timings, hashes and decision. Full sanitized node trees retained in local durable artifacts. No new throughput test or independent review in this spike.

## Next action

One narrower candidate at retained batch500: reshape the execution-total join to avoid repeated scans of the grouped result while preserving existing hash-join advantages elsewhere. First require exact scoped parity and improvement over approximately118ms/1,000orders in the same rollback comparison; reject on regression. Only a winner earns focused race/parity tests and a fresh five-minute10k run. Do not ship the two-CTE candidate, force global planner settings, alter observation cadence, or weaken freshness thresholds. Concurrent bottleneck attribution and sampler completeness remain unresolved.
