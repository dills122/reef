# Handoff: retire post-match redesign attempt

## Objective And Boundary

User stopped the current post-match re-architecture after failed capacity
qualification and requested a fresh design. Preserve this branch and its
evidence for audit. Prepare a separate revert branch removing the redesign
from `master`; do not force-push or delete history. No current candidate was
cut over to production. Do not treat this handoff as approval to resurrect the
abandoned design.

## Canonical Sources

- `docs/DECISIONS.md` D-059 and D-060 describe the old bounded transition and
  later journal proposal. Both are historical until a new decision replaces
  them.
- `docs/work/POST_MATCH_LEDGER_EXECUTION_INDEX_2026-09-28.md` records candidate
  work and checks.
- `docs/research/evidence/post-match-ledger-trial-postmortem-2026-09-29.md`
  records failed local and hosted qualification, including the instrumented
  50/s run. Raw local artifacts are under `/tmp/reef-pmj-timing-60s-artifacts/`
  and `/tmp/reef-pmj-timing-journal.log` on the originating host; those paths
  are not durable repository artifacts.
- `docs/THROUGHPUT_BASELINES.md` and original run artifacts remain the
  performance baseline. Do not compare unlike workloads as a speed ratio.

## Current Repository State

- Archive branch: `codex/ledger-authority-redesign`, PR #408. Its committed
  base is `646987cd` (PR #406) on `master`; this handoff and final local
  corrections are packaged in a later branch commit.
- The first merged redesign commit is `a78f7fd80013fad188d5a7344751dc52238f2378`
  (PR #373), a planning-only change. First implementation is PR #376.
- PRs #371, #372, #374, #375, and #403 are interleaved unrelated work and
  must be retained when reverting redesign commits from `master`.
- Main checkout `/Users/dsteele/repos/reef` contains unrelated untracked
  `.planning/post-match-wave1/`; leave it alone.

## Completed Work And Evidence

- The first complete isolated 50/s, 60-second run accepted and materialized
  2999/2999 commands. After input stopped, its closed cohort matched 1044
  first trades in source, journal, and market, with 1044 financial trade
  projections. Source-to-journal backlog grew during load, then drained.
- A second instrumented 50/s run also accepted and materialized 2999/2999.
  Six loaded writer intervals spent 42.66/59.83 seconds in synchronous
  broker-gap checks, 8.66 seconds in control-prefix verification, and 2.58
  seconds in append plus finality acknowledgement. Source-to-journal frontier
  lag grew from about 168 to 794 positions during input. Its API read gate
  failed. This identifies the measured bottleneck in this local profile; it
  does not qualify a 10k/s candidate or prove the design cannot be rebuilt.
- Candidate market API still performs growing replay/proof work on advancing
  frontiers. Status source-authority correction has focused tests but lacks
  sustained load qualification. Local timing instrumentation is default-off.

## Decisions And Rationale

- Current user direction is to discard the implementation and design a new
  post-match architecture. Revert must preserve unrelated matching, ingress,
  projection recovery, CI, and codebase-memory changes interleaved after #373.
- Existing outcomes remain matching authority. No change to Go matching,
  ingress, pre-trade, or matching-outcome format was authorized for this work.
- Preserve failed-run records and this branch even though its code will be
  removed from the active baseline.

## Blockers And Limitations

- No hosted candidate 10k/s load completed. Three hosted attempts stopped
  before load on preflight/Compose issues; the droplet was shut down at user
  request. The local 50/s result is not an apples-to-apples ratio with an
  earlier hosted baseline.
- The instrumented run's read gate failed before a second closed-cohort parity
  command; its 1036 journal result rows are not a checked parity count.

## Immediate Next Actions

1. Preserve this branch commit and push it to origin.
2. From current `origin/master`, revert only redesign commits from #373 and
   #376 through #406 while retaining unrelated interleaved commits.
3. Verify the resulting tree removes post-match workers, schemas, routes,
   scripts, contracts, and stale plan pointers introduced by those commits.
4. Run focused build/smoke checks, then push the revert branch for review.

## Verification Commands

- `git log --reverse --first-parent --oneline a78f7fd^..origin/master`
- `git diff --check`
- `git diff --stat origin/master...HEAD`

## Delivery Metadata

- Repository: `https://github.com/dills122/reef`
- Archive PR: `https://github.com/dills122/reef/pull/408`
- Date: 2026-09-29 UTC (2026-09-28 America/Toronto)
