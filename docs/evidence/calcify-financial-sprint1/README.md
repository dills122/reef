# Calcify financial sprint1 — October4 verification checkpoint

Source `f56b30b19d00a1033abae44a75435a4e771990e9`, base97924e15;
branch `codex/calcify-sprint1-experiments`.18 software files match testedf75b5d61;
new [8MiB broker profile](broker-profile.json) unrun. Test-only experiments.
[Focused results](verification.json), [current work plan](../../WORK_PLAN.md).

## Review and retest

Review6of6: **Not ready** for bounded broker rerun. No new actionable product-source
defect; P1 operational watchdog fails open on sample/cleanup errors. Author fixed
bounded deadlines, abort recording and cleanup, added supervising controller;
13 mock-only controls pass. Material execution change needs fresh independent
sign-off; human review-limit extension required. No seventh reviewer started.
Follow-up self-check found/fixed completion race with explicit wrapper-exit handshake.
Real disposable-process control confirms observer failure terminates two owned process
groups with zero cleanup errors after reap-before-escalation fix. Earlier EPERM
attempts retained; resource sampling simulated, no Docker/ps/broker calls. [Guard revision](https://github.com/dills122/reef-records/tree/0fd0e45c1efb5464e5308021d4b6834e68b54f76/records/reef/docs/evidence/calcify-financial-sprint1/guard-revision-2026-10-05-f56b30b1).

Prior review findings fixed: timeout120s supports60s commit; E4 deadline counts
cannot exceed final counts; physical-byte overrides cannot undercut conservative
measured floor. Earlier normalization/identity/bounds/staging/locator fixes retained.

- Node CI154/154; reviewer6 independently48/48 model tests.
- Platform795 reported tests,775 reported passes,20 skips,0 failures/errors,
  104suites;208.945s. Offline profile; guarded DB tests may return without assertions.
  No DB integration claim. Financial27/27:14kernel+12oracle+1broker-config test.
- Frozen20cases/52inputs;300 seeded traces/19,200 business prefixes.
  [Fixtures](fixtures.json) SHA256`fee7eead368d2ef2927aad1e53877907a4d74f9762b696d20f7e5575b1361e6d` unchanged.
- E2 finite156+seeded128 pass; symbolic bounds only. Selfcheck40prefixes and
  one-leg mutant rejection. E4 count/byte counterexamples rejected; no benchmark.
- RF3 happy/restart passes26.417s:4→8covered inputs/decisions,2→4settlements,
  two domains. Separate history reconstruction controls; no full authority activation.

## Failed and open gates

**Overall E3/E4 blocked.** Default-segment core/golden run budget-aborted after
10of24core and9of20golden arms passed. Last active allocation sample9,114,636,288bytes
(8.49GiB); peak unmeasured. Shutdown reclaimed preallocation to13,537,280bytes
(12.91MiB); stopped footprint cannot replace active peak. Original16MiB proposal
never applied; final8MiB profile awaits apply/readback and review of corrected guard.
Bounded project started for preflight then stopped; no bounded probe launched.

Full E3 fault/activation matrix, E4 heap/calibration/ACK membership/physical-byte
proof, live reservation policy acceptance and matcher identity integration remain.
No full sprint, capacity or cutover PASS. Reservation model stays proposal.
Only registered broker projects stopped; volumes/topics and unrelated projects preserved.

## Durable evidence and continuation

[Complete extended reviews, failures, raw attempts, JUnit XML and guard controls](https://github.com/dills122/reef-records/tree/ad7b60e9512907865785b3ab3723642c9d56dd86/records/reef/docs/evidence/calcify-financial-sprint1/verification-2026-10-04-f56b30b1),
[Records PR6](https://github.com/dills122/reef-records/pull/6).
Local-only provenance commit serves import; bulk published only to Records.
[Recovery hashes](recovery.json), [active handoff](../../work/handoffs/2026-10-04-calcify-financial-sprint1-recovery.md).
October3 original raw proof lost; October4 reruns are fresh evidence.

[Earlier908c3e54 checkpoint and failed startup](https://github.com/dills122/reef-records/tree/cfa4217708ff0694a966e3d87acce9585010a65a/records/reef/docs/evidence/calcify-financial-sprint1/verification-2026-10-04-908c3e54)
and [E0 historical overview](https://github.com/dills122/reef-records/blob/08a4347f7410f94837cdf18136296629e8f551ce/records/reef/docs/evidence/calcify-financial-sprint1/README.md)
remain immutable. Import open; no local source removal.
