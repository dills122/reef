# Handoff: Calcify sprint1 review and retest

October4,2026. Source recovered, reviewed, retested; E3/E4 remain blocked.

## Objective And Boundary

User requested independent review of all recovered source, then fresh proof/stats.
Review3of3 signed off with follow-ups for E1/E2 and bounded E3 verification only.
Actual RF3 retest found startup blocker. No overall sprint, load or cutover PASS.
Stop review loop at configured maximum; no replacement reviewer4 without human
review-limit decision. Do not quietly change source and retain old sign-off.

## Canonical Sources

`AGENTS.md`, `docs/AI_CONTEXT.md`, `docs/WORK_PLAN.md`,
`docs/work/CALCIFY_SYSTEM_ARCHITECTURE_RFC.md` §10.1,
`docs/RECORDS_RETENTION.md`; [focused verification](../../evidence/calcify-financial-sprint1/verification.json).
Frozen fixtures SHA256`fee7eead368d2ef2927aad1e53877907a4d74f9762b696d20f7e5575b1361e6d`.

## Current Repository State

Persistent `/Users/dsteele/repos/reef/.worktrees/calcify-sprint1-experiments`;
branch `codex/calcify-sprint1-experiments`, base97924e15642826a687db935a219d7927ae649ac8.
Reviewed code908c3e54583cb812b074fe66160c42f0bcbd4921 pushed; latest docs checkpoint
may advance HEAD. Verify latest log/status and remote before resume.
Primary `/Users/dsteele/repos/reef` dirty on codex/calcify-phase2-planning: preserve
AGENTS.md/CLAUDE.md, .mcp.json and preexisting planning/research files.
Local untracked `.planning/` owns recovery trace/reviews/proof/delivery checks;
Kotlin caches, if present, remain untracked. No raw recovery session logs in PR/archive.

## Completed Work And Evidence

17 code/config files recovered; six Kotlin test-only kernel/oracle/probe files,
seven new model/runner scripts, Makefile/CI/fixture/script-surface fixes.
[Original recovery hashes](../../evidence/calcify-financial-sprint1/recovery.json)
remain historic source provenance. Review1 four P2 and review2 three P2 findings
accepted/fixed. Author also corrected four locator parity counterexamples.
Review3 source verdict Ready with non-blocking follow-ups; open E4 assessor P2.
All17 source/config files unchanged during post-signoff proof.

Fresh checks:Node144/144; financial26/26 (14 kernel+12 oracle),20 frozen cases/52inputs,
300 seeded traces/19,200 prefixes. Runtime794 reported tests,774 reported passes,
20 skips,0 failures/errors,103suites; offline profile, some DB guards return without
assertions. E2 finite156+seeded128; selfcheck40prefixes+one-leg mutant rejection.
RF3 worker initialization fails; zero completed decision results. Raw failure retained.
Full [review/proof bundle](https://github.com/dills122/reef-records/tree/cfa4217708ff0694a966e3d87acce9585010a65a/records/reef/docs/evidence/calcify-financial-sprint1/verification-2026-10-04-908c3e54).
October3 original raw proof lost; never present October4 reruns as originals.

## Decisions And Rationale

Code/handoff use persistent worktree and pushed Git checkpoints. Bulk evidence
belongs in reef-records; local-only Reef provenance commit provides exact source blobs
without publishing bulk to Reef. Records owns only remote bulk copy. Archive originals and failures remain immutable.
No production API/events/schema changes; accepted architecture and steering unchanged.
[Unapplied heap-guard draft](calcify-sprint1-unfinished-heap-guard.patch) remains active.

## Blockers And Limitations

E3 worker initializes KafkaStreams with commit interval60000ms; effective producer
transaction timeout10000ms triggers IllegalArgumentException before readiness.
See FinancialBrokerProbe.worker and broker-proof cohort configuration; do not lower
interval silently because transaction grouping belongs to proof contract.
E4 rate-proof assessMeasurement accepts deadline cumulative counts greater than final
counts; add count consistency and expected-offer guard before any E4 PASS.
Full E3 matrix lacks producer failure/committed-before-ACK/broker outage/stale owner/
exact A10B27 mixed-age activation/missing history certification. E4 heap guard,
physical-byte calibration and persistent ACK membership remain incomplete.
Reservation policy and matcher identity lifecycle integration remain open.

Docker accessible outside sandbox; initial denial did not prove stopped daemon.
New project `reef-calcify-financial-s1-908c3e54` stopped after failed proof, volumes/topics
preserved. Old `reef-calcify-financial-s1-97924e15` and unrelated projects preserved.
Never global prune/reset; recheck headroom/settings before restarting test brokers.

## Immediate Next Actions

1. Obtain explicit human review-limit decision before another independent instance;
   surface new RF3 failure and existing E4 P2. Review3of3 exhausted skill maximum.
2. Fix timeout compatibility with regression covering actual KafkaStreams config;
   fix deadline/final count consistency with failing assessor control. Changes need
   new sign-off; current review applies only code908c3e54.
3. Regenerate E3 happy/golden/recovery/fault proof under reviewed config. Do not
   start E4 loads until full E3/heap/calibration gates pass.
4. Keep fresh raw attempts in persistent `.planning/`, publish complete bundle to
   Records before deleting sources. Existing PR checkpoint documents current blocker.

## Verification Commands

Java21 `/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`.
Node CI exact31-file command retained in full proof attempts.jsonl.

```sh
node --test scripts/dev/calcify-financial/reservation-model.test.mjs scripts/dev/calcify-financial/gate-model.test.mjs scripts/dev/calcify-financial/rate-proof.test.mjs
cd services/platform-runtime
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./gradlew --no-daemon test resolverProbeDependencies --rerun-tasks --console=plain
```

## Delivery Metadata

Product PR withheld pending E3/E4 fixes and new sign-off, honoring user
“once good, PR it.” [Records PR6](https://github.com/dills122/reef-records/pull/6)
open; archive commitcfa4217708ff0694a966e3d87acce9585010a65a pushed,256remote blobs
verified byte/hash exact (1,342,028bytes). Integrity776records/hygiene and4archive
unit tests pass; Reef retention9tests/37local links pass. Local-only provenance
commitfa442bbc5ef9fa7ab33e8ae4e6f128e364a30a95 never pushed to Reef; Records-only
route replaced rejected Reef bulk publication. No source deletion or production merge.
Use github-keychain-auth outside sandbox; unset GH_TOKEN/GITHUB_TOKEN per gh command,
never extract credentials. All bulk review/proof archive links use pinned commit.
