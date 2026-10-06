# Calcify financial sprint1 — bounded correctness pass

October5 UTC / October4 local. Branch `codex/calcify-sprint1-experiments`;
Broker/Kotlin/profile proof remains from `f75b5d61` / `f56b30b1`; JavaScript
checkpoint and metric fixes postdate round7.
Test-only experiments. [Focused results](verification.json),
[current work plan](../../WORK_PLAN.md), [broker profile](broker-profile.json).

## Latest empirical checkpoint — October6 UTC

[Current work](../../WORK_PLAN.md#calcify-sprint1-exit-continuation--october-6-2026-utc)
owns sprint status; [focused verification](e4-verification-2026-10-06.json) separates
source-count upper bounds from unmeasured deadline settlement rates. Two fresh
4GiB attempts aborted at disk then heap limits; original failure journals retained.
Explicit 8GiB profile passed review4of4 after user extension. Full 150000 settlements,
300001 histories and result-only replay passed; deadline 63104/60s=1051.73/s missed
2500/s target. Producer132.040s; post-producer drain8.139s. Sampled JVM heap
3962293592B; live allocated project sample peak11773079552B. These are different
measurement scopes, not conservative bounds. Closed input journal independently
verified300000 complete actions/150000pairs, full hashes/payloads and zero tails.
Kernel/managed adapter unchanged; prior49-arm proof separate. No new managed
restart, aged state, SQL/API or production capacity qualification.
Historical draft/pending wording below
predates merged Reef473/474/475/477 and Records6–9. Complete [closed session proof](https://github.com/dills122/reef-records/tree/2331d97d9f9e6af8638d6ed335adbfac743d2c03/records/reef/docs/evidence/calcify-financial-sprint1/sprint1-exit-2026-10-06-1c143125049d) and [delivery review](https://github.com/dills122/reef-records/blob/2331d97d9f9e6af8638d6ed335adbfac743d2c03/records/reef/docs/evidence/calcify-financial-sprint1/sprint1-exit-review-2026-10-06-1c143125049d/reviews/delivery1/report.md)
published at Records `2331d97d9f9e6af8638d6ed335adbfac743d2c03`:7628files/1144093245B verified; all three
journal chunk reassemblies pass. [Records PR10](https://github.com/dills122/reef-records/pull/10)
draft, not merged. Broker topic volumes retained locally, not exported; archive
contains input journals and executed probe receipts, not independently replayable
raw output topics. No tracked Reef originals removed.

## Current E4 continuation

October5 branch `codex/calcify-e4-readiness`, base `29a8926d`.
[Focused verification](e4-verification-2026-10-05.json) and
[current work](../../WORK_PLAN.md#calcify-e4-continuation--october-5-2026)
own bounded diagnostic status. Financial97/97 and NodeCI274/274 passed on
Attempt1 frozen source; independent whole-code cycle3 Ready. Same-candidate49 E3 arms
pass with complete oracle/history checks,1073.813s correctness timing. Original
raw proof preserved; bounded lossless export independently verified, Attempt1
cycle2 Ready. First1000-settled/100-pending bootstrap refused before payload
on JSON integer node-type mismatch. Focused8-test fix passes; new Attempt2 whole
Attempt2 cycle1 review Ready and financial98/98 pass; fresh current49 passed.
Second bootstrap completed2100 actions/2101 histories/five physical checkpoints,
then managed child failed count parity: LongNode vs parsed IntNode2101. Strict
integral correction passed99 financial tests and whole-code Attempt2 cycle2 Ready.
Fresh49 then passed and actual1000-settled/100-pending diagnostic qualified all
seven physical checkpoints, separate-JVM recovery and complete result-only replay;
independent actual qualification instance2 Ready. Owner/history parity verified
from complete raw facts. Failed runs and actual stopped-broker readbacks retained.

Integrated compact reference preserves complete facts using canonical leaf strings.
Whole-code Attempt3 cycle1 Ready, financial109/109, independent Node125/125;
unchanged Node source retains exact CI274/274 scope. Current compact snapshot binds
36 source files/97 classes; fresh compact49 and1000/100 diagnostic passed.
Independent actual instance3of3 Ready: every source/history/genesis/policy/owner
equal baseline; separate child/replay/seven physical checkpoints/cleanup verified.
Compact sampled peaks higher by16,011,784 parent and40,031,248 child bytes; no
causal or capacity inference. Prospective retained floor664,800,000 bytes exceeds
644,245,094 gate; complete finite upper absent. Ordinary/aged ladder remains blocked.
Reviewed code commit `17409ac2`; [Reef PR477](https://github.com/dills122/reef/pull/477)
draft above PR475. Proof published at immutable [Records `87242fe0`](https://github.com/dills122/reef-records/tree/87242fe0415a80a9934846686cd41fc8c08cad6c/records/reef/docs/evidence/calcify-financial-sprint1/e4-session-2026-10-05-29a8926d33f9); [Records PR9](https://github.com/dills122/reef-records/pull/9) draft above PR8. Fresh remote clone verified all24,719 blobs/843,777,692 bytes, including failed runs and reviews. No tracked bulk removed. Final documentation-head hosted CI tracked on PR477.
[Pure allocation profile and review](https://github.com/dills122/reef-records/tree/b9b0e17cbec9cb18e7d6ade3ea05eaff26129702/records/reef/docs/evidence/calcify-financial-sprint1/e4-reference-allocation-2026-10-05-bf04ead83d8b) independently Ready;
accept allocation +5.59%, final digest -88.91%, combined +2.85% for identical2101
pre-parsed histories. Fixed arm order/mapper lifecycle limits; no driver-peak cause
or heap-upper claim. All31 supplemental blobs/5,956,080 bytes remotely verified.
Code readiness grants no ordinary rate qualification.

No ordinary rate or conservative upper cost qualified. Earlier ordinary-shape
model and isolated prototype retain original scope; integrated compact actual
proof above supersedes readiness status, with retained floor still over gate.
Earlier scopes below unchanged.

## Current heap protection component

October5, 2026 UTC; branch `codex/calcify-heap-protection`, base `3ce2bdf2`.
[Focused verification](heap-verification-2026-10-05.json) and
[current work plan](../../WORK_PLAN.md#calcify-heap-protection-component--october-5-2026)
own new test-harness component scope. Financial64/64, exact NodeCI230/230;
independent Attempt1 cycle3 Ready. Actual Java21 capability and final-build child
refusal controls pass without broker/client setup. Sticky sampled protection
continues through replay/publication; emitted peak scope ends at result assembly.
Real conservative cost evidence absent: existing real policies stay BLOCKED.
Full E4/ACK/physical/calibration/supervision and capacity remain open.
Proof published at Records `132dae9d`; [Records PR8](https://github.com/dills122/reef-records/pull/8) draft, landing pending. All216 remote blobs/3,632,431 bytes verified. [Earlier component review](https://github.com/dills122/reef-records/blob/132dae9d9a02b1c6bd11946f9dbd12670a65d70b/records/reef/docs/evidence/calcify-financial-sprint1/heap-protection-2026-10-05-3ce2bdf2729c/review/cycle2/report.md). Original E3 proof below retains separate hashes.

Hosted first Node227/229 failed missing macOS-only lock parent on Linux. Shared fixed platform lock preserves Darwin path and exclusivity; final local230/230, independent Attempt1 cycle3 Ready. Kotlin source/build unchanged; original64-test receipt retained. Supplemental correction proof published at Records `2a2abf9e`:63 remote blobs/700,463 bytes verified; combined279/4,332,894. [Final independent review](https://github.com/dills122/reef-records/blob/2a2abf9ea130636daecc2d35dc6c961b08c182f9/records/reef/docs/evidence/calcify-financial-sprint1/heap-ci-correction-2026-10-05-3bf45160ebfa/review/cycle3/report.md); Records PR8 draft, landing pending; actual hosted status follows [Reef PR475](https://github.com/dills122/reef/pull/475), stacked above E3 PR474.

## Current E3 continuation

October5 session on `codex/calcify-planning-readiness`, base `a6ddafbb`.
[Current work plan](../../WORK_PLAN.md#calcify-e3-continuation--october-5-2026)
owns candidate status. New test-only external faults and partition-cut verifier
have bounded acceptance complete; product merge pending. Kotlin45/45 and exact Node CI216/216 pass.
Reviewed recovery guard passes real parent-observed `starting`/measured-disk/
healthy recovery smoke; no financial workload in that smoke. Candidate external4/4, quartet, core24/24 and golden20/20 pass;
[focused current verification](e3-verification-2026-10-05.json) records same-build
scope, failures, timing and resources. Independent integrated review Ready: M0 Attempt2 cycle3, M1 Attempt1 cycle3,
M2 Attempt1 cycle2; no findings, fresh Node60/60. [Complete proof](https://github.com/dills122/reef-records/tree/ed6faadf261dab78a1c4f49991b8f297aecadea4/records/reef/docs/evidence/calcify-financial-sprint1/verification-session-2026-10-05-a6ddafbbae75) and [review](https://github.com/dills122/reef-records/blob/ed6faadf261dab78a1c4f49991b8f297aecadea4/records/reef/docs/evidence/calcify-financial-sprint1/verification-session-2026-10-05-a6ddafbbae75/integration-review/final2-review/report.md) published;
1358 remote blobs/53,415,268 bytes exact. [Records PR7](https://github.com/dills122/reef-records/pull/7) draft, landing pending.
No tracked source removed. Historical proof below retains
its original hashes. Current explicit review authorization preserves historical
cap7 and adds bounded milestone sessions, without resetting prior counts.

## OCR follow-up

Checkpoint now versioned and validated before state assignment. Invalid resource
metrics and scheduled-duration ratios return null. Four new regressions reproduce
prior gaps; focused47/47 and fresh Node158/158 pass. Initial sandbox loopback EPERM
retained; same command passes outside sandbox. Global lock finding disputed:
exclusive open precedes finally, global serialization and unique output directories
intentional. Fixes await independent sign-off; cap7 exhausted. Historical broker
proof below remains scoped to its original software hashes.

[OCR regression proof and preserved failures](https://github.com/dills122/reef-records/tree/ef6e48ee0469816f83500a46b96f1986ea7b174b/records/reef/docs/evidence/calcify-financial-sprint1/ocr-followup-2026-10-05-0a64c482).

## Review and validation

Review **7/7 Ready with non-blocking follow-ups** for supervised pilot and
implemented matrix after resource gate. Prior watchdog P1 closed. Independent
19 mocked guard controls, 2 real process-tree controls and 48 model tests pass.
Cap 7 exhausted; no automatic reset or further review round.

- Pilot 2/2; full matrix **24/24 core + 20/20 golden**. All 44 read-committed
  oracle and isolated complete-history reconstruction checks pass. Core covers
  happy path, 19 mutation boundaries, forwarding, serialization and two local-loss cases.
- All 132 experiment topics verified: one partition, RF3, write caching disabled,
  8 MiB segments. Source requests Kafka minimum ISR 2; Redpanda does not expose
  that property in readback. Documented Raft majority semantics remain distinct
  from empirical majority-outage proof. Raw inventory also includes internal offsets topic.
- Historical Node CI 154/154; current OCR-fix rerun158/158. Retained identical-code module: 795 reported tests,
  775 reported passes, 20 skips, zero failures/errors; financial 27/27.
  Offline DB guards can return without assertions; no DB integration claim.
- Frozen 20 cases/52 inputs; 300 seeded traces/19,200 business prefixes.
  [Fixture](fixtures.json) SHA256 `fee7eead368d2ef2927aad1e53877907a4d74f9762b696d20f7e5575b1361e6d`.
  E2 finite 156 + seeded 128 pass; symbolic bounds only. Rate selfcheck 40 prefixes
  and one-leg mutant rejection; E4 count/byte counterexamples rejected.

## Resource scope and open gates

Full matrix elapsed 474.917 seconds; correctness timing, no throughput claim.
Conservative pilot forecast 8,280,440,832 bytes (7.71 GiB); sampled active maximum
4,778,024,960 bytes (4.45 GiB), final live allocation 4,752,396,288 bytes.
92 samples at 5-second intervals; minimum guest free 92,796,874,752 bytes.
9 GiB abort / 10 GiB configured budget; samples do not prove continuous peak.
Both owned clusters stopped, six volumes retained, no owned probe processes remain.

Earlier default-segment run budget-aborted after 10/24 core and 9/20 golden.
Last active sample 9,114,636,288 bytes; stopped allocation 13,537,280 bytes after
preallocation reclamation. Historical failure remains immutable. Original 16 MiB
proposal never applied. Initial metadata and assessment errors preserved with corrections.

At original checkpoint, full E3 majority/stale-owner/producer-failure/committed-before-controller-ACK and
A10B27 certified activation remained open; current continuation above closes bounded E3.
Applied heap component passes separately; E4 reviewed costs/ACK membership/physical-byte/
calibration/supervision gates, reservation acceptance and matcher identity integration remain.
No full sprint, capacity, production or cutover sign-off.

## Records and continuation

[Round7 review, pilot, full matrix and raw proof](https://github.com/dills122/reef-records/tree/b5d868b69132e85cdf74e4fc9c948f01eafc74c9/records/reef/docs/evidence/calcify-financial-sprint1/verification-2026-10-05-f22b2f85),
[Records PR6](https://github.com/dills122/reef-records/pull/6).
Bulk only in Records, local-only Reef provenance; no local source removal.
[Earlier rounds4–6 and failed default-segment run](https://github.com/dills122/reef-records/tree/ad7b60e9512907865785b3ab3723642c9d56dd86/records/reef/docs/evidence/calcify-financial-sprint1/verification-2026-10-04-f56b30b1),
[original recovery/startup proof](https://github.com/dills122/reef-records/tree/cfa4217708ff0694a966e3d87acce9585010a65a/records/reef/docs/evidence/calcify-financial-sprint1/verification-2026-10-04-908c3e54).
[Recovery hashes](recovery.json), [active handoff](../../work/handoffs/2026-10-04-calcify-financial-sprint1-recovery.md).
October3 original raw proof lost; subsequent reruns are fresh evidence.
