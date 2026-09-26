# Throughput baselines and evidence ledger

## Authority and mandatory use

Owner directive, 2026-09-24: throughput work must start from recorded evidence
and historical attempts, build on prior investment, and prioritize measured
progress toward sustained10k/s. This ledger is the required source of truth for
throughput claims, within each entry's recorded scope. It does not turn a narrow
benchmark into a guarantee for other workloads, versions, stages or deployments.

Before throughput investigation, tuning, testing, planning, or status reporting:

1. Read this ledger, [Performance Learnings](PERFORMANCE_LEARNINGS.md), relevant
   [projection plan](PROJECTION_THROUGHPUT_SCALING_PLAN.md), and linked prior
   successes AND failures. Inspect available original artifacts, not chat memory.
2. State the proven baseline, unresolved boundary, exact historical run IDs, and
   how the proposed workload/code/configuration/measurement differs. Unknown
   settings stay unknown; do not invent them or silently select defaults.
3. Form one measurable hypothesis; reuse existing commands and evidence. Prefer
   a targeted experiment and before/after workload over new validation tooling.
   Necessary correctness checks remain mandatory. Exploratory tests need not
   wait for final qualification, but must be labelled diagnostic.
4. Record every executed attempt, including setup failures and rejected claims.
   Append new evidence and explicit corrections with reason/source; never erase
   failed runs, silently change thresholds, or rewrite historical success scope.
5. Before claiming improvement/regression, distinguish code, topology, hardware,
   duration, workload age, observer and acceptance-rule changes. Unmatched runs
   are observations, not causal comparisons. Evidence contradicting a baseline
   requires an explicit correction entry, preserving original record.

Throughput PRs/handoffs must cite baseline IDs, prior attempts considered,
changed variable, actual result/artifact location, limitations and next decision.
Read-only evidence review is not a new approval gate for already authorized work.

## Current measured baseline — September24, before0053

Frozen candidate/image/source and0052 settings below; no0053 optimization applied.

| Scope | Measurement | Result |
| --- | --- | --- |
| Full projection accounting,2500/s×300s (C4) |749,977 exactaccepted/directacked/materialized/projected;2499.91/s;zero failures/finalcountgap/lag |Accounting and618,151lifecycle rows pass. Market prices/quantities/currency/source positions match;32`lag`metadata discrepancies fail strictreference. Precise downstreamSLO unproven. |
| Full projection,5000/s×300s (C2) |1,499,950 accepted/materialized;4999.83/s;578,855 finalcountgap |FAILED projection capacity. Original failure preserved. |
| Venue-core materialization,10000/s×300s sample1 (C5) |2,999,959 exactaccepted/directacked/materialized;9998.74/s;p9567.61ms,p99127.19ms |PASS existing materializerchecker;zero failures/countgaps;sourcecohortvalid. Projectionsdisabled. |
| Venue-core materialization,10000/s×300s sample2 (C5) |2,999,963 exactaccepted/directacked/materialized;9999.45/s;p9557.81ms,p99100.55ms |PASS samechecker;zero failures/countgaps;sourcecohortvalid. Samefixture,warmsecondrun;projectionsdisabled. |

C5 usesc-16,1024loadworkers,two300s samples,64instruments,16partitions,four
materializers. FreshseparateComposeproject beforefirstsample; secondretainsstate.
Currentcenttickworkload/code/observer differfromJuly; thisiscurrentbaseline,
not exacthistoricalreproductionorproof ofcausalregression/improvement.
Allbenchmarkcontainersstoppedaftercleanup; paidDropletstillallocatedforongoingwork.

## Established historical record

| ID | Run / scope | Recorded result | Boundary |
| --- | --- | --- | --- |
| H1 | July12 c-16, `do-benchmark-20260712T143401Z`, two300s samples at10k/s |3,000,001 and2,999,950 accepted; zero failures; average9,999.68/s accepted/materialized |Venue core and canonical materialization. Projection deliberately disabled (`projected=0`). |
| H2 | July19 local warm15m at10k/s |8,999,955 accepted/published/acked/materialized;9,999.49/s; zero final lag |Venue core. Live-order inventory growth identified; not full projection proof. |
| H3 | July17 c-16, `do-benchmark-20260717T134058Z`, full projection5k/60s |299,804 exact stage counts; zero final lag/errors/deadlocks |Genuine short-run pass; not sustained5k. Other attempts failed. |
| H4 | August20 c-16, `do-benchmark-20260820T220557Z`, full projection2.5k/300s |749,976 accepted/materialized/projected; zero final lag |Recorded sustained full-projection baseline under then-current checks. |
| H5 | August20 c-16, `do-benchmark-20260820T222945Z`, full projection5k/300s |1,500,002 accepted/materialized;746,047 projected;753,955 count gap;757,955 watermark lag |FAILED sustained5k before current changes. Count gap and watermark lag differ. |
| H6 | August20 local isolated100,002-outcome drain, single maintainer |Completed within10.383s of container start; conservative>=9,631.32outcomes/s; final queues empty |Upstream stopped; local fixed backlog, not concurrent remote sustained capacity. |
| H7 | August21, `do-benchmark-20260821T204347Z`, single maintainer5k/60s |299,959 exact canonical stage counts; zero canonical lag |September audit fails downstream drain check: lifecycle/market lastProcessed500/32. |

Sources: [current status](CURRENT_STATUS.md), [July materializer evidence](PERSISTENCE_MATERIALIZER_TEST_RESULTS_2026-07-04.md),
[projection gate ladder](PROJECTION_THROUGHPUT_SCALING_PLAN.md#7-expand-the-remote-gate-ladder),
[local maintainer experiment](research/PROJECTION_MAINTAINER_CARDINALITY_LOCAL_VALIDATION_2026-08-20.md),
[September audit](IMPLEMENTATION_STATUS_AUDIT_2026-09-04.md#recovered-august-21-projection-evidence).
Original H1/H4/H5/H7 report JSON inspected September24; selected exact values,
relative artifact paths and SHA256 recorded in the
[artifact index](evidence/throughput-baseline-artifact-index-2026-09-24.json).
H2/H3/H6 here rely on linked historical records; no new raw-artifact verification
claimed for those entries. Raw reports are local under `reports/do-benchmark/`,
not guaranteed to exist in a fresh checkout; preserve them before cleanup.

## Configuration and interpretation that must not be forgotten

- Lifecycle/market batch500 and poll250ms were historical defaults. Canonical
  projection batch250 versus later2000 is a different setting.
- August20 sustained tests used lifecycle/market maintenance on four projectors.
  August21 used one designated maintainer with four canonical writers; the
  market worker also invokes lifecycle processing. Count actual callers.
- Code subsequently changed: August21 stack commit`29fb9993`; September22
  retry-safe batch claims`0dd72ba9` (D-055); September24 uncommitted0051/0052
  correctness fixes and productive worker scheduling. Historical/current runs
  do not isolate the cost of any one change.
- Accepted, direct-acked, materialized, canonical-projected, lifecycle-fresh and
  market-fresh are separate facts. Dirty orders/s and instrument cycles/s are
  not source commands/s. HTTP latency is not downstream visibility latency.
- Final empty queues and equal counts do not establish all business values or
  freshness during the run. Conversely, a conservative coverage bound is not
  actual per-record latency. Label observer limitations explicitly.
- The sustained5k limit and broad market aggregation/write amplification were
  already known. Read projection plan sections5,7 and ordered execution steps
  before treating them as new discoveries or recommending the same experiment.

## September24 current candidate observations — not promoted baselines

Source base`0dd72ba9`, branch`codex/projection-sustained-10k`. Productive runtime
`sha256:7c388343d839daad0c44a449fc5c15f914588dc89a3cd449e2d83dd1b111501c`;
source manifest SHA256`b30badccab7b0a5e8e508cb547032cc511ae5080d1980531fbe20541044107c3`.
Dedicated c-16 host603319154;64instruments,256loadworkers,16partitions/four canonical
projectors,canonical batch2000,one lifecycle/market maintainer,0052 installed.

| ID | Actual experiment | Result / decision |
| --- | --- | --- |
| C1 | `/tmp/reef-productive-0052-2500-60s-2` on benchmark host;2500/s60s |149,976commands;2499.55/s; full reference matches123,676orders and64markets. Sampled lifecycle queue max2776, oldest3.033s. Conservative covering p95~58.5s does NOT measure typical order delay. Qualification failed; no sustained claim. |
| C2 | `/tmp/reef-productive-diagnostic-5000-300s-1`;5000/s300s |1,499,950accepted/materialized;4999.83/s; final canonical gap578,855 and watermark lag580,855. FAILED. Sampled lifecycle backlog79,050/oldest41.864s around sample100; not asserted as maxima. Full-reference stage did not run because stress guardrail failed. |
| C3 | `/tmp/market-query-experiment.log`; stopped C2 fixture, rollback-only current/candidate query comparison |64rows equal in both EXCEPT ALL directions. Current403.553/422.101ms; indexedcandidate115.922/115.487ms. Query-local~3.5x improvement; no end-to-end claim. Implement and rerun C2 workload. |

Selected C1–C3 results and original artifact hashes are preserved in
[current candidate evidence](evidence/throughput-current-candidate-2026-09-24.json).
Current host paths are evidence locators, not durable archives. Retain aggregate
results and provenance locally before host teardown. Existing durable checkpoint:
`artifacts/sustained-10k-20260924/`. SQL0053 is implemented and five focused PostgreSQL tests pass. A mixed numeric-scale
parity regression was caught and fixed by retaining MAX/MIN at the selected best
price level. This final SQL differs slightly from C3 and requires remeasurement;
no load result or inherited115ms claim yet.

Corrections retained: post-run container-name assertion, diagnostic artifact-name
error and source-directory permission failure were harness/setup defects, not
application-capacity results. Earlier claim of58s actual lifecycle latency was
incorrect: empty-queue coverage only observed a prefix at drain. Current observer
cannot resolve precise latency under a continuously nonempty queue. Do not erase
historical passes or promote current freshness from sampled queue age alone.

## Recording each next result

Use a stable ID and record: question; baseline/comparison IDs; source commit or
patch/manifest hash; runtime image; migration version; exact command/profile;
host/resources; partitions/maintainers/batches/pools; duration/workload/state age;
observer/settings; counts/rates/latencies/queues/errors; correctness/replay result;
thresholds and verdict; original artifact paths/hashes; limitations; next action.
Unavailable fields are explicitly missing and restrict the claim. Preserve failed
attempts and superseded interpretations. Re-run existing targeted tests as needed;
this ledger does not require a new testing framework.

## C4 — current five-minute 2.5k projection baseline

Run`/tmp/reef-productive-baseline-2500-300s-1` on unchanged C1/C2 candidate,
0052;0053 held back.749,977accepted/direct-acked/materialized/projected,
2499.9148116487227projected/s,zero final countgap/watermarklag andzero failures.
All618,151lifecycle rows match rebuild. Strict market comparison fails32of64rows;
complete follow-up mismatch classification proves ONLY`lag` differs onall32.
Every compared price,quantity,currency,source-position andotherfield agrees.
Do not silently remove`lag`from original comparison or promote strictpass.
The discrepancy's contract treatment remains unresolved. Current measured
accounting baseline is established; precise downstreamSLO is not established.

[Archived C4 aggregates and original report hash](evidence/throughput-projection-baseline-2026-09-24.json).
Original failed fullreference androllback-only diagnostic retained onhost.
Projection project/volumes retained stopped; nextcorebaseline uses separate
`reef-core-baseline`project. No optimization applied between baseline runs.
Postchecker's expected diagnostic filename was also wrong (`venue-event-materializer-stress`
versus actual`report`prefix); original failed completion retained, no rewrittenpass.

## C5 — current10k venue-core baseline, two samples

Run`/tmp/reef-current-core-10000-300s-1`; project`reef-core-baseline`;
existing`venue-event-materializer-stress.mjs`,rate10000,workers1024,duration300s,
repeatSamples2,allprojectionflagsfalse. Samecandidate7c388+0052. Checkprofile
`materializer`: attempted/accepted>=9900/s,p95<=100ms,p99<=200ms,16partitions,
skew<=4,zero429,requiredDB/I/Odiagnostics. Bothsamplespass;sourcecohortvalid;
originalprocess/cleanup/finalexitcodesall0. Total5,999,922commands acrossbothsamples.
No fullprojection,downstreamlatencyor10kread-modelclaim.

[Complete selected counts/rates/latencies,originalreportSHA256,runtimeimageIDs,
allowlistedactualsettings,source/protocolhashesandcleanup](evidence/throughput-core-baseline-2026-09-24.json).
Reports/samplehashesremainseparate. Diagnosticfilenamealiases point toactual
`report-diagnostics`files so unchangedcheckerrecognizesconfiguredReportOut;
no measurementsoracceptancecriteria changed. OriginalC4fixture/volumespreserved.
Durableaggregatecopy`artifacts/sustained-10k-20260924/cloud-0052/core-baseline-C5.json`.

NextthroughputchangecomparesagainstC2/C4ontheirprojectionprofile; C5doesnot
substitute for that comparison.0053first; claimcleanuponlyifremainingmeasuredcost.

## C6 — final 0053 query, preserved C4 fixture

Rollback-only read-only query comparison on the stopped C4 projection database:
64 market rows match in both `EXCEPT ALL` directions, including text and numeric
price/quantity representations. Current query: 514.305 and 510.844 ms. Final
0053 query: 222.919 and 219.995 ms. Roughly 2.3× query-local improvement;
not an end-to-end capacity result. This supersedes neither C3's different
fixture/query timings nor the failed sustained 5k baseline.

Original plan/timing artifact: benchmark host `/tmp/market-query-final-0053.log`.
No migration applied to C4; transaction rolled back, database stopped again.
Next run uses fresh `reef-indexed-0053` Compose volumes on the same host,
unchanged productive runtime image, 256 load workers, and 5000/s for 300 seconds.
Only application SQL addition is migration0053; existing baseline volumes stay
preserved and stopped. Fresh migration runner records normal checksums.

## C7 — market-only 0053, sustained 5k still fails

Run `/tmp/reef-productive-indexed-5000-300s-1`: 1,499,951 accepted and
materialized, 4,999.83/s; 897,840 canonical-projected at the recorded endpoint.
Final count gap602,111; watermark lag604,111. Two projection retries and two
PostgreSQL deadlocks; no exhausted retries or engine/materializer failures.
Sampled lifecycle queue peaked at87,865; last sampled queue29,083. Strict
full-reference check cannot establish equality while canonical accounting is
incomplete. Original failed report and successful writer cleanup retained.

Same load/runtime/settings as C2; fresh separate Compose project preserves C4/C5.
C6's faster query did not produce an end-to-end gain in this run. No promotion.
Live SQL also showed three cleanup lock waiters, longest2.939s. Next controlled
change0054 skips locked expired claims during cleanup, preserving all completion,
retry-deadline, retention and strictly-advanced-frontier predicates. Local
regression reproduces old delete-lock timeout; all7 claim tests and25 migration
tests pass after the fix. Next five-minute5k test changes only that SQL function.

## C8 — 0054 claim cleanup clears canonical 5k, downstream still fails

Run `/tmp/reef-productive-claims-5000-300s-1`: all1,499,950 accepted commands
materialized and canonically projected;4,999.391/s,zero final canonical countgap
or watermark lag. Zero projection retries, exhausted retries or failures; zero
PostgreSQL deadlocks across all three databases. C7's two deadlock log contexts
both identified `runtime_cleanup_projection_batch_claims`.

Lifecycle queue nevertheless peaked at649,410 and remained579,957 at the final
sample, oldest~191seconds. Strict business-reference prerequisite fails because
dirty queues remain nonempty. **Canonical catch-up improved; full downstream
capacity/freshness still fails.** Projection database recorded31.59GB temporary
writes. The faster upstream now exposes the downstream limit without claim-cleanup
serialization. Raw failures retained; writer cleanup passed; C8 volumes preserved.
[Selected evidence and hashes](evidence/throughput-claim-cleanup-2026-09-24.json).

## C9 — eliminate remaining market presence/currency scan, query experiment

On stopped C8 fixture, exact0053 query took115.245/113.455ms. With a transactional
partial currency index, unchanged query took77.014/62.090ms; bounded per-instrument
currency lookup took1.714/1.539ms. All64rows equal in both `EXCEPT ALL` directions.
Index creation and experiment rolled back; baseline fixture unchanged logically.
Original artifact `/tmp/market-currency-experiment.log`. Query-only result;
index write cost and end-to-end effects require the next actual load run.

Migration0055 adds matching partial index and uses its maximum eligible currency
per selected instrument, preserving side-independent presence, nonbest currencies,
price/quantity and claim semantics. All12 focused PostgreSQL tests pass (7claim,
4dirty-concurrency,1comprehensive market-parity);25migration tests pass. Same
five-minute5k workload now launched in fresh `reef-currency-0055` Compose project;
unchanged runtime image and knobs, original four fixture projects stopped.

## C10 — full indexed market lookup, downstream still cannot sustain 5k

Run `/tmp/reef-productive-currency-5000-300s-1`: all1,499,951 commands accepted,
materialized and canonically projected;4,999.752/s;zero final canonical gap/lag,
projection retries/failures or database deadlocks. Lifecycle queue peaks607,427
and ends514,819, oldest~174s. Full-reference prerequisite fails on nonempty dirty
queues. Market-query improvement alone does not solve downstream capacity.
Projection temp writes31.55GB; original failure and cleanup retained.
[Selected counts and hashes](evidence/throughput-indexed-currency-2026-09-24.json).

Post-load rollback-only nested lifecycle plans show500-order calls64.107ms cold,
40.232ms repeat; session work_mem16MB40.017/37.144ms. No lifecycle temp blocks or
JIT. Claim selection itself takes~3ms using existing dirtied_at index and
incremental sort. These observations do not justify adding a dirty-queue index
or claiming work-memory gain. Artifact `/tmp/lifecycle-plan-probe.log`, sanitized
plan-node aggregates retained locally under `artifacts/sustained-10k-20260924/`.

Next one-variable experiment: projection shared_buffers128MB→2GB, same0055 SQL,
image, workers and300s5k workload. This follows the bounded memory experiment in
[August20 system overview](research/PROJECTION_THROUGHPUT_SYSTEM_OVERVIEW_2026-08-20.md#gate-2-bounded-configuration-matrix).
Host has32GB RAM; observe actual memory and latency. Initial launcher assertion
wrongly expected no explicit shared_buffers argument; it failed before traffic.
Corrected to replace exactly `shared_buffers=128MB`, leaving all other command
arguments intact. Failed setup log preserved; it is not a throughput result.

## C11 — 2 GB projection cache does not establish capacity gain

Run `/tmp/reef-productive-buffers2g-5000-300s-1`:1,499,953 accepted/materialized/
canonical-projected at final checks;zero canonical gap/lag, retries or deadlocks.
Lifecycle queue peaks606,794 and ends504,789, oldest~172s. Dirty-queue prerequisite
fails again. Projection temp writes31.60GB; HTTP p95 44.60ms,p99 75.70ms.
Memory observed2.77GiB for projection PostgreSQL on32GB host; SQL SHOW confirms2GB
shared_buffers. This single run shows no meaningful downstream capacity gain
against C10; do not promote a cache improvement from final count equality.
[Selected evidence](evidence/throughput-projection-cache-2026-09-24.json).

Interpretation clarification for C8/C10/C11: canonical equal counts/zero lag are
**final endpoint observations, after the harness drain period**. The report's
`projectedPerSecond` divides its projected delta by the workload duration; it is
not independent proof of an in-load steady canonical processing rate. All three
fail downstream sustained capacity. C10 load ran17:17:59.873–17:22:59.879UTC;
retained sampler file continues through17:24:03.799UTC. Queue maxima/endpoints
above use that complete file; the freshness gate filters its timed cohort.

Next test changes only canonical batch2000→500, retaining2GB cache and0055 SQL.
Hypothesis: shorter transactions/smaller JSON batches reduce resource contention
and/or temporary-file work. Historical successful projection configs used250;
this is a measured configuration experiment, not an asserted cause. New project
`reef-batch500`, output `/tmp/reef-productive-batch500-5000-300s-1`.

## C12 — canonical batch500 eliminates spills, lifecycle still behind

Run `/tmp/reef-productive-batch500-5000-300s-1`:1,500,000 accepted/materialized/
canonical-projected at final endpoint;zero final canonical gap/lag. Projection
PostgreSQL temporary bytes fall from C11's31.60GB to **zero**. Lifecycle queue
still peaks539,426 and ends488,979; full-reference precondition fails. Thus
smaller batches solve measured spill cost but not sustained downstream capacity.
Runtime bytecode read directly from the running image confirms productive lifecycle
batches skip the poll sleep; classSHA256
`cb9ed4c2ee1de8938f1c9cb471a4aa615e5224a56dc5b7eda122096297ea2b6b`.
Live worker phase averages~292ms per lifecycle call despite~40ms isolated calls.

## C13 — parameter-sensitive lifecycle planning experiment

Same stopped C12 fixture,500-order batches, rollback-only forced-plan comparison:
function totals232.694/194.902ms with generic plans versus41.799/40.685ms with
custom plans. Nested lifecycle SQL209.086/191.105ms versus36.005/34.946ms;
claim selection remains~2ms. No temp blocks or JIT in either arm. Warm repeats
have no shared reads. Generic plan joins aggregated results with nested loops;
custom plan uses hash joins. This supports parameter-sensitive planning as a
cause of the isolated/live gap; it is not a capture of the live worker's plan.
Actual load with the fix must establish its effect.

Migration0056 scopes `force_custom_plan` to the lifecycle function, preserving
its SQL body, volatile snapshots and locking. PostgreSQL documents both
[parameter-sensitive planning](https://www.postgresql.org/docs/16/runtime-config-query.html#GUC-PLAN-CACHE-MODE)
and restoration of caller settings for
[function SET options](https://www.postgresql.org/docs/16/sql-createfunction.html).
All12 focused PostgreSQL tests and25 migration tests pass; concurrency fixtures
verify configured function and restoration of an outer generic-plan setting.
Original nested plans: `/tmp/lifecycle-plan-cache-probe.log`; sanitized node
aggregates retained locally. C14 runs the same5k300s/batch500/2GB profile with
only0056 added, under `/tmp/reef-productive-customplan-5000-300s-1`.

## C14 — scoped custom plans remove observed downstream queue growth at 5k

Run `/tmp/reef-productive-customplan-5000-300s-1`: 1,499,950 accepted,
materialized and canonically projected at final endpoint; zero failures, retries,
deadlocks or final canonical gap. Projection temporary bytes remain zero.
All366 retained queue samples peak at **2,264 lifecycle entries**, versus539,426
in C12, and end at zero. This establishes a large observed queue improvement;
it does not independently qualify the per-command freshness SLO.

The conservative covering-observation freshness gate fails lifecycle and market
bounds (p95 151,789ms and318,659ms). These are completion upper bounds from
sampled coverage, not measured individual order latency. Source-to-canonical
p95 173ms,p99 239ms,max522ms passes. Final full-business reference failed during
pre-reference database hashing: PostgreSQL `No space left on device` writing a
temporary file. Host had retained too many stopped fixtures and reached100%.
Do not promote this run. Original failure artifacts preserved.

After writer cleanup passed, retired only four stopped failed fixture projects:
reef-indexed-0053, reef-claims-0054, reef-currency-0055, reef-buffers-2g. Their
raw report directories and selected count/hash artifacts remain; their database
volumes were deleted. Baseline fixtures, C12 and C14 remain intact. Disk returns
to80GiB free. Full-reference retry uses a separate artifact directory on retained
C14 state; fresh workload repeat follows with sufficient disk capacity.
[Selected evidence](evidence/throughput-custom-plan-2026-09-24.json).

C14 reference retry completed after disk cleanup: all1,235,564 lifecycle rows
match the full rebuild; market64/64 rows exist but56 differ. Example differences
include lag and last_partition_seq; do not infer every differing field solely
from examples. All database before/after table hashes and generations match,
confirming rollback left state unchanged. Strict market reference remains failed.

Migration0057 addresses the independently reproduced idle metadata defect:
when source lag and both dirty queues are zero, refresh only changed metadata
for the same market/source and preserve newer watermarks. Old0055 regression
fails expected20|0 vs10|7; corrected test and all13 focused PostgreSQL tests pass,
as do25migration tests. Initial combined test run had an unmigrated local claim
function; after0054 fixture correction, market parity fixture also needed its
real lifecycle-dirty table. Both setup failures preserved; final complete suite
passes. Hosted capacity ladder remains0056 to hold comparison settings fixed;
0057 is not deployed there yet.

## C15 — clean5k repeat confirms bounded observed queues

Same0056/batch500/2GB/256worker setup, fresh dedicated projectreef-ladder-0056,
`/tmp/reef-productive-ladder-5000-300s-1`. Accepted/direct-acked/materialized/
canonical-projected1,499,953;4,999.789/s;zero failures/retries/finalgap/lag.
365rawqueue samples peak2,289 lifecycle entries and end with both queues zero.
Host retains60GBfree after load; no disk failure. Freshness upper-bound gate
still fails. Full business reference explicitly deferred for this capacity-only
repeat: C14 reference retained and metadata defect being corrected by0057.
No qualification claim. Same profile advances to7.5k300s, keeping0056 unchanged.
[Selected evidence](evidence/throughput-clean-5k-repeat-2026-09-24.json).

## C16 — 7.5k exposes canonical projection capacity limit

`/tmp/reef-productive-ladder-7500-300s-1`:2,249,925 accepted/direct-acked/
materialized at7,499.715/s; canonical1,385,523, gap864,402, watermarklag870,902.
No command failures, projection retries or database deadlocks. Projection temp
bytes0. Lifecycle queuepeak2,652, ending567; marketending64. Small downstream
queues do not prove source-command throughput when canonical projection lags.
Final normalized projected rate4,618.39/s includes post-load drain and cannot
be described as in-load throughput. Fullreference deferred; strict capacityFAIL.

Retained last per-projector phase observations: SQL432.88–442.26ms per nonempty
batch, canonical reads14.03–16.70ms per call, transform44.42–45.53ms, commit4.14–
4.38ms. Canonical worker already skips sleep after productive batches; actual
poll10ms/batch500. No cadence fix assumed. Hold10k ladder while profiling SQL.
C16 fixture used afterward for bounded single-worker nested-plan profiling;
original load reports remain immutable but fixture is no longer its exact endpoint.
[Selected result and phase evidence](evidence/throughput-7500-capacity-2026-09-24.json).

## C17 — batch1000 insufficient and restores spills

`/tmp/reef-productive-batch1000-7500-300s-1`:2,249,929 accepted/direct-acked/
materialized; canonical1,478,504,gap771,425,watermarklag775,425. Zero retries and
command failures. Projection temp bytes26,119,669,791 versus C16zero. Lifecycle
queuepeak4,116. Final projected count improves~6.7%, still fails7.5k capacity.
Return to500 to avoid measured spills. [Evidence](evidence/throughput-batch1000-2026-09-24.json).

## C18 — status query/index comparison, exact aggregate equality

Same stopped2,249,929-command C17 fixture. Original710.841/705.749ms; indexed
rewrite with existing index946.150/899.534ms, so reject rewrite alone. Adding
transactional covering(partition_id,stream_sequence) INCLUDE(command_type):
original348.258/398.034ms; rewrite170.211/153.412ms. All16partition aggregates
match in both EXCEPT ALL directions. Probe index rolled back. This measures
queries, not sustained capacity. [Evidence](evidence/throughput-status-query-2026-09-24.json).

Candidate0058 replaces existing index with same keys plus includedcommand_type;
no extra index count. Runtime uses bounded partition/max lookups and exact
unprojected-range counts; market refresh omits unused global projected count.
Sparse partitions, missing watermarks, unsupported command types and error
sentinel covered by real PostgreSQL test. Combined23focused tests+installDist
pass;25migration tests pass; covering-index-specific rerun passes. Native new
image building with local0057metadata fix and improved prefix observer. Next
actual7.5k test returns canonicalbatch500; no new capacity claim yet.

## C19 — indexed status/observer/metadata candidate, four writers still below7.5k

New nativeimage1666e972… built from1107 verified source files;0057/0058 migrations,
new prefix observer, batch500/2GB/4writers. `/tmp/reef-productive-indexedstatus-7500-300s-1`:
2,249,927 accepted/materialized, canonical1,508,928;gap740,999,lag741,999.
No command failures or retries; projectiontemp0. Lifecyclepeak3,121, final565;
marketfinal0. Still fails sustained7.5k; fullreference deferred.

Early live market-refresh average241ms versus685ms in C16; canonicalSQL421–433ms
remains dominant. These different observation points are diagnostic, not a
controlled full-run phase speedup claim. Observer produces regular nonempty-queue
markers with zero clock/generation errors in checked live samples. Source DB
block reads fall89,435,435(C16)→24,638,919(C19); mixed changes mean no isolated
end-to-end attribution. Eight brief aggregate pg_stat_activity samples during
this diagnostic run show20active canonical CPU/null-wait observations,5transaction
lock,2DataFileWrite,1DataFileExtend;7lifecycleCPU. Container CPU limits verified
unset. No raw query payloads exported. [Evidence](evidence/throughput-indexed-status-2026-09-24.json).

Next C20 changes only canonical writer count4→8, splitting the same16partitions
into two per writer, retaining single lifecycle and market maintainers, batch500,
newimage1666 and same7.5k300s workload. Extra four projector ports18095–18098;
resolved Compose topology asserts unique ownership of all16partitions. No JVM
or SQL changes during comparison.

## C20 — invalid eight-writer attempt, do not compare capacity

`/tmp/reef-productive-eightwriters-7500-300s-1`: extra workers4–7 inherited
Compose defaults because their service environments were resolved before the
stress wrapper applied its defaults. Actual settings differed in processing
mode(sync-result vsstream-ack), internal HTTP mode(local vsenabled), partition
count64vs16, source/pool/server configuration and other flags. Diagnostics for
indices4–7 were blocked. Thus the intended one-variable comparison did not occur.
2,250,005accepted/materialized and reportedprojected1,124,149/lag0 cannot establish
8-writer capacity: projected metrics/partition scope are incomplete. Preserve
report failure, not a scaling claim. [Invalid attempt](evidence/throughput-eight-writers-invalid-2026-09-24.json).

Raw effective environments remain host-private. Corrected C21 pins all8 services
to effective benchmark environments from valid workers0/3, changing only names
and unique two-partition assignments, with maintainers enabled only onworker0.
Repeat output `/tmp/reef-productive-eightwriters-7500-300s-2`; original artifacts
retained. This correction does not change SQL/image/batch/workload.

## C21 — corrected eight writers improve canonical capacity; lifecycle falls behind

Same c-16 host/image1666/0058/batch500/2GB, eight canonical writers with
verified identical effective environments and distinct two-partition assignments.
`/tmp/reef-productive-eightwriters-7500-300s-2`: 2,249,926 accepted and
materialized; 2,201,969 canonical projected, gap47,957, reported lag50,457.
Zero failed/retried commands and DB deadlocks. Lifecycle queue peaks188,369,
ends185,985; market ends24. Projection DB temp bytes0. This is **FAIL**, not
7.5k full-pipeline qualification. Final projected count/load duration includes drain.

Canonical SQL mean534–545ms/batch500; lifecycle call123ms, market call637ms.
Sparse container CPU observations do not establish host saturation. More canonical
concurrency exposed lifecycle capacity deficit. Next diagnostic changes hardware
c-16→c-32 and concurrency to16 canonical/four lifecycle maintainers for actual10k;
results must carry the distinct hardware/topology profile, not claim c-16 success.

[Selected counts, phase costs, configuration parity, and CPU samples](evidence/throughput-eight-writers-2026-09-24.json).

### September24 evidence-retention incident during c-32 resize

Provider reboot cleared host `/tmp`, including C1–C21 raw report directories and
private diagnostic/reference files. Selected aggregates, recorded hashes, source
archives, and repository evidence survive locally; database Docker volumes and
application images survived. Historical raw files cannot now be reopened on this
host. Do not describe aggregate backups as complete raw evidence or silently
reconstruct original reports. No C1–C21 result is upgraded by this incident.

Subsequent run artifacts/configuration live under `/home/reefbench/benchmarks/`.
Configuration was reconstructed from stopped-container inspection; c-32 runs are
a separate hardware/topology/configuration profile. First persistent startup
failed before load because the temporary Node-based `bun` launcher was also lost;
its replacement lives under `/home/reefbench/bin/`. Preserve this setup failure.

## C22 — c-32 full-projection10k capacity diagnostic, qualification still fails

Persistent `/home/reefbench/benchmarks/reef-productive-sixteenwriters-10000-300s-3`.
c-32/32vCPU/64GB, image1666/0058;16 canonical writers, four lifecycle maintainers
plus market's nested lifecycle caller, one market maintainer; batches500; projection
shared_buffers2GB; source/projection max_connections512 (expanded startup exceeded
prior200/160). Failed setup attempts retained separately, no load attributed to them.

300s target10k:2,999,950 accepted/direct-acked/materialized/canonical projected,
9,999.77 accepted/s, zero failures/retries/deadlocks, final gap/lag0. Lifecycle
queue peaks5162, final lifecycle/market queues0 across369samples. Projection
temp bytes0. Whole-host60s sample meanCPU78.485%, max81.631%, meanI/Owait0.445%.
Final projected count/load duration includes drain; not independent in-load rate.

**Capacity diagnostic only; qualification FAIL.** Current authority/freshness
checks assume singular lifecycle owner and reject four maintainers. Even before
that correction, command-weighted covering bounds have lifecyclep956711ms and
marketp957720ms, above frozen5000ms. Canonicalp951386/p992582/max3794ms; life
p997915/max9433ms; marketp998914/max10432ms. These are conservative bounds,
not per-record delay measurements. No SLO relaxation. Full reference passed; see completion below.

Early aggregate capture also put a summary in the raw-report root before checker
finished; it was misclassified as a second report. Original checker log retained;
summary moved outside that root. Actual report independently fails freshness.

[Selected count, queue, phase/configuration, CPU, and freshness evidence](evidence/throughput-10000-capacity-2026-09-24.json).

### C22 reference completion and next controlled comparison

Full business reference now PASS:2,471,663 lifecycle rows and64 market rows; before/after runtime-table fact hashes unchanged. Freshness remains unqualified. [Bounded freshness spike](research/PROJECTION_FRESHNESS_SPIKE_2026-09-24.md) selects new worker-group control with batches500, then dedicated lifecycle250 only. Market500 and canonical500 remain fixed. Candidate group image `sha256:a1f3ec30dfb89b844d1c7ae4094406c95c4550c00582fd2060d445dabcf82b4e`; local623 tests pass. Hosted comparisons pending; no new performance claim.

## C23 — bounded lifecycle group, batch500 control

Samec32/16canonical/4materializer topology; newimagea1f3 contains four lifecycle loops in one maintainer plus nested market caller (observed maxConcurrent5). Dedicated lifecycle/market/canonical batches500; cadence unchanged. 10k/s300s:2,999,954accepted/materialized/canonical,zero failures/retries/deadlocks,finalqueues0,lifepeak5609. Finalcounts include drain. Canonicalp95777ms; lifecyclep955702/p997210/max8741ms; marketp956662/p997963/max9652ms. FreshnessFAIL and downstreamauthorityFAIL:299/300successfulsamples,maxgap2003ms exceeds frozen2000ms. Caller topology/coverage/clock/generation checks pass. No threshold change. Fullreference deferred for comparison; no new promotion. [Evidence](evidence/throughput-lifecycle-group-control-2026-09-24.json). Persistent raw `/home/reefbench/benchmarks/reef-productive-lifecyclegroup-10000-300s-1`. Next C24 changes only projector0 dedicated lifecycle batch250; canonical/market500 unchanged.

## C24 — dedicated lifecycle250 rejected; control500 restored

Only change fromC23: projector0 dedicated lifecycle batch500→250. Same image/source,
four dedicated loops plus market helper, canonical/market500, same c32 hardware,
16canonical writers,300s10k workload, fixed cadence/SLO. Actual container settings
verified. Fresh-fixture comparison, not identical restored-data A/B.

3,000,004 accepted/materialized/canonical by final drain, zero failures/retries/
deadlocks; final queues0. Lifecycle peak291,987 versus control5,609. Canonical
p951487ms; lifecyclep9536232/p9937742/max39606ms; marketp9537212/p9938762/
max40611ms. FreshnessFAIL. Sampler317/318 successful,maxgap2001ms also violates
frozen2000ms; topology, membership, coverage and clock/generation checks pass.

After backlog already exceeded89k and oldest age10s, added20 bounded aggregate
activity samples: lifecycle79 observations with no reported wait;4LockManager,
1transactionid lock,1WALInitSync,2WALSync,2WALWrite,1DataFileWrite. These short
samples do not establish total CPU time or dominant bottleneck; they do not support
assuming row-lock contention dominates. Sampling itself makes this diagnostic
only; regression preceded sampling. No new business-row export or SQL-text export.

Decision: reject250, restore exact control500 configuration (SHA256
`353543de16d3c17e89b02e814bdc8038c6ee85745829ff79ef6c1462df8ddcc4`). Writers
stopped; treatment dataset and raw evidence retained. No additional batch guessing.
Next bounded investigation: compare lifecycle function execution plans/cost for250
and500 on retained data, separating claim/recompute/shared-key marking; isolate
measurement-gap cause without altering frozen cadence/limits. Full reference,
recovery, warm/aged/headroom gates remain required for a winning candidate.
[Evidence](evidence/throughput-lifecycle-group-250-2026-09-24.json).
Raw `/home/reefbench/benchmarks/reef-productive-lifecyclegroup-250-10000-300s-1`.

## C25 — timeboxed lifecycle cost spike, no deployed change

Stopped C24 fixture; rollback-only1,000-order/64instrument/5status comparison. Four250calls took195–221ms versus two500calls111–113ms. Claim~1ms; recompute/write dominates.250plan repeatedly evaluates aggregate results under nested joins;500still rescans cached execution totals. Explicit MATERIALIZED on both execution_totals/order_event_state improved250 but regressed500 from118ms to152–155ms; candidateREJECTED. Exact scoped business parity and postrollback function/row/queue hashes pass. Keep500. No new load run or10kqualification. [Method, limitations and next action](research/LIFECYCLE_COST_SPIKE_2026-09-24.md); [aggregate evidence](evidence/lifecycle-cost-spike-2026-09-24.json).

## C26 — keyed execution SUM candidate rejected after actual10k load

SQL-only candidate0059, unchanged imagea1f3 and c32/16canonical/group4/batch500
configuration. Scoped rollback500 improved119.795/116.150ms to101.553/96.205ms
per1,000orders;9arms exact business parity, before/after function/row/queue hashes
match.25migration tests and7focusedPGparity/race/market tests pass.

10k/s300s:2,999,951 accepted/materialized/canonical at final drain; zero failures,
retries/deadlocks; finalqueues0; lifecyclepeak4798. Canonicalp95847/p991814/
max2589ms. Lifecyclep956050/p997368/max8872ms; marketp957037/p998234/max9886ms.
Compared C23control5702/6662ms p95: no measured freshness improvement. Sampling
is fully authoritative:302/302successful,maxgap1003ms; all cohort checks pass.
Prior C23/C24 sampler gaps came from2000msHTTPtimeouts duringload, not timestamp
rounding. Limits/cadence unchanged. Finalcounts include drain, not in-load service
rate proof. Fullreference deferred for rejected candidate; no promotion.

Decision: REJECT/REVERT under predeclared retain criterion. One run per arm does
not prove statistical harm; it does not justify retaining a microbenchmark-only
win. Removed0059 from active migration source; restored previous lifecycle function
and0056customplan in source/projection benchmark DBs with checksum guards and no
business DML. Candidateledger entries removed; C26raw/schema proof remain intact
as historical evidence, with separate rollback proof. Writers remain stopped.

[Hosted evidence](evidence/throughput-keyed-execution-rejected-2026-09-24.json);
[isolated evidence](evidence/lifecycle-keyed-execution-isolated-2026-09-24.json).
Private raw `/home/reefbench/benchmarks/reef-productive-keyedexecution-10000-300s-1`;
rollback `/home/reefbench/benchmarks/selected-aggregates/C26-rollback.json`.
Candidate SQL retained in `artifacts/sustained-10k-20260924/cloud-0052/0059_lifecycle_keyed_execution_totals.sql`
in original checkout; it is not an active migration. Implementation/test/run/revert
completed inside30-minute window begun21:20:06UTC.

## C27 — offline attribution of C26 bound, missing middle-stage timestamps

Reproduced C26 published percentiles from36,248batches/2,999,951commands. Mean285ms
source→durable materialization;3,130ms durable materialization→lifecycle proof;
972ms difference between independent market/lifecycle proofs. That last difference
is negative for100,627commands (3.35%): it is not causal market service time.
The existing `sourceToCanonical` clock ends at materializer commit, before later
canonical-projector completion. Thus the3,130ms interval still bundles canonical
projection, lifecycle processing and conservative proof timing.

Time-sampled nonempty queue-age p95life1494ms/market942ms cannot replace weighted
residence. One-second diagnostics are complete; meanHTTP~50ms,max160ms. Six sparse
canonical status observations (~55s apart) include late incomplete4/16partition
coverage and cannot recover the missing per-prefix split. No stage bottleneck
asserted; no SLO reclassification. Smallest next measurement: expose existing
PendingPrefix.observedThrough and lifecycleCoveredAt on the same emitted marker,
without queries/cadence/limit/acceptance changes. No runtime/SQL/newload in C27.
[Detailed attribution and next scope](research/PROJECTION_RESIDENCE_ATTRIBUTION_2026-09-24.md);
[aggregates](evidence/projection-residence-attribution-2026-09-24.json).

## C28 — same-prefix marker timing, full-projection 10k diagnostic

C27's proposed two existing `PendingPrefix` timestamps were emitted on the same
selected marker, with no new SQL, journal write, poll, acceptance arithmetic, or
threshold change. Focused Kotlin instrumentation tests pass. Fresh c-32 run:
same C23 worker-group topology, 16 canonical owners, four lifecycle loops plus
market helper, all batches500, 1000ms diagnostics, 10k/s for300s. Image
`sha256:361e76e00287197656a3e3b55ece6f5e1287ab38bfd4a47edf40aabbe84e56bb`;
source manifest SHA256`e28c3865cad0f80be78e129d213e2f0531e259fcfd591dc335a02bce8a4fe9be`;
control compose SHA256`34a571b232dd5cee9ad2398a3caab49b05405504052eec506189d8d3423e2c5e`.

2,999,868accepted/materialized/canonically projected by final drain,0reported
failures,finalqueues0,lifepeak5,567. Source/cohort and frozen observation
authority pass. Freshness **FAIL**: lifecyclep956,314ms,marketp957,315ms,
above unchanged5s limit. Reproduced published percentiles from36,259batches.
Paired command-weighted mean durable-materialization→selected lifecycle prefix
recorded1,982ms(p953,701), then same prefix→lifecycle covered1,337ms
(p952,022). Market marker's own lifecycle-covered→market snapshot mean956ms
(p951,052). These are observation-bound intervals: the first includes canonical
projector processing **and** frontier observation delay; the others include
processing **and** dirty-queue proof timing. They are not exact per-order commit
latencies. No prefix preceded its batch's durable commit; marker chronology passes.
Full business reference deferred; C22 remains last full reference pass. Final
count equality includes drain, not independent in-load capacity proof.

[Method and decision](research/PROJECTION_PREFIX_EXPERIMENT_2026-09-24.md),
[run aggregate](evidence/throughput-prefix-attribution-c28-2026-09-24.json),
[paired attribution](evidence/projection-prefix-attribution-c28-2026-09-24.json).
Private raw `/home/reefbench/benchmarks/reef-productive-c28-prefix-10000-300s-1`,
report SHA256`85f75657ae6e37851e079bbeee5acbaaea58a599e45a706d587215a432494ba3`.
The first post-run capture command had a helper-path typo; corrected without
altering the load or evidence. C29 tests one variable, lifecycle loops4→8.

## C29 — eight lifecycle loops rejected, four-loop control restored

Only change from C28: grouped lifecycle loops4→8 on projector0. Same image/source,
16 canonical owners, all batches500, market worker, c-32, 300s10k workload,
1000ms observer and unchanged freshness/authority rules. Live container setting
verified. Fresh fixture, not an identical restored-state A/B. Compose treatment
SHA256`4ab9b671d3bc3a9c529b53bf584ca10c96a0d7c621199ecaaeddd94660e1597b`.

2,999,995accepted/materialized/projected at final drain,0failures/retries/
deadlocks,finalqueues0,lifepeak5,092. Source membership and cohort residence
complete; published percentiles reproduced from36,413batches. Sampler301/301
successful but maxgap**2,005ms**, exceeding frozen2,000ms: downstream authority
**FAIL**. Freshness also **FAIL**: lifecyclep956,280ms,marketp957,280ms versus
C28control6,314/7,315ms. Apparent34–35ms p95 improvement is immaterial and
unqualified.

Same-prefix lifecycle recorded→covered mean fell1,337→1,200ms, while durable
commit→prefix-recorded mean rose1,982→2,146ms; p95 of that first segment rose
3,701→4,322ms. This one pair does not establish causal shared-resource
competition. It does show that extra loops did not achieve the end-to-end
retention criterion. **Rejected.** Writers stopped; exact four-loop compose
restored, SHA256`34a571b232dd5cee9ad2398a3caab49b05405504052eec506189d8d3423e2c5e`.
No full business reference for losing treatment; no promotion claim.

[Method and limits](research/PROJECTION_PREFIX_EXPERIMENT_2026-09-24.md),
[run aggregate](evidence/throughput-lifecycle8-c29-2026-09-24.json),
[diagnostic paired attribution](evidence/projection-prefix-attribution-c29-2026-09-24.json).
Private raw `/home/reefbench/benchmarks/reef-productive-c29-lifecycle8-10000-300s-1`,
report SHA256`a3646886c830556ee3ba5b3b25a99108816a9d4e4b60667bd6e6aa154e9dd102`.

## C30 — nested canonical SQL attribution, diagnostic only

On the same C28 application image/source, c-32 host, 16 canonical writers,
four lifecycle loops plus market helper, all batches500 and 10k/s ×300s,
C30 preloaded `pg_stat_statements` with nested tracking on three PostgreSQL
databases. Fresh fixture; changed database instrumentation means no causal
throughput/freshness comparison to C28. The raw C28 control and compose were
preserved. The C30 compose SHA256 is
`728ad25abf8ba2d7d1eb9d571b82e718b0907848c834bfd8b3454108fb2e8e72`.

Stress exited0; 2,999,771accepted/direct-acked/materialized/projected by final
drain,0reported command failures,0final count/lag gap. The protocol/checker
exited1 solely on unchanged downstream freshness: conservative lifecycle
p95 **44,641ms** and market p95 **46,020ms** exceed5s. Full business reference
deferred; no qualification or observed latency-improvement claim. Cleanup
exited0 and stopped all runtime writers.

The profiled full persistence call averaged604.5ms over6,624 calls. The nested
status/fill multi-table statement averaged357.8ms (8.93GB tracked WAL), the
timeline event/payload statement144.7ms (6.62GB WAL), and separate
submit-result conflict check43.0ms. Parent/nested times overlap and must not
be summed. This identifies the status/fill statement as the largest current
SQL component, but does not split its individual table writes. Read-only index
stats show a270MB `idx_submit_results_occurred_typed` with0 scans in this
write-heavy run; current runtime code reads submit results by command ID.
This supports one reversible index-ablation A/B only, not an automatic drop.

[Full method, results and decision](research/PROJECTION_CANONICAL_SQL_SPIKE_2026-09-24.md),
[sanitized statement aggregate](evidence/projection-canonical-sql-c30-2026-09-25.json).
Raw host `/home/reefbench/benchmarks/reef-productive-c30-nested-sql-10000-300s-1`;
report SHA256`0dc9e95796b8967cbd9218a0e67de44ba4db78f9cc18a6123bdd3aacd83317b5`,
diagnostics summary SHA256`9728bb27cbe3e5696ec2e88fb424db67bfe182fda21fb34e93ab55d88fc8df8b`.
No application, migration, or worker setting retained.

## C31–C32 — submit-result time-index ablation rejected; fresh control audit

C31 tested a single SQL-only migration in a disposable copy of C28 source:
drop `idx_submit_results_occurred_typed`, preserve primary key/typed facts.
Same C28 image, source except that migration, exact compose, 16 writers,
four lifecycle loops plus market helper, batch500, 10k/s ×300s, no profiler.
The migration ledger row was present and target index absent before load.
Fresh reset removed37owned project resources without error. C31 final
accepted/direct-acked/materialized/projected3,000,012,0reported failures,
0final lag. **Rejected:** final canonical SQL mean529.123ms over7,618
productive calls versus C28 control451.498ms; conservative lifecycle/market
p9514,577/15,520ms, and sampler max gap2,002ms>2,000ms invalidated
downstream authority. C31's full business reference began, but expensive
three-database before/after table hashing was intentionally stopped after
the performance/authority reject; cleanup passed and no active writer/query
remained. No full reference or replay pass claimed.

User questioned fixture/config validity. C28 and C31 had identical private-env
and compose hashes, identical selected workload settings and effective runtime
fingerprint, and the same successful destructive disposable-volume reset.
Projection DB block reads nevertheless rose3,963,512→21,000,403 and
`submit_results` sequential scans455→2,883. This invalidates any simple
attribution of the slowdown to index maintenance; it does not demonstrate
old-row pollution or a configuration mismatch.

C32 immediately reran the original C28 source/image/compose with a fresh
37-resource reset, index present and C31 migration absent. Final counts
3,000,003accepted/materialized/projected,0reported failures/lag. SQL mean
428.673ms over8,031 productive calls and projection DB reads4,186,036
returned near C28; `submit_results` sequential scans506. Thus a persistent
host/config slowdown is unsupported. C32 still **failed** unchanged freshness
(lifecycle/market p956,867/7,777ms) and sampler authority by1ms (max gap
2,001ms). Neither C31 nor C32 is a 10k qualification, and one pair does not
establish the cause of C31's extra scans. Index treatment remains rejected;
the authoritative worktree contains no 0059 diagnostic migration.

[Protocol, correction and limits](research/PROJECTION_SUBMIT_RESULT_INDEX_ABLATION_2026-09-25.md),
[final three-run phase aggregate](evidence/projection-submit-index-ablation-phases-c31-c32-2026-09-25.json).
Raw host C31 `/home/reefbench/benchmarks/reef-productive-c31-submitindex-10000-300s-1`,
report SHA256`1e833077a0b2b2a0de47f93c801187094a5e2c06359e587876d1f9e610926ad0`;
C32 `/home/reefbench/benchmarks/reef-productive-c32-control-10000-300s-1`,
report SHA256`c183d14a0fac346351490486e533deb21e058e2fbb61a390378d9723f0414cb5`.
All runtime writers stopped; five DB/broker containers remain on owned host.

## C33 — fifth-caller candidate cloud setup, no throughput run

On September 25, the C32 host was absent from DigitalOcean inventory and the
benchmark OpenTofu state was empty. A new `c-32` create attempt in `sfo2`
failed before resource creation because that size was unavailable. A reviewed
two-resource plan then created temporary `nyc3` droplet `603508014` and firewall
`5819dac7-dc16-4fe8-acda-c1c6b85701ac`. Automatic approval review rejected
syncing private repository source to the external host without specific user
authorization; no application source, workload, or throughput result was sent
or run. The reviewed destroy plan removed both resources. OpenTofu state and
provider benchmark inventory were empty afterward. This is setup evidence only:
the fifth-caller change and migration 0059 have local focused tests but no
remote capacity/freshness result. C28/C32 remain the relevant failed 10k
full-projection observations, with their previous image and five-caller topology.

## C34 — fifth-caller removal and trade replay SQL, 10k full-projection diagnostic

September 25 disposable DigitalOcean `nyc3` `c-32`, OpenTofu 1.12.5/provider
2.100.0, fresh volumes, source-synced candidate including runtime migration
0059. Pinned local images; 16 canonical owners, four dedicated lifecycle loops
on projector0, market worker, batches500, 256 load workers, target10k/s for
300s. PostgreSQL max connections400/320, projection shared buffers2GB, 1s
downstream sampler, unchanged5s lifecycle/market p95 gate. This is not a
matched C28/C32 A/B: host region, database configuration and code differ.
Initial startup attempt failed at default primary connection limit. Second
attempt (`v2`) had invalid downstream probes because extra projector settings
were not propagated; retained as setup failure. Fresh reset and explicit safe
setting propagation preceded valid `v3`; neither failed attempt is throughput
evidence.

`v3`: stress exit0, 2,998,276 accepted/direct-acked/materialized/canonical
projected (9,993.78/s), zero reported command failures, final lag/count gap0.
Source and downstream cohort authority pass; 409/409 sampler observations,
max gap1,378ms. Lifecycle had only `order-lifecycle-projector` caller,
max concurrent4; market had only `market-data-projector`. Full canonical
sequence integrity passed across16 partitions (2,998,276 distinct contiguous
rows); dirty queues0. Rollback-only full business reference passed:
2,470,276 lifecycle rows and64 market rows, no missing/extra rows; `updated_at`
excluded as documented by reference protocol.

**Freshness FAIL, no 10k qualification.** Conservative source-to-canonical,
source-to-lifecycle and source-to-market p95 bounds were62,527ms,90,738ms and
92,675ms respectively; frozen checker exit1. These are sampled cohort upper
bounds, not observed per-command latency. The fifth caller was removed as
intended but did not meet the retention gate on this fixture. No causal SQL or
caller performance attribution is claimed from this nonmatched run.

Post-run correction: this was a growing pipeline backlog, not merely a slow
final read-model flush. At 04:30:37Z sampled counts were 2,736,856 published,
2,225,187 materialized, and 1,997,803 projected: 511,669 waiting before
materialization plus 227,384 after it. Compared with C32, primary PostgreSQL
block reads rose 521,082→14,437,026 and projection reads 4,186,036→25,055,023;
projection SQL mean rose 428.673→654.697ms and commit mean 7.954→90.373ms.
These identify throughput loss in both materialization and projection, but
different host/configuration/code and absent matched control leave its root
cause unproven. In particular, this run does not justify blaming migration0059
or treating the earlier canonical status/fill statement as its proven cause.

All203 nonsecret protocol/results files saved at
`artifacts/sustained-10k-20260925/test2/`; checksum dry-run matched remote.
`v3` report SHA256`2271fbd35aefd0d94c1ce13609708c742ced1ad7723aa5f899520fa229d1391d`,
business result SHA256`5ab57bb3949b6b76f225f8b0ad022d14a67e265c59c876314d9eb3cd83099c59`.
Reviewed destroy plan removed only benchmark droplet `603509874` and firewall
`d38af38a-3626-4805-887b-7b6fcece4968`; state empty and both absent from
provider inventory afterward. Unrelated provider resources remained.

## C35–C36 — new-host control and primary-cache treatment, both failed

September 25, disposable `nyc3` c-32, fresh volumes for each 10k/s × 300s run,
256 load workers, 16 canonical projectors, four materializers, four dedicated
lifecycle loops plus market's nested fifth lifecycle caller, batch 500. Source
was the `dc5c355b` branch control without migration 0059 or fifth-caller
removal. Projection PostgreSQL shared buffers 2GB; max connections 400/320.
These runs are separate from historical C28's image/host/database settings.

| ID | Primary shared buffers | Fixed report snapshots and verdict |
| --- | --- | --- |
| C35 (`paired/A`) | 128MB | 2,998,669 accepted (9,995.6/s), 2,395,223 materialized at materializer collection, 2,658,530 projected at later projector collection, lag 342,139. Stress/checker exit 1; source cohort incomplete. |
| C36 (`paired/A-primary2g`) | 2GB | 2,915,449 accepted (9,718.2/s), 2,884,448 materialized, 2,749,194 projected, lag 171,255. Stress/checker exit 1; intake and projection gates fail. |

Counts above are at their respective collection times; do not subtract stages
sampled at different instants. C35 eventually reached 2,998,669 unique,
contiguous canonical outcomes, matching projector frontiers and zero dirty
queues after the failed timed gate. The primary-cache treatment reduced the
materializer backlog but did not establish a full-system improvement. Original
reports, diagnostics, postdrain queries and remote checksums are preserved in
`artifacts/sustained-10k-20260925/paired/`. Reviewed teardown removed droplet
`603628136` and firewall `bc7ce0fb-e083-4703-833f-53a9c4f142cf`; provider
returned 404 for both and state was empty.

## C37 — fresh reproduction of best 10k full-system C28 setup, diagnostic FAIL

User-directed clean rerun, September 25, on new `nyc3` c-32. C28 was the best
recorded 10k full-pipeline topology, **not** an accepted 10k qualification:
its 2,999,868 final stage counts passed, but lifecycle/market conservative
p95 bounds were 6,314/7,315ms versus the unchanged 5s gate. Exact archived
C28 Compose SHA256 `34a571b232dd5cee9ad2398a3caab49b05405504052eec506189d8d3423e2c5e`
and 64-instrument fixture SHA256 `b6de86e60892ecfb7d85b0d7644d72a4d978952ecbd7e77874dd50842246980a`
were used. 1,107 of 1,109 pinned source files matched the C28 manifest; only
two non-executable `.planning/` notes were unrecoverable. All application,
migration and benchmark source files matched. Native rebuilt image IDs differ
from the original C28 images, and the host/region differs; this is a
reproduction, not a matched causal A/B. Original C28 settings: primary/projection
shared buffers 128MB/2GB, max connections 512/512, 16 canonical owners, four
materializers, four grouped lifecycle loops plus market's nested caller, all
projection batches 500. No `pg_stat_statements` profiler was added. Read-only
five-second database wait/I/O and 15-second container samples ran alongside the
existing one-second downstream observer.

Precisely 2,999,951 commands received HTTP 202 and direct-engine ack in
300.0009s (9,999.81/s), with zero command failures. The fixed materializer
snapshot had 2,294,741 outcomes (705,210 short); the later projector snapshot
had 2,659,318 projected with lag 341,633. Stress and unchanged checker both
exited 1. Source/downstream cohort authority and freshness are unavailable;
HTTP p95 87.083ms is intake latency, not read-model latency. Same-sample flow
at 14:54:04Z showed 2,999,951 engine-acked, 1,952,007 materialized,
1,640,818 projected, and 281,444 lifecycle-dirty. At 14:56:49Z all were
materialized, 2,651,818 projected, and lifecycle-dirty had grown to 514,818.
Thus backlog began before canonical materialization and then grew in both
projection stages; it was not merely a final flush artifact.

During the timed load, primary PostgreSQL added 3,614,992 block reads,
3.394GB temporary data, and 137,483 `wal_buffers_full` events. Across the
full diagnostic collection, primary block reads were 13,716,772 and temp
bytes 6.191GB versus C28's 518,706 and 0.152GB; projection reads were
1,489,090 versus C28's 3,963,512, so the primary read increase is not a
whole-system read increase. Five-second `pg_stat_activity` samples found
84/268 active primary backend observations waiting on `WALWrite`, and
453/1,159 active projection observations on `WALWrite` plus 201/1,159 on
`transactionid` locks. These are sampled backend observations, not elapsed
wait-time shares. Postdrain stats showed 12,671,406 heap blocks read from
primary `canonical_command_outcomes` and 2,547,676,038 sequential tuples
read from projection `submit_results` in 2,584 sequential scans. C28 had 465
`submit_results` sequential scans. The exact-count path in
`PostgresRuntimePersistence.projectionStatusAcrossStores` executes
`SELECT COUNT(*) FROM submit_results`; `canonicalPartitionStats` also performs
per-partition exact lag counts. These are concrete high-volume status reads,
but this run does not prove either query alone caused the materializer deficit.

Writers were stopped after late catch-up. A separately labelled postdrain
diagnostic found all 2,999,951 canonical rows unique and contiguous across
16 partitions, projector frontiers equal to source maxima, and both dirty
queues empty. Rollback-only full business reference then passed 2,471,642
lifecycle rows and 64 market rows, excluding only `updated_at`. These late
checks do **not** change the frozen timed failure. All 98 sealed remote files
were copied and SHA256-verified at
`artifacts/sustained-10k-20260925/accepted-c28-diagnostic/`, including stage
flow, database samples, report/checker, and reference output. Reviewed destroy
plan removed only droplet `603639011` and firewall
`0206482d-807b-4927-9b3c-f043a9ed615d`; provider GET returned 404 for both
and OpenTofu state was empty. Next implementation target: remove exact full-row
status counts from frequent projection/backpressure paths while preserving
exact public/report checks, then address materializer write/read and projection
WAL/transaction contention against this pinned fixture.

## C38 — reviewed SQL remediation on clean C28 topology, timed FAIL

September 25, new `nyc3` c-32, current `codex/projection-sustained-10k`
source after focused PostgreSQL tests and fresh independent review. The 300s
10k/s run used C28's 16 owners, four materializers, four dedicated lifecycle
workers plus nested fifth caller, batch 500, 128MB/2GB shared buffers,
512/512 connections, and the same fixture SHA256
`b6de86e60892ecfb7d85b0d7644d72a4d978952ecbd7e77874dd50842246980a`.
The archived C28 Compose SHA256 was
`34a571b232dd5cee9ad2398a3caab49b05405504052eec506189d8d3423e2c5e`.
Its three database-init bind paths referred to an old host directory; first
startup stopped before load because `runtime` schema was absent. After saving
that failure, all containers and volumes were removed. The active Compose
changed only those three paths to the synced init directory (SHA256
`1717001fc9fdc5fadaaa0686d236e4af7f19b886968b7aeaf191efaf54b1c3a9`).
Fresh source verification matched all 1,142 manifest files. Node remained
v22.22.1; no SQL statement profiler was added.

| Fixed 300s stage snapshot | C37 same topology | C38 SQL treatment |
| --- | ---: | ---: |
| HTTP accepted/direct acked | 2,999,951 (9,999.81/s) | 2,998,112 (9,992.81/s) |
| Intake p95 | 87.083ms | 88.757ms |
| Materialized at materializer collection | 2,294,741 (7,649.11/s) | 2,226,441 (7,420.80/s) |
| Projected at later projector collection | 2,659,318 (8,864.37/s) | 2,733,855 (9,112.03/s) |
| Projector lag at collection | 341,633 | 266,257 |

C38 had zero HTTP/engine failures, materializer failures, projector failures,
retries, or deadlocks, but stress and checker both exited 1: the fixed
accepted-to-materialized gap was 771,671, projector lag was 266,257, and
cohort/freshness authority was incomplete. HTTP p95 measures intake only; no
qualified downstream p95 exists. Stage snapshots were collected at different
times and must not be subtracted as simultaneous counts. C4 remains the proven
2.5k/s full-projection reference; neither C37 nor C38 qualifies 10k/s.

Treatment cut projection `submit_results` sequential scans from 2,584 to 43
and projection PostgreSQL returned tuples from 2.967B to 186.7M (pre/post
database counters). Primary WAL grew from 6.565GB to 6.811GB while the new
canonical unique index enforced partition-sequence integrity; primary block
reads grew from 13.717M to 14.791M. These are observed run differences, not
isolated causal attribution. The projection read reduction did not remove the
primary materializer deficit. Next code target is the canonical batch commit
and its indexed writes; preserve uniqueness and replay semantics while reducing
primary work. Separately revisit projection capacity if the canonical stage
reaches 10k/s.

After the failed timed gate, all 2,998,112 canonical rows were unique and
contiguous across 16 partitions; source and projector frontiers matched and
both dirty queues were empty. The rollback-only lifecycle/market business
reference passed, excluding only `updated_at`. These are postdrain diagnostics,
not a timed pass. All 101 sealed evidence files verified locally at
`artifacts/sustained-10k-20260925/sql-remediation-c28/`. Reviewed teardown
removed only droplet `603665006` and firewall
`fb4d845e-be4c-416a-8802-d5201928d0c4`; provider GET returned 404 for
both and OpenTofu state was empty.

## C39 — canonical batch SQL/index treatment on clean C38 topology, timed FAIL

September 25, new `nyc3` `c-32`, committed source `f066fdfe`. Treatment adds
`0062` (one unique covering partition-sequence index in place of overlapping
indexes) and `0063` (reuse parsed batch outcomes for duplicate-ID validation)
to C38's reviewed SQL fixes. Same 300s × 10k/s fixture SHA256
`b6de86e60892ecfb7d85b0d7644d72a4d978952ecbd7e77874dd50842246980a`,
Node v22.22.1, 16 canonical projector owners, four materializers, four
dedicated lifecycle workers plus nested fifth caller, batch 500, 512/512
connections, and 128MB/2GB shared buffers. Added projectors were checked
against the active projector's 35 nonsecret benchmark settings; owners 4–15
used Redpanda, the correct stream and partition, and responded to diagnostics.

| Fixed 300s stage snapshot | C38 | C39 |
| --- | ---: | ---: |
| HTTP accepted/direct acked | 2,998,112 (9,992.81/s) | 2,981,641 (9,937.34/s) |
| Intake p95 | 88.757ms | 91.41ms |
| Materializer metric delta at its collection | 2,226,441 (7,420.80/s) | 2,331,512 (7,770.56/s) |
| Projector metric delta at its later collection | 2,733,855 (9,112.03/s) | 2,366,114 (7,885.88/s) |
| Projector lag at collection | 266,257 | 371,574 |
| Accepted minus materialized at materializer collection | 771,671 | 650,129 |

C39's canonical stage gained 105,071 items (4.7%) versus C38 at the same
collection point, but intake fell 0.55%, projector progress fell 13.5%, and
the frozen 10k gate still failed. Stage collections occur at different times;
do not subtract the materializer and projector rows as simultaneous counts.
Stress and checker exited 1. All HTTP commands were acknowledged and there
were zero materializer/projector failures, retries, and database deadlocks.
Neither run has a qualified downstream latency distribution. C4's 2.5k/s
full-projection result remains the proven sustained reference.

C39 primary PostgreSQL counters: 15.400M block reads, 5.834GB temp bytes,
6.299GB WAL; C38: 14.791M, 6.314GB, 6.811GB. C39's canonical outcome table
grew about 4.756GB, including 1.260GB of indexes; the canonical batch table
grew about 0.968GB, largely in TOAST storage. This is concrete write and
temp-I/O cost, though this
combined treatment and one run cannot assign the 4.7% gain or projector
regression to one statement. The canonical stage remains about 2.23k/s below
the 10k target, before the required 20% drain margin. Next work should change
the canonical storage/write shape while preserving replay uniqueness and
auditability, then separately address projector contention. More count-query
cleanup is not supported as the main 10k fix.

Postdrain, all 2,981,641 canonical and projected rows matched, all 16
canonical partition sequences were unique and contiguous, projection frontiers
matched, and both dirty queues emptied. Rollback-only rebuild matched
2,456,576 lifecycle rows and 64 market rows, excluding only `updated_at`.
These checks do not change the timed failure. Two setup attempts were kept:
first stopped before load because the new host lacked Bun; second had 12
projectors on fallback stream/partition settings and is not comparable. All
corrected-run evidence checksums verified at
`artifacts/sustained-10k-20260925/batch0063-treatment/run-10000-300s-v3/`.
After evidence transfer, droplet `603699973` and firewall
`583e64c0-dd01-45b2-9506-1a8312973046` both returned provider 404;
OpenTofu state was empty.

## C40 — fail-closed single-pass batch insert, timed FAIL

September 25, fresh `nyc3` `c-32`, committed source `3de0702a`. Treatment
`0064` replaces normal-path `COUNT(DISTINCT commandId)` plus a second JSON
conflict comparison with one outcome insert, input array count, and PostgreSQL
`ROW_COUNT` check. A skipped insert now aborts the new batch header and all
outcomes. Same C39 fixture SHA256
`b6de86e60892ecfb7d85b0d7644d72a4d978952ecbd7e77874dd50842246980a`,
Node v22.22.1, Bun 1.3.14, 16 canonical owners, four materializers, four
dedicated lifecycle workers plus nested caller, batch 500, 512/512
connections, and 128MB/2GB shared buffers. All 16 running projectors had
Redpanda, distinct expected partitions, and identical hashes for 35
nonsecret settings; the live proof is in the run artifacts.

| Fixed 300s stage snapshot | C39 | C40 |
| --- | ---: | ---: |
| HTTP accepted/direct acked | 2,981,641 (9,937.34/s) | 2,999,098 (9,996.30/s) |
| Intake p95 / p99 | 91.41 / 180.78ms | 74.51 / 140.00ms |
| Materializer metric delta at its collection | 2,331,512 (7,770.56/s) | 2,872,096 (9,572.99/s) |
| Projector metric delta at its later collection | 2,366,114 (7,885.88/s) | 2,992,120 (9,973.05/s) |
| Projector lag at collection | 371,574 | 7,478 |
| Accepted minus materialized at materializer collection | 650,129 | 127,002 |

The materializer stage gained 1,802.43/s (23.2%) and the projector stage
gained 2,087.16/s (26.5%) against C39's fixed collections. Those collections
occur at different times; do not subtract their counts from each other. The
frozen 10k full-pipeline gate **still failed**: materialization had a 127,002
command gap, projector lag was 7,478, source/downstream cohort authority and
freshness checks failed, and neither stage showed the required 20% drain
margin. Stress and checker exited 1. There were zero direct-stream failures or
NAKs, materializer failures, projector failures/retries, and database
deadlocks. This one matched run supports an observed improvement, not isolated
causal attribution or a sustained-capacity promotion.

Primary PostgreSQL block reads fell from C39's 15.400M to 10.495M, while
temp bytes rose from 5.834GB to 6.892GB and WAL rose from 6.299GB to
6.566GB. C40's postdrain canonical outcome table still occupied 4.885GB
(1.281GB indexes), and retained batch table 1.008GB; `0064` did not change
their storage shape. The next code target is narrower canonical outcome and
batch storage/write design, followed by projection write contention. Do not
repeat count-query micro-tuning as the main 10k strategy.

Postdrain, all 2,999,098 canonical and projected rows matched, all 16
partition sequences were unique and contiguous, projector frontiers matched,
and both dirty queues were empty. Rollback-only rebuild matched 2,470,978
lifecycle rows and 64 market rows. This correctness result does not repair the
timed gate. All 109 transferred evidence files verified at
`artifacts/sustained-10k-20260925/batch0064-treatment/run-10000-300s-v1/`.
Droplet `603722627` and firewall
`c81f5b6f-c0b0-4230-9cf2-d22fddda4269` both returned provider 404;
OpenTofu state was empty.

## C41 — six materializers with 0064, source caught up, full pipeline timed FAIL

September 25, fresh `nyc3` `c-32`, same committed runtime source `3de0702a`,
fixture SHA256 `b6de86e60892ecfb7d85b0d7644d72a4d978952ecbd7e77874dd50842246980a`,
Node v22.22.1, Bun 1.3.14, database settings, 16 canonical owners, and
projection topology as C40. The only intended capacity change was six
materializer consumers instead of four. Both added consumers had distinct
Kafka client IDs. Live proof found 16 correctly partitioned Redpanda
projectors and six materializers, with no mismatch across the same 35
nonsecret settings.

| Fixed 300s stage snapshot | C40, four materializers | C41, six materializers |
| --- | ---: | ---: |
| HTTP accepted/direct acked | 2,999,098 (9,996.30/s) | 2,999,515 (9,997.99/s) |
| Intake p95 / p99 | 74.51 / 140.00ms | 72.69 / 103.83ms |
| Materializer metric delta at its collection | 2,872,096 (9,572.99/s) | 2,999,515 (9,997.99/s) |
| Projector metric delta at its later collection | 2,992,120 (9,973.05/s) | 2,971,475 (9,904.53/s) |
| Projector lag at collection | 7,478 | 30,040 |
| Accepted minus materialized at materializer collection | 127,002 | 0 |

C41's source cohort was authoritative with exact accepted/direct-acked/
materialized counts and no materializer failures. The frozen full-pipeline
checker **still failed**: projection lag was 30,040, its separately sampled
materialized/projected gap was 28,040, lifecycle and market maintenance were
still active, and downstream freshness/cohort authority failed. Stress and
checker exited 1. These stage collections occur at different times; the table
rows are not simultaneous counts. Full-pipeline capacity and the required 20%
drain margin are unproven. Six consumers remove the observed source gap under
this fixture but do not qualify as the accepted full-system configuration.

Primary PostgreSQL block reads rose from C40's 10.495M to 18.354M while
primary WAL remained about 6.59GB. Projection PostgreSQL wrote about 17.40GB
WAL in C41, versus 17.56GB in C40, and sampled active sessions still included
WAL-write and transaction-ID waits. These counters do not identify one SQL
statement as the cause. The immediate code target is projection write and
dirty-queue contention with the existing 16-owner topology; narrower canonical
storage remains important for sustainable margin and aged state. Another
materializer-only scaling step is not supported by this full-pipeline result.

Postdrain, all 2,999,515 canonical and projected rows matched, all 16
partition sequences were unique and contiguous, projection frontiers matched,
and both dirty queues were empty. Rollback-only rebuild matched 2,471,278
lifecycle rows and 64 market rows. All 109 transferred evidence files verified
at `artifacts/sustained-10k-20260925/batch0064-six-materializers/run-10000-300s-c41/`.
Droplet `603730171` and firewall
`12454174-c19f-45f8-a98b-7930697e2cae` both returned provider 404;
OpenTofu state was empty.

## C42 — lock-only dirty conflicts, write target removed, full pipeline timed FAIL

September 25, fresh `nyc3` `c-32`, committed source `9d6dd614` with migration
`0065`, same fixture SHA256
`b6de86e60892ecfb7d85b0d7644d72a4d978952ecbd7e77874dd50842246980a`,
Node v22.22.1, Bun 1.3.14, six materializers, 16 canonical projector owners,
four lifecycle workers plus the nested caller, and the C41 database settings.
The 35 nonsecret worker settings, partition ownership, distinct materializer
client IDs, and both active `0065` function definitions were verified. One
live verifier initially used the wrong Compose base-container names; its
corrected live proof has zero mismatches. The flow sampler started partway
through load, so its samples are diagnostic only; fixed pre/post database
counters and the frozen report cover the full run.

| Fixed 300s stage snapshot | C41, six materializers | C42, `0065` |
| --- | ---: | ---: |
| HTTP accepted/direct acked | 2,999,515 (9,997.99/s) | 2,999,882 (9,996.57/s) |
| Intake p95 / p99 | 72.69 / 103.83ms | 73.37 / 103.38ms |
| Materializer metric delta at its collection | 2,999,515 (9,997.99/s) | 2,999,882 (9,996.57/s) |
| Projector metric delta at its later collection | 2,971,475 (9,904.53/s) | 2,966,681 (9,885.93/s) |
| Projector lag at collection | 30,040 | 34,201 |
| Lifecycle / market dirty tuple updates | 275,082 / 35,106 | 0 / 0 |

`0065` removed the exact targeted no-op dirty-queue tuple updates. Lifecycle
dirty total relation size fell from 133.10MB to 91.55MB. Both queues are
UNLOGGED, and the change did not reduce projection WAL: 17.41GB in C41 versus
17.47GB in C42. Projection database tuple updates fell from 444,404 to
166,577, yet projector throughput did not improve. Its roughly 19.96M tuple
inserts and 398.8M block hits remained. This is evidence that dirty-marker
tuple rewrites were a real avoidable cost but not the 10k full-projection
limiter under this fixture. Do not promote `0065` as a throughput solution.

Stress and frozen checker both exited 1. Checker reported 33,201 separately
sampled materialized/projected gap, 34,201 lag, projected rate below 9,900/s,
and non-authoritative downstream freshness/covering markers. Stage counts in
the table were sampled at different collection points. No direct failures,
materializer failures, projection retries, or database deadlocks were observed.
The full-pipeline gate and 20% drain margin remain unproven. The next code
slice should reduce normalized projection write amplification while retaining
immutable replay/audit facts; another dirty-queue micro-optimization is not
supported by C42.

Postdrain, all 2,999,882 canonical and projected rows matched, all 16
partition sequences were unique and contiguous, projection frontiers matched,
and both dirty queues were empty. Rollback-only rebuild matched 2,471,607
lifecycle rows and 64 market rows. All 102 remote evidence files passed local
SHA256 verification at
`artifacts/sustained-10k-20260925/dirty0065-six-materializers/run-10000-300s-c42/`.
Droplet `603737502` and firewall
`77ce4dee-e610-49bc-b8b5-d495a7a64dc5` both returned provider 404;
OpenTofu state was empty.

## C43 — broad runtime-event order/time index removed, full pipeline timed FAIL

September 25, fresh `nyc3` `c-32`, base source `3e763b39` plus migration
`0066` and compat-bootstrap removal (exact file hashes in the artifact source
manifest). C42's fixture SHA256 `b6de86e60892ecfb7d85b0d7644d72a4d978952ecbd7e77874dd50842246980a`,
six materializers, 16 projector owners, 35 compared settings, 10k/s for 300s,
Node v22.22.1, Bun 1.3.14, and database settings were retained. The flow
sampler started before load in C43, versus partway through C42; these are
matched declared workloads, not a perfectly isolated causal pair.

| Fixed 300s stage snapshot | C42, index present | C43, index absent |
| --- | ---: | ---: |
| HTTP accepted/direct acked and source materialized | 2,999,882 (9,996.57/s) | 2,992,504 (9,973.99/s) |
| Intake p95 / p99 | 73.37 / 103.38ms | 78.06 / 122.08ms |
| Projector metric at its later collection | 2,966,681 (9,885.93/s) | 2,967,975 (9,892.24/s) |
| Projector lag at collection | 34,201 | 26,029 |
| Projection WAL delta per accepted command | 5,820.59B | 5,658.69B |

C28 had recorded zero scans and 284MB for
`idx_runtime_events_order_occurred_typed`. Migration `0066` applied to all
three fresh databases; the index was absent and order/trace and narrow
`OrderModified` indexes remained. On populated C43 data, order-event and
latest-modification reads planned on those retained indexes. Projection WAL
per accepted command fell about 2.8%, consistent with less index maintenance;
the different intake and sampler timing prevent a causal throughput claim.
Projector rate rose only 6.31/s and remained below 9,900/s. Stress and frozen
checker exited 1; checker also rejected lag, downstream freshness, and cohort
authority. No projection retries or database deadlocks were observed. The
index removal is a bounded write/storage cleanup, not a sustained-10k fix or
evidence for 20% drain headroom.

Postdrain source/projected counts both equalled 2,992,504, all 16 sequence
frontiers were contiguous, both dirty queues emptied, and rollback-only
business rebuild matched 2,465,524 lifecycle rows and 64 market rows. All 102
remote evidence files passed SHA256 verification under
`artifacts/sustained-10k-20260925/index0066-six-materializers/run-10000-300s-c43/`.
Droplet `603746089` was intentionally retained for follow-up testing, with a
scheduled local-time cost cutoff before 21:00; destruction remains pending.

## C44 — bounded canonical selection promoted, local proof only, no full-pipeline run

September 25/26, local Docker Postgres 16 only (`compose.local.yml`
`projection-postgres`), no droplet. This is a local-evidence code promotion,
not a sustained-throughput measurement; it does not supersede C43's timed-gate
failure and makes no rate claim.

`0060`'s canonical selector ranked and prefix-checked the entire post-watermark
backlog per partition (`row_number`, `first_value`, `lead`, and a `bool_and`
window each scanning every unclaimed row) before slicing to the per-partition
budget, regardless of budget size. `EXPLAIN (ANALYZE, BUFFERS)` against a
synthetic 500,000-row backlog (16 partitions, empty watermarks, explicit
partitions passed as production does) measured 839ms per selection call: a
`Seq Scan`, an external-merge disk sort (18.6MB), and two full-backlog
`WindowAgg` passes. The parked
`.planning/sustained-10k/bounded-canonical-selection.candidate.sql` (LATERAL
per-partition `LIMIT` before ranking) measured 1.05ms on the same fixture and
data: an `Index Only Scan` on `idx_canonical_command_outcomes_partition_seq`
with `Heap Fetches: 0`. Both selected the identical 489 of 500 rows end to end
(via the real `runtime_project_canonical_command_outcomes` call, not just the
bare `SELECT`) on a fixture with a deliberately punched gap, and both correctly
stopped at the gap (9 of 9 rows before it, none after). `0061`/`0062` already
made `(partition_id, stream_sequence)` globally unique, closing the integrity
gap that had parked the candidate.

`0067_bounded_canonical_selection.sql` promotes this, keeping every
prefix-validity branch from `0060` verbatim (including the pre-encoding
legacy-data compatibility branches) plus the adjacent-duplicate tie guard,
evaluated over the bounded LATERAL fetch instead of the full backlog (fetching
`per_partition_limit + 1` rows so the last budgeted row can still see whether
an immediate duplicate follows it). `PostgresCanonicalSequencePrefixGuardIntegrationTest`
(gap/duplicate/tie/legacy-namespace cases) now runs against `0067` and passes.
A new `PostgresVenueEventBatchMaterializationIntegrationTest` case seeds a
20,000-row backlog directly in the real schema and asserts the call completes
in well under 2s regardless. Full `platform-runtime` suite (640 tests),
`scripts/dev/db/migrate.test.mjs` (25 tests), and arena-control-plane's
`PostgresSchemaMigrationIntegrationTest` all pass against `0067`.

Needs runtime validation: this closes a real, measured, backlog-scaling cost
in the selection query itself, and is a plausible mechanism for why C38-C43
plateaued (every write-amplification fix was downstream of this SELECT), but
that is inference, not proof. No matched full-pipeline treatment run has been
made against `0067`; C43's 10k/300s timed-gate failure stands until one is.

## C45 — cloud treatment attempt for `0067`, inconclusive (host CPU, not the selector)

September 26, fresh `nyc3` `c-8` droplet (`sfo2` no longer offers `c-8` at
all as of this date; C43 likely ran on different physical hardware as a
result), same 16-projector/6-materializer topology as C43 reconstructed from
its preserved protocol files, `verify-workers.py` confirmed 35/35 settings
matching with zero mismatches across all 22 app containers. Full setup,
every command run, every gap found and fixed, is recorded at
`.planning/sustained-10k/c45-dirty0067-cloud-attempt.md`; do not re-derive it
from scratch, read that file.

The 300s run completed (stress and checker both exit 1) but at 3,523.87/s
accepted, not 10,000/s: only 45.2% of scheduled commands were even scheduled
(1,357,241 of 3,000,000), intake p50/p95/p99 was 59.93/178.36/254.24ms
(worse than C43's own 78.06/122.08ms p95/p99), and this held steady on a
separate 30s repeat (3,755.86/s, 55.43/174.53/241.82ms) - not a warm-up
fluke. Live container CPU during the repeat (`sample-flow.py`, 8 vCPUs):
`reef-postgres` 149.84%, `reef-matching-engine` 117.20%, `reef-platform-api`
115.28%, `reef-projection-postgres` 107.84%, versus roughly 4-10% each
across all 16 projectors and 6 materializers combined. The bottleneck is the
intake/accept/match path, entirely upstream of what `0067` changes; the
projection layer never came under real pressure. Idle single-request
latency was 2ms, ruling out a static network/proxy misconfiguration.

This run neither confirms nor refutes `0067`. C44's local EXPLAIN evidence
is unaffected by this result. The leading unverified suspect is the
region/hardware difference above, not a setting mismatch - every checkable
config item matched C43 exactly. A valid retest needs either matching
hardware (try `c-8-intel`) or accepting that intake capacity is a separate,
pre-existing ceiling from `0067` on whatever hardware is available.
