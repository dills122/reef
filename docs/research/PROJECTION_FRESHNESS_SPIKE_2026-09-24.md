# Projection freshness spike — 2026-09-24

Status: bounded research complete; experiment recommendation, not a performance result or architecture change.

## Decision

Run the built worker-group candidate at existing batch500 first, then change **only dedicated lifecycle batch500 → 250**. Keep market batch500, canonical batch500, four dedicated lifecycle loops, one market caller, 16 canonical writers, c-32, workload and observation cadence fixed. Consolidation is an ownership/accounting change with unmeasured scheduling effects, not a proven throughput optimization. Do not combine it with two batch changes and attribute the result to smaller transactions.

Owner: throughput workstream maintainer under user's existing testing authorization. Scope: C22 aggregates, historical results, relevant implementation and PostgreSQL16 primary documentation. Stop condition: identify next controlled comparison and rejection criteria. No runtime/configuration changes or new load during this spike. Existing reference verification was allowed to finish.

## Evidence and limits

**Observation — C22:** 10,000/s scheduled for300s on32vCPU/64GB; 2,999,950 accepted/materialized/canonical at final observation; zero failures/retries/deadlocks; final queues empty. Final totals include drain and are not independent in-load service-rate proof. Source-cohort membership passes. Source-to-canonical p95 upper bound1386ms, lifecycle6711ms, market7720ms. Frozen p95 limit5000ms fails; p99 limits10000ms and max30000ms remain unchanged. Four lifecycle-maintainer processes additionally violate singleton qualification assumptions.

C22 full business reference completed during spike: lifecycle2,471,663/2,471,663 and market64/64 exact; wrapper's before/after fact hashes match. This proves reference parity, not freshness. Retained aggregate: [C22](../evidence/throughput-10000-capacity-2026-09-24.json). Private raw evidence: `/home/reefbench/benchmarks/reef-productive-sixteenwriters-10000-300s-3`; report SHA256 `eef114c66f02ee21b461f3e94d2fc9f8179f3432c410486fd01a93899fdf13b4`; runtime image `sha256:1666e9727bb48421a8c81b8d072bdad568ee349805ac773ee82fa7ebcc67c712`. Base commit `0dd72ba9ee703c29446dee8750760e0ac6bd39ec`; candidate includes uncommitted changes, so image/source manifest in evidence is identity authority.

**Code fact — measurement:** `DownstreamProjectionInstrumentation.observe` records a committed frontier, waits until oldest lifecycle dirtied time passes that observation (or queue empties), then requires a second market barrier. `cohort-residence.mjs` assigns source batches their first covering observation and weights by command count. These are conservative prefix-completion bounds. Queue-age samples are a different population and clock interval. Do not subtract p95 values, replace command-weighted latency with queue age, or declare measurement overhead explains the whole miss. Exact processing-versus-proof-delay decomposition remains unknown.

**Code fact — shared contention point:** migration0052 claims lifecycle orders using oldest-first `FOR UPDATE SKIP LOCKED`, recomputes state, then upserts dirty market instrument keys before committing. Market function claims instrument keys and holds them through recomputation. Dedicated consumers can claim different orders yet converge on the same instrument rows. Zero deadlocks does not mean zero lock waits. C22 lacks enough attributed lock-wait evidence to establish this as dominant cost.

**Code fact — batch units differ:** lifecycle batch counts orders; market batch counts instruments. C22 uses64 instruments, so both market limits250 and500 can claim all64. However `PostgresRuntimePersistence.projectMarketDataSnapshots` first invokes lifecycle with the same batch size, then reads source status and runs market SQL. Reducing market batch to250 changes this helper's lifecycle work while leaving instrument ceiling effectively unchanged. Market worker sleeps250ms after every call. Do not call the approximately1s difference between stage p95 values a measured market service time.

**Historical observation:** C8–C12 had large downstream backlogs and ~31GB projection temp spills; C12 canonical batch500 eliminated spills. C13 custom lifecycle plans substantially improved isolated queries; C14 then drained5k queues. C17 increasing canonical batch to1000 restored26GB temp spill and still failed. C21 eight writers nearly matched7.5k upstream but lifecycle backlog grew. C22 expanded topology reaches final counts with projection temp0. These results support targeted latency work, not reopening generic memory tuning or claiming the layer was never researched. See [baseline ledger](../THROUGHPUT_BASELINES.md).

## Primary research

Sources consulted2026-09-24; PostgreSQL16 matches repository Compose major version.

- [SELECT locking](https://www.postgresql.org/docs/16/sql-select.html#SQL-FOR-UPDATE-SHARE): SKIP LOCKED supports queue consumers but skips unavailable rows; it does not remove contention from later writes. **Inference:** more consumers need not shorten the oldest outstanding prefix.
- [Explicit locking](https://www.postgresql.org/docs/16/explicit-locking.html): locks normally last until transaction end; consistent acquisition order helps prevent deadlocks. **Inference:** smaller lifecycle batches may shorten lock occupancy, but extra transactions and repeated instrument invalidations may reduce capacity. Preserve sorted acquisition and atomic dirty/state updates.
- [Read Committed](https://www.postgresql.org/docs/16/transaction-iso.html): successive statements may see later committed state. Preserve existing separate claim/recompute statements and VOLATILE behavior; combining statements as an optimization risks snapshot semantics.
- [EXPLAIN](https://www.postgresql.org/docs/16/sql-explain.html): ANALYZE executes statements and adds profiling overhead. Use existing low-cost metrics first; if attribution is needed, one separate diagnostic run with rollback-safe plans and `TIMING OFF`, not profiling added to a qualifying run.

## Options and next gate

| Option | Benefit hypothesis | Risk / decision |
|---|---|---|
| New group, four loops, all batches500 | Complete caller accounting in one process | First control run; JVM/pool placement changes can affect performance |
| Dedicated lifecycle250, market500 | Shorter order-claim transactions | Next comparison; retain only if bounds improve without throughput/backlog regression |
| Market batch below64, e.g.16 | Actually reduces instrument lock set | Conditional next experiment if shared-key contention persists; lexicographic claim order and hot-key starvation require checking |
| More workers / larger batches | More concurrent work | Defer: can amplify shared-row contention; historical spill regression |
| Faster sampler / relaxed SLO | Changes proof timing or pass threshold | Excluded; keep frozen1000ms observation cadence and limits |

Run each control/treatment with identical fresh-fixture setup,10k/s for300s and existing drain bound. Preserve exact source/image/config manifests and all failed results outside report roots. Verify actual four dedicated loops plus nested market lifecycle caller, caller completion/failure accounting, full partition coverage, source membership, final counts, and queue tails. Record queue-age trajectory, per-call timing, CPU/temp/retries and unchanged cadence. A fresh run is a comparison, not a restored identical-dataset A/B; repeat a promising winner before promotion.

Reject treatment on growing backlog, dropped demand, count mismatch, failures/retries/deadlocks, or correctness regression. Freshness gate requires authoritative complete coverage and p95≤5s/p99≤10s/max≤30s for required stages; full reference/recovery and sustained headroom/warm/aged requirements remain separate. If250 fails, stop batch guessing: capture a bounded attribution sample separating lock waits, query execution and proof observation lag, then select one next change.

Confidence: high on code-path distinctions and unsuitable250 market-lock hypothesis; medium on lifecycle250 as first useful latency trial; low on exact dominant bottleneck until controlled measurement. No new independent review conducted; existing review does not cover later worker-group implementation.

## Executed comparison — C23/C24

| Metric | Group4 / lifecycle500 control | Group4 / lifecycle250 treatment |
|---|---:|---:|
| Accepted and final materialized/projected | 2,999,954 | 3,000,004 |
| Failures / retries / deadlocks | 0 / 0 / 0 | 0 / 0 / 0 |
| Peak lifecycle queue | 5,609 | 291,987 |
| Lifecycle p95 upper bound | 5,702ms | 36,232ms |
| Market p95 upper bound | 6,662ms | 37,212ms |
| Final queues | Empty | Empty |
| Sampler successful / total; max gap | 299/300;2003ms | 317/318;2001ms |

Both fail frozen freshness and sampler completeness.250 is rejected; exact500
configuration restored after writers stopped. Final counts include drain. One
run per configuration, fresh fixtures, no promotion or newly completed full
business reference. C24 received20 short aggregate activity samples only after
clear regression: most lifecycle observations had no reported wait. Do not infer
CPU saturation or lock-wait dominance from this sample. Next work should attribute
lifecycle execution cost rather than guess another batch size. See C23/C24 in
[baseline ledger](../THROUGHPUT_BASELINES.md) for retained evidence and caveats.
