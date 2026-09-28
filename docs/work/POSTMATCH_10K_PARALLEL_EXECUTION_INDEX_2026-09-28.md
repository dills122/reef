# Post-match 10k parallel execution index — 2026-09-28

Objective: sustain 10,000 accepted commands/s for 300 seconds with in-load
post-match freshness, exact closed-cohort live and settlement parity, and
bounded drain. Use disposable droplets only for hosted capacity tests; use
local Docker for focused development tests. Keep D-059 durable rank,
same-account predecessor order, deterministic replay, and four-leg DvP.

Canonical context: [D-059](../DECISIONS.md#d-059-durable-settlement-admission-order-for-scarce-accounts),
[PM-S3 evidence](../../artifacts/postmatch-capacity-20260928/attempt-3/),
[PM-S3 fault lines](../research/POST_MATCH_PM_S3_FAULT_LINE_ANALYSIS_2026-09-28.md),
and [capacity decision](../research/POST_MATCH_SETTLEMENT_CAPACITY_DECISION_2026-09-27.md).

| ID | Lane and owner | Scope and dependency | Acceptance | Status |
| --- | --- | --- | --- | --- |
| PM10-LIVE | Independent live implementation chat | Live partition worker, bulk fact persistence, precise stage metrics; owns `PostMatchRuntimeWorkers`, `PostMatchLiveEffectWriter`, `PostMatchOperationalStore`, focused live tests. Starts from PR #402 head and preserves current WIP in `post-match-storage` worktree. | Local PostgreSQL integration proves independent partition progress and exact replay/fact parity. Report per-window read/write cost and SQL call count on a fixed cohort. Independent review before PR. | PR #404 open with `ocr-pilot`; independent review passed, all OCR threads resolved, and 27 CI checks passed; awaiting user merge |
| PM10-SETTLE | Independent settlement implementation chat | Bounded rank allocation, admission/execution separation, coalesced ordered execution; owns settlement store/worker and settlement focused tests/migrations. Starts from PR #402 head in separate checkout. | Hot and diverse account cohorts, crash/replay, durable rank, same-account order, four-leg DvP, exact proofs. Measure admission separately; compare execution against an identical imported admission log. Report window/s and trade/s. Amend D-059 and transition contract before merging multi-window commits; independent review before PR. | Group-wide bulk executor, multi-rank atomic admission, D-059 amendment, and 19 focused PostgreSQL tests in place; hot fixed-log rate remains about 1.0–1.1k trades/s. Typed arrays showed no repeatable gain; structural fact-layout decision pending before review 2 and PR |
| PM10-RESEARCH | Research spike chat | Read-only evidence review of cross-partition dependency chain, ranked-group bulk execution, and PostgreSQL write options; isolated checker timing fixture. No product code. | Decision-ready group-wide design with exact proof method and cited primary sources; credible two-hour proof budget before hosted pair. | Group design, 3m checker timing, and source-backed fact-persistence comparison complete |
| PM10-INSTITUTIONS | Independent external research chat | Primary-source investigation of how clearinghouses, exchanges, and ledger builders handle hot-account ordering, batch posting, recovery, and read models. Owns only its research artifact. | Distinguish documented practice from inference, compare grouped atomic execution, deferred/netted settlement, and account partitioning against Reef's D-059/DvP semantics; identify design gates, no implementation authority. | Research complete; [decision note](../research/POST_MATCH_INSTITUTIONAL_SETTLEMENT_DESIGN_RESEARCH_2026-09-28.md) integrated into lead branch |
| PM10-CHECKER | Independent checker implementation chat | Optimize read-only lag polling and exact final proof in `settlement-shadow-check.mjs` and `postmatch-shadow-check.mjs` with focused tests; separate worktree from merged #402 base. | No repeated full-table scans while waiting; final exact receipt/trade/obligation/ledger/accounting checks preserved. Quantify query/time reduction and review before PR. | PR #405 open with `ocr-pilot`; 512k and 3m same-cohort proofs passed, 62 retained files checksum-verified, independent review 2 cleared PR; three OCR comments under evaluation |
| PM10-INTEGRATE | Lead chat | Matched local fixed-cohort comparisons, cross-lane review, PR/OCR sequencing, disposable 320/240 matched droplet pair, final forensic report. No source-file overlap with child owners. | Same source workload/topology/config/observer for control and treatment; completed in-load stage freshness and backlog evidence plus exact stopped-source checkers within two-hour droplet cap; no production operations. Time full-size local checkers first. | Active |

Parallel lanes may read each other's branches but must not edit owned files.
Lead reconciles all lane handoffs and common contracts,
then runs integrated verification before the droplet pair. PR #402 merged at
`3d4f180a` on 2026-09-28; implementation PRs target `master`. Green CI, independent review,
OCR label and resolved comments remain delivery gates.

## Local test setup

On 2026-09-28, the lead started the existing local Reef PostgreSQL Compose
containers and migrated fresh `reef_pm10_test` databases on source port 5432,
post-match port 5436, and settlement port 5437. The pre-existing `reef`
database has an older settlement migration checksum; no existing volume was
reset. The PR #402 `PostMatchRuntimeWorkersIntegrationTest` passed against
the fresh source/post-match databases (2 tests, 0 skipped, 0 failures).
This verifies local schema placement and basic worker behavior, not throughput.
The live lane's isolated four-writer, 10,000-outcome/5,000-trade cohort
measured 2,610 ms without pgJDBC insert rewrite and 1,680 ms with it;
append-only SQL calls fell from thousands to 120 per table in this fixture.
That is writer-only throughput, excluding source read, settlement, and API
visibility. In a corrected 16-window/8,000-outcome repeat, rewrite-on elapsed
1,145/965/1,122 ms at 4/8/16 concurrent writers, respectively. Eight
writers were faster than sixteen on cohort elapsed time, while target-transaction p95 rose
to 483/595 ms at eight/sixteen; the specific wait cause is unmeasured.
A proposed live write cap and four-partition blocked-progress
integration test passed 4/4 after Docker recovery. A warm source-read-only
500-outcome fixture measured 49.08 ms mean and 55.01 ms p95 with smaller,
rejected-payload effects; it is not a matched full-window read measurement.
The settlement lane's first prototype on the same retained 16-window
admission log measured 417–518 trades/s with single-window execution and
604–696 trades/s with four-window grouped execution across ABBA runs.
The subsequent group-wide bulk-fact and eight-rank-admission redesign passed
19 active focused PostgreSQL tests (two opt-in diagnostics skipped) and
measured 1,501 trades/s in a fresh hot 24×40 direct-seed diagnostic with
rank batches of eight; diverse-account parallel execution reached 1,838
trades/s. Its separate same-log group-of-four ABBA measured 859/877 trades/s
versus 494 cold/872 warm single-window execution. These local fixtures differ
in setup and cannot be combined into a causal 2.5× claim. A later fixed-log
ABCCBA group-of-eight comparison passed exact replay at 1,007/1,138
trades/s. Applying took 562/499 ms, including 371/364 ms fact persistence
(66–73%); planning plus locking took roughly 59–67 ms. A later same-log
larger-group sweep measured group-of-four 939/871, group-of-eight 1,129/1,023,
and group-of-sixteen 908/730 trades/s; larger groups regressed. In the
group-of-eight sweep, ledger statements took 199/256 ms for 640 trades and
were the largest fact-write component (total 341/408 ms). This points to
fact writes before proof reads as the next measured cost, and hot execution
remains below the roughly 4,400 trades/s workload target.
In an earlier 24-window
hot-account group-of-eight diagnostic, 1,345.7 of 1,582.8 ms was spent
applying windows, including 907.8 ms in fact persistence; planning and union
locking accounted for 102.1 and 126.6 ms. These process-side phases support
an inner-loop persistence bottleneck in that local fixture, not a measured
production attribution. The next measured structural candidate is reducing
group-wide fact persistence cost while preserving exact per-window facts.
The current executor already uses eight set-based `jsonb_to_recordset` writes.
A read-only source-backed spike recommended comparing typed `UNNEST` or bounded
`VALUES` inputs for attempts and ledger first. The fixed-log typed-array
comparison passed exact replay and the 19 active PostgreSQL tests, but did not
show a repeatable rate gain: JSON group-eight arms were 939/874/1,627
trades/s (median 939); typed-array arms were 891/805/1,657 (median 891).
Typed encoding was slower; JDBC execution of canonical fact writes dominated
and varied widely. Commit time was about 4–6 ms in this fixture. This rejects
input encoding as the primary local bottleneck. Transaction-local `COPY`
staging would still perform canonical index/constraint writes, so it is not
promoted without a separate attribution. The next research decision concerns
which canonical facts must be synchronous and which indexes are rebuildable;
any secondary-index removal requires read-plan proof.
These fixtures are diagnostics, not integrated capacity claims. A user-triggered
Docker Desktop settings restart briefly interrupted tests; the lead restarted
Desktop and the existing PostgreSQL containers without resetting volumes.
The named test databases survived; the live focused test passed 4/4 and
the updated settlement focused suite passed 19 active tests with two opt-in
diagnostics skipped. An isolated 512,000-outcome/16-partition checker pilot
with a 44% trade mix passed three baseline exact checker repeats: live
4.60/4.42/4.43 s and settlement 11.23/10.85/10.74 s. The seed generated
about 2.66 GB WAL across source, live, and settlement clusters; Docker VM
had 68.4 GiB free after seeding. On the same frozen cohort, optimized live
checker took 4.80/4.87/4.37 s and optimized settlement checker took
10.25/9.77/9.74 s in the retained timed runs; all reports passed and matched baseline counts and hashes
apart from check timestamps. The optimization removes repeated whole-cohort
poll scans but does not materially change the final live proof cost. The
frozen named databases remain available. A separate full-size synthetic
fixture seeded 3,000,000 outcomes, 1,320,000 trades/attempts, 5,280,000
ledger legs, and 30,000 admissions/completions. Three baseline exact checker
passes all succeeded: live 18.04/17.98/16.00 s; settlement
36.10/34.42/33.80 s. Docker VM retained 55.6 GB (51.8 GiB) free after the checks.
The full-size seed took 454.23 s for migrations and three database seeds;
final databases total 13.00 GB and generated 15.59 GB WAL. Six serial baseline
proofs generated 34.70 GB cumulative temp traffic, which is not retained disk
or peak usage. Optimized same-cohort exact reports also passed all six runs:
live 16.87/17.74/16.99 s and settlement 38.36/31.80/27.79 s. The first
optimized settlement pass exceeded the slowest baseline, so the final-scan
speedup is not stable; the checker change targets repeated polling scans.
This fixture measures checker cost, not matching replay or business correctness.
The 512k pilot and 3m fixture prove stopped-source checker parity and local
timing only. Their generated placeholder digests cannot prove business replay;
the hosted run must capture exact real-cohort business and accounting checks.

## Capacity and time gates

The next 320/240 droplet pair is an **architecture diagnostic** while legacy
projection and the new shadow stages coexist. It must report accepted,
direct-acked, materialized, live/order/market/audit/settlement progress,
trades/s, accounting facts/s, in-load backlog slopes, predecessor-complete to
execution-start delay, actual same-cohort API visibility, WAL/rows, locks,
CPU/I/O, and stopped-source drain. A passing
diagnostic pair alone cannot qualify public reads or full-system 10k capacity.

Full Wave 4 qualification follows cutover ownership and stops the old full
projector in the treatment image. For a frozen 300-second workload it requires
sustained 10,000 accepted commands/s, no growing mandatory-stage backlog,
exact business/replay/audit/accounting results, command-weighted lifecycle
and market freshness p95 ≤5s, p99 ≤10s, max ≤30s, crash recovery, and
three-run stopped-source drain headroom ≥20%, as specified by the
[active scaling plan](POST_MATCH_SCALING_IMPLEMENTATION_PLAN_2026-09-26.md#wave-4--one-integrated-cutover-and-capacity-campaign).
Draining eventually is not an in-load capacity pass.

Before reserving another disposable droplet, run both exact checkers on a
local full-size cohort of at least 3 million outcomes with the corresponding
trade mix. This timing run and the retained seed/resource artifact review
passed; independent checker review instance 2 found no actionable defect.
Allocate a two-hour budget for
setup, seed, 300-second load, drain, exact proofs, artifact capture, and
teardown in **each** arm; abort the hosted pair if local proof timing leaves
no safe margin. For settlement execution A/B, restore the same retained
admission log in both arms; test admission throughput separately.

## Independent review

Review instance 1 of 3 returned **Not ready** for the execution plan. It
confirmed the forensic arithmetic and treatment-only limits, then found
missing in-load freshness/drain qualification gates, missing fixed-log
execution comparison, no demonstrated checker time budget, and an imprecise
spike citation. The gates and fixed-log protocol above address the plan gaps;
the spike citation was corrected in the analysis. Full-size synthetic checker
timing completed three paired repeats, with size, WAL, temp-traffic, and raw
timing artifacts retained by the checker lane. Independent checker review
instance 2 cleared that branch for PR. A separate fresh review of this
execution plan follows the final settlement design and D-059 grouped-commit
interpretation.
