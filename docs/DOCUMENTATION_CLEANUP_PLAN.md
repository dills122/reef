# Documentation lifecycle

This is policy for keeping the active reading path small while preserving
history. Start at [the documentation map](README.md).

## Ownership

| Kind | Owner | Rule |
| --- | --- | --- |
| Developer setup and accepted local configuration | `ONBOARDING.md`, `LOCAL_CONFIGURATION.md`, `.env.example`, Make/Compose/scripts | Update instructions with command or setup changes; verify against executable config. |
| AI entry and repository rules | `AGENTS.md`, `AI_CONTEXT.md`, relevant steering | Keep first reads task-specific; point to owner docs instead of copying reports. |
| Current execution | `WORK_PLAN.md` | One work board. Date every reconciliation; no parallel checklist becomes authoritative. |
| Accepted decisions and contracts | `DECISIONS.md`, steering, `contracts/`, boundary docs | Preserve decisions and amendment/supersession links. |
| Throughput evidence | `THROUGHPUT_BASELINES.md`, original artifacts, `PERFORMANCE_LEARNINGS.md` | Keep successful and failed attempts, exact stage/scope, and explicit corrections. |
| Dated plans, audits, run reports, handoffs | Reef Records or temporary `.planning/` / `docs/work/` | Never infer unfinished work from an old checklist. Promote live tasking into `WORK_PLAN.md` before archiving. |

## Promotion and archive rules

- Run [required completion pass](RECORDS_RETENTION.md#required-completion-pass)
  after each feature/code change. Keep current planning, session/ramp-up context,
  pertinent system/design facts and latest verification; move superseded records
  to Records and retrieve historical context there when needed.
- A new active document names its owner, scope, and last verification date.
  Entry indexes link it only when routine work needs it.
- A completed or superseded plan moves to Reef Records with links repaired and
  an archive-index entry. Preserve its original claims and failed results.
- Before moving a document, check active links and extract any still-live
  contract or task into its owner document. Preserve benchmark, decision,
  security, and replay evidence in checksum-verified Records before local removal.
- Dated status reports say what was checked and what was not. A new branch,
  test result, or hosted observation needs a new dated correction; do not
  silently revise history.
- Documentation review checks relative links, setup commands against Make and
  scripts, and any current claim against source, tests, and recorded evidence.

The prior cleanup queue is preserved as the
[September 4 cleanup plan](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/archive/DOCUMENTATION_CLEANUP_PLAN_2026-09-04.md).

Cross-repository retention follows [Records retention](RECORDS_RETENTION.md). Keep current docs, concise verification summaries and executable fixtures in Reef. Complete raw evidence belongs in Records, including latest failures and corrections; publish, land and verify exact archive bytes before removing source copies.

## Script review — October 13, 2026

Bounded caller review against `150c6d6f2`; generated inventory comes from
`node scripts/dev/script-surface-check.mjs --inventory`, not a manually maintained
list. Tests and historical mentions no longer establish operational ownership.
Test/evidence-only results remain visible review candidates, not deletion orders.

| Family | Disposition | Reason |
| --- | --- | --- |
| Arena phase-1 local wrapper | Retired `arena:local-run`; [migration](BOT_SDK_HOSTED_RUNTIME.md) | Synthetic action/latency equity wrapper superseded by policy-backed tick sessions; current score-v0 fixture remains |
| DO materializer/scaling/freshness gates | Three wrappers and tests consolidated into one profile CLI/test | Existing Make commands, tiers and override semantics preserved |
| Submission, worker/session and hosted smoke tools | Retained | Separate workflow trust boundaries and worker/network protocols |
| Projection headroom and isolated downstream diagnostics | Retained for owner review | Only test references found; distinct proof contracts, no equivalent replacement established |
| Node/Deno runner microbench family | Deferred retirement review | Synthetic comparison has package/internal callers; no established current owner or supersession decision |
| SQL migrations, fixtures and meaningful tests | Retained | Executable dependencies; not entrypoint sprawl |
| Active Calcify financial and finite-P3 tools | Excluded from edits | Concurrent implementation; review warnings do not authorize retirement |

Raw evidence migration and byte verification are recorded in
[retention policy](RECORDS_RETENTION.md#october-13-raw-campaign-cleanup).
Ordinary retired script versions remain in Git history. No bulk source snapshot
or ignored-data deletion is part of this cleanup.

Verification: independent review 1 of 3 Ready; exact Node dev-tooling CI step
replayed in clean source checkout with cleanup patch, 414/414 pass, no skips.
Initial sandbox run failed only local loopback fixture permission (413/414);
authorized rerun passed without code changes. DO dispatch/plan and classifier/retention
regressions pass; 20 old/new DO plan outputs match. Arena report variants pass
with read-only shared dependencies. No cloud workload or runtime qualification run.
