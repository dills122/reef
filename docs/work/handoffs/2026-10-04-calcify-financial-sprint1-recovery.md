# Handoff: Calcify sprint1 source recovery

October4,2026. Recovery checkpoint; experiments remain paused pending user check-in.

## Objective And Boundary

Recover all October3 code before further work. User requested check-in after recovery.
No E3 faults, E4 calibration/load, new heap implementation or production work authorized
at this checkpoint. Existing sprint scope remains test-only RFC experiments.

## Canonical Sources

`AGENTS.md`, `docs/AI_CONTEXT.md`, `docs/WORK_PLAN.md`,
`docs/work/CALCIFY_SYSTEM_ARCHITECTURE_RFC.md` §10.1,
`docs/RECORDS_RETENTION.md`. Frozen fixtures unchanged:
`fee7eead368d2ef2927aad1e53877907a4d74f9762b696d20f7e5575b1361e6d`.

## Current Repository State

Persistent worktree `/Users/dsteele/repos/reef/.worktrees/calcify-sprint1-experiments`.
Branch `codex/calcify-sprint1-experiments`, base97924e15642826a687db935a219d7927ae649ac8.
Source checkpoint06911bcf committed and pushed to origin. Verify latest `git log -1` before resume.
Primary `/Users/dsteele/repos/reef` remains dirty on codex/calcify-phase2-planning;
preserve AGENTS.md/CLAUDE.md and preexisting untracked planning/research files.

## Completed Work And Evidence

17 code/config files restored from successful literal writes and ordered targeted
patches in retained Codex sessions; no arbitrary historical command replay.
[Source hashes](../../evidence/calcify-financial-sprint1/recovery.json).
Six Kotlin files restore kernel/oracle/tests/shared broker/rate adapters; seven new
scripts restore reservation/gate/rate models, tests and broker runner. Makefile/CI
wiring, fixture scope output and explicit Node syntax-check fix restored.

Unfinished heap-guard test draft preserved as
[unapplied patch](calcify-sprint1-unfinished-heap-guard.patch). Implementation never
started. Full original draft/recovery trace/old handoff remain in persistent local
`.planning/sprint1-recovery/`; exclude raw trace/build caches from code commit.

October3 temporary worktree and proof were deleted. Prior15 financial/38 model
passes survive as conversation reports only; full original raw attempts/XML gone.
Fresh recovery verification:17 hashes match,38 Node tests pass, Java21 test-source
compile passes42s. Financial JUnit suite not rerun.
Fresh logs use persistent `.planning/sprint1-recovery/verification/`.
Do not recreate historical proof or present reruns as original artifacts.

## Decisions And Rationale

Exact source recovery first; preserve incomplete code without claiming readiness.
Permanent worktree plus Git checkpoint protects source/handoff. Bulk new proof still
belongs in reef-records at sprint delivery; local pending proof must use persistent
storage rather than `/private/tmp`. Existing E0 Records archive08a4347 remains intact.

## Blockers And Limitations

E3/E4 adapters were compiled before loss but never ran live experiments. Remaining
E3 gates: coverage-head/history binding review, actual topic settings, transaction
faults/production error/broker outage/takeover, true staged phase, exact A10/B27
mixed-age activation and no-republish barrier. Current isolated reconstruction is
result-only sequence control, not full qualified activation.

E4 needs heap-sizing guard, E3 pass, actual producer/observer/byte calibration and
resource gates. RAM membership recovery/technical encoded-store bytes remain gaps.
No capacity qualification, architecture acceptance or live reservations. Owner must
accept/amend reservation proposal. Matcher identity integration gap stays open.
Broker/volume state has not been rechecked after loss; do not assume old resources
exist. Original isolated project reef-calcify-financial-s1-97924e15 was stopped at
pause. Never global prune/reset or remove unrelated volumes.

## Immediate Next Actions

1. Check in with user after recovery checkpoint and verification; do not resume
   experiments until user resumes them.
2. On resume, verify source hashes and branch. Read newest handoff only.
3. Freeze compiled runtime before E3; recheck actual isolated Docker resources,
   versions, health, disk and topics. First real arm run-happy with explicit
   persistent proof directory. Runner default still points at private/tmp: override
   before any run, then fix durable defaults as separately recorded change.
4. Finish E3/E4 gates, broader module checks, compact checkpoint/full Records proof,
   fresh-context independent review, then focused PR. No new PR created yet.

## Verification Commands

Java21 `/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`;
Gradle cache access outside sandbox. Source-recovery verification only:

```sh
node --test scripts/dev/calcify-financial/reservation-model.test.mjs scripts/dev/calcify-financial/gate-model.test.mjs scripts/dev/calcify-financial/rate-proof.test.mjs
cd services/platform-runtime
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./gradlew --no-daemon compileTestKotlin resolverProbeDependencies
```

## Delivery Metadata

No experiment PR or new Records import. No production edits. Primary checkout
unchanged. Code hashes/incident disclosure committed; bulk trace remains local for
later immutable Records publication. Original session IDs in recovery.json identify
source provenance without copying full session logs.
