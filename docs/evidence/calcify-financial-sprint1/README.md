# Calcify financial sprint1 — E0 checkpoint

October 3, 2026: preparation checkpoint. Full E0 readiness blocked; E1-E4 unrun.
Execution source `85f0ce8c84cd7de6bc22322bac0b32da109073ab`; planning source
`cda4185b8065bd38126c7547b30fef79a813f68a` (PR #466 subsequently merged, docs only).
Production post-match behavior and local volumes unchanged.

## Focused inputs and checks

- [Frozen inputs](fixtures.json):20 cases/52 inputs; SHA256
  `fee7eead368d2ef2927aad1e53877907a4d74f9762b696d20f7e5575b1361e6d`.
  Normalization/framing/golden stability only; financial kernel/oracle unimplemented.
- [RF3 config](broker.compose.yml): isolated project `reef-calcify-financial-s1-85f0ce8c`,
  ports39192/39292/39392; offline configuration validated, no resources created.
- Java21 clean compile and70 Calcify tests/16 suites pass; matcher app/streamdirect
  suites and focused identity/IOC/replay/restore/reuse checks pass.
- Recorder byte-capture regression2/2, retention9/9, fixture and script-surface
  checks pass. [Independent review2](https://github.com/dills122/reef-records/blob/08a4347f7410f94837cdf18136296629e8f551ce/records/reef/docs/evidence/calcify-financial-sprint1/reviews/instance-2.md): Ready
  with non-blocking follow-ups for checkpoint publication; prior P2 fixed.

## Current synthetic input boundary

Recovered test-only gross-DvP kernel accepts positive quantities and nonnegative
prices, including zero-price transfers; negative prices are invalid. Both funding
legs must fit signed64 storage, including opening-resource debit. Identical retries
of rejected inputs retain original decision and policy context without new effects.
Consumed attempt/time/funding values checked before economic effects, with explicit
sign/range error dispositions.
Namespace/domain/action IDs require nonempty text; typed invalid identities cannot
alias valid text. Malformed bounded requests stage durably during active phase,
restore pending membership, then reject after phase drains.
These experiment rules do not establish live venue policy. E0 checks below remain
dated evidence; current execution status lives in [work plan](../../WORK_PLAN.md).

## Open gates and next slice

All six actual language-server queries fail shared manager initialization. Docker
socket/application absent; guest disk, image digests and live topic durability
unverified. Matcher same-run order reuse conflicts with resolver immutable
acceptance; reservation-source lifecycle incomplete. No capacity or cutover claim.

E1a: test-only `FinancialKernel`, independent BigInteger `FinancialOracle` and
`FinancialKernelTest` under platform-runtime Calcify financial tests. Start balanced
journaled genesis and two-asset capture/settle; implement frozen20 cases and
`decide→validate→encodeBounded→evolve`. Compare every business prefix, complete
owner state/history and checkpoint suffix; fill abbreviated dedup/history schema,
then seeded schedules/crash cuts/mutants. Reservation E1b remains separate proposal.
Before E3: successful tool/runtime gates, explicit broker project/evidence paths,
20GiB host/guest headroom,10GiB experiment/raw256MiB caps, pinned digests/settings.

## Complete evidence in Reef Records

[Full overview and reasoning](https://github.com/dills122/reef-records/blob/08a4347f7410f94837cdf18136296629e8f551ce/records/reef/docs/evidence/calcify-financial-sprint1/README.md),
[runtime/source manifest](https://github.com/dills122/reef-records/blob/08a4347f7410f94837cdf18136296629e8f551ce/records/reef/docs/evidence/calcify-financial-sprint1/environment.json),
[all attempts, successes and failures](https://github.com/dills122/reef-records/blob/08a4347f7410f94837cdf18136296629e8f551ce/records/reef/docs/evidence/calcify-financial-sprint1/raw/attempts.jsonl),
[original checksums](https://github.com/dills122/reef-records/blob/08a4347f7410f94837cdf18136296629e8f551ce/records/reef/docs/evidence/calcify-financial-sprint1/SHA256SUMS),
[archive index](https://github.com/dills122/reef-records/blob/08a4347f7410f94837cdf18136296629e8f551ce/INDEX.md), [import manifest](https://github.com/dills122/reef-records/blob/08a4347f7410f94837cdf18136296629e8f551ce/manifests/2026-10-03-calcify-e0.json).
97 originals copied byte-for-byte from Reef `41bb17dcb5e93b7bce9444dd7f597c90ea01a3e1`,
published via [Records PR #5](https://github.com/dills122/reef-records/pull/5), verified
at pinned archive commit before Reef removal. Original early relocation output
incomplete; historical recorder byte limitation disclosed. Failures/corrections
remain intact. [Local relocation inventory](../../records/2026-10-03-calcify-e0-migration.json)
resolves original paths/hashes. Local raw captures stay ignored; publish complete
attempt bundles to Records before replacing this checkpoint.

```sh
bun scripts/dev/calcify-financial/freeze-fixtures.mjs --check
node --test scripts/dev/calcify-financial/record-attempt.test.mjs
bun run repo:check:records
```
