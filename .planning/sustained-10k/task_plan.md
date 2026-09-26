# Sustained projection 10k/s execution

Goal: complete the frozen projection throughput workstream through sustained 10k/s, preserving durable intake, replay, claims, and exact downstream freshness. User authorizes local and droplet-backed testing (2026-09-23).

Branch: codex/projection-sustained-10k. Base: 0dd72ba9. Checkout: /Users/dsteele/repos/reef.

## Sequence

- [x] Reconcile unmerged 23d3ca58 (1B/1C) with current master; verify tests and independently review measurement authority.
- [x] Correct measurement defects and reduce observer cost; corrected-profile A/Bv5 passes all12checks, maximum0.507998% within frozen1% (v4failure retained).
- [x] Task 2: isolated one-caller lifecycle and repeated-redirty market capacity; frozen freshness thresholds; Checkpoint A passes after v5 overhead proof.
- [x] Correct online dirty-invalidation race (0052, four deterministic PostgreSQL tests, fresh online full-reference equality at149,975commands; corrected recoveryv4 also passes). Capacity/freshness qualification remains separate.
- [x] Establish and preserve current baselines C2/C4/C5; record mandatory throughput-history policy in AGENTS and canonical evidence ledger.
- [ ] Task 3 and evidence-led one-lever capacity changes; independent review of material changes.
- [ ] Promote sustained 5k, 7.5k, 10k with minimum 20% drain margin, warm/aged, bounded-state and recovery evidence.
- [ ] Update canonical plan/status, preserve artifacts, provide branch/PR metadata and test evidence.

## Targeted SQL follow-up — September 25

### User-authorized SQL remediation round — September 25

- [x] Make submit-result replay conflict rejection atomic inside the unique-key conflict arm; test identical, conflicting, and concurrent replay.
- [x] Remove exact dirty-queue counts from frequent freshness-marker reads while preserving one-snapshot oldest/empty proof and exact final counts.
- [x] Bound the canonical lag decision used by backpressure, retain exact report/status lag, and preserve sparse-partition behavior.
- [x] Repair duplicate canonical partition-sequence acceptance and fail-closed prefix selection on fresh schema.
- [ ] Preflight aged canonical source and plan unique-index rollout before aged deployment.
- [x] Run focused PostgreSQL/behavior tests, then one fresh independent review of these changes and fixes for any blockers.
- [x] Run the C28 full-system configuration on a clean disposable droplet with comparable stage/DB diagnostics; preserve all results and destroy owned resources. C38 timed gate failed; postdrain integrity and business reference passed.
- [x] Consolidate overlapping canonical partition-sequence indexes in `0062`; live PostgreSQL tests cover uniqueness, covering read shape, wrong prebuild rejection, and valid prebuild adoption. No throughput claim until matched full-pipeline run.
- [x] Reuse materialized outcome rows for duplicate command-ID count in `0063` and Kotlin compatibility bootstrap; live PostgreSQL replay/rollback tests and migration tests pass. One fresh independent review found no blocker.
- [x] Run clean C28-topology 10k/s x 300s treatment with `0062`+`0063`, preserve timed stage/DB diagnostics and postdrain reference, then destroy droplet. C39 timed gate failed: 2,981,641 accepted, 2,331,512 materialized at collection (7,770.56/s), 2,366,114 projected later (7,885.88/s). All rows/frontiers/queues and rollback-only business reference passed postdrain. Evidence at `artifacts/sustained-10k-20260925/batch0063-treatment/`; provider 404 and empty OpenTofu state verified.
- [ ] Next code slice: reduce canonical outcome/batch-payload write and temp-I/O cost while retaining global command-ID uniqueness, replay rejection, and rebuildability; address projection contention separately. C39 improved materialization 4.7% versus C38 but did not qualify 10k.
- [x] Promote the parked bounded canonical selector (`0067`, was `.planning/sustained-10k/bounded-canonical-selection.candidate.sql`) now that `0061`/`0062` closed the sequence-uniqueness gap that parked it. Local `EXPLAIN (ANALYZE, BUFFERS)` proof: 839ms (0060, full-backlog scan+sort+2 WindowAggs) vs 1.05ms (bounded, index-only scan) at 500,000 rows, identical selected rows end to end on a punched-gap fixture. See C44. Local only, no droplet; full-pipeline treatment run against `0067` still needed before any throughput claim.
- [ ] Cloud treatment attempt made (C45): `nyc3` `c-8` droplet, topology matched C43 exactly (verify-workers.py: 35/35 settings, zero mismatches), but intake/matching/boundary-DB path was CPU-saturated (149%/117%/115%/108% of one core on an 8-vCPU box) while the projection layer sat at 4-10%, so the run never pressured what `0067` changes. Inconclusive, not a pass or fail for `0067`. Full setup and findings in `.planning/sustained-10k/c45-dirty0067-cloud-attempt.md`. Still needed: a valid full-pipeline run on hardware that can actually sustain the intake path near 10k/s (leading suspect: `sfo2` no longer offers `c-8`, so C43 likely ran on different physical CPUs than this attempt's `nyc3` box; try `c-8-intel` or a region match next).

- [x] Trace the backpressure status contract; make its lag-only read avoid the exact submit-results count while retaining public diagnostics.
- [x] Run focused Kotlin and live PostgreSQL tests for count/lag parity and the unchanged backpressure decision.
- [x] Profile the active 0052 status/fill SQL by plan node on an isolated synthetic cohort; no safe table-write lever established, so require a matched aged mixed-cohort profile next.
- [x] Measure 0055/0058 migration lock and build cost on an aged disposable database; document its 200k-row, warm local fixture limit.
- [x] Preserve local evidence and document diagnostic claims in the canonical plan; throughput ledger remains unchanged because no end-to-end rate or freshness claim changed.
- [x] Profile status/fill on a 13GB aged copy of C32: five serial mixed/no-fill pairs, nested write-node/WAL plan, and corrected 16-writer synthetic cohorts. Record limits and reject speculative SQL rewrite.
- [ ] Capture matched current-image full-pipeline control and bounded wait/I/O/nested-plan diagnostic before selecting another status/fill lever; preserve C28/C32 as historical controls.

## Constraints

- Main checkout has pre-existing user tooling changes; preserve them. Work directly in /Users/dsteele/repos/reef per user direction.
- Existing Slice 1A remains always enabled. No SQL/scheduler/capacity tuning before Checkpoint A.
- Imported 1B/1C claims are testimony; inherited local A/B failed p95 (+4.99%). No promotion inferred.
- Review budget exhausted: targeted SQL review was instance 3 of 3. Report its findings; do not start another review instance.
- Paid tuning follows Checkpoint A. User authorization covers necessary tests and disposable infrastructure; preserve cleanup evidence.

## Verification

Focused Node/Bun report tests, Go streamdirect and module tests, Kotlin affected tests and module checks, PostgreSQL integration, real-stack cohort/replay/crash checks, matched instrumentation A/B, isolated capacity, then sustained remote gates.

## Prepared execution details (not gate completion)

- Optional-cost A/B rerun v2: fixed2.5k/s,256workers,60s, empty volumes per sample; order instrumented1/control1/control2/instrumented2/instrumented3/control3. All six use same images/settings except toggle. Full cohort/runtime checks required per sample. Artifact root /private/tmp/reef-10k-ab-v2. First stopped attempt and initial15s smoke remain historical diagnostic evidence.
- Task2 lifecycle: preload actual fixed canonical cohort, disable market helper, leave exactly one lifecycle caller. Report orders/call and orders/s separately from source commands/s; never equate coalesced dirty rows with source throughput.
- Task2 market: fixed fully projected order dataset, all other callers stopped, controlled repeated dirties for same representative instruments. Report instruments/cycle and repeated cycles/s, not unique instrument count as command throughput. Verify stable final public state and queues.
- Task3 after CheckpointA: change lifecycle idle/backoff policy only; productive cycles continue without250ms delay, empty/error cycles retain backoff. Focused scheduling and stop/error tests required. Keep market helper topology unchanged for first comparison.
- Recovery proof must cover unlogged queue loss with explicit lifecycle+market rebuild before fresh-state claim. Database-generation guard only invalidates measurement after observed restart; it does not recover pre-existing work.

## Frozen sustained freshness criteria (before capacity tuning)

- Measured source work-finished → canonical commit-observed, lifecycle covering observation and market covering observation: each p95<=5000ms, p99<=10000ms, max<=30000ms for complete command-weighted cohort. Bounds remain conservative and explicitly labelled; never substitute final-tail timing. These are engineering acceptance limits for async control-room projections, not exchange market-feed latency promises.
- Rationale: one-second frozen sampling plus current250ms maintenance cadence permits a few seconds of transient batching, but sustained backlog must not consume most of a five-minute run. Thresholds fixed before isolated capacity/tuning evidence, not selected from successful candidate output. No relaxation to promote a candidate.
- At each5k/7.5k/10k five-minute gate: >=99% scheduled demand completed, all accepted commands direct-acked/materialized/projected, failures/retries/deadlocks0, dirty queues0/maintainersidle on final drain, exact authoritative cohort/generation/sampler evidence, no growing steady-state backlog; >=20% independent fixed-backlog drain margin at same shape. Warm/aged and explicit crash→rebuild equivalence required. Intake HTTP latency continues reported separately.

## Prepared Task3 test cases (no implementation before CheckpointA)

Current lifecycle loop is explicit daemon thread + unconditional Thread.sleep(pollIntervalMs), not scheduled executor. Planned one-lever change only: positive rows continue; zero/error retains existing wait. Verify with synchronization barriers (avoid sub-millisecond wall-clock assertions): productive nextcall before anywait, empty/error wait, stopduringproductive preventsnextcall, stopinterruptsidlewait, duplicate start doesnotspawnsecondworker. Preserve callercounters and failure accounting. Existing stop/start overlap semantics need inspection so productive-loop change cannot bypass interruption/stop signals.

## Evidence-led execution revision — September24

User direction: prioritize actual capacity testing and fixes; defer additional
validation-framework expansion. Existing qualification limits remain unchanged.
Historical baseline: venue-core10k/5m and warm10k/15m passed; fullprojection2.5k/5m
passed; fullprojection5k/60s had successful runs;5k/5m failed before this work.
Sources: CURRENT_STATUS.md107–112, PROJECTION_THROUGHPUT_SCALING_PLAN.md597–633,
IMPLEMENTATION_STATUS_AUDIT_2026-09-04.md55–80. Do not describe this known boundary
as newly discovered incapacity or retrospectively invalidate historical results.

Measured current candidate7c388+0052:
-2.5k/60s149976accepted/materialized/projected,2499.55/s; fullbusiness123676orders
and64markets exactly match rebuild. Sampled lifecycle queue peak2776/oldest3.033s.
Covering-observationp95~58.5s is a conservative empty-queue bound, not measured
individual-order latency; observer cannot resolve steady nonempty-queue freshness.
-5k/300s1499950accepted/materialized,4999.83/s; finalcanonical lag580855,
materialized/projected gap578855. Run fails. Live sampled lifecycle backlog79050
andoldest41.864s at~100samples;market query temp-file reads;claimcleanup lockwaits.
-Read-only stopped5kfixture market query comparison: current403.553/422.101ms,
indexedcandidate115.922/115.487ms,all64shapedrows identical bothEXCEPTALLdirections.
Only a query-local speedup, not end-to-end evidence.

Next sequence:
1. Implement indexed market recompute using existing0028indexes, preserving0052
claim serialization/freshsnapshot and exactcurrency/quantity/emptybook semantics.
FocusedlivePGtests, then same5k/300s run; retainbefore/afteractualruntime/source/schema.
2. Measure remaining hotpath. Claimcleanup contention from0050 is separately
identified; change only if stillmaterial. Test eachchange against sameworkload;
never attribute current/historical difference to0052 without matchedmeasurement.
3. Once5kthroughput/backlog stable, run7.5kthen10kdiagnostics; fix measured limits.
4. On viablecandidate complete precise sustainedfreshness evidence,20%drainmargin,
aged/public/recoveryapplicability and remaining independentreview. Do not block
exploratoryloadtests on optionalobserverA/B; do not label themqualifiedpasses.
5. Archiveactualevidence,updatecanonicalstatus,packagebranch/PR,anddestroyowned
Droplet/firewallwhenworkstops. No renewedtestingapprovalneeded.
