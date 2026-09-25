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
