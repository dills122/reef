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

Cross-repository retention follows [Records retention](RECORDS_RETENTION.md). Keep current docs and latest complete evidence bundles in Reef; archive older records unchanged before removing source copies.
