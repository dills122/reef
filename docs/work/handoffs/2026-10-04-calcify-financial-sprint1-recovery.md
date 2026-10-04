# Handoff: Calcify sprint1 review and proof

October4,2026. Source recovered/pushed; bulk proof preserved in Records.
Review6of6 Not ready for bounded broker rerun; author-fixed guard needs fresh sign-off.

## Objective And Boundary

User requested independent review of all recovered code, then retest/proof/stats,
PR once good. Human authorized additional rounds after original3; parent cap6.
Review4/5 accepted source with follow-ups; review6 found operational guard P1,
no new product-source defect. Material guard fix needs new pass; cap exhausted.
No seventh reviewer, bounded rerun, E4 load or product PR pending gate closure.

Canonical: AGENTS.md, AI_CONTEXT.md, WORK_PLAN.md, RFC §10.1, RECORDS_RETENTION.md;
[focused verification](../../evidence/calcify-financial-sprint1/verification.json).

## Durable Repository State

Persistent `/Users/dsteele/repos/reef/.worktrees/calcify-sprint1-experiments`;
branch `codex/calcify-sprint1-experiments`, base97924e15642826a687db935a219d7927ae649ac8.
Source/configf56b30b19d00a1033abae44a75435a4e771990e9 pushed; focused docs checkpoint
advances HEAD.18 software hashes match testedf75b5d6187b631d5d608a24b883df6c57478b151.
Primary `/Users/dsteele/repos/reef` dirty on codex/calcify-phase2-planning: preserve
AGENTS.md/CLAUDE.md, .mcp.json and existing planning/research files. Never reset primary.

Full new [Records bundle](https://github.com/dills122/reef-records/tree/ad7b60e9512907865785b3ab3723642c9d56dd86/records/reef/docs/evidence/calcify-financial-sprint1/verification-2026-10-04-f56b30b1).
Local-only source provenance commit used for import; bulk never pushed to Reef.
[Records PR6](https://github.com/dills122/reef-records/pull/6) open.
[Original rounds1–3/failed908c startup](https://github.com/dills122/reef-records/tree/cfa4217708ff0694a966e3d87acce9585010a65a/records/reef/docs/evidence/calcify-financial-sprint1/verification-2026-10-04-908c3e54)
immutable. Persistent .planning retained; no source deletion. Private recovery logs,
caches/binaries excluded. October3 raw proof lost; October4 reruns are fresh.

## Completed Source And Proof

18 software/config files: original17 plus actual StreamsConfig regression.
Fix3435d7b0: producer timeout120s supports60scommit grouping; E4 deadline cumulative
counts bounded by final counts/expected timed offers. Fixf75b5d61: physical-byte
overrides positive safe integers at/above conservative measured floor.
Earlier normalization/dedup/bounds/typed identity/staging/locator fixes retained.

Node154/154; runtime795 reported tests,775 reported passes,20 skips,0failures/errors,
104suites,208.945s. Offline DB guards may return without assertions; no DB integration
claim. Financial27/27 (14kernel,12oracle,1config). Frozen20cases/52inputs;300seeded
traces/19,200business prefixes. E2finite156+seeded128 pass; symbolic bounds only.
Selfcheck40prefixes+one-leg mutant rejection; failed missing-argument attempt retained.
Reviewer6 independently48model tests/all18source hashes.
Fixture SHA256`fee7eead368d2ef2927aad1e53877907a4d74f9762b696d20f7e5575b1361e6d`.

RF3 happy/restart PASS26.417s:4→8covered inputs/decisions,2→4settlements,two domains.
Separate result-history reconstruction controls, no full managed authority activation.
Core10of24/golden9of20 passed then budget-aborted, wrappers exit-15. Last active
sample9,114,636,288bytes; peak unmeasured. Stopped footprint13,537,280bytes=12.91MiB
after preallocation reclaimed; historical13.2MiB/16MiB notes corrected append-only.

## Review6 P1 And Author Fix

Reviewed watchdog had unbounded commands, escaping sampling/parse errors and
compose-stop failure before probe signals/abort record. Original helper retained as
`.planning/sprint1-proof-bounded/resource_watchdog.review6.py`; report/counterexamples
unchanged. No bounded probe executed under faulty guard.

Corrected resource_watchdog.py:5s sample/ps timeout,15s compose stop, abort flushed
before cleanup, exact owned Node/Java signals independent of Docker stop, errors retained.
New supervise_checks.py owns created process groups, requires initial heartbeat,
detects watcher death/stale heartbeat, terminates own groups then attempts project stop.
11 mock-only controls pass; no actual Docker/ps/signals in controls. Material behavior
change needs independent sign-off. remediation-manifest.json freezes helper hashes.

## Runtime And Remaining Gates

Owned97924e15/908c3e54/3435d7b0 projects stopped, volumes/topics retained.
New `reef-calcify-financial-s1-bounded` preflight up succeeded then stopped;
ports39192/39292/39392.8MiB profile NOT applied; no bounded core/golden probes.
Unrelated projects untouched; no prune/reset/volume deletion.

[Broker profile](../../evidence/calcify-financial-sprint1/broker-profile.json):
log_segment_size,compacted_log_segment_size,transaction_coordinator_log_segment_size
8388608 on fresh project before any init/seed. RF3/minISR2/write caching false,
60scommit/120stimeout/retention unchanged. Verify actual readback/image/hardware/
disk/topic settings. Watcher9GiBabort,10GiB configured budget,20GiBfree,256MiBraw;
5s samples cannot guarantee continuous peak/hard10GiB ceiling.16MiB never applied.
Synthetic correctness only; no throughput transfer.

FullE3 broker-majority/stale-owner/producer-failure/committed-before-ACK/A10B27
activation matrix incomplete. E4 heap/ACK membership/physical-byte/calibration gaps.
Reservation policy/matcher identity integration open.
[Unapplied heap draft](calcify-sprint1-unfinished-heap-guard.patch) active.
No production API/events/storage/authority or acceptedADR change.

## Next Actions And Delivery

1. Obtain human authorization for additional independent round7; retain6used.
   Neutral bootstrap includes corrected watcher/supervisor/mockcontrols and full
   unchanged source/config/plan scope; preliminary before author packet.
2. After sign-off restart only bounded project, apply/readback8MiB properties,
   freeze preflight/command manifest. Supervisor CLI:
   `python3 .planning/sprint1-proof-bounded/supervise_checks.py <commands.json>`.
   Two registered wrappers, absolute Python/run_checks paths, exact proof-dir/run-ID
   markers; command validation precedes launch.
3. Run24core+20golden correctness arms with distinct bounded-f56b30b1 namespaces,
   supervised guard. Preserve all attempts; no capacity loads.
4. Archive append-only proof, verify remote bytes, refresh focused docs, product PR
   once implemented scope passes. FullRFC gaps explicit.

Java21 `/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`.
Exact31-file Node command retained. Full module already rerun on same18 software hashes;
docs-only edits need retention/link checks, no repeated software run.

```sh
node --test scripts/dev/calcify-financial/reservation-model.test.mjs scripts/dev/calcify-financial/gate-model.test.mjs scripts/dev/calcify-financial/rate-proof.test.mjs
python3 -B .planning/sprint1-proof-bounded/watchdog_controls.py
node scripts/ci/check-records-retention.mjs
```

Use github-keychain-auth outside sandbox; unset GH_TOKEN/GITHUB_TOKEN per gh command,
never extract credentials. Records PR6 archives evidence; no sprint/capacity sign-off.
