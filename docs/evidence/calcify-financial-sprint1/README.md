# Calcify financial sprint1 — bounded correctness pass

October5 UTC / October4 local. Branch `codex/calcify-sprint1-experiments`;
Broker/Kotlin/profile proof remains from `f75b5d61` / `f56b30b1`; JavaScript
checkpoint and metric fixes postdate round7.
Test-only experiments. [Focused results](verification.json),
[current work plan](../../WORK_PLAN.md), [broker profile](broker-profile.json).

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

Full E3 majority/stale-owner/producer-failure/committed-before-controller-ACK and
A10B27 certified activation matrix remains open. E4 heap/ACK membership/physical-byte/
calibration gates, reservation acceptance and matcher identity integration remain.
No full sprint, capacity, production or cutover sign-off.

## Records and continuation

[Round7 review, pilot, full matrix and raw proof](https://github.com/dills122/reef-records/tree/b5d868b69132e85cdf74e4fc9c948f01eafc74c9/records/reef/docs/evidence/calcify-financial-sprint1/verification-2026-10-05-f22b2f85),
[Records PR6](https://github.com/dills122/reef-records/pull/6).
Bulk only in Records, local-only Reef provenance; no local source removal.
[Earlier rounds4–6 and failed default-segment run](https://github.com/dills122/reef-records/tree/ad7b60e9512907865785b3ab3723642c9d56dd86/records/reef/docs/evidence/calcify-financial-sprint1/verification-2026-10-04-f56b30b1),
[original recovery/startup proof](https://github.com/dills122/reef-records/tree/cfa4217708ff0694a966e3d87acce9585010a65a/records/reef/docs/evidence/calcify-financial-sprint1/verification-2026-10-04-908c3e54).
[Recovery hashes](recovery.json), [active handoff](../../work/handoffs/2026-10-04-calcify-financial-sprint1-recovery.md).
October3 original raw proof lost; subsequent reruns are fresh evidence.
