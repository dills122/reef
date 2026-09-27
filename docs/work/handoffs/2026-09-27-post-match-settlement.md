# Handoff: bounded post-match settlement

## Objective And Boundary

Continue the user-directed post-match rearchitecture until the integrated cutover is ready for a matched 10k/s disposable-droplet campaign. Preserve canonical source authority, deterministic replay, independent frontiers, and no synchronous matching-path settlement writes. Use local Docker for quick database checks; use disposable droplets for benchmarks. Do not use production or backbone for this work.

This handoff covers the merged settlement intake foundation and the **incomplete** dependent policy/obligation branch. It is a continuation aid, not a new product decision or proof of 10k/s capacity.

## Canonical Sources

- `AGENTS.md`, `docs/AI_CONTEXT.md`, and `docs/README.md` for repository rules and documentation map.
- `docs/WORK_PLAN.md` and `docs/work/POST_MATCH_SCALING_IMPLEMENTATION_PLAN_2026-09-26.md` for active sequence and cutover gates.
- `docs/work/POST_MATCH_CANONICAL_EFFECTS_CONTRACT_2026-09-26.md` for source-window and replay contract.
- `docs/THROUGHPUT_BASELINES.md`, original run artifacts, and `docs/PERFORMANCE_LEARNINGS.md` before a throughput claim or droplet run.
- `docs/DECISIONS.md` D-050 for settlement profiles and policy versions; D-055 for atomic projection progress.
- [PR #388](https://github.com/dills122/reef/pull/388) for merged canonical trade intake and review history.

## Current Repository State

- Repository: `/Users/dsteele/repos/reef`.
- Reused managed worktree: `/Users/dsteele/.codex/worktrees/post-match-storage/reef`.
- Branch: `codex/postmatch-bounded-policy`, stacked from PR #388 head `47a7c403`.
- Retained WIP commit: `b037008b` (`wip(postmatch): scaffold bounded obligation policy`). No PR or remote push for this branch.
- The other managed worktree, `/Users/dsteele/.codex/worktrees/post-match-market-state/reef`, remains on merged PR #388 branch `codex/postmatch-bounded-settlement`.
- PR #388 merged 2026-09-27 03:00:57 UTC. Latest PR head passed checks, including dedicated PostgreSQL schema-placement integration; all OCR threads were replied to and resolved before merge.
- At handoff creation, no known uncommitted source changes in the policy worktree. Verify with `git status --short --branch` before editing.

## Completed Work And Evidence

- PR #388 introduced default-off settlement trade intake on a distinct PostgreSQL target: keyed order ownership, canonical trade rows, receipts, coverage, and partition frontier commit atomically. Source-window byte and effect bounds, replay checks, local Compose target, migration routing, docs, and CI database integration are included.
- Two fresh-context independent reviews drove fixes before PR. OCR findings were fixed or answered with exact schema/column evidence; no open threads remained. Full local Kotlin tests, migration tests, Compose validation, and latest CI passed. This is intake only: it does not create obligations or ledger entries or switch public reads.
- WIP `b037008b` adds `settlement/0009_bounded_obligation_policy.sql` with proposed obligation frontier, immutable run/session policy bindings, and compact obligation table. It also adds `SettlementObligationPolicySource.kt`, a batch lookup of scenario, venue-session, and active platform profiles using one repeatable-read source snapshot and existing profile precedence.
- WIP compile: `./gradlew compileKotlin --no-daemon` passed in `services/platform-runtime`. The first sandboxed attempt could not open the Gradle cache lock under `~/.gradle`; the same compile passed with outside-sandbox execution. No tests yet cover WIP code and no database migration has been exercised for `0009`.

## Decisions And Rationale

- Keep the policy branch shadow-only and do not present its frontier as settlement completion. The new schema and source lookup are scaffolding; binding timing and replay behavior still need review before implementation is accepted.
- Bind policy by run/session and record profile ID, policy version, mode, and selection source on each obligation so later workflow and ledger transitions can be keyed and versioned. This follows the active scaling plan and D-050, but exact binding semantics remain provisional.
- Existing `TradeSettlementObligationMaterializer` loads all candidate trades, all run facts, and an all-events map; `PostgresSettlementFactStore.appendFacts` loads run facts again for validation. The replacement must read only changed trade, obligation, and affected accounts, with atomic progress.

## Blockers And Limitations

- **Unresolved design gate:** current policy references and admin profiles are mutable. WIP lookup selects their state when the consumer runs; it does not prove the policy effective when the trade occurred. Decide and document an immutable run/session binding or versioned control-plane history before relying on it for replay or public cutover. Do not silently treat first-consumer observation as historical truth.
- WIP has no obligation worker/store, no per-partition progress implementation, no account balance maintenance, no instant workflow/ledger legs, and no repair transition. `0009` must not be described as complete or deployed.
- Exact legacy trace/correlation metadata and direct operator/repair provenance remain cutover gates. Audit/history parity and live route gates also remain before full-pipeline droplet qualification.
- Local Docker daemon was inaccessible in this session; use CI's separate PostgreSQL job or restored local Docker for actual migration/transaction tests. Do not substitute a skipped local DB test for PostgreSQL evidence.

## Immediate Next Actions

1. Verify worktree status and fetch `origin/master` after PR #388 merge using Keychain-backed Git outside sandbox. Rebase **only** WIP commit `b037008b` onto merged master if needed; check whether #388 was squash-merged before choosing the command. Preserve both managed worktrees and unrelated main-checkout files.
2. Resolve the mutable-policy timing gate against D-050. Update canonical architecture decision/spec if a new versioned binding rule is chosen. Recheck WIP migration/source lookup against that rule before expanding it.
3. Add focused tests for profile precedence, missing/unknown assignments, source snapshot behavior, and migration schema. Then implement a bounded obligation window and atomic target apply from `canonical_trade_intake`, including empty outcome ranges, per-partition frontier, replay, fanout cap, and policy binding verification.
4. Implement keyed account state plus full instant allocation/confirmation/affirmation, clearing, DvP legs, ledger, break/repair, and public-read parity in the next cohesive change. Run independent review before each involved PR, label PR `ocr-pilot`, read and handle all comments.
5. After all mandatory stages and route adapters pass closed-cohort parity and rollback checks, use an approved disposable droplet for matched 10k/s 300s full-pipeline measurement. Record stage progress, in-load freshness, trade/ledger rates, resource budget, and drain headroom in the throughput ledger. Never use production or backbone.

## Verification Commands

Run from `/Users/dsteele/.codex/worktrees/post-match-storage/reef` after rebase and implementation:

```sh
git status --short --branch
cd services/platform-runtime && ./gradlew test --no-daemon
cd /Users/dsteele/.codex/worktrees/post-match-storage/reef && node --test scripts/dev/db/migrate.test.mjs
docker compose -f compose.base.yml -f compose.local.yml -f compose.arena.yml --profile postmatch config --quiet
git diff --check
```

Database integration requires the dedicated `settlement-postgres` target, migrations with `REEF_SETTLEMENT_POSTGRES_MIGRATIONS=1`, and `SETTLEMENT_POSTGRES_JDBC_URL_TEST` against that target. The WIP has only been compiled, not tested.

## Delivery Metadata

- Date: 2026-09-27 UTC.
- Base/checkpoint: merged PR #388 head `47a7c403` (verify merge commit on `origin/master`).
- Current retained commit: `b037008b` on `codex/postmatch-bounded-policy`; local only, no PR.
- Merged PR: [#388](https://github.com/dills122/reef/pull/388).
- Worktree to resume: `/Users/dsteele/.codex/worktrees/post-match-storage/reef`.
