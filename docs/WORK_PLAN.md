# Reef Work Plan

## Purpose

This is Reef's single repository execution ladder. It links to the documents
that own detailed contracts, evidence, and sprint tasking. Its September 4
alignment is a checkpoint, not a live claim about later branches or runs;
check source and newer evidence before reporting any item as current.

Historical [CURRENT_STATUS.md](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/CURRENT_STATUS.md) records September 4 implementation
snapshot and scoped performance claims. Use newer source and evidence for current claims.

Last aligned: 2026-09-04 against `master` at `cebbffc1`; hosted release gates
were not re-run during this documentation check.

Source/test/artifact reconciliation:
[`IMPLEMENTATION_STATUS_AUDIT_2026-09-04.md`](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/archive/IMPLEMENTATION_STATUS_AUDIT_2026-09-04.md).
Items below distinguish missing implementation from evidence not found in the
audited checkout; missing local reports do not prove a run never happened.

## Calcify sprint1 exit continuation — October 6, 2026 UTC

Baseline `863503ec`; branch `codex/calcify-sprint1-exit-2026-10-06`.
Reef PRs #473/#474/#475/#477 and Records PRs #6–#9 merged; earlier
checkpoint draft/pending wording below records pre-merge scope. Landed E4 source
tree equals reviewed source; original proof commits remain reachable in Records.

Four-hour continuation follows [existing first experiment sprint](work/CALCIFY_SYSTEM_ARCHITECTURE_RFC.md#101-first-experiment-sprint).
User direction removes test-harness memory optimization from critical path.
Explicit larger test resources may support empirical rate/storage diagnostics;
no conservative heap bound or production capacity follows from larger budget.
Fresh fixed 2,500-trades/s,60s diagnostic closed. Two 4GiB attempts hit disk then
heap guards. Explicit 8GiB permission passed review4of4 after user extension;
full 150000 trades settled,300001 histories and complete result-only replay passed.
Deadline 63104 settled/60s=1051.73/s misses2500/s target. Producer132.040s and
post-producer drain8.139s miss frozen duration/drain gates; later completion does
not count toward deadline. No harness optimization. Kernel/managed adapter unchanged.
Final source Node CI279/279, financial112/112. Implementation commit1c143125 created
after closed run; executed baseline marker and dirty-source hashes retained in
[focused verification](evidence/calcify-financial-sprint1/e4-verification-2026-10-06.json).
Old 768 MiB conservative and 1,000/100 bootstrap evidence remain unchanged.
No new managed-restart qualification or fourth prior E4 qualification instance.
Complete [raw proof](https://github.com/dills122/reef-records/tree/2331d97d9f9e6af8638d6ed335adbfac743d2c03/records/reef/docs/evidence/calcify-financial-sprint1/sprint1-exit-2026-10-06-1c143125049d) and [independent delivery review](https://github.com/dills122/reef-records/blob/2331d97d9f9e6af8638d6ed335adbfac743d2c03/records/reef/docs/evidence/calcify-financial-sprint1/sprint1-exit-review-2026-10-06-1c143125049d/reviews/delivery1/report.md)
published/verified; [retention inventory](records/2026-10-06-calcify-sprint1-exit-retention.json).
Records PR10 remains draft; no local tracked original removed.

October7 PR478 follow-up: shared empirical policy digest preserves v1 bindings;
persisted config evidence and both resource-profile round-trips covered. Focused
Node53/53 and exact hosted Node invocation280/280; retention check passes.
[Verification and retention scope](evidence/calcify-financial-sprint1/README.md#pr478-policy-review-follow-up--october7-2026).
No new timed load; next finite P0 source gate below remains pending.

| Existing sprint exit | Current evidence / next action |
| --- | --- |
| Kernel contract | Keep bounded unreserved P1a contract; oracle/reconstruction proofs passed. Live-source integration separate. |
| Gate protocol | Finite156 + seeded128 model cases passed. Propose RFC source-prefix baseline; credits require concrete isolation/progress need and access proof. Production selection unresolved. |
| Reservation policy | Proposed executable fixture; owner acceptance absent. Keep live holds deferred; present own-hold/residual/cancel cases for accept/amend. |
| Adapter recovery | Keep bounded E3 RF3/EOS contract: external4/core24/golden20/activation1 passed. No production/cluster-loss guarantee. |
| Rate/storage cost | Fresh8GiB empirical deadline1051.73settled/s; targetmiss. Full 150000 parity/result-only replay passed. Sampled costs measured; aged, production SQL/API and full qualification open. |

After measured E4 evidence, select existing P3 first useful-path slice, preceded
by unresolved P0 source acceptance/identity gate. RFC already defines venue and
lifecycle input, admission, kernel, one SQL bundle and existing API; shadow step
alone does not complete P3. Existing `matching-fact-v2` length framing supersedes
old delimiter collision concern; do not reopen hashing redesign. Source audit confirms retained `(run, order)` IDs reject duplicates, but bounded
terminal eviction deliberately permits same-run reuse. Snapshot restores retained
IDs only. Default terminal retention0 preserves all; stress profile250000 does not
prove full-run uniqueness. First P3 slice may pin retention0 with finite resource
proof, or add Calcify-scoped durable no-reuse enforcement. Preserve legacy reuse
semantics; incarnation contract needed only if owner requires same-run reuse.
Source: `internal/app/terminal_retention_replay_test.go` and
`internal/app/service_snapshot.go` under `services/matching-engine/`.
Adoption authority ADR and numeric replay/capacity/SLO choices stay separate from
synthetic E4 permission. No production financial authority changed.

### Next P3 slice: finite source-to-read path

Proposed scope follows RFC P3: isolated opt-in run, one instrument, two mapped
accounts/assets, journaled opening resources, unreserved gross DvP, source-prefix
admission, one SQL bundle, existing authenticated settlement reads, one
funding/new-attempt repair. Current proof kernel fixes `buyer`/`seller` and
`USD_NANO`/`ACME_SHARE`; mapping/generalization requires explicit invariant tests.
Authority adoption remains owner decision; legacy accounts stay isolated.

| Dependency order | Finite acceptance |
| --- | --- |
| P0 source gate | Pin retention0 plus finite run/order/resource budget. Real submit/fill/cancel/amend, zero-trade lifecycle, same-run reuse rejection after terminal state and restore; cross-run raw-ID reuse stays valid. |
| Source/lifecycle closure | Extend existing resolved-trade facts with versioned lifecycle/coverage membership. Every execution and zero-trade member has explicit disposition. Bound slice/fanout/bytes; future-window burst cannot block admitted closure; restore retains exact frontier. |
| Financial admission/adapter | Durable common order for captures/funding/repair; stable identity/request digest, identical retry returns original disposition, changed request conflicts. Promote reviewed kernel semantics with exact balanced legs and once-only obligation discharge. |
| SQL and read seam | One transaction writes journal/account/obligation/exception/checkpoint under epoch and entity-version checks. Post-commit crash replay once; stale projector refused. Existing API reads coupled rows/progress under common snapshot. |
| End-to-end repair/restart | Insufficient resources yield pending with zero partial legs; new funding plus distinct attempt settles once. Restart restores source/admission/owner/SQL cuts; missing required history refuses activation. Record finite stage costs. |

Production Phase1/2 resolved facts, settlement routes/store and legacy projections
exist. Financial lifecycle/gate/admission, runtime financial adapter and bounded
SQL projector/common-snapshot progress contract remain implementation work.
Existing legacy materializer scans complete persisted run facts; it does not
establish new bounded SQL bundle behavior. Reservations, external effects, netting,
production migration and full-path throughput qualification remain outside this
first slice. First implementation task: explicit opt-in finite isolated source
profile/startup guard pinned to terminal retention0, with terminal duplicate and
restore acceptance. Existing no-reuse behavior needs binding to declared source
contract, not another ID set or snapshot redesign. Live adoption remains owner
decision; lifecycle/coverage follows this scoped gate.

## Calcify E4 continuation — October 5, 2026

Branch `codex/calcify-e4-readiness`, base `29a8926d`. Test-only bounded diagnostic
Attempt1 code passed independent cycle3 review, financial97/97 and exact
NodeCI274/274 on its frozen source. Recoverable bounded ACK membership,
raw physical inventories, local-resource supervision, current-candidate E3
binding and separately launched JVM recovery are implemented. Review establishes
code readiness for that candidate. Its49-arm correctness passed on same source/build:
external4/core24/golden20/activation1,1073.813s,233 resource observations
(238 journal rows;4 fault-phase grants and1 closure distinct),228 with all3
actual allocations. Sampled maximum
project charge6,658,150,400 bytes includes fixed3GiB fault-target reservation;
maximum fully measured broker sum5,135,011,840 bytes has separate scope.
All three owned brokers stopped, volumes retained. First1000-settled/100-pending
diagnostic invocation refused before payload: equal heap maxima compared as
different JSON integer node types. Research reproduced boundary; strict integral
comparison fix passes focused8-test regression, including invalid forms. New
Attempt2 cycle1 whole-code review Ready; fullfinancial98/98 passes on frozen
source, actual capability round trip passes. Fresh same-candidate49 passed:
external4/core24/golden20/activation1,1054.051s correctness timing,228 resource
observations (233 journal rows),225 with all3 actual allocations. Second diagnostic
completed2100 actions/2101 histories and five physical checkpoints, then managed
child refused activation parity. Child count compared LongNode with parsed
IntNode2101; prior numeric audit missed this callback. Actual owner/hash parity
remains unproven because no child summary was emitted. Strict integral count
fix requires exact2101 and preserves hashes, ordinal2099 and journal offset;
semantic regression reproduced9 tests/1 failure before correction; corrected
whole financial suite99/99 and exact NodeCI274/274 pass on unchanged frozen
source. Independent whole-code Attempt2 cycle2 Ready. Fresh corrected49 passed:
external4/core24/golden20/activation1,1074.258s;232 resource observations
(237 journal rows),230 with all3 actual allocations. Sampled maximum project
charge6,657,753,088 bytes includes fixed3GiB fault-target reservation; maximum
fully measured broker sum5,073,129,472 bytes remains separate. Ordinary-shape
bounded diagnostic then passed1000 settled/100 pending,2100 actions/2101 histories,
all seven physical checkpoints, distinct parent/child JVMs and complete result-only
replay. Independent actual qualification instance2 Ready; owner SHA and history
checksum verified from complete raw facts. Parent sampled heap peak198,005,296
bytes; child179,424,816 bytes. These diagnostic observations establish no ordinary
rate, capacity, native-memory or conservative upper-cost qualification.

Compact canonical-leaf reference now preserves complete owner facts and history
without retained JsonNode trees. Whole-code Attempt3 cycle1 Ready on frozen27
paths; financial109/109 and independent focused Node125/125 pass. Exact existing
NodeCI274/274 retains unchanged Node source scope. Attempt2 cycle3 failed one
negative test because JsonNode Iterable overload appended record fields; explicit
singleton-list correction and rejection-message assertion pass. Original failure
and bytecode research retained before new attempt. Fresh compact capability binds
36 source snapshot files and97 Financial classes. Distinct compact49 passed in
1059.676s;234 journal rows/229 resource observations/226 fully measured all3.
Compact1000-settled/100-pending diagnostic passed all seven physical stages,
distinct child JVM recovery and complete result replay. Independent actual
qualification instance3of3 Ready; every2100 source envelope/2101 history record,
genesis/policy and reconstructed owner equal baseline without normalization.
All process groups and exact broker resources closed; volumes retained. Setup
watcher20-minute timeout preserved; fresh bounded metadata restart then succeeded.

Compact sampled parent peak214,017,080 bytes versus baseline198,005,296;
child219,456,064 versus179,424,816. Single paired run shows higher peaks, not
causal benefit or retained upper. Current prospective JVM layout/helper bytecode
binds retained lower664,800,000 bytes for150000 trades, above strict644,245,094 gate
by20,554,906. Exact active-PID layout not attested; complete finite upper absent.
Ordinary/aged ladder not authorized. Next bounded proposal: share two exact
constant leaf values without dropping facts, inspect canonical transient allocations,
then derive complete finite upper. Hypothetical608,400,376-byte weak lower would
still not prove fit. Reviewed code `17409ac2`, [PR477](https://github.com/dills122/reef/pull/477)
draft above PR475; all26 code-commit hosted checks passed/skipped. Proof published at immutable [Records `87242fe0`](https://github.com/dills122/reef-records/tree/87242fe0415a80a9934846686cd41fc8c08cad6c/records/reef/docs/evidence/calcify-financial-sprint1/e4-session-2026-10-05-29a8926d33f9); [Records PR9](https://github.com/dills122/reef-records/pull/9) draft above PR8. Fresh remote clone verified all24,719 blobs/843,777,692 bytes, including failed runs and reviews. No tracked bulk removed. Final documentation-head hosted CI tracked on PR477.

Pure Reference allocation research independently Ready: identical2101 pre-parsed
histories, two warmups/five measured repetitions per arm. Median accept allocated
2,244,541,400 ordinary versus2,370,007,696 compact bytes (+5.59%); final owner digest
66,991,464 versus7,431,576 bytes (-88.91%); combined median +2.85%. Reflection
included; parsing/construction/kernel/Kafka/other-thread/native costs excluded.
Fixed ordinary-first order and mapper lifecycle differ. Gross allocations are not
retained heap, driver-peak cause or conservative upper. Transient acceptance cost
now explicit next research target. [Profile and independent review](https://github.com/dills122/reef-records/tree/b9b0e17cbec9cb18e7d6ade3ea05eaff26129702/records/reef/docs/evidence/calcify-financial-sprint1/e4-reference-allocation-2026-10-05-bf04ead83d8b)
published; all31 supplemental blobs/5,956,080 bytes remotely verified.

Earlier ordinary-shape model refuses smallest proposed ordinary arm,2500
trades/s for60s, under pinned Java21/768MiB two-owner retention. Map entries alone require at least
732,000,000 bytes, above strict644,245,094-byte heap gate; fuller structural lower
is804,000,000 bytes. This is conditional resource admission refusal, not measured
latency, rate capacity or conservative upper cost. Diagnostic cohort cannot
authorize ordinary ladder. Integrated compact candidate now has independently qualified bounded actual
owner/history/recovery comparison; ordinary ladder still needs separately supported
finite cost admission.

E3 and heap predecessor drafts remain separately scoped below. Reservation owner
acceptance, matcher identity, SQL/API cost and production financial authority
remain separate. [Focused current verification](evidence/calcify-financial-sprint1/e4-verification-2026-10-05.json) owns diagnostic status.

## Calcify heap protection component — October 5, 2026

Component verification passes on `codex/calcify-heap-protection`, starting from
E3 delivery checkpoint `3ce2bdf2`. Actual JVM capability binding, checked finite
retained-state admission and sticky sampled protection cover result-only replay
and publication. Financial64/64 and exact NodeCI230/230 pass. Independent review
Attempt1 cycle3 **Ready**; cycle1 P2 peak-scope finding fixed with actual RED/GREEN
control. Emitted peaks explicitly exclude final serialization/staged writes.
[Focused verification](evidence/calcify-financial-sprint1/heap-verification-2026-10-05.json)
owns exact source/build/test scope. Proof published at Records `132dae9d`; [Records PR8](https://github.com/dills122/reef-records/pull/8) draft, landing pending. All216 remote blobs/3,632,431 bytes verified; local-only provenance `faa6c0f5`, product checkpoint `799760dc`. No tracked bulk removed.
No broker load or real conservative cost qualification.

Missing conservative cost provenance blocks existing real-load policies. Full E4
readiness still needs recoverable ACK membership, distinct physical-byte metrics,
producer/observer cost calibration and resource supervision through restore.
Heap protection alone establishes no capacity, native-memory or OOM guarantee.
Real launcher remains pinned to local macOS Java21/128m/768m; portability needs
explicit new launcher binding. Cost calibration needs separately reviewed finite
derivation and safe bootstrap envelope before existing real policies can run.

Hosted first Node227/229 failed missing macOS-only lock parent on Linux. Shared fixed platform lock preserves Darwin path and exclusivity; final local230/230, independent Attempt1 cycle3 Ready. Kotlin source/build unchanged; original64-test receipt retained. Supplemental correction proof published at Records `2a2abf9e`:63 remote blobs/700,463 bytes verified; combined279/4,332,894. [Final independent review](https://github.com/dills122/reef-records/blob/2a2abf9ea130636daecc2d35dc6c961b08c182f9/records/reef/docs/evidence/calcify-financial-sprint1/heap-ci-correction-2026-10-05-3bf45160ebfa/review/cycle3/report.md); Records PR8 draft, landing pending; actual hosted status follows [Reef PR475](https://github.com/dills122/reef/pull/475), stacked above E3 PR474.

## Calcify E3 continuation — October 5, 2026

E3 delivery branch `codex/calcify-planning-readiness`, baseline `a6ddafbb`.
Current session explicitly authorizes new bounded milestone reviews: three cycles
per attempt, research between attempts, maximum three attempts. Historical cap7
and original proof remain dated evidence; neither count is reset.

Test-only candidate adds four external fault controls and shared partition-cut
certification at worker activation. Actual committed source offsets, topic UUIDs,
coverage/history/full semantic state and framework group checkpoint stay separate.
Exact quartet uses isolated mixed-cut refusal plus real new-directory restore;
no injected mixed RocksDB/changelog restore claim.

Candidate checks: financial Kotlin **45/45**, exact Node CI **216/216**, zero
failures/skips. Recovery guard Attempt2 cycle2 Ready for bounded live proof.
Real owned recovery smoke passes: four parent `starting` observations with actual
`du`, fixed 3 GiB target charge, healthy final sample, CLI exit0 and clean stop;
three named volumes retained. Candidate full external4/4, quartet restore,
core24/24 and golden20/20 oracle/complete-history checks pass on identical source/
build/fixture hashes. [Focused candidate verification](evidence/calcify-financial-sprint1/e3-verification-2026-10-05.json)
records exact scope and prior failures. Final independent review **Ready**, no actionable findings: M0 Attempt2 cycle3,
M1 Attempt1 cycle3, M2 Attempt1 cycle2. Reviewer verifies49 candidate arms,246
resource samples and fresh Node60/60. Bounded E3 acceptance/retention complete.
[Full proof](https://github.com/dills122/reef-records/tree/ed6faadf261dab78a1c4f49991b8f297aecadea4/records/reef/docs/evidence/calcify-financial-sprint1/verification-session-2026-10-05-a6ddafbbae75) and [independent report](https://github.com/dills122/reef-records/blob/ed6faadf261dab78a1c4f49991b8f297aecadea4/records/reef/docs/evidence/calcify-financial-sprint1/verification-session-2026-10-05-a6ddafbbae75/integration-review/final2-review/report.md) pinned to `ed6faadf`;
1358 remote blobs/53,415,268 bytes verified exact. [Records PR7](https://github.com/dills122/reef-records/pull/7) draft,
landing pending; no tracked bulk removed. Product checkpoint `c4d332a0` holds
reviewed code; actual runs bind prior dirty/new source/build hashes.

Applied heap component above closes implementation slice; remaining E4 gates:
recoverable ACK membership, distinct physical-byte metrics, reviewed finite heap
costs and producer/observer calibration/resource supervision through restore.
Existing financial rate assessment remains LIMITED. Reservation owner
acceptance, matcher identity and production financial authority remain separate.

## Calcify sprint1 verification — October5 UTC / October4 local, 2026

Merged-source reconciliation October 4 local: `origin/master` at `a6ddafbb`,
[PR #473](https://github.com/dills122/reef/pull/473) merged; required CI passed.
This chat's clean worktree updated from `97924e15` on
`codex/calcify-planning-readiness`. Primary checkout remains on its existing dirty
planning branch; unrelated edits preserved. No Calcify PR remained open at that reconciliation.

| Merged slice | PRs | Resulting scope |
| --- | --- | --- |
| Phase 1 | #429, #430 | Opt-in commitment/extractor/verifier/receipt path |
| Phase 2 | #431, #433 | Full-fact managed resolver and run-scoped matching |
| Correctness and CI | #435, #436, #438, #439, #440, #461, #463, #465 | Receipt integration tests, run identity, retention/topic binding, hidden-order facts and matching fixes |
| Throughput and overview | #458, #462 | Flow reference; local RF1 standing-liquidity D7 pass at 10,425.19 verified trades/s for 300s |
| Financial planning/preparation | #466, #472 | Proposed architecture/proof plan and frozen E0 inputs |
| Financial experiments | #473 | Test-only kernel/oracle, reservation/gate models and bounded RF3 correctness proof |

Branch `codex/calcify-sprint1-experiments`; recovered test-only source preserved,
primary dirty checkout untouched. [Focused checkpoint](evidence/calcify-financial-sprint1/README.md),
[active handoff](work/handoffs/2026-10-04-calcify-financial-sprint1-recovery.md).

OCR follow-up: checkpoint schema/state validation and null invalid metrics fixed;
four new regressions, focused47/47 and exact Node158/158 pass. Lock finding
disputed with exclusive-open ownership evidence. New JS changes await independent
sign-off; cap7 exhausted. [Reef PR473](https://github.com/dills122/reef/pull/473).

**Review7/7 Ready with non-blocking follow-ups.** Guard P1 closed; independent
19 mocked + 2 real process-tree controls pass. Small pilot 2/2; full implemented
matrix 24/24 core + 20/20 golden, all 44 isolated-history checks pass. Actual RF3/
write-caching/8 MiB settings verified on 132 topics. Sampled maximum 4.45 GiB;
474.917 seconds correctness timing. Both clusters stopped, six volumes retained.
Fresh Node154/154; retained identical-code financial27/27 and offline module
775 reported passes/20 skips/zero failures. No DB integration or capacity claim.

At original checkpoint, full E3 majority/stale-owner/producer-failure/committed-before-controller-ACK/A10B27
activation and E4 heap/ACK/physical-byte/calibration gates remained open. Next: close
missing fault/activation acceptance cases before E4 rate qualification. Reservation
acceptance and matcher identity integration remain. No full sprint/cutover sign-off.
Cap 7 used; material changes need human review extension.

Next bounded slice: extend test-only E3 adapter/probes with one-broker outage and majority intact,
stale-owner takeover, producer failure, committed-before-controller-ACK and exact
A10/B27 certified activation. Freeze each failure trigger and oracle/cut assertion
before execution; preserve original 44-arm proof as its own software scope.
Recheck isolated RF3 profile, image/build hashes, ownership and resource guards.
Pass requires exact committed history/state/oracle agreement, once-only financial
IDs, refusal on missing history and no old-output republication during restore.

After E3 acceptance: apply/test E4 heap guard, prove durable ACK membership,
measure physical replicated bytes and calibrate producer/observer before rate loads.
Reservation policy and live matcher identity need separate owner/source decisions;
synthetic financial proof does not close those gates. Records PR #6 remains open
at `ef6e48ee`; immutable proof bytes are published, archive merge still pending.
Fresh local preparation check: focused financial Node suite 54/54 passes.

Documentation completion: current board/navigation, discovery scope, phase overview,
RFC status link and active handoff reconciled. No runtime/contracts/accepted authority change.
Retention no-op: no standalone record or evidence bundle superseded; historical
proof and existing immutable Records links preserved.

[Bulk reviews/proof](https://github.com/dills122/reef-records/tree/b5d868b69132e85cdf74e4fc9c948f01eafc74c9/records/reef/docs/evidence/calcify-financial-sprint1/verification-2026-10-05-f22b2f85) only in Records; focused verification and executable
fixtures remain in Reef. Prior failures immutable. Completion/retention pass updates
current owners; no source removal. Test-only change affects no production API,
events, storage, authority or accepted ADR; guidance/overview checked.

## Calcify financial experiment E0 checkpoint (2026-10-03)

Execution source85f0ce8c; planningcda4185b/PR#466 subsequently merged, docs only.
Java21 compile/70 Calcify tests, matcher suites, frozen20cases/52inputs and recorder
regressions pass. Review2 accepts preparation checkpoint; E0 language-server and
Docker gates blocked, E1-E4 unrun. Same-run reuse/reservation-source gaps remain.
[Focused checkpoint and next E1 slice](evidence/calcify-financial-sprint1/README.md).
97-file complete evidence bundle verified and landed via [Records PR #5](https://github.com/dills122/reef-records/pull/5);
[full details](https://github.com/dills122/reef-records/blob/08a4347f7410f94837cdf18136296629e8f551ce/records/reef/docs/evidence/calcify-financial-sprint1/README.md). Reef keeps scripts/tests, fixtures and concise
status; raw proofs/reviews live in Records. No production integration/cutover.

## CI dependency throttling recovery (2026-10-02)

Master `2af704d7` CI recovered on [failed-job rerun](https://github.com/dills122/reef/actions/runs/37090920609/attempts/2).
Initial stock-data Docker build failed on Maven Central HTTP 429 after green
PR CI; required gate correctly failed. `codex/ci-maven-rate-limit` adds
three-attempt bounded Docker retries for this exact dependency failure, with
regressions wired into CI and local tooling. [CI Operations](CI_OPERATIONS.md#workflow-maintenance-rules)
owns cause, source runs, protection check and retry limits.

Local verification: exact Node CI suite passes 104 tests, zero failures/skips;
local dev-tooling target, observed-failure classifier check, actionlint,
script-surface check, offline Records retention and diff whitespace pass.
Sandbox blocked localhost test listener on first full-suite attempt; same
suite passed with localhost access. Hosted Docker builds remain PR CI checks.

Completion pass: CI owner docs and board updated; aggregate gate and delivery
invariants preserved. No runtime/API/event/storage/scenario changes, so contracts,
architecture overviews and boundary steering remain applicable. No standalone
record or retained evidence bundle superseded; archive pass is a no-op.

## Calcify joined10k checkpoint (2026-10-02)

**Configuration correction:** campaign tested PostgreSQL-backed smoke ingress, not documented in-memory-intake/publish-pipeline performance shape. Intended fast-ingress+Calcify capacity remains unmeasured. Verify explicit profile/accounting before further tuning;7,708.36/s is scoped SQL-smoke result.

Two-hour frozen-base campaign fails10k accepted-order target. Best300s **7,708.36durable order ACKs/s**,1,158,676 exact resolved contexts and Phase1 counts, bounded drain, zero failures. Final higher-concurrency attempt aborts on Docker guest-disk ENOSPC. Latest-master correctness fixes integrated and separately checked; no capacity transfer. [Campaign evidence/next gates](research/CALCIFY_10K_TIMEBOX_2026-10-02.md), [handoff](work/handoffs/2026-10-02-calcify-10k-timebox.md). Restore guest storage headroom and repeated-state controls before further sustained load; durable-intake batching remains measured hypothesis. Older checkpoints below retain dated scope.

## Idempotency renewal and run-detail fixes (2026-10-02 branch)

`codex/idempotency-renewal-run-detail`, based on `76872e9d`, addresses #442
and #456. Expired boundary result rows renew atomically without cleanup;
live results and TTLs remain immutable under concurrent writers. In-memory
expiry/renewal follows the same rule, including stale-reader safety. Capture
and command-log reservation lifecycles remain separate. Arena run detail now
publishes only the current request and invalidates pending work on navigation,
empty selection and unmount. Both focused regressions are wired into CI.

Local verification: Java 21 focused `ExternalApiBoundaryTest`,
`InMemoryIdempotencyStoreTest` and `PostgresIdempotencyStoreIntegrationTest`
pass **38 tests, zero skipped/failures**, using isolated PostgreSQL 16 on
localhost:55442 with `RUNTIME_DB_*_TEST` configured. Deferred UI race/lifecycle
checks, Svelte check (zero errors/warnings), guarded static build, CI workflow
guard, actionlint, Node script-surface check, diff whitespace and offline
Records retention pass. Browser-render and full-stack qualification not run.

Completion pass: boundary storage and Arena owner docs updated; D-016, API
steering and overview invariants reviewed. No route/schema/event changes or
new architecture decision; general guidance remains applicable. No standalone
topic record/evidence replaced, so archive pass is a no-op; maintained doc
versions remain in Git history.

Independent review instance 1 of 3: **Ready**, no actionable findings. Reviewer
verified frozen file hashes, reran UI/CI/build/retention checks and inspected
prior zero-skipped 38-test Kotlin XML; no fresh DB or mounted-browser rerun.

## Records separation (2026-10-02)

Reef Records bootstrap and archive import landed in [Records PR #2](https://github.com/dills122/reef-records/pull/2). 386 historical files (39.36 MiB) preserved byte-for-byte at pinned archive commit; Reef cleanup on `codex/extract-historical-records` retains 89 current evidence files plus active code/docs and required fixtures. [Retention policy](RECORDS_RETENTION.md) and relocation inventory own selection and lookup. Local archive hashes, retained checksums, active links and five retention failure-path tests pass; no new runtime or performance qualification claimed.

## Records CI follow-up (2026-10-02)

PR #467 script-surface gate flagged retained `scripts/dev/projection-dirty-crash-test.mjs` after its only research-doc reference moved to Records. Current migration runbook now documents executable harness, prerequisites, isolation and limits, with archived F02 evidence link. Script-surface check passes; exact Node dev-tooling CI suite passes 87 tests. Retention pass: current runbook updated; no evidence bundle replaced or additional historical import needed. Later master merge `9b2a4861` conflicted only at work-board insertion; both current Calcify checkpoint and Records sections preserved. One new upstream link repaired to pinned Records; 228 newly merged evidence companions added to retained completeness inventory. Retention check, script-surface and exact 98-test Node CI suite pass after merge.

## Ongoing records guidance (2026-10-02)

Feature/fix/refactor completion now requires implementation/tests/contracts/docs/latest relevant evidence, affected guidance/overview review, then supersession/archive pass. AGENTS, contributor/PR guidance, delivery policy and AI reading path point to [required completion pass](RECORDS_RETENTION.md#required-completion-pass). Local context serves current planning, session/ramp-up and system/design; historical lookup uses Records originals/corrections with source/commit/scope citations. Retention pass for this guidance-only update: touched docs remain current normative owners; no new run evidence, completed plan or replaced bundle, so no additional archive import. Existing archived bytes and retained evidence pass unchanged.

## Calcify Phase 2 implementation (2026-09-30 branch)

User approved [six-slice implementation plan](work/CALCIFY_PHASE2_IMPLEMENTATION.md). Active branch: `codex/calcify-phase2-implementation`, continuing preserved experiment commits. [Draft PR #433](https://github.com/dills122/reef/pull/433) records nightly checkpoint and merge blockers. D-042 run-scoped matching/V3 snapshots, full-fact Protobuf/pure resolver, managed Streams role, bounded lane faults, source identity/retention guards, and operational configuration implemented. Actual HTTP two-order smoke emits one exact context. Corrected RF3/fsync fault matrix passes nine boundaries; million-row cold recovery reaches observed RUNNING in19.97s with exact catch-up. Those broker cohorts precede final throughput changes and require unchanged-candidate repeat. Latest100k hot diagnostic reaches11.12k/s with JFR and exact full-fact parity. Latest sustained hot3.15m cohort reconciles exact at10.16k/s but producer310.73s fails frozen301s maximum; spread/skew/aged unrun. Final local platform regression/coverage passes. Review found open verified-input retention and verified/output UUID binding gaps. Work paused at [nightly handoff](https://github.com/dills122/reef-records/blob/6b838e5287c5428c914f6577ff176c1cd03c44ec/records/reef/docs/work/handoffs/2026-09-30-calcify-phase2.md); correctness fixes, unchanged-candidate fault/recovery, production-role smoke and sustained qualification remain; no full-system20k upstream capacity or production availability claim. [Implementation evidence](evidence/calcify-phase2-implementation/README.md) preserves failed attempts, durability correction and exact measurement scope.

## Calcify Phase 2 experiment checkpoint (2026-09-30 branch)

`codex/calcify-phase2-experiments`, based on merged #431, contains isolated source
fixtures, managed-runtime/fault prototypes and measured local-store diagnostics.
Recommendation: verified-led one-input Kafka Streams with managed full-fact local
state/changelog/standby and demand source reader. Scratch D-042 alignment proves
cross-run isolation/replay fixture; production matching alignment still pending.
Recovery, topic-generation/retention and two-lane fault probes passed in stated
local RF1 scope. Five-minute 10k local store/codec joins/s is not full resolver or
system capacity qualification. [Decision/evidence report](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/research/CALCIFY_PHASE2_EXPERIMENTS_2026-09-30.md)
records remaining implementation gates and reproducible commands. User sync subsequently approved implementation; see implementation checkpoint above. Legacy remains intact.

## Calcify Phase 1 checkpoint (2026-09-29 branch)

`codex/calcify-phase1` adds opt-in matching commitment extraction, stub
verification, and PostgreSQL processing receipts without switching legacy
post-matching. Local fixtures, partition-poison isolation, and receipt restart
passed on two-partition Redpanda. A later four-partition local smoke passed
PostgreSQL-backed HTTP intake through Go matching to an exact Calcify receipt;
bounded 2,000-order hot-lane local load also reconciled 1,001 trades, links,
and receipts after drain. These are diagnostics, not hosted capacity or
settlement qualification.
Measured verifier-per-link transaction backlog was corrected by per-poll
transaction batching: two repeated local 1,000-pair runs drained receipts
within 960 and 942 ms after last acceptance with exact stage counts.
Phase 1 paired load gate then passed: 1,000-pair burst plus five minutes at
100 crossing pairs/s, with 60,000 accepted load orders, 30,001 trades/links/
receipts including preflight, sampled accepted-to-receipt gap p95/peak 70
trades, and 1,923 ms final drain. Scope remains local single-lane diagnostic.
Follow-up five-minute run at 300 crossing pairs/s accepted 180,000 load
orders; 90,001 trades/links/receipts reconciled, sampled gap p95/peak was
200 trades with steady first/last half means, and final drain was 1,943 ms.
Tail stayed bounded at this tested rate; higher-rate and longer-run limits
remain unproven.
High-rate CAL-P1-L6 probes exposed a Kafka 1 MiB matching event-batch ceiling,
per-record receipt transaction/checkpoint backlog, and Bun socket saturation.
Phase 1 high-rate local setup now uses 200-command matching batches and per-poll
receipt batches. With pooled Go load, 10k/s-offered 30-second probes reached
about 7.7k accepted orders/s. First 7.5k/s-offered five-minute run accepted
7,258.61/s with exact stage counts, but gap samples were lower bounds.
Corrected 7.5k/s run accepted 6,420.72/s and failed intake gate despite exact
stage counts. Fresh 5k/s-offered five-minute run passed at 4,998.03 accepted
orders/s with 749,952 exact trades/links/receipts, conservative upper-gap
p95/peak 628/846 trades, and 1,478 ms final drain. Repeat sustained plus
short stress probes for every material post-match phase; 7.5k/s and 10k/s
local and multi-lane qualification remain open.
Exact wire contract, corrections, and limits:
[Calcify Phase 1 implementation](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/work/CALCIFY_PHASE1_IMPLEMENTATION.md).
Replay-retention window enforcement and independent source-to-link
reconciliation remain follow-ups before non-diagnostic use. Repeat
`make dev-smoke-calcify-full-path` as each post-match slice expands, adding
assertions for that slice's source identity, durable effect, and replay behavior.
After each material post-match phase change, run a fresh full-path preflight,
a 30-second 10k/s-offered probe, and a 300-second 5k/s-offered run through
`make dev-smoke-calcify-high-rate` and `make dev-soak-calcify-high-rate-load`.
Use `make dev-verify-calcify-high-rate` for exact final stage counts; record
offered/accepted rate, dropped pairs, conservative in-load receipt gap, drain,
failures, and phase-specific effects. Raise sustained target toward 7.5k/s
only after it passes with the new phase. Retain raw reports for failed runs.

## Source Of Truth

- Command and acceptance semantics:
  [`COMMAND_INTAKE_PROCESS.md`](./COMMAND_INTAKE_PROCESS.md)
- API/control-plane boundary:
  [`API_SURFACE_POLICY.md`](./API_SURFACE_POLICY.md)
- CI merge and scheduled-check operations:
  [`CI_OPERATIONS.md`](./CI_OPERATIONS.md)
- Active throughput handoff:
  [`THROUGHPUT_SCALING_IMPLEMENTATION_PLAN.md`](./THROUGHPUT_SCALING_IMPLEMENTATION_PLAN.md#pause--resume-handoff)
- Projection scaling:
  [`PROJECTION_THROUGHPUT_SCALING_PLAN.md`](./PROJECTION_THROUGHPUT_SCALING_PLAN.md)
- Scenario contracts and assertions:
  [`SCENARIO_CONTRACTS.md`](./SCENARIO_CONTRACTS.md) and
  [`SCENARIO_ASSERTION_PLAN.md`](./SCENARIO_ASSERTION_PLAN.md)
- Post-trade model and remaining hardening:
  [`SETTLEMENT_CLEARING_STRATEGY.md`](./SETTLEMENT_CLEARING_STRATEGY.md) and
  [`POST_TRADE_LIFECYCLE_SPRINT.md`](./POST_TRADE_LIFECYCLE_SPRINT.md)
- Arena preview implementation and release gate:
  [`BOT_ARENA_INVITE_PREVIEW_SPRINT.md`](./BOT_ARENA_INVITE_PREVIEW_SPRINT.md)
  and [`BOT_ARENA_RELEASE_READINESS.md`](./BOT_ARENA_RELEASE_READINESS.md)
- Documentation lifecycle:
  [`DOCUMENTATION_CLEANUP_PLAN.md`](./DOCUMENTATION_CLEANUP_PLAN.md)

Historical plans and dated reports remain evidence, not parallel execution
ladders.

## Planning Posture

Reef remains a simulation-first institutional trading venue and post-trade
platform. Correctness, durable acceptance, deterministic ordering, replay,
auditability, and idempotency are never traded for a higher throughput number.

Reef and Bot Arena are separate product surfaces. Reef owns venue and
post-trade behavior. Arena consumes Reef contracts through its optional
artifact and Compose overlay; Reef-only builds, routes, migrations, storage,
and readiness remain independent.

## Promoted Baseline

- The Redpanda/Kafka-compatible direct path is the canonical venue-core shape:
  durable publish acknowledgement, matching-engine partition consume,
  transactional venue-event publication and command-offset commit,
  `read_committed` canonical materialization, then asynchronous projections.
- The verified venue-core ceiling remains `10k commands/sec`. The corrected
  local `15m` run closed `8,999,955` commands at `9,999.49/sec` with no final
  gap; it did not justify a `20k` claim.
- Full projection passed historical `5k/60s` gates, but the August sustained
  baseline is `2.5k/5m`. The `5k/5m` run failed freshness with `757,955`
  watermark lag despite exact intake and canonical materialization; see
  [`PROJECTION_THROUGHPUT_SCALING_PLAN.md`](./PROJECTION_THROUGHPUT_SCALING_PLAN.md).
- P1 hidden-cross and P2 settlement-break/repair scenarios have local public
  readback plus replay/checksum evidence.
- Reef/Arena artifact, route, persistence, Compose, failure-isolation, and P1
  equivalence gates are promoted in
  [`REEF_BOT_ARENA_SEPARATION_PROMOTION.md`](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/archive/REEF_BOT_ARENA_SEPARATION_PROMOTION.md).
- Fork admission, SHA-bound maintainer approval, and external-account
  onboarding are complete through the July 23 `noodle-invite-smoke` test and
  follow-up fixes. The completion record lives in
  [`BOT_ARENA_RELEASE_READINESS.md`](./BOT_ARENA_RELEASE_READINESS.md#admission-and-onboarding-completion).
  Hosted game evidence and open-intake release requirements remain separate.

## Recent Implementation Checkpoint

- PR #337 (`a38489a9`) implemented the named architecture-review code items:
  canonical cancel/modify routing and ownership checks (`ARCH-IR-01/02`),
  deterministic malformed-timestamp rejection (`ARCH-IR-05`), and descriptor
  compatibility plus generated-source drift checks (`ARCH-IR-08`). Their
  regression tests remain required; these are no longer initial implementation
  tasks. This checkpoint does not claim a separate review sign-off.
- PR #341 (`29fb9993`) landed fail-closed stress evidence, projection phase and
  statement instrumentation, fixed-backlog drain tooling, unsafe stage
  configuration guards, and the one-maintainer remote gate configuration.
  August 21 remote short runs exercised that topology: `2.5k` passes the
  current checker; `5k` fails downstream maintainer drain despite exact
  canonical/projected counts. See the audit's recovered evidence.
- PR #349 (`16d0022c`) landed CI reliability and scheduled-check hardening.
  Treat subsequent failures and required-check rollout as CI operations under
  [`CI_OPERATIONS.md`](./CI_OPERATIONS.md), not an unfinished feature sprint.

## Work Board

This is the repository work-board view of the execution ladder below. Status
describes the remaining task, not whether the whole subsystem exists.

| Work | Status | Next bounded action |
| --- | --- | --- |
| Settlement read visibility/authorization | Contract and code work | Define run/participant/operator visibility; enforce and test both adapters. |
| Projection `5k` downstream drain | Failed evidence gate | Diagnose final lifecycle/market maintainer work; prove drain before sustained promotion. |
| Bounded-state workload | Implemented, unmerged | Reconcile `codex/throughput-state-shape-control`; smoke all three shapes. |
| Arena multi-seed/hosted games | Evidence to locate or produce | Complete missing campaign/rehearsal records; admission, roster and scoring code already exist. |
| Post-trade scenario hardening | Evidence to locate or produce | Record live security-repair/realistic-pending reports; behavior already implemented/tested. |
| Operational readiness and service identity | Partial implementation | Extend existing config/health/TLS foundations with operational and peer-identity proof. |
| Compact canonical storage | Deferred until state-shape gate | Run measured storage A/B with retained audit/replay proof. |
| CI, onboarding, mutation/protobuf hardening | Delivered; regression maintenance | Preserve gates; do not reopen initial implementation. |

Evidence and source/test mapping:
[`IMPLEMENTATION_STATUS_AUDIT_2026-09-04.md`](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/archive/IMPLEMENTATION_STATUS_AUDIT_2026-09-04.md).
Supplemental UI and simulator backlogs are candidate catalogs, not competing
priority ladders; reconcile individual candidates before scheduling them.

## Active Execution Ladder

1. Complete the remaining invite-only game-preview evidence.
   - Preserve the completed external admission/onboarding test as regression
     evidence; do not repeat its implementation or initial proof as backlog.
   - Use the implemented and tested `T-72h` / `T-48h` / `T-24h` eligibility,
     roster lock, and `T-30m`/`T0` run binding in recorded preview evidence.
   - Locate or record the remaining multi-seed and hosted preview runs with
     immutable roster, policy, seed, artifact, replay, accounting, and scoring
     evidence. Existing July 14 hosted scoring proof is already recorded.
   - Execution-role propagation and evidence isolation are corrected and the
     fresh local three-policy matrix passes with 30 scoped fills per policy,
     complete reconciliation, zero accounting gap, and no unspecified roles.
     Repeat this evidence on the promoted hosted Arena profile; retain the
     crossed-book warning until market-data reads are venue-session scoped.
   - Do not advertise open or self-service submissions before the release
     matrix is green.

2. Finish the API/control-plane hardening backlog.
   - Participant order/current/history/fill reads and command client/participant
     checks already have implementation and negative tests; preserve them.
   - Next code slice: define run/participant/operator visibility for the six
     `/api/v1/settlement/*/{scenarioRunId}` read families in the API surface
     policy, then add allowed/denied tests and enforcement in both HTTP
     adapters. These routes currently authenticate/rate-limit, but do not pass
     a principal into the run-level read gateway. Do not silently choose a new
     visibility policy or repeat the existing order-authorization work.
   - Keep hosted, CI, and operator callers off raw `/internal/*` HTTP. The
     current [`INTERNAL_HTTP_CALLER_INVENTORY.md`](./INTERNAL_HTTP_CALLER_INVENTORY.md)
     has no hosted migration candidate; retain local diagnostic callers as
     loopback-only tooling and treat new remote callers as regressions.
   - Extend existing TLS/mesh client configuration to explicit peer/service
     identity and deployment proof. Standard engine gRPC health already exists.
   - Deepen existing enabled-role readiness beyond configuration checks;
     lifecycle/market-data readiness currently reports `true` when enabled.
   - Keep `/api/v1` and `/admin/v1` as the only externally reachable HTTP
     product families.

3. Resume venue-core scaling only from the recorded pause handoff.
   - Reconcile the existing local `codex/throughput-state-shape-control`
     implementation (`f00dd590`) with current `master` before adding another
     bounded-state implementation. It is not merged; its plan still requires
     local three-shape smoke and hosted promotion evidence.
   - First prove bounded working-set/state-shape behavior, including live-order
     retention and terminal-order cleanup.
   - Then run the compact canonical storage A/B and measure WAL/table bytes per
     command.
   - Preserve transactional command/event handoff, static ownership fencing,
     semantic checksums, full-log recovery, and materializer idempotency.
   - Raise the verified ceiling only after short and soak gates close with zero
     accepted/direct-acked/materialized gaps.

4. Reduce projection write amplification.
   - Reuse the completed August 21 instrumented one-maintainer short comparison.
     Close the `5k` downstream-drain failure, then run the bounded memory/batch
     matrix and sustained gates from the projection plan. Do not infer
     downstream freshness from canonical/projected count equality alone.
   - Keep the command-status write subset and full timeline projection stages
     measurable independently, but do not claim independent lifecycle
     freshness until cancel/modify event dependencies are explicit.
   - Reduce `runtime_events`, lifecycle/fill, WAL, tuple, and temp-file pressure
     before longer `5k` soaks or higher projection rates.
   - Keep projection freshness claims separate from venue-core acceptance and
     canonical materialization claims.

5. Harden the implemented post-trade lifecycle.
   - Preserve the allocation, confirmation, affirmation, clearing, novation,
     obligation, instruction, attempt, leg, ledger, break, repair, resolution,
     exception-queue, proof, and score fact chain.
   - Security-fail/repair and `ops-realistic-v1` pending behavior already have
     implementation and passing tests. Locate or record their remaining live
     scenario reports, then define one operator workflow slice from those
     results. Missing reports are evidence work, not missing lifecycle code.
   - Keep deterministic netting as a separately scoped contract/preview; do
     not broaden this checkpoint into a clearinghouse build or mutate matching
     history.

6. Keep documentation synchronized as behavior lands.
   - Update contracts, internal docs, public docs/API pages, and README in the
     same change when routes, commands, deployment shape, or release claims
     change.
   - Move superseded plans to `docs/archive/`; never delete decision,
     benchmark, security, or replay evidence.

## Non-Goals At This Checkpoint

- No `20k` or higher venue-core claim from short, no-op, accepted-only, or
  unmaterialized evidence.
- No UI/control-room freshness requirement folded into the `202 Accepted`
  contract.
- No raw `/internal/*` route presented as a public, partner, bot, SDK, or stable
  operator API.
- No open Bot Arena intake before remaining hosted-preview and open-intake
  requirements pass; admission/onboarding is already complete.
- No Arena implementation dependency in Reef-only artifacts or deployment.
- No broad clearinghouse build before the current post-trade facts and operator
  paths are hardened.

## Definition Of Done For Active Work

- focused tests cover the changed behavior and failure modes
- contracts and documentation change with behavior
- replay, idempotency, ordering, and audit evidence remain intact
- performance claims name attempted, accepted, direct-acked, materialized, and
  projected stages separately
- artifacts and run identifiers are recorded for promoted evidence
- Reef-only and Arena-enabled boundaries remain independently testable

## Records second pass — October 2, 2026

Default `master` synced to `76872e9db249152e0329393d73c4a2f850ea2b93` before this pass. Completed R08 records, closed campaign scratch plans and superseded handoff/pilot evidence moved into [published Records PR #4](https://github.com/dills122/reef-records/pull/4). [Inventory](records/2026-10-02-second-pass-migration.json) pins verified originals. Latest R08 verification rehomed locally; current Calcify owner docs, open gates and evidence retained. CI Operations owns current pilot guidance. No runtime behavior or qualification changed.
