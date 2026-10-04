# Calcify financial sprint1 — October4 verification checkpoint

Reviewed source `908c3e54583cb812b074fe66160c42f0bcbd4921`, base97924e15;
branch `codex/calcify-sprint1-experiments`. Test-only experiments; production
post-match behavior unchanged. [Focused results](verification.json).

## Review and retest

Independent review3of3: **Ready with non-blocking follow-ups** for E1/E2 and
bounded E3 proof regeneration. First two reviews found seven accepted P2 defects;
input normalization, dedup, signed64 bounds, typed identities, malformed staging
and locator parity corrected. Final source unchanged during retest.

- Node CI:144/144 pass. Sandbox listener failure retained; outside-sandbox rerun passes.
- Platform runtime:794 reported tests,774 reported passes,20 skips,0 failures/errors,
  103 suites;191.762s. Offline profile; guarded DB tests can return without assertions.
  No DB integration claim. Financial subset26/26,14 kernel+12 independent oracle.
- Frozen20cases/52inputs;300 seeded traces/19,200 business prefixes, replay cuts,
  staging and omission/mutation controls. [Frozen inputs](fixtures.json) unchanged:
  SHA256`fee7eead368d2ef2927aad1e53877907a4d74f9762b696d20f7e5575b1361e6d`.
- E2:156 finite cases+128 seeded cases,0 failures; symbolic bounds only.
- Rate selfcheck40 prefixes+one-leg mutant rejection; fixture/script-surface checks pass.

## Failed and open gates

**Overall E3/E4 blocked.** RF3 happy/restart arm fails before worker readiness:
transaction timeout10000ms below commit interval60000ms. Zero completed decision
results; golden/recovery/fault arms unrun. E4 assessor accepts impossible deadline
counts greater than final counts; fabricated control reproduces defect, no rate result.
Review maximum3 reached; human review-limit decision required before new instance.

Full E3 fault/activation matrix, E4 heap guard/calibration/ACK membership/physical-byte
proof, live reservation policy acceptance and matcher identity integration remain.
No full sprint, capacity or cutover PASS. Reservation model stays proposal.

Docker29.7.2 accessible outside sandbox; initial socket denial did not prove daemon
stopped. Redpanda26.2.3 RF3 project `reef-calcify-financial-s1-908c3e54` started healthy,
then only this project stopped; topics/volumes preserved. Image pinned to
`sha256:9e83cfa99278f30d0133271c26bf670cd69c94ffa6ba0b42830dd0c3bd9dcfd9`.
No volume prune. [Broker config](broker.compose.yml).

## Durable evidence and continuation

[Complete reviews, raw attempts, failed controls and JUnit XML](https://github.com/dills122/reef-records/tree/cfa4217708ff0694a966e3d87acce9585010a65a/records/reef/docs/evidence/calcify-financial-sprint1/verification-2026-10-04-908c3e54),
[retest closure](https://github.com/dills122/reef-records/blob/cfa4217708ff0694a966e3d87acce9585010a65a/records/reef/docs/evidence/calcify-financial-sprint1/verification-2026-10-04-908c3e54/proof/closure.json).
Local-only provenance commit serves archive import; bulk published only to Records.
[Records PR6](https://github.com/dills122/reef-records/pull/6):256files/1,342,028bytes,
remote hashes verified; archive integrity and4unit tests pass. Import remains open;
no local source removal.
[Recovery hashes](recovery.json), [current work plan](../../WORK_PLAN.md),
[active handoff](../../work/handoffs/2026-10-04-calcify-financial-sprint1-recovery.md).
October3 original raw proof lost; October4 reruns are fresh evidence.

[E0 historical overview](https://github.com/dills122/reef-records/blob/08a4347f7410f94837cdf18136296629e8f551ce/records/reef/docs/evidence/calcify-financial-sprint1/README.md)
retains dated preparation scope and failures; never rewritten as current status.
