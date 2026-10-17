# AI working context

Read [`AGENTS.md`](../AGENTS.md) for repository invariants. Then load only the
documents needed for the task. Search code and current configuration before
repeating a dated status claim. Treat memory and handoffs as leads, not as
higher authority than source, tests, accepted decisions, and recorded evidence.

| Task | First read | Conditional follow-up |
| --- | --- | --- |
| Local setup or teardown | [Local configuration](LOCAL_CONFIGURATION.md), [Onboarding](ONBOARDING.md) | [DEV_ENV](DEV_ENV.md) for advanced knobs; `Makefile`, `.env.example`, and Compose for exact behavior |
| Behavior or API change | [Steering index](steering/README.md), relevant contract, [Decisions](DECISIONS.md) | Relevant language steering and tests |
| Current work/status | [Work plan](WORK_PLAN.md), source/tests and latest relevant evidence | Records only when dated comparison or prior rationale is needed |
| Throughput | [Throughput ledger](THROUGHPUT_BASELINES.md), [measured run selector](LOCAL_RUN_PROFILES.md#measured-configurations-choose-by-the-stage-you-need), [Performance Learnings](PERFORMANCE_LEARNINGS.md), active scaling plan | Original success and failure artifacts for the exact profile; distinguish venue-core from full-projection claims |
| Arena release | [Release readiness](BOT_ARENA_RELEASE_READINESS.md) | [Invite preview sprint](BOT_ARENA_INVITE_PREVIEW_SPRINT.md), current code and hosted run artifacts |
| Calcify | [Program status and sprint plan](work/CALCIFY_PROGRAM_STATUS_AND_SPRINT_PLAN_2026-10-11.md), then the [Calcify map](README.md#calcify-map) for the task's contract (finite source contract, O3 packet, role definition) | Evidence and research only when a claim cites them; handoffs and the [history log](https://github.com/dills122/reef-records/blob/f3c9941d5cd35df81b32970e9873ad8ca9f86391/records/reef/docs/work/CALCIFY_HISTORY_LOG.md) are history, not tasks. No financial-authority, credit or capacity claim without its own evidence and an owner decision |
| Post-trade | [Post-match standards](POST_MATCH_STANDARDS.md), [Work plan](WORK_PLAN.md) | Relevant settlement contracts and remaining lifecycle sprint |

## Context budget rules

1. Identify task and owner area. Read the short entry, then the relevant
   sections of at most a few source documents; do not bulk-load the archive.
2. Before making a current claim, check each document's alignment date and
   verify its scope against code, configuration, tests, and newer evidence.
3. If two documents disagree, preserve the older record and correct the
   active owner with an explicit date, source, and supersession link.
4. Put execution status only in `WORK_PLAN.md`. Keep detailed reports in their
   topic file or archive; link instead of copying them into summaries.
5. Keep temporary task plans and handoffs in `.planning/` or `docs/work/`.
   They do not become repository policy until promoted to steering, contract,
   decision, or active plan. Retain only active planning/session context; archive
   completed or superseded records during required completion pass.
6. Local docs serve current planning, ramp-up, operations and system/design
   understanding. Search Records for historical context; load only relevant
   originals, parse corrections, cite commit/scope and check current implications.
   Promote concise current facts with citations; leave historical bulk in Records.

Historical files live in [Reef Records](https://github.com/dills122/reef-records/blob/main/INDEX.md). Open one when an
active document cites its result, a failed attempt matters, or a prior design
choice must be reviewed.

[Records retention](RECORDS_RETENTION.md) defines focused local evidence and immutable links to complete proof in Records.
[Required completion pass](RECORDS_RETENTION.md#required-completion-pass) applies
after every feature or code change, including guidance/overview review and archive pass.
