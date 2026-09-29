# Post-match ledger trial postmortem — 2026-09-29 UTC

Status: architecture candidate remains default-off. No 10,000 accepted commands/s,
300-second post-match qualification run occurred. Disposable `sfo3`/`c-32`
droplet and firewall were destroyed after raw artifacts were fetched.

## What was tested

Three hosted attempts on branch `codex/ledger-authority-redesign` ended before
measured load. Attempts `do-benchmark-20260928T235051Z` (`13286ee1`),
`do-benchmark-20260928T235845Z` (`30036400`), and
`do-benchmark-20260929T001956Z` (`e50b255a`) have raw logs under the matching
local `reports/do-benchmark/` directories. The first was stopped during
preflight after review found market HTTP age missing. The second passed the
legacy-topology preflight smoke, then failed cold candidate enrollment because
its one-shot process lacked `MATCHING_ENGINE_EVENT_STREAM`. The third enrolled
a verified 16-partition source, then candidate settlement service failed health:
its Compose environment lacked that same required venue topic. No hosted
attempt produced a measured 10k/s report. The old preflight smoke did not boot
the exact candidate topology, so it missed these startup faults.

An isolated local Compose project then exercised the actual candidate stack:
separate source, market, settlement, and finality databases; 16 source
partitions; immutable controls; journal writer; direct market and financial
projectors; and candidate reads. The first local boot found Netty did not
dispatch `/internal/admin/settlement/controls`, despite the JDK route and
gateway existing. After that fix, 326 controls reached journal and independent
finality. A 50/s, 20-second load accepted and materialized 1000/1000 commands,
but journal worker exited on a source gap whose earlier position had not yet
materialized; restart waited for its independent lease to expire. The gap
handler now defers only while live pinned broker identity/retention validates,
never claims the interval empty, and its focused database test passes. A
closed-head writer SIGKILL/restart passed V2 snapshot hydration and higher
external lease epoch without changing the closed journal or finality head.
That test did not crash an in-flight append or restore a PostgreSQL database.

The final local 50/s, 60-second run used the fixed source-gap handler. Source
intake, Go matching, and venue-event materialization each completed 2999/2999
commands in 60.002 seconds; candidate writer and market workers stayed up.
Raw evidence is `/tmp/reef-pmj-localboot-60s-artifacts/` and
`/tmp/reef-pmj-localboot-60s-final.log`. After drain, closed-cohort check passed:
2999 retained outcomes across all 16 frontiers and 680 coverage windows;
1044 exact first-trade facts each in source, journal, and market tape;
1044/1044 financial trade projections; 326 immutable controls; 325 final
balances reconstructed from openings and four DvP effects; no duplicate first
attempts, duplicate settlements, or attempt gaps. Journal, external finality,
and financial projection closed at batch 806. This is **after-drain parity**,
not sustained in-load capacity or every intermediate balance proof.

## Why sustained trial failed

| Observation | Evidence and mechanism | Classification |
| --- | --- | --- |
| Source-to-journal backlog grew during 50/s load | Five in-load stage intervals showed source-to-journal gap 330 → 505 → 610 → 634 → 731 positions; it drained after input stopped. Journal-to-financial gap stayed 0–1 batch and source-to-market was near zero. Source→journal is measured failing stage. Per-stage journal timing is not yet recorded, so broker gap probes, source reads, evaluation, serialization, head wait, and append cannot be ranked from this run. | Sustained-path failure; precise journal substage unmeasured |
| Market reads did heavy recovery work | API and projector instantiate separate `MatchingOutcomeMarketCandidate` objects. Every advancing market frontier makes API `ensureRecoveredTargetReplay` replay retained source from origin, rebuild target in a rollback-only transaction, and compare order/level/tape rows before its indexed read. Defaults cap replay at 10,000 windows, 64 million source bytes, and 200,000 target rows. Those bounds and growing replay work are incompatible with a 3-million-command read qualification. | Design blocker, independently visible in code and local latency |
| Market API errors/latency | 60-second probe: book 4/60 failures, depth 2/60, tape 3/60; logs identify nine `market replay frontier changed after source was pinned` failures while projector advanced. Book/depth/tape p95 were 594.6/550.1/594.1 ms and p99 742.3/738.4/738.0 ms. Provisional diagnostic limits were 250/500 ms. A bounded retry fixed a later proof-to-read race, but cannot fix full-prefix replay or its earlier race. | Read-availability and latency failure |
| Financial status read race | Two status probes returned 503 because independent finality advanced between first and second anchor reads. Balance read had 0/60 failures and p95 5.2 ms; status had 2/48 failures and p95 3.8 ms among responses. | Implementation race; bounded same-authority retry underway |
| Executed trade could appear as null settlement | Candidate status `currentAtObservation` compared financial projection to acknowledged journal, not journal to matching source. During source→journal lag, a matched trade could have `settlement=null` while status labeled current to journal. It must expose committed execution and settlement pending separately, with source and journal as-of positions. | Contract/read-model gap |
| Three market-frontier-ahead stage samples | Stage observer read source DB before market DB; target advanced between those reads by 2–6 positions. Sampler now rereads monotonic source head after target. These samples are observer skew, not proved target-authority violation. | Measurement defect corrected after run |

Code inspection also found repeated journal-side reads: each `pollOnce` renews its
lease, checks source binding and journal head, calls `readVerifiedPrefix` over
the retained control log, selects a source window, and re-verifies controls and
source before append. `readVerifiedPrefix` reads and rehashes all retained
control batches and members from the origin (326 controls in this cohort).
Those are definite operations, but this trial did not time them separately;
none can yet be named the cause of source-to-journal backlog.

Market source-to-visible age upper-bound p95 was about 1.56 seconds, below the
5-second age limit, among successful HTTP samples. That does not rescue failed
read responses or slow response latency. The prior 28–37 ms journal append
proof measured a small component shape, not this end-to-end workload. Peak
memory, full-cohort cold restore, source/account ownership parity, market
order/level value parity, scarce-account winners under hosted load, and
in-flight crash behavior remain unmeasured.

## Corrective order before another hosted attempt

1. Replace per-read full-prefix market replay with a bounded read trust model.
   Define how worker attestations, target incarnation, lagging as-of reads,
   cold rebuild, and target-only restore interact; test separate API/projector
   processes and advancing frontiers. Raising replay bounds alone preserves
   unbounded work and is not a fix.
2. Bind trade status to committed matching execution and report settlement
   pending/broken separately. Retry only benign strictly advancing external
   finality reads; same-position digest or incarnation changes still fail.
3. Instrument journal source read, broker proof, evaluation, serialization,
   head wait, append/commit, and snapshot costs under a local sustained run.
   Explain growing source→journal backlog before changing batching or storage.
4. Re-run isolated candidate topology with enough duration for stage trend,
   market API latency/availability/age, closed-cohort parity, and fault proof.
   Preserve each failed run. Only then provision a new hosted 10k/s ×300s run.

No Go matching, ingress, pre-trade, or matching-outcome format changed in these
attempts. No cutover, old-table removal, or production deployment occurred.

## Follow-up instrumented local run (same day)

An isolated `reef-pmj-timing` 50/s, 60-second run used a fresh stream and
volumes plus default-off 10-second candidate-writer stage timing. Two harness
attempts failed before load because the local timing script omitted the
settlement JDBC URL and then immutable-control seed settings; both were fixed
before this measured run. The measured load accepted and materialized 2999/2999
commands, with no ingress failures. Candidate read gate still failed: book,
depth, and tape had 2/60, 3/60, and 1/60 failures; p95 latency was 496, 489,
and 495 ms respectively against the 250 ms diagnostic gate. Raw artifacts:
`/tmp/reef-pmj-timing-60s.log`, `/tmp/reef-pmj-timing-journal.log`, and
`/tmp/reef-pmj-timing-60s-artifacts/`.

Across six loaded writer intervals, 59.83 seconds of cumulative `pollOnce`
time comprised 42.66 seconds in broker gap checks (71%), 8.66 seconds in
repeated control-prefix reads/verification (14%), 2.58 seconds in journal
append plus finality acknowledgment (4%), 2.26 seconds in source-identity
checks (4%), and 0.76 seconds in settlement evaluation (1%). Categories
are selected nested stage timings, not an exhaustive decomposition; the
broker gap stage includes stable-end and empty-range checks. Individual broker
checks reached about 500 ms. Comparable source frontier offsets versus journal
frontier offsets grew from roughly 168 to 794 positions across in-load stage
samples. After input stopped, journal/financial frontiers reached
all 2999 source positions, with 1036 journal result rows and 805 batches.

This narrows the failure from generic "heavy reads" to synchronous broker
absence verification in source selection, compounded by repeated full-prefix
control verification. It does not prove that broker verification is unnecessary:
empty ranges must still be certified. Current candidate serially probes gaps
while selecting one source window per journal batch. Moving proof preparation
off that serial commit path, reusing a verified immutable control frontier,
and batching ready windows are design changes to test; their throughput and
recovery correctness have not yet been demonstrated.
