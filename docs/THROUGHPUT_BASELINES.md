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

Stage-by-stage index of targets, last measurements and unmeasured stages: [Calcify throughput budget](work/CALCIFY_THROUGHPUT_BUDGET.md). It copies from this ledger; this ledger wins on any disagreement.

Current goal clarification, October11: owner now requires10k distinct trades/s
through user-facing trading API after Calcify, with settlement allowed to lag.
[Proposed arena contract](work/CALCIFY_ARENA_THROUGHPUT_CONTRACT.md) defines complete
booked view, clocks, fixed deadlines, paired-order workloads and bounded session
flush. Numeric targets remain proposals, not measured results. Earlier capture,
venue-core and financial entries keep original scope; none alone qualifies this
extended product boundary. No new benchmark run accompanies goalpost research.

## Calcify Phase 2 managed resolver diagnostic, CAL-P2-I1 (September30/October1)

Baseline considered: CAL-P2-E4 (~10k local WAL-off joins/s, excluding source decode/broker/Streams), CAL-P1-L9 (~5k successful full-path commands/s), L6/L8 failures. New implementation uses production Protobuf resolver, sequential full JSON/checksum decode, persistent RocksDB index and EOS changelog/output on three local Redpanda26.2.3 brokers. Explicit `write.caching=false`, RF3, two partitions/threads,1MiB probe topic segments; each broker1CPU/1GiB heap/2GiB container limit. Host MacBookPro18,4,10CPU/64GiB; Docker VM15.6GiB. Upstream logs injected from real Go200-outcome/100-trade fixture; no HTTP throughput claim. Source topics fresh, long IDs, two new accepted facts per resolved trade; output~2KiB.

`capacity-e7bec93b` failed before seeding: harness tried parsing empty init output as JSON. Preserve all three setup attempts; no rate result. `capacity-6588b9b2` hot20k backlog emitted18,412 unique contexts in120s timeout; active covering rate155.85/s. Subsequent oracle snapshot18,917 exact contexts, zero duplicates, still incomplete cohort. Normal resolver requested EOS commit per trade. Hypothesis: use configured100ms Streams commit interval to batch transactions while preserving state/output/checkpoint atomicity. Subsequent same-size fresh-topic observations and crash checks must qualify change; no sustained10k or large-state recovery claim yet. [Raw attempts](evidence/calcify-phase2-implementation/).

## Calcify Phase 2 local store/codec diagnostic, CAL-P2-E4 (September30)

Isolated experimental branch at planning base `79dab22b`. Single Java thread,
RocksDB bounded cache32MiB/memtable16MiB×2, WAL off, JVM1GiB; MacBookPro18,4,
10CPU/64GiB. One million aged rows before paced300s cohort:3,000,000 joins and
6,000,000 synthetic new full-fact row inserts, 300.000055s, ~10k joins/s.
Transient offered-minus-resolved peak165 trades, final exact3m; sampled local
service p95 25.0µs. Full example JSON row646B/V1 1,985B. Source parsing/checksum,
Kafka/Streams transactions, changelog/standby, intake and matching excluded.
No full Phase2/system10k claim. Prior CAL-P1-L9 full Phase1 ~5k commands/s and
L6/L8 failures, hosted C5 ~10k venue-core commands/s considered; stages/workloads
not causally comparable. Separate short knees, CPU/RSS resource probe, logical
changelog sample, fault tests and all corrections:
[report](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/research/CALCIFY_PHASE2_EXPERIMENTS_2026-09-30.md),
[300s artifact](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/calcify-phase2/E4-aged-paced.json),
[experiment directory](evidence/calcify-phase2/).

## Calcify Phase 1 local diagnostic, CAL-P1-L2 (September29)

On `codex/calcify-phase1`, local one-hot-lane full path accepted 2,000 load
orders in 1.402 seconds, then drained all 1,001 trades to commitment,
verification, and receipt stages 8.196 seconds after last acceptance. Two
attempts are retained: first pipeline reconciled but observer timed out because
it assumed one source batch per command; corrected run passed. This short burst
uses one instrument, four broker partitions, and no legacy materializer or
projectors. It is not a sustained capacity or latency qualification and cannot
be compared causally to C5 venue-core or C2 full-projection hosted runs.
[Exact settings and attempts](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/calcify-phase1-basic-load-2026-09-29.json).

Follow-up CAL-P1-L3 traced 6,799–7,001 ms last-commitment-to-last-verified
delay to verifier's one-transaction-per-link loop. Batching up to 100 links
per poll preserved partition-prefix checkpoints and reduced that endpoint
difference to 31 and 54 ms in two local repeats; final receipt drain after
last acceptance measured 960 and 942 ms. All 1,001 trade/link/receipt counts
reconciled. Same nominal workload and topology, but selected lane partition
and aged local state differed; no sustained or hosted promotion follows.
[Before/after evidence and limits](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/calcify-phase1-verifier-batch-2026-09-29.json).

CAL-P1-L4 paired a fresh 1,000-pair local burst with a 300-second paced run
at 100 crossing pairs/s through PostgreSQL HTTP intake, Go matching, and all
three Calcify stages. Burst accepted 2,000 orders in 1,542 ms, then drained
1,001 receipts in 1,197 ms. Paced run accepted 60,000 load orders in 299,911
ms (200.06 orders/s); all 60,002 intake/source commands, 30,001 source
trades, commitment links, verified links, and receipts reconciled including
preflight. Fifty-nine in-load samples had accepted-to-receipt gap p95 and peak
70 trades with no growth; final drain took 1,923 ms. Frozen local diagnostic
gate passed. Same single hot lane and local reused volumes; no hosted,
multi-lane, fault-at-load, or higher-rate capacity claim. [Paced raw report](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/calcify-phase1-5m-2026-09-29.json),
[burst raw report](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/calcify-phase1-burst-repeat-2026-09-29.json), and
[method/limits](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/work/CALCIFY_PHASE1_IMPLEMENTATION.md#paired-burst-and-five-minute-run-cal-p1-l4).

CAL-P1-L5 repeated the same 300-second, single-hot-lane local path at 300
crossing pairs/s (three times L4's requested rate), on the same code and
topology with fresh topics and further-aged reused volumes. All 180,000 load
orders were accepted in 299,975 ms (600.05 orders/s); all 180,002 source
commands and 90,001 source trades/commitment links/verified links/receipts
reconciled including preflight. Across 59 five-second samples, gap p95/peak
was 200 trades. First/last six-sample means were 175.2/163.5; first/last
half means were 171.1/171.7. At last acceptance, 89,811 receipts existed;
final drain took 1,943 ms. The predeclared local gate passed without gap
growth at this rate. Counts sample in-flight work, not individual latency;
this does not establish a capacity ceiling or guarantee against growth at
higher rates or longer duration. [Raw report](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/calcify-phase1-5m-300pps-2026-09-29.json),
[method/limits](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/work/CALCIFY_PHASE1_IMPLEMENTATION.md#three-times-rate-five-minute-run-cal-p1-l5).

CAL-P1-L6/L7/L8 raised local Phase 1 full-path pressure. Initial 10k/s and 5k/s
30-second Bun probes failed: client sockets failed and a 500-command matching
batch exceeded Kafka's 1 MiB producer message cap by 504 bytes, leaving a
20,163-command hot-partition backlog. With matching batches at 200, matching
reconciled but per-link receipt DB transactions/checkpoints lagged. A per-poll
receipt batch fixed that backlog: a 300,000-order probe reconciled 150,001
trades/commitments/verifications/receipts with p95/peak in-load gap 300, but
the Bun client needed 52,368 socket retries and delivered only 2,977.08
orders/s. A pooled Go crossing-pair client then delivered 7,691.22 and
7,738.65 orders/s in 30-second 10k/s-offered probes with 512 and 1024
workers; neither met the 10k target. All attempts, including failures, are
retained in [high-rate attempt ledger](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/calcify-phase1-high-rate-attempts-2026-09-29.json).

CAL-P1-L7 used fresh generation 19 and topics, 200-command matching batches,
batched receipts, one hot instrument/partition, and the same local 10-CPU
Docker host. Over 300 seconds with 7,500 orders/s offered, 2,179,758 orders
were accepted in 300.299 seconds (7,258.61/s; 96.78% of target), with zero
request failures/retries. Ingress and matching each counted 2,179,760
commands including preflight; source trades, commitment links, verified links,
and receipts each counted 1,089,880. Matching had zero NAKs/failures. Across
60 five-second samples, the *lower-bound* accepted-pair-to-receipt gap p95 was
1,025 trades, peak 1,670, first six mean 824.5 and last six mean 460.5;
final drain was 1,133 ms. Intake, accounting, and drain gates passed. Gap
gate was unproven: accepted count was read before the receipt query, so these
samples could understate the gap. This is diagnostic single-lane evidence
for Phase 1 only, not hosted C5's 64-instrument 10k/s venue-core profile,
per-trade latency, or future post-match phase capacity. [Raw load report](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/calcify-phase1-go-7k5-5m.json),
[exact stage reconciliation](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/calcify-phase1-go-7k5-5m-reconciliation.json),
and [method/limits](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/work/CALCIFY_PHASE1_IMPLEMENTATION.md#high-rate-calibration-and-five-minute-run-cal-p1-l6-l7-l8-l9).

CAL-P1-L8 corrected sampling by bracketing each receipt query with accepted
counts and using the conservative upper gap. Fresh generation 20, same runtime
code and 7,500/s offered workload, accepted 1,930,226 orders in 300.624
seconds (6,420.72/s); 159,887 offered pairs dropped at the bounded client
queue. Intake gate failed. Exact source trades, commitments, verifications,
and receipts each counted 965,114, with zero matching failures. Conservative
upper gap p95/peak was 866/1,272 trades and final drain was 1,183 ms. This
corrected run does not establish sustained 7.5k/s on the increasingly aged
local volumes. [Raw load report](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/calcify-phase1-go-7k5-upper-5m.json),
[exact stage reconciliation](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/calcify-phase1-go-7k5-upper-5m-reconciliation.json).

CAL-P1-L9 used fresh generation 21 and the corrected sampler at 5,000
orders/s offered for 300 seconds. It accepted 1,499,902 load orders in
300.098 seconds (4,998.03/s); 49 of 750,000 offered pairs were dropped by
the bounded client queue, with zero request failures/retries. Exact intake
and source command counts were 1,499,904 including preflight; matching
acked all, with zero NAKs/failures. Source trades, commitments, verifications,
and receipts each counted 749,952. Across 60 five-second samples,
conservative upper-gap p95/peak was 628/846 trades; first/last six sample
means were 436.17/477.67 trades. Final drain took 1,478 ms. Local 5k/s
Phase 1 single-lane gate passed. No individual trade latency, fault/restart,
or later post-match phase capacity was measured. [Raw load report](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/calcify-phase1-go-5k-upper-5m.json),
[exact stage reconciliation](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/calcify-phase1-go-5k-upper-5m-reconciliation.json).

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

Sources: [current status](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/CURRENT_STATUS.md), [July materializer evidence](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/archive/PERSISTENCE_MATERIALIZER_TEST_RESULTS_2026-07-04.md),
[projection gate ladder](PROJECTION_THROUGHPUT_SCALING_PLAN.md#7-expand-the-remote-gate-ladder),
[local maintainer experiment](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/archive/research/PROJECTION_MAINTAINER_CARDINALITY_LOCAL_VALIDATION_2026-08-20.md),
[September audit](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/archive/IMPLEMENTATION_STATUS_AUDIT_2026-09-04.md#recovered-august-21-projection-evidence).
Original H1/H4/H5/H7 report JSON inspected September24; selected exact values,
relative artifact paths and SHA256 recorded in the
[artifact index](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/throughput-baseline-artifact-index-2026-09-24.json).
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
[current candidate evidence](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/throughput-current-candidate-2026-09-24.json).
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

[Archived C4 aggregates and original report hash](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/throughput-projection-baseline-2026-09-24.json).
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
allowlistedactualsettings,source/protocolhashesandcleanup](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/throughput-core-baseline-2026-09-24.json).
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
[Selected evidence and hashes](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/throughput-claim-cleanup-2026-09-24.json).

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
[Selected counts and hashes](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/throughput-indexed-currency-2026-09-24.json).

Post-load rollback-only nested lifecycle plans show500-order calls64.107ms cold,
40.232ms repeat; session work_mem16MB40.017/37.144ms. No lifecycle temp blocks or
JIT. Claim selection itself takes~3ms using existing dirtied_at index and
incremental sort. These observations do not justify adding a dirty-queue index
or claiming work-memory gain. Artifact `/tmp/lifecycle-plan-probe.log`, sanitized
plan-node aggregates retained locally under `artifacts/sustained-10k-20260924/`.

Next one-variable experiment: projection shared_buffers128MB→2GB, same0055 SQL,
image, workers and300s5k workload. This follows the bounded memory experiment in
[August20 system overview](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/archive/research/PROJECTION_THROUGHPUT_SYSTEM_OVERVIEW_2026-08-20.md#gate-2-bounded-configuration-matrix).
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
[Selected evidence](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/throughput-projection-cache-2026-09-24.json).

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
[Selected evidence](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/throughput-custom-plan-2026-09-24.json).

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
[Selected evidence](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/throughput-clean-5k-repeat-2026-09-24.json).

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
[Selected result and phase evidence](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/throughput-7500-capacity-2026-09-24.json).

## C17 — batch1000 insufficient and restores spills

`/tmp/reef-productive-batch1000-7500-300s-1`:2,249,929 accepted/direct-acked/
materialized; canonical1,478,504,gap771,425,watermarklag775,425. Zero retries and
command failures. Projection temp bytes26,119,669,791 versus C16zero. Lifecycle
queuepeak4,116. Final projected count improves~6.7%, still fails7.5k capacity.
Return to500 to avoid measured spills. [Evidence](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/throughput-batch1000-2026-09-24.json).

## C18 — status query/index comparison, exact aggregate equality

Same stopped2,249,929-command C17 fixture. Original710.841/705.749ms; indexed
rewrite with existing index946.150/899.534ms, so reject rewrite alone. Adding
transactional covering(partition_id,stream_sequence) INCLUDE(command_type):
original348.258/398.034ms; rewrite170.211/153.412ms. All16partition aggregates
match in both EXCEPT ALL directions. Probe index rolled back. This measures
queries, not sustained capacity. [Evidence](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/throughput-status-query-2026-09-24.json).

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
unset. No raw query payloads exported. [Evidence](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/throughput-indexed-status-2026-09-24.json).

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
report failure, not a scaling claim. [Invalid attempt](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/throughput-eight-writers-invalid-2026-09-24.json).

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

[Selected counts, phase costs, configuration parity, and CPU samples](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/throughput-eight-writers-2026-09-24.json).

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

[Selected count, queue, phase/configuration, CPU, and freshness evidence](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/throughput-10000-capacity-2026-09-24.json).

### C22 reference completion and next controlled comparison

Full business reference now PASS:2,471,663 lifecycle rows and64 market rows; before/after runtime-table fact hashes unchanged. Freshness remains unqualified. [Bounded freshness spike](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/research/PROJECTION_FRESHNESS_SPIKE_2026-09-24.md) selects new worker-group control with batches500, then dedicated lifecycle250 only. Market500 and canonical500 remain fixed. Candidate group image `sha256:a1f3ec30dfb89b844d1c7ae4094406c95c4550c00582fd2060d445dabcf82b4e`; local623 tests pass. Hosted comparisons pending; no new performance claim.

## C23 — bounded lifecycle group, batch500 control

Samec32/16canonical/4materializer topology; newimagea1f3 contains four lifecycle loops in one maintainer plus nested market caller (observed maxConcurrent5). Dedicated lifecycle/market/canonical batches500; cadence unchanged. 10k/s300s:2,999,954accepted/materialized/canonical,zero failures/retries/deadlocks,finalqueues0,lifepeak5609. Finalcounts include drain. Canonicalp95777ms; lifecyclep955702/p997210/max8741ms; marketp956662/p997963/max9652ms. FreshnessFAIL and downstreamauthorityFAIL:299/300successfulsamples,maxgap2003ms exceeds frozen2000ms. Caller topology/coverage/clock/generation checks pass. No threshold change. Fullreference deferred for comparison; no new promotion. [Evidence](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/throughput-lifecycle-group-control-2026-09-24.json). Persistent raw `/home/reefbench/benchmarks/reef-productive-lifecyclegroup-10000-300s-1`. Next C24 changes only projector0 dedicated lifecycle batch250; canonical/market500 unchanged.

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
[Evidence](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/throughput-lifecycle-group-250-2026-09-24.json).
Raw `/home/reefbench/benchmarks/reef-productive-lifecyclegroup-250-10000-300s-1`.

## C25 — timeboxed lifecycle cost spike, no deployed change

Stopped C24 fixture; rollback-only1,000-order/64instrument/5status comparison. Four250calls took195–221ms versus two500calls111–113ms. Claim~1ms; recompute/write dominates.250plan repeatedly evaluates aggregate results under nested joins;500still rescans cached execution totals. Explicit MATERIALIZED on both execution_totals/order_event_state improved250 but regressed500 from118ms to152–155ms; candidateREJECTED. Exact scoped business parity and postrollback function/row/queue hashes pass. Keep500. No new load run or10kqualification. [Method, limitations and next action](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/research/LIFECYCLE_COST_SPIKE_2026-09-24.md); [aggregate evidence](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/lifecycle-cost-spike-2026-09-24.json).

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

[Hosted evidence](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/throughput-keyed-execution-rejected-2026-09-24.json);
[isolated evidence](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/lifecycle-keyed-execution-isolated-2026-09-24.json).
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
[Detailed attribution and next scope](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/research/PROJECTION_RESIDENCE_ATTRIBUTION_2026-09-24.md);
[aggregates](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/projection-residence-attribution-2026-09-24.json).

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

[Method and decision](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/research/PROJECTION_PREFIX_EXPERIMENT_2026-09-24.md),
[run aggregate](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/throughput-prefix-attribution-c28-2026-09-24.json),
[paired attribution](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/projection-prefix-attribution-c28-2026-09-24.json).
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

[Method and limits](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/research/PROJECTION_PREFIX_EXPERIMENT_2026-09-24.md),
[run aggregate](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/throughput-lifecycle8-c29-2026-09-24.json),
[diagnostic paired attribution](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/projection-prefix-attribution-c29-2026-09-24.json).
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

[Full method, results and decision](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/research/PROJECTION_CANONICAL_SQL_SPIKE_2026-09-24.md),
[sanitized statement aggregate](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/projection-canonical-sql-c30-2026-09-25.json).
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

[Protocol, correction and limits](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/research/PROJECTION_SUBMIT_RESULT_INDEX_ABLATION_2026-09-25.md),
[final three-run phase aggregate](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/projection-submit-index-ablation-phases-c31-c32-2026-09-25.json).
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

## F02 — LOGGED dirty-queue crash recovery, matched hosted 10k/300s diagnostic

September 26, fresh-volume sequential control/treatment on one `sfo3` `c-32`.
The first UNLOGGED control attempt was **invalid**: 2,999,951 accepted and
materialized, only 1,021 projected, due to reused matching event IDs rejected
by migration `0068`'s replay check. Preserve its original artifact under
`artifacts/projection-dirty-f02-20260926/failed-control/`; do not count it as
capacity evidence. Event-ID correction `fabc556c` (draft PR #371) was applied
identically to both rerun arms. The arms used runtime `de93d526`, runtime image
`sha256:63921b7d4b5e7dc850059d77a7edbb8422ce3e7c15a756e3cae85cff1d4afd5a`,
matching image `sha256:2202fe760be115df7ffc6ea4158e42e598e798053488d0e0f43411f149847365`,
Node 22.22.1, Bun 1.3.14, the same C43-shaped fixture, six materializers, 16
projector owners, four lifecycle workers plus the nested caller, and fixed
observer settings. Control omitted migration `0069` and verified UNLOGGED queue
storage; treatment included it and verified LOGGED storage. Full configuration,
fixture, protocol, image, and source hashes are in each local ignored artifact.

| Fixed workload and later stage collection | UNLOGGED control | LOGGED treatment |
| --- | ---: | ---: |
| Accepted/direct-acked/materialized/projected | 2,999,880 | 3,000,005 |
| Projected rate | 9,999.20/s | 9,997.40/s |
| Projector lag | 0 | 0 |
| Intake p95 / p99 | 59.50 / 83.65ms | 59.25 / 83.90ms |
| Conservative source→canonical p95 / p99 | 358 / 632ms | 373 / 819ms |
| Conservative source→lifecycle p95 / p99 | 42,289 / 45,934ms | 41,871 / 62,271ms |
| Conservative source→market p95 / p99 | 44,418 / 53,006ms | 47,777 / 62,451ms |
| Projection database WAL | 17,641,971,924B | 19,236,983,026B |
| Projection database blocks read | 1,466,202 | 2,285,875 |

Both stress runs exited 0. Both frozen checkers exited 1 **solely on lifecycle
and market sustained freshness**; exact cohort, authority, duration, and queue
checks passed. Source/projected equality, 16 contiguous unique frontiers, empty
dirty queues, zero projection retries/deadlocks, and rollback-only exact business
reference passed after drain: 2,471,569 lifecycle/64 market rows in control and
2,471,661/64 in treatment. LOGGED added 1,595,011,102B WAL (9.04%, about
532B/accepted command) without an observed projected-rate loss in this one
pair. The higher block reads and market p99 bound cannot be assigned causally
from one sequential pair. Neither arm passes the frozen 5s downstream limit or
proves independent 20% drain headroom. These are conservative covering
observation intervals, **not actual per-order visibility latency**.

Same-prefix analysis of the no-profiler control reproduced published cohort
percentiles and split command-weighted p95 into durable→observed lifecycle
prefix 10,849ms and prefix→covering lifecycle marker 34,027ms; market
counterparts 12,445ms and 34,054ms. These p95s are not additive and each
includes observation. F04 must separate execution, waiting, barrier and
observation before a work-reduction trial. The fresh-region, current source,
event-ID correction, and differing observer timing prevent direct causal
comparison with C43. Raw evidence and 125-file SHA256 manifests per arm passed
verification under `artifacts/projection-dirty-f02-20260926/hosted-control/`
and `artifacts/projection-dirty-f02-20260926/hosted-treatment/`. Further
interpretation and crash/DDL limits are in
[F02 investigation](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/research/PROJECTION_DIRTY_RECOVERY_F02_2026-09-26.md).
Disposable droplet `603811601` and firewall
`0e3d8cf7-d244-4546-a6db-8310a7f8b7c0` were destroyed after local evidence
verification; both provider lookups returned 404 and OpenTofu state was empty.

### F02 DDL rehearsal — disposable c-32, diagnostic

September 26, isolated `sfo3` `c-32` droplet `603914045`, PostgreSQL 16,
same two-queue columns and indexes, synthetic data. The order queue held
1,000,000 rows/159,678,464B against the prior aged control's 127,041,536B;
market held 10,000 rows/999,424B against 335,872B. A reader-held lock made
the configured 2s timeout cancel conversion after 2,068ms. With the reader
gone, both `SET LOGGED` operations committed in 2,542ms under the configured
30s statement timeout; both relations were LOGGED and all rows remained.
This is a size/lock diagnostic on the run-plane worker class, not a restored
dataset, capacity run, or statistical upper bound. The temporary droplet and
firewall were destroyed and provider lists contain neither. Original script,
hash and result record are under ignored local
`artifacts/projection-dirty-f02-20260926/droplet-rehearsal-20260926/`;
interpretation and the excluded backbone setup attempts are in the
[F02 investigation](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/research/PROJECTION_DIRTY_RECOVERY_F02_2026-09-26.md).

### CAL-P2-I1 follow-ups: transaction batching

Per-trade commit cohort `capacity-6588b9b2` completed spread20k exact at283.62/s; hot and skew timed out with18,412/19,322 measured unique outputs. No loss claim: source and verified backlogs remained; workers stopped after diagnostics. One brief Gradle build overlapped skew; class hash records unchanged already-running processor.

`capacity-47d3f803` removes normal per-trade commit requests and retains configured100ms Streams EOS interval. Same RF3/fsync/two-thread fresh-topic20k workloads and long-ID paired source. Hot/spread/skew all exact20k full-fact contexts, zero duplicate/gap, short active observed hot rate4,214.12/s. No sustained/aged/HTTP claim or causal factor guarantee from one sequential pair. Further bounded8MiB state-cache trial coalesces frequently updated counters/queue keys in transactional changelog; crash/replay gates must still pass. [Batched observations](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/calcify-phase2-implementation/capacity-47d3f803/results.json).

### CAL-P2-I1 cache, CPU profile and large-state observations

`capacity-0ccfefa6` enables bounded8MiB Streams state cache. All20k hot/spread/skew outputs reconcile full facts; active short rates4,466/5,811/4,551 per second. Fresh source topics, differing broker ages and short cohorts limit inference. `capacity-237bc880`100k hot with JFR passed exact at4,537/s; profile highlighted canonical checksum/formatting. `capacity-9858d7fa` preserves canonical tokens with fixed1536-prefix table, direct hex formatting and direct field sorting;100k exact at4,940/s. JFR remains ignored local artifact with summary/hash; no causal improvement or sustained qualification claimed. Golden tests initially omitted object-count payload from manually assembled expected bytes; corrected explicit protocol fixture, no production checksum change. Existing Go source checks remain unchanged and pass.

`recovery-5545850c` injected5,000 paired batches,1,000,000 accepted rows/500,000 trades; source1,879,510,678B, seed54,832ms. All500k exact full-fact contexts; active covering rate4,251/s over117.61s. Local managed state767,176,186B. After process kill and complete local state loss, same application/changelog reached observed RUNNING in19,971ms (includes group takeover/startup; not isolated physical restore latency). Fresh100-trade catch-up yields500,100 exact contexts, no duplicate or gap. Predeclared diagnostic objective120s/1m rows passed once. Broker RF3/fsync/1MiB-segment fixture scope only; no production p99 recovery SLO, fault-at10k or HTTP20k claim. [Large-state record](https://github.com/dills122/reef-records/blob/a1050bc104ea0b701f436c36f09b1ef5561d9b91/records/reef/docs/evidence/calcify-phase2-implementation/recovery-5545850c/result.json).

Next hypothesis:128KiB producer batches/20ms linger/LZ4 reduce broker fsync/replication work versus default small batches; source fixture and state logic unchanged. Producer/output byte caps also align with16MiB resolver budget plus1024B framing allowance. These are coherent configuration changes, not proof of a single cause.

### CAL-P2-I1 producer, flow control and ready-target observations

`capacity-863faaaf`:128KiB batches/20ms linger/LZ4,100k hot exact at8,032.08/s (one5s window8,496.83/s). `sustained-1fadb3e2` froze300s/11k offered and failed infrastructure before completing load: all three Redpanda containers exited133 on ENOSPC, observer stalled at1,056,864 outputs and resolver stopped on source metadata timeout. No sustained or full-cohort claim. Docker image-declared anonymous data volumes survived earlier plain Compose down; six exact volumes were inspected read-only and shown to contain only recorded probe/internal topics, then removed with ownership/results/logs preserved. Other user data retained; no broad pruning. Current test Compose uses explicit labeled named volumes and32MiB segments (earlier1MiB). This topology/storage difference prevents causal comparison with prior rates.

`capacity-28900eca`: flow-control pause/resume only on transitions, fresh cleaned test cluster,100k hot exact8,348.93/s. `capacity-a8ded376`: bounded decoded accepted-row LRU256/4MiB serialized,100k hot exact8,718.85/s;5s window9,313.91/s. `capacity-64c101a6`: ready current-target publication avoids transactional pending queue churn,100k hot exact10,638.42/s; one5s window11,386.85/s. Shared checksum field-token memo is bounded128 names/128 UTF-8 bytes per call and preserves canonical bytes. All scopes are read-committed resolver-stage covering rates, including observer cost; not individual latency, sustained qualification, HTTP20k capacity or controlled causal claims. Per-lane ordered full Protobuf equality and length-framed key/value SHA256 recorded. [Latest short record](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/calcify-phase2-implementation/capacity-64c101a6/results.json).

`sustained-53a64664` started unchanged processor/checksum classes with frozen300s/11k offered, minimum10k durable/s,20k load-end gap,90s drain, exact full-fact reconciliation; hot/spread/skew/aged planned sequentially. Results pending; failure stops later profiles. Large-state/fault repeat and final full-path smoke follow final candidate.

`sustained-53a64664` completed3.3m unique contexts at10,605.80/s active covering rate over311.15s; source pacing33000 waves/300.011s,12,570,920,678B source and7,078,329,400B resolved payload. Independent full Protobuf oracle confirms all3.3m ordered unique contexts. Frozen load-end gap failed:127,510 vs20,000 maximum. Drained within12.43s of observer start+300s, but no four-profile qualification; later profiles intentionally unrun. Exact owned topics deleted only after full oracle; logs, source UUID and manifests retained. [Failed backlog gate](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/calcify-phase2-implementation/sustained-53a64664/results.json). Next unchanged-resolver trial offers10.5k/s, retaining minimum10k durable/s,20k end gap and90s drain; adds predeclared100k conservative covering gap using source acknowledgements versus last5s read-committed observation. This upper covering count includes producer/observer delay; not per-trade latency or a continuous physical lag measurement. Freeze expands to all Calcify production/test classes, checksum classes, fixture, harness and broker Compose.

Final review also found reused changelog validation gap: runtime previously applied desired settings only on creation. Existing changelog now checks actual RF/backend acknowledgement and16MiB+1024B message cap; new changelog configured with same cap. Unit first failed missing acknowledgement helper, then focused Calcify/checksum tests passed. This role-startup correction does not alter resolver fixture algorithm; final qualification records new classes/config explicitly.

`sustained-fa7fec51` offers10.5k/s for300s, retains10k durable/s and20k end-gap gates, adds frozen100k conservative covering-gap maximum and broader class/harness/fixture hashes. All3,150,000 contexts exact/ordered/unique; rate9,881.14/s, end gap210,491, covering peak274,978 (63 observer windows). Fails rate and both backlog limits; later profiles unrun. Runtime startup changelog byte/durability validation and probe changelog max bytes17MiB differ from prior run. No causal regression inferred from this sequential pair. Full platform check/coverage and Go race gates ran after rate observation completed, during independent full-fact audit; unchanged already-running oracle classes. Owned cohort topics removed after exact audit. [Result](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/evidence/calcify-phase2-implementation/sustained-fa7fec51/results.json).

Next bounded hypothesis: canonical checksum dispatch queries JsonNode type once instead of repeated virtual type predicates; identical canonical tokens and all JSON types covered by direct known-byte test. Focused Calcify/checksum tests pass.100k hot backlog trial with32MiB JFR cap follows; profiler changes scope and may affect rate. No sustained or causal improvement claim before measurement.

`capacity-e1255d3f`: single node-kind dispatch,100k hot exact10,535.25/s with JFR,5s window11,294.84/s. `capacity-906dcd15`: direct byte source parsing plus strict bounded16KiB UTF-8 validation scratch,100k hot exact10,664.64/s with JFR,5s window11,740.58/s. UTF-16/32 and BOM auto-detection initially violated old String parser contract; added failing regression and disabled Jackson charset detection, all focused tests pass. Independent String-source oracle remains unchanged. Sequential short/profiler results do not establish causal gain or sustained qualification. Byte parser preserves old semantic SHA256 and source facts; malformed, overlong, surrogate, truncated UTF-8 and scratch-window boundary covered. Profile now shows canonical digest and timestamp parsing among costs; fixed and per-call bounded token reuse is next hypothesis.

`capacity-156d616c`: bounded shared per-digest128 string token memo (fields≤128 UTF-8 bytes, values≤32),100k hot exact11,121.32/s with JFR,5s window12,019.45/s. `capacity-4c565d6d`:512KiB producer batching,100k hot exact10,679.02/s with JFR,5s window11,792.06/s. Sequential short observations do not isolate causality;512KiB trial does not show gain and is reverted to128KiB for next qualification. Direct golden test covers short values after memo saturation; no canonical byte change. All raw records retained.

Next sustained protocol retains rate/end-gap/covering-gap/drain limits and10.5k offered load. Samples observer every1s instead of5s to reduce conservative covering-gap inflation; max100k covering gap remains fixed. Require source load overrun≤1s beyond300s; this checks actual delivered offered cohort rather than configured pace alone. Freeze adds all generated fact classes, checksum helper classes and dependency jars. Role review found ERROR status could be lost when close transitions to NOT_RUNNING; retain failure flag so supervised infrastructure exit remains nonzero. This operational fix is separate from rate path and needs final role smoke.


### CAL-P2-I1 nightly checkpoint — sustained delivery gate remains open

`sustained-8ea6c8ce` freezes300s/10.5k offered, minimum10k durable/s,20k end gap,100k covering gap,1s observer windows,≤1s load overrun and90s drain. All3,150,000 contexts reconcile full Protobuf facts in lane order, unique with zero duplicates/gaps. Active read-committed covering rate10,160.50/s over310.024s; source11,997,898,178B, resolved6,754,923,400B. End gap1,184 and conservative covering peak16,842 pass respective count gates. **Actual producer duration310.730s fails301s maximum**, so cohort and four-profile qualification fail; spread/skew/aged remain unrun. Small backlog under delivered load does not establish behavior at frozen10.5k/s. No individual latency or upstream HTTP capacity claim. [Frozen failed result](https://github.com/dills122/reef-records/blob/a1050bc104ea0b701f436c36f09b1ef5561d9b91/records/reef/docs/evidence/calcify-phase2-implementation/sustained-8ea6c8ce/results.json).

Current fixture producer waits synchronously for each source acknowledgement. Next test-only hypothesis: bounded FIFO source sends, then publish each wave's verifications only after its actual durable source acknowledgement; preserve exact offsets and per-lane order. This change is **not implemented** at nightly checkpoint. Keep frozen thresholds unchanged, record workload delivery differences, and repeat all profiles only after correctness fixes.

Fresh independent source review found fail-closed gaps: expired verified-input checkpoints can reset to earliest, and reused verified/output topic names are not bound to persisted UUIDs. These defects remain open. Earlier nine-case fault evidence covers source retention/UUID only; it does not validate these missing input/output boundaries. Final unchanged-candidate fault/recovery and production-role smoke must follow fixes. Final local platform `check` and61% instruction coverage gate pass at nightly checkpoint, including bounded shared string-token checksum memo and infrastructure failure latch. Actual supervised nonzero failure exit remains unproven.


### CAL-J10K — two-hour joined campaign (October2), target FAIL

Frozen base `037971d6` plus bounded stage polls/checksum candidate; local Docker10CPU/RF1, stub verifier, PostgreSQL durable intake, five topic cohorts fsync after initial diagnostics. Unit: HTTP-durably-accepted **order commands/s**, not resolved contexts/s. q1 completed300s at6,250.80 strict ACK/s;940,045 exact trade contexts/receipts, receipt789ms/resolved upper2,065ms. q2 balancedfour partitions/Netty256/pool128/PGgroup1000µs completed300s at**7,708.36 strict ACK/s**,1,158,676 exact contexts/all-stage counts, receipt1,191ms/resolved upper1,652ms, zero failures/retries. Both fail10k intake/drop gates. q3 higher concurrency stops200.974s on guest-disk ENOSPC;800,374 trade contexts exact, but1,805 request failures and partial-pair command accounting fail. Never count incomplete q3 as five-minute record.

First three60s cohorts used old sampled-deadline/drain origins; later strict per-ACK measurement preserves all old artifacts. Early topics inherited caching; fsync cohorts are different configuration. Direct matcher admission groups corrected beforegen6;50k backlog limit unchanged. Captured350-outcome checksum A/B shows~23% less time/~70% fewer allocations, not causal whole-platform gain. Latest-master `16e15340` integration preserves run/hidden-limit and Phase1 retention guards; timed numbers do not transfer to updated code. [Full report/results/corrections](research/CALCIFY_10K_TIMEBOX_2026-10-02.md). Guest disk150GB,35MB free at q3 failure; ten oldest audited test topics deleted with proof, best q2 and PG volumes retained. No further load without guest-space sizing. No10k/RF3/hosted/settlement qualification.


### CAL-J10K configuration correction — October2

Campaign reused Calcify functional smoke, which forces PostgreSQL intake/idempotency and disables publish pipeline. Documented direct-ingress/materializer performance profile uses in-memory intake and enabled publish pipeline while retaining broker-durable ACK/direct Go consumer. Thus7,708.36/s and PostgreSQL contention apply only to SQL-backed smoke configuration; intended fast-ingress+Calcify ceiling remains unmeasured. Original records preserved; selecting smoke as capacity profile was setup error. [Source/config correction and next checks](research/CALCIFY_10K_TIMEBOX_2026-10-02.md#configuration-correction--october2-after-campaign).

### Calcify direct-path D7 sustained qualification (October 2, 2026)

Fresh C5-style durable direct ingress16lanes/64instruments/1,024workers/matching batch500 attached Phase1 stub verifier and Phase2 managed resolver. Local10CPU/15.6GiB, RF1/write.caching=false; PostgreSQL startup identity control only. D7 passed300s: conservative deadline rates10,425.19 verified matched trades/s and10,440.92 resolved full-fact contexts/s. All3,142,846trades exact/ordered/unique, zero HTTP failures/retries;64seeded maker orders excluded. Timed durable HTTP ACKs10,475.59orders/s. One aggressor order per trade; does not establish two-sided paired or mixed-command10k trade capacity. Whole-batch matching-entry->verified visibility p99 upper433ms, not exact individual matching-acceptance latency. Bounded poll-level extraction transactions fixed actual backlog: comparable60s paired D4~2,045->D5~5,167verified trades/s. D6 standing-liquidity60s diagnostic precedes fresh D7; workload/retention changes separately declared. [Report/config/corrections](research/CALCIFY_DIRECT_THROUGHPUT_2026-10-02.md), [frozen policy and exact qualification](https://github.com/dills122/reef-records/blob/a1050bc104ea0b701f436c36f09b1ef5561d9b91/records/reef/docs/evidence/calcify-direct-throughput-2026-10-02/d7-300s-standing-liquidity/results.json). Earlier SQL campaign and D1 caching mistake remain historical diagnostics, not intended-path capacity bounds.


### Calcify financial empirical diagnostic — October6, 2026 UTC

Baseline863503ec, unchanged financial kernel/managed adapter; prior bounded
1000/100 managed-restart proof is separate. New fixed fresh single-domain workload:
2500trades/s for60s,150000trades,300000CAPTURE/SETTLE actions; finite exact
prefunding,4GiB Java21 JVM,2GiB ACK journal. Three pinned RF3 Redpanda brokers,
1CPU/2GiB container each, cachingfalse,2000ms commit interval, no compression.
This is empirical test-adapter scope; no production capacity/conservative upper
bound, aged state, SQL/API cost or new managed restart.

Two setup refusals preserved: arm1 relative output rejected before payload; arm2
missing registered store/journal directories rejected before payload and stopped
owned brokers. Correction uses absolute unique output plus precreated empty
registered store/journal; proof output stays absent until launcher creates it.

Arm3 strict observation started02:22:28.778Z; allocation guard aborted02:24:34.816Z.
Last valid project allocation9649577984B; rejected raw sum10155438080B exceeded
9GiB abort threshold. Broker allocation8690835456B plus host1464602624B at
rejected observation. No final measurement. Closed journal verifies272154
published actions/136077paired SETTLE inputs, no tails; first-to-last source
offered span120.708s. Processor requires published journal membership before
execution, hence settled count at any cut <=136077, below150000 target. This
is count upper bound, not observed deadline settlement rate.

Arm4 used fresh cluster and fixed financial-empirical-disk16-v1 profile:
14GiB abort/16GiB hard,20GiB guest-free floor,256MiB raw-proof cap,5s supervision;
old9/10GiB limits preserved. Strict observation started02:39:20.457Z; run aborted
02:41:40.473Z at sampled80% heap guard3435973836B. Actual peak not emitted.
Last valid project allocation11005157376B stayed below14GiB. Closed journal
verifies297280actions/148640paired inputs, no tails; offered span129.572s.
Same source-membership argument bounds any-cut settled count <=148640; target
miss proven, actual deadline count unknown. Neither arm completed economic/
history/replay audit. Exact owned brokers/JVM stopped; volumes retained.

Closed read-only volume audits occur after stop and differ from live peak
allocation observations; do not substitute them for peak or infer causal cost.
Fixed 8GiB permission retains workload/disk limits, changes no kernel/reference
algorithm. User authorized one extra review; review4of4 code/concrete envelope Ready.
Original failures, frozen identities, cleanup and forensic receipts published
in [immutable closed proof](https://github.com/dills122/reef-records/tree/2331d97d9f9e6af8638d6ed335adbfac743d2c03/records/reef/docs/evidence/calcify-financial-sprint1/sprint1-exit-2026-10-06-1c143125049d); exact source bytes and journal reassembly
verified. Local raw session retained until archive lands; no tracked originals removed. Current execution owner: [work plan](work/CALCIFY_HISTORY_LOG.md#calcify-sprint1-exit-continuation--october-6-2026-utc).


Arm5 fixed 8GiB/fresh cluster strict sample03:06:14.705Z; closed03:10:21.499Z.
Wrapper0/PROOF_READY; launcher1/FAIL_DIAGNOSTIC preserves useful target miss.
At60s: offered71296,callback71286,admitted71256,decided/settled63104,pending0;
settled covering rate1051.7333/s. Final 150000 offered/admitted/decided/settled,
300001 complete histories; independent economic/owner/history checks and
result-only replay pass. Replay98.545s; no separately launched managed restart.
Producer132.040s, post-producer drain8.139s; frozen producer,drain,rate and lag
limits fail. Later completion cannot repair deadline miss. Sampled150 individual
offer-to-read-committed-settlement latencies p95=7904ms,p99=8003ms cover full
cohort, not just deadline. Closed source journal300000 exact actions/150000pairs,
3146publication batches, zero tails,112172230source bytes; frame/hash chain/index/
witness/scope/full payloads independently verify against frozen gross fixture.

JVM sampled heap3962293592B excludes final serialization/staged writes; reported
RSS4726587392B separate. Same-JVM process CPU321184ms covers timed load through
full coverage/drain, including worker/producer/observer inline validation. It
excludes later owner digest, result-only replay, final serialization and brokers.
Assessment divides by scheduled60s to
normalize cohort cost;5.353 equivalent cores is not actual core utilization.
Encoded input112172230B, results883951833B; encoded technical store bytes unknown.
53strict live allocation samples peak11773079552B include three broker directories
and registered host store/journal/proof; VM overhead excluded. Probe physical
inventory disk8831485940B has different measurement scope/time, not replacement
for allocated project peak. No continuous memory/disk bound or causal bottleneck
attribution. All owned brokers/JVM stopped; volumes retained. Reviewed implementation
commit1c143125 follows run; actual baseline863503ec+frozen patchda8ad0e573c9d4a17a4da81c425401b08a34f4d3c04b8f301ec4061c1564117b.
[Focused current verification](evidence/calcify-financial-sprint1/e4-verification-2026-10-06.json)
links measured scope and failed original arms; [complete proof](https://github.com/dills122/reef-records/tree/2331d97d9f9e6af8638d6ed335adbfac743d2c03/records/reef/docs/evidence/calcify-financial-sprint1/sprint1-exit-2026-10-06-1c143125049d) and
[delivery review](https://github.com/dills122/reef-records/blob/2331d97d9f9e6af8638d6ed335adbfac743d2c03/records/reef/docs/evidence/calcify-financial-sprint1/sprint1-exit-review-2026-10-06-1c143125049d/reviews/delivery1/report.md) published/verified. Raw output topic records remain
in retained broker volumes, not exported; external independent replay of every
owner/history output is not claimed.

## Calcify architecture diagnostic — October 11, 2026

Scope: offline test kernel, baseline `b9a8756e8d0efc5e8b665a5c62da1ab064d01539`,
Java21/Kotlin2.4.20/Jackson2.22.3,768MiB heap, one hot buyer/seller domain.
No broker, SQL, managed adapter or new capture qualification. Owner clarified
hard10k/s capture/context only, single server; settlement may lag within an
explicit operating envelope. Larger server allowed if justified.

[Research](research/CALCIFY_SINGLE_SERVER_ARCHITECTURE_REVIEW_2026-10-11.md#5-new-experiment-diagnose-the-kernel-before-blaming-its-adapter)
and [compact receipt](evidence/calcify-architecture-pressure-test/verification.json)
record all five runs, initial profiling run and harness changes. Each run warms
2000 trades then executes four10000-trade arms (encode off/on/on/off). Latest
baseline encoded arms3342.42/3371.93 trades/s; scratch-copy streaming canonical
encoder5497.02/5532.94. Seven correctness checks, final economics and1000-case
canonical digest parity pass; history checksum and encoded byte count match.
Original kernel unchanged. Sequential shared-host diagnostic, with warmup/order
effects; no production gain, comparative heap bound or financial10k claim.

Budget model checks5239 finite queue recurrences plus illustrative visibility and
headroom controls. These are arithmetic/toy-model checks, not measured service
capacity or broker fault tests. D7 retains original standing-liquidity/retention
scope; it does not qualify P0 no-reuse state at millions of order IDs.

## Calcify aged single-server contention/recovery — October 11, 2026

[Proof](research/CALCIFY_AGED_CONTENTION_PROOF_2026-10-11.md),
[compact receipt](evidence/calcify-aged-contention/verification.json), and
[retention](records/2026-10-11-calcify-aged-contention-retention.json).
Baseline b9a8756e plus research probe/harness; current Phase1/2, finite P0 off,
64 standing makers, terminal retention0/no eviction,300s@10,500 offered trades/s.
Local Docker10CPU/16,745,824,256B; host HTTP driver on same64GiB Mac. RF1 cached
Redpanda26.2.3 differs from checkout26.2.4; all5topic write.caching=false inspected.
Financial/atomic SQL contender starts, crashes/replays, then resolver cold-restores
while intake continues. Contender uses independent synthetic trades, not captured
trade integration or production financial/SQL materializers.

Completed attempt02:3,063,383 accepted timed trades,64 makers excluded,86,617
scheduler drops, zero HTTP failures/retries. Verified deadline lower3,051,648
(10,172.16/s); resolved lower2,432,098, first following sample2,443,533
(**8,106.99–8,145.11/s; target miss proven**). Full-context end gap upper631,285;
HTTP-completion-to-resolved drain upper29,118.95ms. Bounds are not exact latencies.
Final full-fact parity/ordered uniqueness pass for all3,063,383 contexts. SQL full
history/balance replay passes366,625 synthetic settlements/37domains, including
post-load activity. Restored current domain at nextTrade41,200 in1,289ms excluding
JVM startup. No10k financial rate or integrated financial readiness claim.

Sampled matching memory4.205GiB; resolver final logical state2,332,079,011B plus
old retained1,413,794,317B.146 named-service/financial samples exclude observers
and host driver; no continuous peak or causal attribution. Original D7 fresh
10,440.92/s remains scoped to its different source/config/workload. Smoke passes
15,000contexts/500SQLtrades; attempt01 interrupted by missing Compose profiles,
49,100SQLtrades audit pass, no capture qualification. Corrected attempt02 fresh
cohort; both retained. All owned services stopped, volumes preserved.

## Calcify matched no-cold-restart control — October 11, 2026

[Control/comparison](research/CALCIFY_MATCHED_CONTROL_2026-10-11.md) and
[compact evidence](evidence/calcify-matched-control/comparison.json). Same source
b9a8756e, exact runtime images/resource settings, normalized Compose and300s
workload as preceding treatment:64makers,retention0,10,500 offered/s,1,024workers.
Control generation5/fresh cohort retains financial/SQL start and crash/replay;
resolver container ID/start time unchanged. No cold restart. Prior state retained.

Capture gate PASS:3,131,951 accepted timed trades,18,049scheduler drops, zero
HTTP failures/retries; verified lower10,404.61/s, resolved10,321.21–10,349.52/s.
Aged60–300s resolved lower10,295.95/s. End gap upper35,589; drain upper2,404.86ms
from conservative HTTP completion, not exact per-trade latency. Full source fact
parity/ordered uniqueness pass all3,131,951 contexts. SQL replay/balance audit
passes361,075independent synthetic trades/37domains, including post-load work.

Treatment/control first-minute ranges overlap; control leads382.29–489.73/s
over0–180s and4,705.65–5,032.88/s over180–300s. Difference after subtracting
preperiod lead4,215.92–4,650.59/s is descriptive observation bound, not causal
confidence interval. Timed financial progress higher in control; sequential
arrivals, different financial recovery prefix/timing, broker/SQL restart, cache/
disk occupancy and minor observer/host-check differences prevent pure attribution.
Matching sampled4.71GiB; guest-free minimum15.880GB above8GBreserve. No cold
recovery qualification, host-loss proof or production financial integration.
22focusedNode tests and all audits pass; owned services stopped, volumes retained.

## Calcify finite finance lane non-ledger costs, CAL-FIN-S0 (October 12, 2026)

Diagnostic, finance lane stand-in only; **no ledger, no capture, no financial 10k claim**. Source: branch of PR 514 (`S0EndToEndCostSpikeTest`,
`S0ProjectorCostSpikeTest`, `StateCommitmentCostSpikeTest`; research and broker tags, `-PbrokerTests -PresearchTests`). Host: development MacBook,
Docker Desktop VM, one run per row, local sockets; Redpanda v26.2.3 (single node, `--smp 1`, 1G) and Postgres 16-alpine in disposable containers removed with
`docker rm -f -v`. Streams settings are the production pins (`FinanceTopology.streamsProperties`: EOS v2, one thread, one partition,
`commit.interval.ms` 100, `max.task.idle.ms` 0), persistent RocksDB store with caching disabled and the default changelog. Processor: P puts of 300 bytes per
input and one 100-byte forward; inputs produced in one burst; the clock starts after Streams reports RUNNING.

Streams, inputs per second by run length (same code, same host): **5,000 inputs**: P=0 2,867, P=1 2,927, P=10 2,491. **20,000 inputs**: P=0 11,054, P=1
10,701, P=10 7,643. **100,000 inputs**: P=0 47,880, P=1 43,498, P=10 14,079 (140,788 puts/s). Rates rise steeply with run length because ramp-up dominates short
windows, so none of these is a capacity or ceiling and the 100,000-input figures may still understate steady state.

**Correction (same day):** the 20,000-input result was first reported as "about 7,600 inputs/s, a ceiling" in chat and in design revision 3 (PR 514) and used to
argue a single partition could not carry the goalpost. That inference is withdrawn; the original figures are retained above as superseded by run length, and the
design document carries the correction (section 1.4).

**Sustained run:** 1,000,000 inputs, P=10, the same settings, one run: **90.0 s = 11,111 inputs/s (111,108 puts/s)**. So the 100,000-input figure (14,079/s) also
overstated steady state by about 27%, and the steady-state rate of this stand-in is about 11k inputs/s. Unit caution: a booked and settled trade is **two** finance inputs (a
capture and a settle, each about 6 to 10 store puts), so this is about 5.5k trades/s through this stand-in, against the arena contract's 10,000 trades/s booking and
about 9,091/s settlement proposals (about 19k inputs/s for both). A fresh-lane stand-in with no ledger, no encoding, one partition and one thread; not a ceiling, because
partitioning and tuning were not tried.

Restore: changelog read into the heap (`FinanceBrokerFixture.readAll`, what `FinanceEvidenceReader` does) 200,000 records 1.2 s and 26 to 56 MB held;
1,000,000 records 1.9 s and about 337 MB. Store restore phase (Streams restore listener): 345 ms for 200,000 records; 1,764 ms for 1,000,000; warm restart with an
intact state directory 9 to 12 ms for 0 records. Restart to RUNNING wall clock was 40 to 43 s in every cold and warm case, so it is not restore time; cause not
established (suspected previous group member's session timeout). Not tested: a multi-gigabyte store, a minutes-long sustained run, SIGKILL.

Projector (`FinanceProjector.apply`, Postgres 16, local socket, fresh tables, 10 bindings x 120 capture-and-settle decisions, no JDBC batching inside a call):
batch 1 106 decisions/s (9.47 ms each) at 20,000-input scale and 103 at the 5,000-input scale; batch 10 427 and 413; batch 120 740 and 698. The migration pins
`last_sequence <= 320` per binding, so volume came from many bindings. One checkpoint `FOR UPDATE` lock per call.

Projector, JDBC-batched research variant (test code only; same SQL, `addBatch` per statement class in one transaction, positions pre-aggregated per account and asset,
the in-memory link and digest checks omitted since they cost microseconds): 10 bindings x 120 decisions, 120 per call: 3,688 decisions/s (0.27 ms each) with the plain
driver and 4,313/s with `reWriteBatchedInserts=true`; 10 per call: 676 and 761/s. 100 bindings x 120 decisions (12,000 decisions, tables growing): as written 78/s at 1 per
call, 335/s at 10, 579/s at 120; batched 616 and 572/s at 10, **2,651/s plain and 3,413/s rewrite at 120**. So batching gives about 4.6x to 5.9x over the as-written projector
at 120 per call, the rate falls as the tables grow (740 to 579/s as written), and the batched figure is still about 2.7x below the 9,091/s settlement service rate the
arena contract proposes (a different, finite-lane workload, so a scale comparison only).

Restart floor explained (`S0EndToEndCostSpikeTest.restartFloor`, 1,000 inputs, same state directory and application id, Redpanda v26.2.3): after the default
`KafkaStreams.close(Duration)` a restarted instance took **43.1 s** to reach RUNNING; after `close(CloseOptions().timeout(30 s).leaveGroup(true))` it took **0.5 s** and
**0.4 s** (two repeats). Cause: the default close does not send LeaveGroup, so the restarted member waits for the previous member's 45 s consumer session timeout to
expire. This does not cover a crash or SIGKILL, where the session timeout still applies (the resolver runtime pins `session.timeout.ms` at 6,000 as a precedent; the
finance app does not set one). Crash restart (`S0EndToEndCostSpikeTest.crashRestart`, a child JVM running the real finance topology on the production properties, SIGKILL after 8 s, same
application id restarted in-process): **44.1 s** to RUNNING with the production default session timeout, **6.1 s** with `session.timeout.ms` 6,000 and heartbeat 1,000 (one run each).
Not yet tested: static membership, rebalance behaviour under a 6 s timeout with a slow processing step.

Ledger spike (same branch, corrected after review: exact entity counts, leaf hashed once; indicative, one JVM, in-memory): whole-state digest 2.7 ms at 1,000
entities, 25 ms at 10,000, 344 ms at 100,000; the unbounded part of `checkInvariants` 1.3 ms, 118 ms, 9.6 s (quadratic); seven-entity incremental path-compressed
trie update 27, 45, 44 us. Attempts retained: the first spike (1.25n entities, leaf double-hashed) was superseded by the corrected run; the first S0 Streams
run (20,000 inputs) is superseded by the 100,000-input run. A scheduled warn-only re-measurement and its baseline are described in the stage index.

## Calcify finite finance lane stand-in on the hosted runner, CAL-FIN-S0-HOSTED (October 13, 2026)

Diagnostic, same stand-in and tests as CAL-FIN-S0 (no ledger, no capture, no financial claim), run by the `calcify-finance-throughput` workflow on the GitHub-hosted `ubuntu-latest`
runner: run 38334113934 (master `f0d5adaec`, before the session-timeout pin) and run 38482491956 (master `f7b27717b`, after it), 100,000 inputs per Streams run, Redpanda v26.2.3 and
Postgres 16 in Docker, one run each. Different host from CAL-FIN-S0, so the two are observations, not a causal comparison.

Streams, inputs per second at ten 300-byte puts per input: **8,976 and 8,822** (laptop 14,079); no puts 37,677 and 37,008 (laptop 47,880); one put 36,233 and 34,111 (laptop 43,498).
Projector as written, decisions per second at 1, 10 and 120 per call: 93/469/969 and 97/499/1,087 (laptop 106/427/740). JDBC-batched research variant at 120 per call: 2,782 and
3,147 plain, 3,084 and 3,398 with `reWriteBatchedInserts` (laptop 3,688 and 4,313). Restore: changelog read of 1,000,000 records 1,914 and 1,811 ms (laptop 1,900); store restore phase
3,769 and 4,265 ms (laptop 1,764, so about 2x slower here). **Restart to RUNNING: 43 s cold and warm in the first run, 7 s cold and 4 s warm in the second**: the drop is the consumer
session timeout pinned at 6,000 ms in between (PR519); the default close still does not leave the group, so the remaining seconds are the 6 s eviction plus startup.

Interpretation limits: two runs on a shared runner; the spread between them is 2 to 14 percent per metric, so the workflow's 30 percent warn threshold is wider than the noise. The hosted
figures are now the workflow's comparison baseline (`docs/work/calcify-throughput-baseline.json`, mean of the two runs; restart rows from the second run only). No new capacity claim.


## Calcify finite finance Streams lane tuning, CAL-FIN-P2 (October 13, 2026)

Diagnostic, laptop (Apple M1 Max, 64 GiB, Docker 29.7.2, Redpanda v26.2.3 single node `--smp 1 --memory 1G` unless named, RF1, EOS v2, one stream thread per lane, caching off unless named),
`S0StreamsLaneTuningSpikeTest` (research tag; branch `claude/calcify-streams-lane-tuning`, base master `7cea03270`). The stand-in of CAL-FIN-S0 (no ledger, no capture): ten 300-byte store puts per input and one
forwarded output, the production `FinanceTopology.streamsProperties` plus the override named. Ten puts per input is the order of the real processor's writes per decision (entities, history entry, head, meta), not a
measured count. Rates are inputs per second end to end (produce, process, read-committed output) over the whole run; 200,000 inputs per lane unless noted; one run per cell unless several are listed.

Before the change (Kafka producer defaults): 9,342 / 9,242 / 9,316 / 11,357 (four runs), **11,781 sustained over 1,000,000 inputs (84.9 s)**, in line with the 11.1k of CAL-FIN-S0.
Overrides that did not clearly help: `commit.interval.ms` 500 8,017 and 1,000 14,201 (noise-sized, non-monotonic); consumer `max.poll.records` 5,000 with larger fetch 15,296; two of the ten puts on a hot key, record cache off 13,436 and on 16,104;
a broker with `--smp 2 --memory 2G` 10,970; two lanes 15,102 and four lanes 15,544 in total (no scaling across independent lanes with the default producer). Fewer puts: four per input 31,358.
**Producer batching (`batch.size` 262,144, `linger.ms` 20): 33,603 / 34,735 / 32,622 / 34,667 (200,000 inputs), 23,821 and 23,945 (two runs of 1,000,000, 42 s), 34,968 (3,000,000, 85.8 s)**, so 2.0x to 3.7x over the defaults;
with `compression.type=lz4` 30,493; sweep: 64 KiB and 5 ms 24,872, 256 KiB and 5 ms 30,432, 256 KiB and 50 ms 31,512, 1 MiB and 20 ms 35,571; with the 500 ms commit interval 28,174; with the record cache and two hot keys 31,152.
With batching, independent lanes scale sublinearly: two lanes 41,173 in total (20.6k each), four lanes 48,935 (12.2k each).

Measurement note: the clock includes the output consumer's group join (about 1 to 3 s, started after production), so the 200,000-input figures are conservative by up to a third; the 1,000,000- and 3,000,000-input figures are not materially affected.

Interpretation limits: the 1,000,000-input and 3,000,000-input batched runs differ by 47 percent (23.9k against 35.0k) and the cause is not established (machine state, compaction, or run length), so the sustained claim is the lower figure. The stand-in has no ledger,
no capture decode, no real key sizes; the real lane's puts per input are unmeasured; the projector, the Postgres write path and the lane were not run together on one machine; the arena contract's need is about 19k inputs/s (10,000 booked and 9,091 settled
trades/s). Hosted-runner figures not taken. The change is now the production default (`FinanceTopology.PRODUCER_BATCH_BYTES`, `PRODUCER_LINGER_MS`, pinned to exactly these values by `verifyPinned`); the weekly workflow's Streams rows will read "improved" against the old hosted
baseline until that baseline is re-taken. Latency cost: up to 20 ms added before a decision is visible. The finance broker, launcher, crash and projection suites passed unchanged on the new properties (0 skipped).
## Calcify finite finance projector capacity spike, CAL-FIN-P1 (October 13, 2026)

Diagnostic, laptop (Apple M1 Max, 64 GiB, Docker 29.7.2, Postgres 16-alpine, local socket, `synchronous_commit` on, `fsync` on unless named), `S0ProjectorCapacitySpikeTest`
(research tag, branch `claude/calcify-projector-capacity-spike`, head `a9c948e74` plus the spike). Synthetic rows of the product shape (decision per input, obligation on capture, attempt, obligation
update and four position deltas on settle), 30,000 trades = 60,000 decisions on **one uncapped binding** (migration 0014 with its two 320 bounds lifted), 1,000 accounts, one connection, one
transaction per call, checkpoint row locked and advanced per call. No ledger, no hash-link or input-digest checks, no Kafka read side, no concurrent readers. One run per cell unless a repeat
is listed; the same cell repeated varied by up to 10 percent. Trade = capture + settle = 2 decisions, so the arena contract's 10,000 booked trades/s is 20,000 decisions/s.

Write strategy, decisions per second overall (decisions per call 120 / 1,000 / 5,000): **A. JDBC batches** with `reWriteBatchedInserts` 2,678 / 4,588 / 6,004; **B. set-based `unnest()` arrays,
one statement per table** 3,858 / 5,967 / 9,242; **C. `COPY` into session staging tables then one INSERT SELECT per table** 1,941 / 6,268 / 7,236. Per-call time grows with batch size
(p50 for B: 29 ms at 120, 149 ms at 1,000, 569 ms at 5,000), so the cost is work inside Postgres, not round trips. Strategy B at 5,000 per call repeated: 11,783, 12,425, 12,678 and 12,486
(db-only excluding row construction 12,899 to 12,976); the 9,242 above was the first run of the matrix and is an outlier to the low side.

What the floor is made of (strategy B, 5,000 per call, one run each unless noted): `synchronous_commit=off` 12,448 and `fsync=off, full_page_writes=off, synchronous_commit=off` 12,487 against
12,486 default, so **commit durability is not the cost**. Dropping the four foreign keys to the checkpoint table: **17,350** (+39 percent); dropping the attempt-to-obligation key: 13,424 (+7);
dropping the decision `UNIQUE (binding, action)`: 13,166 (+5); dropping all foreign keys and that unique: **20,195** (21,017 with the durability settings off too). At 1,000 per call the
same sequence is 7,222 default and 9,602 without the keys. Last-quarter rates matched the overall rates (no visible fall as the tables reached 60,000 decisions; 120,000 not tried).

Interpretation limits: constraint removal is a diagnostic, not a recommendation; the binding foreign keys are redundant with the per-call `FOR UPDATE` on the checkpoint, the attempt key and the
action unique are integrity guards that would need a replacement argument before removal. The figures are the projector's database cost only; the Streams lane stand-in measured
11.1k inputs/s (about 5.5k trades/s) and shares the machine, so end to end is lower than either. Hosted-runner and Linux-native Postgres figures not taken. No capacity claim.

## Calcify finite finance ledger at scale, CAL-FIN-P3 (October 13, 2026)

Diagnostic, laptop (Apple M1 Max, 64 GiB, JVM test heap 12 GiB), `S3cLedgerScaleSpikeTest` and `S3dLaneWithLedgerSpikeTest` (research tag; branch `claude/calcify-ledger-scale-spike`, base master `7cea03270` plus PRs 531 and 532). In-memory prototype in
test code (`ScaleLedgerPrototype`): O3a's decision semantics for capture, fund and settle, O(touched) work per decision (aggregates and local invariants instead of the whole-state check), and a path-compressed binary trie over SHA-256 of the entity key as the
incremental commitment (design section 4.1). No overlay or abort journal, no store-backed view. 5,000 accounts, random buyer and seller, one capture plus one settle per trade, which is **5 entities per pair and 2 decisions**.

Correctness first: the prototype and the real `FinanceLedger` agree on disposition, recorded-or-not and legs for **48,000 inputs over 400 seeds (all 18 dispositions, per-class budgets enforced)** and on positions after every input. The trie root equals a full rebuild
from the entity tables (shuffled insertion order) at K = 1, 7 and 100, and at 100,002 and 1,000,002 entities (0.2 s and 2.0 s). With the commitment on another thread, the digest a decision carries is the root at the batch boundary one full batch behind it, identical across
two runs whatever the thread timing, and equal to the synchronous root at that boundary for every decision checked (more than 1,000). Mutants killed: an off-by-one in the cash check, a trie path not marked dirty, dropped deferred writes, an off-by-one in the lag.

**In memory, decisions per second at 100,002 / 1,000,002 / 5,000,002 entities** (final code, one run each): state digest on every decision (K=1) **43,615 / 37,009 / 28,325**; root every 100 decisions on the deciding thread **54,557 / 44,517 / 31,309**; root every 100 decisions on a second
thread **50,710 / 45,518 / 34,343** (at 5,000,002 entities the second thread is the bottleneck: the decider waited on it for 37.6 s of 46.6 s). Heap 366 to 377 bytes per entity (1.77 GiB at 5,000,002). With no commitment at all about 130k to 225k and 160 bytes per entity. The
current ledger's whole-state digest alone is 25 ms at 10,000 entities and 344 ms at 100,000 (CAL-FIN-S0), about 3 to 40 decisions/s.

**Inside the Streams lane (S3d)**: the prototype in a Streams processor on Redpanda v26.2.3 (EOS v2, one thread, persistent store, caching off, producer batching 262,144 and 20 ms), 800,000 inputs, each decision followed by a put of every touched entity plus a history entry, a head and a meta
record (8 puts per input on average) and one 250-byte output. Inputs per second, two runs (A / B): Streams and puts with no ledger 36,786 / 32,631; ledger without commitment 36,987 / 30,201; **state digest every decision 17,937 / 16,782; root every 100 on the deciding thread 18,850 / 19,611; the same
with the ledger rotated every 1,000,000 entities 19,919 / 20,881, every 250,000 entities 22,488 / 19,767; root every 100 on a second thread 31,742 / 30,899; second thread with rotation every 1,000,000 entities 32,107 / 27,597, every 250,000 entities 30,743 / 24,953; root every 1,000 on a second thread with rotation
every 250,000 entities 31,011 / 31,066.** Earlier runs of the final code in the same cells: second thread 29,407, with rotation 33,959 and (K=1,000) 32,756. The decider waited on the commitment thread 0 to 1.4 s in 25 s (K=100) and almost never at K=1,000. Rotation starts a new ledger, carries the closing
balances as 10,000 funding decisions (counted in the time) and a new store key prefix, and seals by waiting for the commitment thread; the old prefix is never cleaned up. The lane without a ledger varied 32.6k to 42.8k between runs, so differences under about 15 percent are noise.

Corrections kept. The first version of this spike (eager path hashing, an O(depth squared) descent for a representative key, a slow hex formatter, a fresh `MessageDigest` per hash) measured in memory 22,992 / 17,847 / 12,701 with a digest per decision and 12.0k inputs/s in the lane; removing the descent and
deferring entity writes to root time gave a first set of figures (21.9k at 10^6 entities with a digest per decision; 18.3k to 22.1k in the lane at K=100); a first off-thread run then showed no gain (17.9k to 22.0k) because the formatter and per-call digests, not the trie, were the contention. Fixing both gave the figures above. The earlier
tables in the design and the per-decision "cost is 90 percent the commitment" statement came from the slower code and are superseded; the commitment is still the largest single cost, but a smaller share.

Interpretation limits: the prototype is not the design's engine (no staging overlay for multi-decision records, no store-backed view, no strict canonical encodings, no config-v2 pins), and the trie is the spike's transcription, not a reviewed specification. Lane inputs are generated from the record ordinal (no protobuf decode,
no restore cut, no history chain), so a real lane is slower. The lagged digest means a decision attests a state between K and 2K decisions older than its own, which changes what restore and audit verify (a checkpoint is verifiable against the digest carried by decisions K to 2K later); that is a design change not yet specified. The second
thread needs a second core. One or two runs per cell on a laptop. Heap of about 370 bytes per entity makes the 15 million entities of a 5-minute session at 10,000 trades/s about 5.5 GiB in one ledger, which is why rotation is still needed. No capacity claim.

## Calcify sharding by instrument: cash sub-account rebalancing, CAL-FIN-P6 (October 14, 2026)

Simulation, not a system measurement: `S5ShardingCashSimulationTest` (research tag; pure JVM, no Kafka, Docker or Postgres). Option S1 of the sharding analysis shards by instrument and gives each participant a cash sub-account per shard; this counts how often a trade finds the buyer's cash in the wrong shard, i.e. the cross-shard cash moves a rebalancing rule would have to issue.
**The workload is an illustrative assumption, not the arena's**: 100 participants, 64 instruments, one price of 100, quantity 1 to 10, uniformly random counterparties, instrument popularity uniform or Zipf(1.0), seller holdings of 1,000 per instrument, immediate settlement, 2,000,000 trade attempts, one seed. No credit, reservation, open orders or time. Cash is conserved (asserted); one shard never moves cash (asserted).
Opening cash 1,000,000 per participant ("ample"), cash moves per 1,000 trades (share of trades needing a move) at 1 / 2 / 8 / 64 shards: pull exactly the deficit: 0 / 0 / 0 / 17.0 (1.7 percent); refill to the opening share: 0 / 0 / 0 / 1.3 (0.13 percent). Zipf gives the same within 0.3.
Opening cash 200,000 ("tight"; true shortfalls of 0.05 percent exist even in one shard): pull the deficit: 0 / 3.5 / 24.7 / 157.7 (0.35 percent at 2 shards, 2.4 at 8, 15.4 at 64); refill: 0 / 2.9 / 45.3 / 120.8 (0.29, 2.5, 8.0). Zipf within about 20 per 1,000 of uniform.
Reading: with two or three shards the cross-shard cash traffic is at most a few trades in a thousand even with tight cash, and zero with ample cash; it rises steeply only at 64 shards. So a small number of instrument shards is cheap in cash movement under this workload; throughput, not cash, picks the shard count.
**Load balance, computed (not simulated):** splitting 64 instruments with Zipf popularity into shards with the best greedy partition, the busiest shard carries (shards 2 / 3 / 4 / 8): exponent 1.0, hottest instrument 21 percent of trades: 50 / 33 / 25 / 21 percent; exponent 1.2 (29 percent): 50 / 33 / 29 / 29; exponent 1.5 (42 percent): 50 / 42 / 42 / 42. A single hot instrument therefore caps the speedup from sharding by instrument at 1 / (its share): 2.4x at 42 percent.
Interpretation limits: no workload from the arena was used; no reservation or credit decision interacts with the cash move; the cost of a cross-shard move (two shards, ordering, failure) is not measured; one seed. No capacity claim.
## Calcify finite finance end to end on one machine, CAL-FIN-P4 (October 14, 2026)

Diagnostic, laptop (Apple M1 Max, 64 GiB, Docker 29.7.2; Redpanda v26.2.3 `--smp 1 --memory 1G`, Postgres 16-alpine, the Streams JVM, the producers and the tailer all on this machine and its Docker VM), `S4EndToEndSpikeTest` (research and broker tags; base: PR 533's
branch). The first measurement of the whole chain, run to find the binding term. Producers send real `FiniteLifecycleCaptureV1` envelopes (CAPTURE) and real `FinanceInputV1` Settle and Fund inputs (CONTROL); settles are produced only after the capture decision has been read back. The Streams processor
does the real envelope parse, schema, binding and content-digest check and capture mapping, the S3 O(touched) ledger prototype (commitment on a second thread, K=100 unless named), the real history-entry chain and encoding, the real `FinanceWire` decision protos, and writes every touched entity, a history
entry, a head and a meta record to a persistent store with a changelog (EOS v2, producer batching 262,144 and 20 ms). A tailer decodes the DECISIONS topic with the real proto decode, and a set-based (`unnest`) projector applies up to 5,000 decisions per transaction to Postgres (checkpoint row locked, one
transaction per batch, uncapped copy of migration 0014; "nobindingfk" drops the four foreign keys to the checkpoint). 100,000 trades = 200,000 inputs plus 2,000 funding inputs, 1,000 accounts drawn from a pool after the real mapping. After every run the projected positions equal the processor's ledger positions, the
settled-obligation count equals, and the checkpoint is at the last decision.

**Lane alone** (projector off, three runs): 13,251 / 11,389 / 12,822 inputs/s. **Whole chain, product constraints**: lane 12,151 and 13,041 inputs/s, projected 10,426 and 11,022 decisions/s, the projection finishing 2.7 and 2.8 s after the lane. **Whole chain, without the binding foreign keys**: lane 12,905 and 12,170, projected 12,072 and 10,398.
**Commitment on the deciding thread** (K=100, product constraints): lane 9,510, projected 8,843, with `decide` taking 30.0 microseconds per record against 9.2 to 9.6 with the second thread. One run at K=1,000 gave lane 9,622 and projected 9,231 while every section of the processor ran about 20 percent slower, so it is treated as machine noise, not a K effect.
**Where the processor spends its time** (microseconds per record, 202,000 records, about 68 in total inside `process`): capture envelope handling 18.1 (per capture record: protobuf parse 18.0, schema, binding and content-digest check 16.4, mapping 2.1), other decode 4.6, `decide` 9.2, history entry 9.7, store puts 19.7, output encode and forward 7.0. The lane's 11.4k to 13.3k inputs/s means another 15 to 25 microseconds per record outside `process` (poll, commit, producer, changelog).
**Candidate savings** (`S4_LEAN`: skip the content-digest recompute and write head and meta every K records instead of every record; each has a semantic cost): processor about 50 to 57 microseconds per record; lane 15,664 and 15,143 with the projector (projected 13,450 and 13,525) and 16,622 with it off.

Interpretation limits: one machine shares CPU between Redpanda, Postgres, the JVM and the load generator, so every figure is a lower bound for dedicated hosts and an upper bound for nothing; the ledger is the prototype (no overlay or abort journal, no store-backed view), the projector skips `verifyLink`, the decision log is read as a stream, there is no restore cut, and the fixture binding has two accounts so buyers and sellers are re-drawn from a pool after the real mapping.
Run-to-run spread in the repeated cells is up to 15 percent (the lane alone ranged 11.4k to 13.3k). The 10,000 booked trades/s of the arena contract is 20,000 inputs/s for this chain (a trade is a capture plus a settle). No capacity claim.

## Calcify finite finance sharded lanes and CPU cost per decision, CAL-FIN-P5 (October 14, 2026)

Diagnostic, same machine and test as CAL-FIN-P4 (`S4EndToEndSpikeTest`, now with `S4_LANES`): N independent shards in one JVM, each with its own topics, Streams application, ledger prototype, tailer, projector and binding digest, **disjoint accounts** (the best case for sharding: no cross-shard trade), sharing one Redpanda
(`--smp 1 --memory 1G` unless named) and one Postgres. 100,000 trades per lane, 1,000 accounts per lane, K=100 with the commitment on a second thread, projector without the four binding foreign keys. Each lane's projection equals its own ledger after every run.
**The machine was not quiet.** Other work on it (other agent sessions and test suites) held the load average between 8 and 50 during these runs (recorded per run in the output); treat every figure as a lower bound for dedicated hardware, and the spread as real.

First matrix, one run each, lanes together (inputs/s): 1 lane 9,600; 2 lanes 17,962; 3 lanes 20,260; 4 lanes 18,332; 2 lanes on a broker with `--smp 2 --memory 2G` 19,186; 4 lanes on that broker 8,442 (load average then 40 to 50, an outlier); 2 lanes with the candidate savings 15,548; 3 lanes with them 8,877 (outlier); 2 lanes with the commitment on the deciding thread 9,596.
Interleaved repeat matrix (three rounds, whole chain, projected decisions/s; medians in brackets): 1 lane 11,134 / 9,235 / 11,968 (**11,134**); 2 lanes 14,779 / 15,340 / 19,485 (**15,340**); 3 lanes 15,445 / 17,532 / 17,992 (**17,532**). With the cached pointer store and the content-digest check on a verifier thread ("free"): 1 lane 11,692 / 10,839 / 11,973 (11,692); 2 lanes 15,161 / 15,246 / 17,451 (15,246); 3 lanes
11,366 / 15,139 / 14,944 (14,944). **The free variant showed no clear gain on this machine** (the verifier is one more thread competing for cores).
So adding lanes helps but sublinearly on this host: about 1.4x at two lanes and 1.6x at three, and only one run (19,485 at two lanes, load average 14.8) reached the 20k need.

**CPU cost per decision, which does not depend on the neighbours' load as much as the rate does** (microseconds of CPU for the JVM including producers and tailers, the Redpanda container, the Postgres container; container CPU read from cgroup `usage_usec`): 1 lane 203 + 19 + 81 = 303 and 206 + 20 + 67 = 292; 2 lanes 220 + 21 + 88 = 329. At 20,000 decisions/s that is **about 5.8 to 6.6 cores** of this machine's cores for the whole chain,
with the load generator (producers) and Docker VM overhead included in those figures.

Interpretation limits: see CAL-FIN-P4 (prototype ledger, no restore, no overlay, stream-read decision log, two-account fixture binding re-drawn from a pool). Disjoint accounts do not model the cross-shard problem that real sharding has to solve (a trade between participants in different shards); sharding by instrument still shares cash. Lanes share one JVM, so GC and JIT are shared (GC 0.5 to 2.7 s per run).
The CPU figures include the in-JVM load generator and one untimed warm-up; a deployment with separate producers would show less JVM CPU. No capacity claim.

## Calcify finite finance on a dedicated host, and why it was slower than the laptop, CAL-FIN-P7 (October 14, 2026)

Diagnostic, same test as CAL-FIN-P4 and P5 (`S4EndToEndSpikeTest`), run on one DigitalOcean CPU-optimized droplet (`c-16`: 16 vCPU, 31 GB, Intel Xeon Platinum 8168 at 2.7 GHz under KVM, ext4 on the virtual disk, Ubuntu 24.04, OpenJDK 21.0.12, Docker 29.9, Redpanda `--smp 1 --memory 1G` and Postgres 16 in containers, `git archive` of tracked files only). Steal time was 0.4 percent and the host was never CPU-idle-starved (a third idle at three lanes), so neighbours are not the explanation. Droplet created and destroyed in this session; no benchmark droplet, firewall or volume remains.
100,000 trades (200,000 inputs) per lane, 1,000 accounts per lane, K=100, commitment on a second thread, projector without the binding foreign keys. Rates are lanes together (inputs/s, which equal decisions/s) and the projected decisions/s through the whole chain. Run-to-run spread on one identical cell was 4,273 to 5,559 inputs/s (one lane with the projector), so differences under about 15 percent are noise.

**First matrix, as the earlier ledger entries ran (JaCoCo agent attached, default G1):** 1 lane 4,921 inputs/s (projected 4,228); 2 lanes 7,728 (6,954); 3 lanes 8,895 (8,392); 4 lanes 9,513 (9,198); 3 lanes on a broker with `--smp 2 --memory 2G` 8,685 (8,148); 1 lane with the projector off 5,352. CPU per decision (JVM + Redpanda + Postgres, microseconds): 576 + 53 + 218 = 846 at one lane, 575 + 38 + 241 = 854 at two, 679 + 37 + 269 = 986 at three, 751 + 40 + 317 = 1,108 at four, against 203 + 19 + 81 = 303 on the laptop (CAL-FIN-P5). Every component was 2.7 to 2.8x the laptop's CPU. Inside `process`: 168 to 182 microseconds per record at one lane against 82 on the laptop.

**Why it was slow, by measurement:**
1. **The JaCoCo agent was attached to every capacity run in this ledger, laptop included.** `build.gradle.kts` applied it to the `test` task; it instruments every class the test JVM loads (Kafka, protobuf, RocksDB). Without it (`-PnoJacoco` on the droplet, the same switch now tied to `-PresearchTests`): droplet one lane with the projector off 5,352 to 6,827 inputs/s (+28 percent), CPU 530 to 471 microseconds; **laptop one lane 11.5k to 17.9k inputs/s (projected 15.0k), three lanes 15.5k to 32.2k (projected 27.6k), CPU per decision 303 to 233 (one lane) and 221 (three lanes), processor 82 to 47 microseconds per record.** The laptop figures in CAL-FIN-P4 and P5 were therefore understated by about a third to a half; their ratios between variants are still valid.
2. **Per-thread speed of this CPU class.** A single-thread JVM microbenchmark (string building, UTF-8 encode and `HashMap`, no I/O) ran 3.75 M ops/s on the droplet against 7.17 on the laptop (1.9x); the real processor, which has a larger working set, 2.8x (131 against 47 microseconds per record, both without JaCoCo). Random 64 MB pointer chase is the same (111 against 105 ns), so main memory latency is not the gap; single-thread copy bandwidth is 4.6 against 22.8 GB/s. SHA-256 of 64 bytes is **471 ns against 61 ns** because the Xeon 8168 has no SHA instructions; this costs the commitment thread (42 percent of its samples) and almost nothing on the processor thread (2 percent), so it does not set the lane rate.
3. **16 vCPUs are about 9 cores of work.** The same microbenchmark on 1, 8 and 16 threads gave 3.75, 3.05 and 2.20 M ops/s per thread (35.2 M in total, the laptop's 10 cores give 37.5 M); the vCPUs are hyperthread-like and the all-core clock sags. Lanes therefore scale sublinearly well before the vCPU count is used (with the projector, 9.2k projected at four lanes while only about 60 percent of vCPU time was busy).
4. **The projector costs the lanes about 25 percent even with CPU to spare.** One lane, same build: 6,941 inputs/s with the projector off, 5,158 and 5,133 with it on (processor time per record only 131 to 141 microseconds, so the lane spends about 53 microseconds per record outside `process`, against 13 with the projector off). A JFR profile of the processor thread with the projector on shows 19 percent of its samples in `EPollSelectorImpl.doSelect` (waiting for the broker in poll, send or commit) and 6 percent in `RocksDB.put`. **Not the cause (each tested):** storage speed (Redpanda and Postgres data on tmpfs: 5,559, 4,962 and 4 lanes 18,446 against 16,357 on disk; the disk does 4 KiB `dsync` writes at about 1.5 ms but nothing waits on it), broker cores (`--smp 2` and `--smp 4`: 3,807 and 3,859 at one lane, no gain), Docker's userland proxy (`--network host` for the broker: 4,868 and 4,603; proxy CPU 3 s), transparent huge pages (5,397 against 5,341), GC pauses (22 pauses, 0.6 s of 42 s). Left open: the projector and tailer share the lanes' JVM and allocation; a separate projector process is the test that would settle it, and it is also the deployment shape.

**What helped (one lane, projector off, no JaCoCo unless said):** no JaCoCo, +28 percent (droplet) and +55 percent (laptop); `-XX:+UseParallelGC` over G1, +12 percent with JaCoCo (5,341 to 6,070) and +2 to +7 percent without it (6,827 to 6,941; three lanes 16,950 to 18,186), CPU per decision about 12 percent lower; pinned in neither the test nor the runtime yet.
**Best droplet figures, no JaCoCo and `-XX:+UseParallelGC`, whole chain with the projector (lanes together / projected decisions/s; CPU per decision in microseconds):** 1 lane 5,158 / 4,442 (739); 3 lanes 12,855 / 10,819 (671); 4 lanes 16,357 / 14,008 (686); 5 lanes 18,656 / 15,834 (696). With the projector off: 3 lanes 18,186 inputs/s (360). At 20,000 decisions/s the 671 to 696 microseconds is **13.4 to 13.9 vCPUs of this class**, and given the hyperthread and projector effects above the measured rate (15.8k projected at five lanes using about 11 vCPUs) says a 16-vCPU host of this CPU class is short of the need and a 32-vCPU host is the smallest plausible one. The laptop without JaCoCo reached 27.6k projected decisions/s at three lanes (4.4 cores); no DigitalOcean size has been measured that matches that per-core speed.
Where the time goes on the droplet (JFR, one lane, projector off): processor thread 49 percent protobuf parse and serialise, 25 percent the content digest's canonical strings (`FiniteLifecycleContract.digest`, `Canon`), 19 percent `decide`, 2 percent SHA-256; commitment thread 42 percent SHA-256, the rest trie insert and settle and `Arrays.fill`; the load generator (main thread) 62 percent protobuf and 54 percent hex and string encoding. These are all prototype and fixture paths that a production processor shares only in part (the capture is parsed once, the digest is recomputed only if the content-digest check stays).

Interpretation limits: see CAL-FIN-P4 and P5. One droplet, one run per cell unless stated, same spread as the laptop; the JVM flag and JaCoCo effects were measured one lane at a time and carried to more lanes only where the table says so. The droplet had one Redpanda core and the projector shared the lanes' JVM. No capacity claim.

## Calcify finite finance on a newer CPU class, and the disk that decided it, CAL-FIN-P8 (October 14, 2026)

Diagnostic, same test and settings as CAL-FIN-P7 (`S4EndToEndSpikeTest.wholeChainOnOneMachine`, 100,000 trades and 1,000 accounts per lane, K=100, commitment on a second thread, no binding foreign keys, JaCoCo off, `-XX:+UseParallelGC` unless named), run on one DigitalOcean "v5 General Purpose" droplet (`g5-16vcpu-32gb-80gb`, region `atl1`, $0.588/h): 16 vCPU, 31 GB, **AMD EPYC 9555P** under KVM, ext4 on the virtual disk, OpenJDK 21.0.12, Docker 29.9. Created and destroyed in this session (35 minutes); an API check afterwards shows only the owner's two droplets and their firewalls.

**The CPU class is fast.** Microbenchmarks (`scripts/dev/calcify-financial/droplet-microbench/`): single-thread string and `HashMap` loop 9.6 M ops/s (laptop 7.2, c-16 Xeon 3.75); 16 threads together 54.2 M (laptop 10 cores 37.5, Xeon 35.2), so 16 vCPUs scale to 5.6x one thread here against 9.4x on the Xeon; SHA-256 of 64 bytes 82 ns (Xeon 471, laptop 61); 64 MB pointer chase 87 ns; copy 22.8 GB/s (same as the laptop). Processor time inside `process` 37 to 42 microseconds per record (laptop 47, Xeon 131); CPU per decision with the projector 204 to 269 microseconds (laptop 221 to 233, Xeon 671 to 739).

**The root disk is what limited it, not the CPU.** Synchronous 4 KiB writes (`dd oflag=dsync`) took 43 to 57 ms each (500 writes in 21 and 29 s), against 1.5 ms on the c-16 droplet. Redpanda and Postgres both write synchronously, so every extra lane queued behind the disk. Data on the root disk, projector on: 1 lane 7,674 inputs/s (projected 6,872); **2 lanes 5,336 (5,303); 3 lanes 3,801 (3,799)**: more lanes made it slower while CPU per decision stayed at 133 to 140 microseconds of JVM. One lane with the projector off 11,457. The scripted matrix was stopped there to save the droplet's time and rerun with the data directories on tmpfs.
Redpanda and Postgres data on `tmpfs` (6 GB each; `S4_RP_DOCKER`, `S4_PG_DOCKER` environment hooks, in the S1 prototype change): 1 lane 11,557 inputs/s (projected 10,270); **3 lanes 47,660 (42,890), a second run 48,337 (44,627), G1 instead of ParallelGC 44,819 (41,747); 5 lanes 51,718 (51,061); 7 lanes 52,378 (51,686)**. CPU per decision 199 to 234 microseconds, **about 4.0 to 4.7 cores at 20,000 decisions/s**; projector backlog drained within 0.2 to 1.4 s of the lanes. That is 2.1x to 2.6x the 20,000 decisions/s need and flat from five lanes on.
Which disk: Postgres on tmpfs alone, 3 lanes 8,957 inputs/s; Redpanda on tmpfs alone, 3 lanes 12,291 (projected 9,435, the projector then 15 s behind on its own disk). So the broker's disk and the projector's disk each cap the chain at about 9k to 12k on this volume and both must be fast. Redpanda `--set redpanda.write_caching_default=true` and `--unsafe-bypass-fsync=true` with `synchronous_commit=off` on the disk gave no change (4,155 and 3,736 inputs/s at 3 lanes), so the cost is the write path to this volume, not only `fsync`.

**S1 instrument shards on tmpfs, ample cash, 300,000 trades, 1,000 participants (the prototype protocol, not tuned):** 1 shard 9,534 decisions/s together, **4,502 booked trades/s** through the whole chain; 2 shards 11,716 and 5,019; 3 shards 13,593 and 5,408; no cash moves planned. The prototype's lanes are about a quarter the speed of the S4 lanes (same hardware: 47k at three lanes), because its settle path is gated on the decision read-back; it is not evidence about the sharding design, and the 10,000 booked trades/s target is 20,000 decisions/s.
Not measured on this host: `c-32`, a separate projector process, a durable fast volume (DigitalOcean local NVMe sizes, `gd-` and `c2-` "2x SSD" sizes, were not tried), lanes beyond seven.

Interpretation limits: see CAL-FIN-P4, P5 and P7. tmpfs is not durable and not a deployment; it measures what the CPU, JVM and the chain code can do when storage is fast, and the disk figures measure what one slow volume does. One run per cell except the three-lane tmpfs pair (47,660 and 48,337). The v5 line's disk was far slower than the older line's, so storage latency must be measured on any venue before a rate is read from it. No capacity claim.

## Calcify finite finance on an 8 vCPU droplet with RAM-disk data, and what the run writes, CAL-FIN-P9 (October 14, 2026)

Capacity-plan check (option A: a short-lived box with the broker and Postgres data on tmpfs; a lost host means rerun from the captured inputs). Same test and settings as CAL-FIN-P8 on one `g5-8vcpu-32gb-50gb` droplet (`atl1`, $0.360/h): 8 vCPU, 31 GB, AMD EPYC 9555P (single-thread microbenchmark 9.1 M ops/s, as on the 16 vCPU box), projector on, `-XX:+UseParallelGC`, JaCoCo off, `S4_RP_DOCKER`/`S4_PG_DOCKER` tmpfs of 6 GB each. The droplet lived 8 minutes (about $0.05) and was destroyed; an API check shows only the owner's two droplets and their firewalls.

**Rate (lanes together inputs/s, projected decisions/s through the whole chain; CPU per decision in microseconds):** 1 lane 10,477 / 9,412 (285); **2 lanes 28,460 / 24,462 (237) and 29,185 / 25,338 (226)**; 3 lanes 31,127 / 28,302 (238); 4 lanes 31,035 / 30,057 (246). The 20,000 decisions/s need (10,000 booked trades/s) is met with two lanes and about 22 to 27 percent to spare; four lanes reach 1.5x. Processor time inside `process` 45 microseconds per record at one lane, about 60 at two, 82 at three, 112 at four (the lanes share eight vCPUs, so per-record time rises as they fill them). One run per cell except two lanes.

**What a run writes, which is the real sizing constraint** (largest data directory seen by a 2 second sampler; every topic including the Streams changelog, no retention trimming): 2 lanes (400,000 decisions) Redpanda 1.59 GiB and Postgres 1.17 GiB; 3 lanes (600,000) 2.37 GiB and 1.74 GiB. That is **about 4.3 KB of Redpanda log and 3.1 KB of Postgres data per decision, 7.4 KB together**. At 20,000 decisions/s that is **about 148 MB/s, 8.9 GB/min and 530 GB/h**. A 32 GB box with 12 GB of JVM heap leaves about 20 GB for tmpfs, which is **about 2 minutes of full-rate run**; 10 minutes needs about 90 GB. The one-lane Redpanda figure (56 KiB) is a sampling miss.
The Postgres figure is the projection itself (with its indexes) and cannot be trimmed without changing the model; the Redpanda figure includes topics that are only needed to restore and replay (changelog, consumed captures) and could be trimmed by retention, which was not tried.

Interpretation limits: see CAL-FIN-P4, P5, P7, P8. Short fixture runs (100,000 trades per lane, 14 to 26 s); sustained-rate and restore behaviour at run lengths of minutes to hours are not measured. Sizes come from the fixture's accounts and trades and include the changelog and indexes as written in a short run. The droplet script's three "case failed" lines in this run came from the size sampler's `pkill` ending the remote shell after a good result; fixed in the script change. No capacity claim.

## Calcify arena baseline A: one 32 vCPU server, everything on its root disk, full retention, CAL-FIN-P10 (October 14, 2026)

Baseline for the arena profile (owner decisions 2026-10-14: one server, durable disk, full retention, the 20,000 decisions/s need held for the whole session; simulator-only needs deferred). Same test and settings as CAL-FIN-P7 to P9 (`S4EndToEndSpikeTest.wholeChainOnOneMachine`, 100,000 trades and 1,000 accounts per lane unless stated, K=100, commitment on a second thread, projector on without the binding foreign keys, JaCoCo off, `-XX:+UseParallelGC`, Redpanda `--smp 1 --memory 1G`, no retention trimming), run by `droplet-chain-run.sh baseline` on one DigitalOcean `c-32` droplet (`sfo2`, $1.00/h): 32 vCPU, 64 GB, Intel Xeon Platinum 8168 (the same CPU class as CAL-FIN-P7), ext4 root disk of 387 GB, OpenJDK 21.0.12, Docker 29.9. Broker and Postgres data on the root disk. Lived 18 minutes (about $0.30) and was destroyed; an API check shows only the owner's two droplets and their firewalls.
Host: single-thread microbenchmark 3.8 M ops/s (as the c-16), 16 threads together 50.2 M; synchronous 4 KiB writes on the root disk **about 10 ms each** (200 writes in 2.0 s), against 1.5 ms on the c-16.

**Rate (lanes together inputs/s; projected decisions/s through the whole chain, then how long the projector finished after the lanes; CPU per decision in microseconds; processor time inside `process` in microseconds per record):**
4 lanes 16,930; 14,287 (8.7 s); 518; 119. 6 lanes 19,933 and 19,630; 17,087 and 17,051 (10.0 and 9.2 s); 542 to 544; 135 to 141. 8 lanes 21,106; 18,471 (10.8 s); 550; 155. **Sustained, 6 lanes with 250,000 trades each (3,000,000 decisions, 152 s of lanes): 19,750 inputs/s, 18,729 projected through the whole chain (160 s in all); 552; 148.** Repeat of six lanes within 1.5 percent of the first (the spread seen elsewhere in this ledger is up to 15 percent).
**The server comes close to the need and does not reach it:** the best projected rate is 18.5k to 18.7k decisions/s (92 to 94 percent of 20,000), the lanes alone 19.6k to 21.1k, and the projector trails the lanes by 8 to 11 seconds at the end of a run. Eight lanes are not clearly better than six. Total CPU at 20,000 decisions/s is 10.4 to 11.0 vCPUs of the 32, so the limit is not CPU. Per-record processor time rises with lanes (119 to 155 microseconds), the contention one JVM with all lanes shows; the 10 ms synchronous disk and the single broker core are untested candidates here (on the c-16 the same chain gained about 13 percent at four lanes when the data went to tmpfs, CAL-FIN-P7).

**What a run writes, corrected at scale** (largest data directory seen, 2 second sampler, per decision): topics at 4 to 8 lanes: capture 2.3 KB, control 0.17 KB, decision log 0.67 KB, Streams store changelog 1.5 to 1.7 KB (not yet compacted in a short run), consumer offsets about 0.1 KB. **Marginal growth between runs of different length, which removes the fixed overhead: Redpanda 3.8 to 4.2 KB per decision, Postgres 1.3 KB per decision** (1.0 to 1.5 GB of the Postgres figure is fixed, which is why the 3.1 KB per decision in CAL-FIN-P9 was too high at run lengths of seconds). Sustained 3,000,000 decisions: Redpanda 13.8 GiB, Postgres 4.6 GiB. About **5.3 KB per decision all-in, 106 MB/s at 20,000 decisions/s, 380 GB for a 60-minute session** (72,000,000 decisions), 190 GB for 30 minutes, 95 GB for 15, 32 GB for 5; replay inputs alone (capture and control) are 2.5 KB of that; the changelog is compacted by Streams over time and would be smaller than the short-run figure.

**Not run:** session B (the fast g5 line with a 100 GB block-storage volume holding both stores) stopped at volume creation, 403 "not authorized" for the benchmark token; the droplet was destroyed within two minutes (about $0.02). No volume was created.

Interpretation limits: see CAL-FIN-P4, P5, P7, P8, P9. One droplet, single runs except the repeat; fixture runs of seconds to 160 s, not minutes to an hour; sizes are encoded volume in this fixture and include the changelog before compaction. The server did not meet the 20,000 decisions/s need in this configuration; no capacity claim.

## Calcify arena baseline C: the same 32 vCPU server with the stores on tmpfs, CAL-FIN-P11 (October 14, 2026)

Control for CAL-FIN-P10 (same droplet size `c-32`, `sfo2`, Xeon Platinum 8168, same test, settings and matrix, `droplet-chain-run.sh baseline` with `REEF_BASELINE_STORE=tmpfs`): Redpanda and Postgres data on tmpfs (24 GB and 12 GB; **not durable**, it measures what the server does when storage costs nothing), everything else unchanged. 16 minutes (about $0.27), destroyed, API check clean.

**Rate (lanes together inputs/s; projected decisions/s through the whole chain, projector lag after the lanes; CPU per decision in microseconds):** 4 lanes 24,693; 20,302 (7.0 s); 524. 6 lanes 32,507 and 32,949; **27,115 and 27,473** (7.3 s); 571 and 564. 8 lanes 35,281; **31,368** (5.7 s); 618. **Sustained, 6 lanes with 250,000 trades each (3,000,000 decisions): 29,596 inputs/s over 101 s, 27,581 projected through the whole chain**; 601. A four-core broker (`--smp 4 --memory 4G`) at six lanes: 32,424; 26,611, no gain over one core (32.5k to 32.9k; 27.1k to 27.5k).
Against CAL-FIN-P10 (same server, root disk, 10 ms synchronous writes): **+53 to +67 percent at the same lane count** (6 lanes 17.1k to 27.1k and 27.5k projected, 8 lanes 18.5k to 31.4k, sustained 18.7k to 27.6k). Total CPU at 20,000 decisions/s is 10.5 to 12.4 vCPUs of the 32. The processor time per record is no better with tmpfs (127 to 184 microseconds against 119 to 155): the lanes were waiting on the disk, not computing.

**What this establishes:** on this CPU class a 32 vCPU server meets the 20,000 decisions/s need with margin (1.35x to 1.55x projected) **when the stores are fast**, and misses it by 6 to 8 percent when they are on the slow disk. The limit in baseline A was storage latency, not CPU, not the broker's core count, not the JVM. Four lanes just reach the need on tmpfs (20.3k); six give about 1.35x.
It does not establish that a durable disk delivers this: tmpfs has no write cost at all. What is needed is a durable volume with synchronous writes in the low milliseconds or below (the c-16's root disk measured 1.5 ms; the c-32's 10 ms; the g5 line's 43 to 57 ms; local NVMe and block-storage volumes are untested) and a capacity of about 380 GB for a 60-minute session (CAL-FIN-P10).

Interpretation limits: see CAL-FIN-P10 and earlier. One droplet, single runs except one repeat (six lanes within 1.4 percent); runs of 36 to 101 s; tmpfs is not a deployment. No capacity claim.

## Calcify arena baseline D: the "2x SSD" 32 vCPU size, CAL-FIN-P12 (October 14, 2026)

Third storage point for CAL-FIN-P10 and P11: same test, settings and matrix (`droplet-chain-run.sh baseline`, root-disk stores, full retention) on one DigitalOcean `c2-32vcpu-64gb` droplet (`sfo2`, $1.12/h, "CPU-Optimized 2x SSD", 774 GB root disk, Intel Xeon Platinum 8168, 32 vCPU, 64 GB), tried because the larger local disk was expected to write faster. 18 minutes (about $0.34), destroyed, API check clean.
**Synchronous 4 KiB writes on the root disk: about 7 ms each** (200 writes in 1.41 to 1.54 s), against 10 ms on the `c-32` (P10), 1.5 ms on the `c-16` (P7) and 43 to 57 ms on the g5 line (P8). It is not the low-millisecond disk that was hoped for.

**Rate (lanes together inputs/s; projected decisions/s; projector lag; CPU per decision):** 4 lanes 18,769; 15,932 (7.6 s); 497. 6 lanes 21,099 and 21,302; **17,985 and 18,074** (9.8 and 10.1 s); 545 and 531. 8 lanes 21,675; **19,051** (10.2 s); 551. **Sustained, 6 lanes with 250,000 trades each (3,000,000 decisions): 18,800 inputs/s over 160 s, 17,819 projected**; 564.
Against the `c-32` on its 10 ms disk (P10): +5 to +11 percent at 4 and 6 lanes, +3 percent at 8, **-5 percent sustained** (18.7k to 17.8k); inside the 15 percent spread this ledger sees run to run. Against the same `c-32` with free storage (P11: 27.1k to 31.4k): 63 to 68 percent of it. **No size tried so far delivers a durable disk fast enough for the 20,000 decisions/s need on this chain;** the best durable result is 19,051 projected (95 percent) with eight lanes.

| Storage | Root disk 4 KiB synchronous write | Best projected decisions/s (lanes) |
| --- | --- | --- |
| tmpfs, `c-32` (P11) | none | 31,368 (8), sustained 27,581 (6) |
| `c-16` root disk (P7, Xeon, 16 vCPU) | 1.5 ms | 15,834 (5) |
| `c2-32vcpu-64gb` root disk (this entry) | 7 ms | 19,051 (8), sustained 17,819 (6) |
| `c-32` root disk (P10) | 10 ms | 18,471 (8), sustained 18,729 (6) |
| g5 EPYC root disk (P8) | 43 to 57 ms | falls with lanes: 3,801 inputs/s at 3 lanes |

The c-16 line shows the latency is not the whole story (a 16 vCPU host with a 1.5 ms disk delivers less than a 32 vCPU host with a 7 to 10 ms one, because it has half the cores). What it does show is that the two 32 vCPU hosts stay at 92 to 95 percent of the need whatever the disk, within noise of each other, while free storage gives 135 to 155 percent. The remaining open candidates: a disk with synchronous writes well under 1 ms (local NVMe on another provider, or a block-storage volume, neither tried), and less synchronous write work per decision (broker and Postgres settings that were not effective on the slow g5 disk, CAL-FIN-P8, were not retried here).

Interpretation limits: see CAL-FIN-P10 and P11. One droplet per size, single runs except one repeat (six lanes within 1.0 percent); the root-disk check is a 200-write `dd` sample taken once per host. No capacity claim.

## Calcify commitment on the deciding thread versus a second thread, end to end without the coverage agent, CAL-FIN-P14 (October 14, 2026)

Settles the size of the gain behind design section 5.6 (lagged commitment), raised by independent review 1 of 5.6 (the earlier 1.5x to 1.8x came from the S3d stand-in lane, and the only end-to-end pair, CAL-FIN-P4, ran with the JaCoCo agent). `S4EndToEndSpikeTest.wholeChainOnOneMachine`, laptop (load average 5 to 7, not quiet), one lane, projector off, K = 100, 100,000 trades (200,000 inputs), JaCoCo off (`-PresearchTests`), `-XX:+UseParallelGC`, Redpanda and Postgres in Docker; `S4_ASYNC=false` puts the root on the deciding thread, `true` on a second thread; three interleaved pairs.

| Pair | Root on the deciding thread | Root on a second thread |
| --- | --- | --- |
| 1 | 13,238 inputs/s (66.9 microseconds per record in `process`) | 18,386 (45.3; 7 waits for the worker) |
| 2 | 13,244 (66.5) | 18,862 (45.0; 8 waits) |
| 3 | 14,567 (60.6) | 18,405 (46.0; 3 waits) |

Means 13,683 and 18,551: **a second thread is 1.35x faster end to end** (processor path 64.7 against 45.4 microseconds per record), against 1.5x to 1.8x in the S3d stand-in lane and about 1.3x in the one CAL-FIN-P4 pair. The lane with the root on a second thread is within 8 percent of 20,000 inputs/s on this machine; the same lane with the root on the deciding thread is 32 percent short. Not measured: the undo ring's put, the worker's wait share at higher rates, any restore. Limits: one machine, three pairs, 15 percent spread elsewhere in this ledger; a one-lane, projector-off lane isolates the commitment term and is not the whole chain. No capacity claim.

## Calcify finance projector: set-based writes in product code, CAL-FIN-P13 (October 14, 2026)

First product-code change from the capacity work: `FinanceProjector.apply` now verifies every record in memory (order, input digest, replay equality, hash link) and writes the batch with one `INSERT ... SELECT FROM unnest(arrays)` statement per table (decisions, positions with net deltas, obligations, attempts) and one set-based update for settled obligations, instead of up to five prepared statements per record. Behaviour is unchanged: the same fence, the same replay check (now one query for all replayed sequences), the same rollback of the whole batch on any failure. Measured with `FinanceProjectorThroughputSpikeTest` (new, research-tagged) on the product class, the laptop's Docker Postgres 16 (load average 5 to 7, not quiet), decisions from the real `FinanceLedger`, 40 bindings of 207 decisions per batch size, the 320-sequence bound of migration 0014 lifted as in the capacity spikes:

| Schema | Batch | Before (per-record statements) | After (set-based) |
| --- | --- | --- | --- |
| product constraints | 1 | 98 decisions/s | 92 |
| product constraints | 10 | 417 | 665 |
| product constraints | 120 | **690** | **4,894** (7.1x) |
| without the four binding foreign keys | 1 | 97 | 86 |
| without the four binding foreign keys | 10 | 426 | 703 |
| without the four binding foreign keys | 120 | 792 | 4,410 |

A call of one record is bound by its commit and does not move; the gain grows with the batch. With the foreign keys kept (the product's schema) the projector is not measurably slower here (4,894 against 4,410, within the spread), so they stay. Existing suites pass against real Postgres: `FinanceProjectorPostgresTest` (8, including random batching with redelivery, rollback of a bad batch and the role-restriction test), `JdbcFinanceReadStorePostgresTest`, `FiniteP3EndToEndTest`, the launcher and runtime broker tests.

**What this does not change.** The runner (`FinanceProjectionRunner`) re-reads the whole decision log on every pass and the product migration still bounds a binding to 320 sequences, both correct for the finite profile and both incompatible with the capacity profile; the next projector step is an incremental runner that tails the log in batches of hundreds to thousands and a capacity-profile schema decision (lifting the bound, keeping or dropping foreign keys). Batch sizes above 120 were not measured (a ledger run admits at most 128 captures). The 4,894 decisions/s is the projector alone; it is below the 20,000 decisions/s need by itself, and the Postgres side of the chain reached 15,000 to 31,000 projected decisions/s in CAL-FIN-P4 to P11 only with the test projector's larger batches and a lifted bound. No capacity claim.
