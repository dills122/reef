# Calcify history log

Status: **read-only history.** These dated checkpoint essays were moved verbatim from the work board on October 11, 2026
so [`WORK_PLAN.md`](../WORK_PLAN.md) can stay a board. They are evidence for their stated scope and date, not instructions to reopen work.
Current state and next work: [program status and sprint plan](CALCIFY_PROGRAM_STATUS_AND_SPRINT_PLAN_2026-10-11.md).
Relative links in the moved text were rewritten for this location; wording, numbers and anchors are unchanged.
Candidates for Records archive under the [retention policy](../RECORDS_RETENTION.md) once their evidence is published there.

| Date (2026) | Milestone | PR | Section |
| --- | --- | --- | --- |
| Oct 9-10 | Eight-hour P3 session: O0 contract, O1 lifecycle capture model, reviews, OCR delivery | PR485 | [Calcify eight-hour P3 session — October 9–10, 2026](#calcify-eight-hour-p3-session--october-910-2026) |
| Oct 7 | Finite P0 matching source gate (retention 0, finite order envelope) | PR479 | [Calcify finite P0 source gate — October 7, 2026](#calcify-finite-p0-source-gate--october-7-2026) |
| Oct 6-7 | Sprint 1 exit: finite financial diagnostic; timed settlement target missed | PR478 | [Calcify sprint1 exit continuation — October 6, 2026 UTC](#calcify-sprint1-exit-continuation--october-6-2026-utc) |
| Oct 5 | Bounded recovery and compact owner diagnostics (E4 continuation) | PR477 | [Calcify E4 continuation — October 5, 2026](#calcify-e4-continuation--october-5-2026) |
| Oct 5 | Heap admission and sampled guard (scoped protection) | PR475 | [Calcify heap protection component — October 5, 2026](#calcify-heap-protection-component--october-5-2026) |
| Oct 5 | E3 certified cuts and external broker recovery | PR474 | [Calcify E3 continuation — October 5, 2026](#calcify-e3-continuation--october-5-2026) |
| Oct 4-5 | Sprint 1 verification: E1/E2 financial correctness experiments (test-only) | PR473 | [Calcify sprint1 verification — October5 UTC / October4 local, 2026](#calcify-sprint1-verification--october5-utc--october4-local-2026) |
| Oct 3 | E0 frozen inputs and readiness | PR472 | [Calcify financial experiment E0 checkpoint (2026-10-03)](#calcify-financial-experiment-e0-checkpoint-2026-10-03) |
| Oct 2 | Joined 10k checkpoint; D7 sustained direct qualification | PR462 | [Calcify joined10k checkpoint (2026-10-02)](#calcify-joined10k-checkpoint-2026-10-02) |
| Sep 30 | Phase 2 implementation and experiment checkpoints (resolver) | — | [Calcify Phase 2 implementation (2026-09-30 branch)](#calcify-phase-2-implementation-2026-09-30-branch) |
| Sep 29 | Phase 1 checkpoint (match commitment) | — | [Calcify Phase 1 checkpoint (2026-09-29 branch)](#calcify-phase-1-checkpoint-2026-09-29-branch) |

## Calcify eight-hour P3 session — October 9–10, 2026

Started October9 at22:55:41 America/Toronto; deadline October10 at06:55:41.
Baseline `51cd90eee` (merged PR479), branch
`codex/calcify-p3-overnight-2026-10-09`. User authorized manager/developer agent
orchestration, testing, fresh independent reviews and failure-triggered research
spikes. [Reviewed session plan](../work/CALCIFY_OVERNIGHT_SESSION_PLAN.md) owns
task queue and acceptance gates; local manager ledger
`.planning/calcify-p3-overnight/session.json` owns dispatch/review/resource leases.

O0 source/budget contract accepted after review2of3, committed `45211653`;
O0D documentation/history corrections committed `7aba4d0a`. Eight-hour window
expired with O1 unaccepted; full P3 target not achieved. User resumed O1 delivery
and OCR-pilot only. Combined review1of3 restore-semantic/trailing-JSON findings
fixed after research; resumed review2of3 returned Not ready: certified replay
accepted invalid or duplicate physical member IDs. Finding accepted, bounded
research followed, deterministic replay correction completed. Final combined
review3of3 returned Ready with no findings; both developers accepted verdict.
Exact56-path O1 model committed `0a7df6f85c1f8636fdb74177afc49fa3eb14d8bd`.
Final author36 finite/70 surrounding tests pass; independent36 finite, focused
Go and exact replay/semantic/JSON probes pass. Earlier213-test receipt remains
earlier source. Combined O1 review cap exhausted at3of3; no counter reset.

[Focused O1 checkpoint](../evidence/calcify-finite-p3/README.md) owns model proof scope
and current delivery fields; [handoff](../work/handoffs/2026-10-10-calcify-finite-p3-overnight.md)
preserves deadline snapshot and resumed checkpoint. O1 accepted/committed;
complete230-file/7002941-byte proof published and remotely verified at Records
`cbf2413db33dc7cb65c339cbc51e0eb98c2633f3`, [PR11](https://github.com/dills122/reef-records/pull/11).
Focused summary and [retention receipt](../records/2026-10-10-calcify-finite-p3-retention.json)
retain exact scopes; local-only proof checkpoint not pushed/merged into Reef,
no tracked source removal. Separate final integration review1of3 Ready/manager
Accept for exact70-path delivery; O1 source unchanged. Initial owner docs committed `1dee92a64cc86e58ae5fcff98f9f6118533e2bfd`;
ready [Reef PR485](https://github.com/dills122/reef/pull/485) remains unmerged.
Reviewed/CI-gated trusted config [PR486](https://github.com/dills122/reef/pull/486)
and [PR487](https://github.com/dills122/reef/pull/487) merged; generated-Java
exclusion,1500000-token budget and pinned-CLI updater opt-out preserve O1 source.
Config review count2of3; internal O1 count remains3of3.

Full pinned OCR run38070877163/job114267851324: CLI1.12.9, exact51cd..dcb range,
17 selected/17 completed/0 reused/0 failed/0 waived,31 generated Java excluded.
Five findings plus older constructor claim reconciled Dispute with canonical/
source/test/probe evidence; manager Accept, six bot threads resolved. Successful
execution and scoped finding reconciliation accepted; no zero-finding claim.
Earlier budget/install failure, version drift and no-op retry retained separately.
Current source head `dcb106c4ddd5ccda60df7c8e6411fca88ef5be14`:31 successful
checks/4 skips, no active/failure. [PR485](https://github.com/dills122/reef/pull/485)
owns later final owner-doc head/check outcomes; those checks remain pending.

[Delivery supplement](https://github.com/dills122/reef-records/tree/dff3a1b59f3b4beb8ea88f84833f71cc5a105a56/records/reef/docs/evidence/calcify-finite-p3/2026-10-10-o1-delivery-supplement), [manifest](https://github.com/dills122/reef-records/blob/dff3a1b59f3b4beb8ea88f84833f71cc5a105a56/manifests/2026-10-10-calcify-finite-p3-o1-delivery-supplement.json) published at
`dff3a1b59f3b4beb8ea88f84833f71cc5a105a56`:190 records/2259259bytes, all193 record/metadata paths and174 unique
remote blobs verified. Exact-head hosted integrity36814/hygiene/4tests and
`--base 1419b7e0` passed. Local-only exact proof-source575c52b0 not pushed;
first normalization failure/correction preserved, original230 cbf proof unchanged.
[Retention receipt](../records/2026-10-10-calcify-finite-p3-retention.json) records
publication checks without recursive archival of future verification. Defaults off;
O2–O5 unimplemented at O1 delivery checkpoint; newer O2 state above.
Live binding/ingress/isolation/process restart, financial
adoption and resource/capacity qualification remain open.

## Calcify finite P0 source gate — October 7, 2026

Reef478 merged at `85e513278`; Records10 merged at `1419b7e0`.
Branch `codex/calcify-p0-source-gate` implements next bounded task from P3 ladder:
opt-in finite matching profile/startup guard pinned to retention0, one or two
explicit runs, one session/instrument, numbered finite order IDs and bounded
quantity/price/text. Existing no-reuse index retained; no snapshot redesign or
second ID store. Optional profile fingerprint extends existing snapshot metadata.
[Source contract](../../contracts/calcify/README.md#finite-p0-matching-source-profile-2026-10-07)
and [focused verification](../../services/matching-engine/README.md#finite-calcify-p0-source-profile)
own configuration, restore boundaries and exact local test scope. Whole matching
module and focused app/stream race checks pass. Canonical command-path tests use
broker doubles; no live broker or production financial authority qualification.
Retention pass no-op: no new bulk evidence or standalone superseded record;
continuously maintained owners updated, prior empirical proof unchanged.

Next bounded task: versioned source lifecycle/coverage membership and finite
command/history/closure budgets, with exact execution and zero-trade dispositions,
future-window burst closure and restored frontier. Then financial admission/runtime
adapter, atomic SQL/read seam and funding/repair/restart acceptance. Profile changes
still require frozen canonical replay configuration; durable broker profile binding
is not established by this matching-only gate. Full P0 RFC/adoption decisions stay
open beyond this finite identity acceptance slice.

## Calcify sprint1 exit continuation — October 6, 2026 UTC

Baseline `863503ec`; branch `codex/calcify-sprint1-exit-2026-10-06`.
Reef PRs #473/#474/#475/#477 and Records PRs #6–#9 merged; earlier
checkpoint draft/pending wording below records pre-merge scope. Landed E4 source
tree equals reviewed source; original proof commits remain reachable in Records.

Four-hour continuation follows [existing first experiment sprint](../work/CALCIFY_SYSTEM_ARCHITECTURE_RFC.md#101-first-experiment-sprint).
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
[focused verification](../evidence/calcify-financial-sprint1/e4-verification-2026-10-06.json).
Old 768 MiB conservative and 1,000/100 bootstrap evidence remain unchanged.
No new managed-restart qualification or fourth prior E4 qualification instance.
Complete [raw proof](https://github.com/dills122/reef-records/tree/2331d97d9f9e6af8638d6ed335adbfac743d2c03/records/reef/docs/evidence/calcify-financial-sprint1/sprint1-exit-2026-10-06-1c143125049d) and [independent delivery review](https://github.com/dills122/reef-records/blob/2331d97d9f9e6af8638d6ed335adbfac743d2c03/records/reef/docs/evidence/calcify-financial-sprint1/sprint1-exit-review-2026-10-06-1c143125049d/reviews/delivery1/report.md)
published/verified; [retention inventory](../records/2026-10-06-calcify-sprint1-exit-retention.json).
Records PR10 was draft at this checkpoint; merged October7 at `1419b7e0`. No local tracked original removed.

October7 PR478 follow-up: shared empirical policy digest preserves v1 bindings;
persisted config evidence and both resource-profile round-trips covered. Focused
Node53/53 and exact hosted Node invocation280/280; retention check passes.
[Verification and retention scope](../evidence/calcify-financial-sprint1/README.md#pr478-policy-review-follow-up--october7-2026).
No new timed load; finite P0 source gate now implemented in October7 section above.

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
first slice. Finite profile/startup guard now implemented in October7 section above; terminal
duplicate and restore acceptance covered locally. Lifecycle/coverage and finite
command/history budgets follow this scoped gate. Live adoption remains owner decision.

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
remain separate. [Focused current verification](../evidence/calcify-financial-sprint1/e4-verification-2026-10-05.json) owns diagnostic status.

## Calcify heap protection component — October 5, 2026

Component verification passes on `codex/calcify-heap-protection`, starting from
E3 delivery checkpoint `3ce2bdf2`. Actual JVM capability binding, checked finite
retained-state admission and sticky sampled protection cover result-only replay
and publication. Financial64/64 and exact NodeCI230/230 pass. Independent review
Attempt1 cycle3 **Ready**; cycle1 P2 peak-scope finding fixed with actual RED/GREEN
control. Emitted peaks explicitly exclude final serialization/staged writes.
[Focused verification](../evidence/calcify-financial-sprint1/heap-verification-2026-10-05.json)
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
build/fixture hashes. [Focused candidate verification](../evidence/calcify-financial-sprint1/e3-verification-2026-10-05.json)
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
primary dirty checkout untouched. [Focused checkpoint](../evidence/calcify-financial-sprint1/README.md),
[active handoff](../work/handoffs/2026-10-04-calcify-financial-sprint1-recovery.md).

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
[Focused checkpoint and next E1 slice](../evidence/calcify-financial-sprint1/README.md).
97-file complete evidence bundle verified and landed via [Records PR #5](https://github.com/dills122/reef-records/pull/5);
[full details](https://github.com/dills122/reef-records/blob/08a4347f7410f94837cdf18136296629e8f551ce/records/reef/docs/evidence/calcify-financial-sprint1/README.md). Reef keeps scripts/tests, fixtures and concise
status; raw proofs/reviews live in Records. No production integration/cutover.

## Calcify joined10k checkpoint (2026-10-02)

**Configuration correction:** campaign tested PostgreSQL-backed smoke ingress, not documented in-memory-intake/publish-pipeline performance shape. Intended fast-ingress+Calcify capacity remains unmeasured. Verify explicit profile/accounting before further tuning;7,708.36/s is scoped SQL-smoke result.

Two-hour frozen-base campaign fails10k accepted-order target. Best300s **7,708.36durable order ACKs/s**,1,158,676 exact resolved contexts and Phase1 counts, bounded drain, zero failures. Final higher-concurrency attempt aborts on Docker guest-disk ENOSPC. Latest-master correctness fixes integrated and separately checked; no capacity transfer. [Campaign evidence/next gates](../research/CALCIFY_10K_TIMEBOX_2026-10-02.md), [handoff](../work/handoffs/2026-10-02-calcify-10k-timebox.md). Restore guest storage headroom and repeated-state controls before further sustained load; durable-intake batching remains measured hypothesis. Older checkpoints below retain dated scope.

## Calcify Phase 2 implementation (2026-09-30 branch)

User approved [six-slice implementation plan](../work/CALCIFY_PHASE2_IMPLEMENTATION.md). Active branch: `codex/calcify-phase2-implementation`, continuing preserved experiment commits. [Draft PR #433](https://github.com/dills122/reef/pull/433) records nightly checkpoint and merge blockers. D-042 run-scoped matching/V3 snapshots, full-fact Protobuf/pure resolver, managed Streams role, bounded lane faults, source identity/retention guards, and operational configuration implemented. Actual HTTP two-order smoke emits one exact context. Corrected RF3/fsync fault matrix passes nine boundaries; million-row cold recovery reaches observed RUNNING in19.97s with exact catch-up. Those broker cohorts precede final throughput changes and require unchanged-candidate repeat. Latest100k hot diagnostic reaches11.12k/s with JFR and exact full-fact parity. Latest sustained hot3.15m cohort reconciles exact at10.16k/s but producer310.73s fails frozen301s maximum; spread/skew/aged unrun. Final local platform regression/coverage passes. Review found open verified-input retention and verified/output UUID binding gaps. Work paused at [nightly handoff](https://github.com/dills122/reef-records/blob/6b838e5287c5428c914f6577ff176c1cd03c44ec/records/reef/docs/work/handoffs/2026-09-30-calcify-phase2.md); correctness fixes, unchanged-candidate fault/recovery, production-role smoke and sustained qualification remain; no full-system20k upstream capacity or production availability claim. [Implementation evidence](../evidence/calcify-phase2-implementation/README.md) preserves failed attempts, durability correction and exact measurement scope.

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
