# Documentation Cleanup Plan

## Purpose

Keep current planning easy to scan while preserving historical evidence. Reef
has valuable benchmark reports, sprint plans, audits, and design notes, but
active work should not require reading every historical document.

## Document Classes

### Active Operating Docs

These shape current work:

- `CURRENT_STATUS.md`
- `WORK_PLAN.md`
- `DECISIONS.md`
- steering docs under `docs/steering/`
- current contract and boundary docs
- current sprint/tasking docs linked from `WORK_PLAN.md`

Rule: if a doc is active, `CURRENT_STATUS.md` or `WORK_PLAN.md` should say why.

### Evidence Docs

These prove or explain a claim:

- benchmark reports
- crash-gate reports
- scenario assertion reports
- dated hardening/audit results
- migration inventories

Rule: keep them near the active topic while they are frequently cited. Move
them under `docs/archive/` once they are superseded and only needed as history.

### Historical Planning Docs

These describe past plans or superseded approaches:

- old sprint plans
- pre-D-041 throughput plans
- obsolete baseline reports
- design investigations that no longer drive current work

Rule: move to `docs/archive/` and leave a short note in the archive index.

## Cleanup Rules

- Do not delete decision records, benchmark evidence, or security reports.
- Prefer moving stale planning docs to `docs/archive/` over rewriting history.
- Update links when a moved doc is still referenced by active docs.
- Keep one active execution ladder in `WORK_PLAN.md`.
- Keep `CURRENT_STATUS.md` short enough to orient work, not repeat every report.
- Mark historical scope directly in old docs when moving them would break too
  many links in one change.

## Near-Term Cleanup Queue

1. Moved in the 2026-07 cleanup pass:
   - `ARCHITECTURE_THROUGHPUT_PLAN.md`
   - `ARCHITECTURE_THROUGHPUT_TRACKER.md`
   - `STREAM_ACK_ARCHITECTURE_PLAN.md`
   - `THROUGHPUT_SCALING_WORK_PLAN.md`
   - `SPRINT_CRITICAL_QUALITY_HARDENING.md`
2. Move old sprint/baseline docs after any still-active tasking is extracted:
   - dated Bot Arena and benchmark baselines that are no longer active gates
3. Split Bot Arena docs into active product work versus historical evidence.
4. Keep post-trade sprint docs active until the lifecycle V1 work lands, then
   fold stable decisions into `SETTLEMENT_CLEARING_STRATEGY.md` and archive the
   sprint plan.

## Acceptance For A Cleanup PR

- active docs still link cleanly
- moved docs are listed in `docs/archive/README.md`
- `WORK_PLAN.md` remains the active execution source
- no benchmark, decision, or security evidence is deleted
