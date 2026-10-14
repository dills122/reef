# Documentation map

Start here. This index separates instructions for today's checkout from plans,
reference material, and dated evidence. A document's presence under `docs/`
does not make its old status or benchmark result current.

## Develop Reef

1. [Onboarding](ONBOARDING.md) — clean-machine prerequisites and first smoke.
2. [Local configuration and lifecycle](LOCAL_CONFIGURATION.md) — accepted default
   stack, optional profiles, start, stop, reset, and configuration authority.
3. [Contributing](../CONTRIBUTING.md) — change and verification workflow.
4. [System overview](SYSTEM_OVERVIEW.md) — short architecture orientation.

Use [DEV_ENV](DEV_ENV.md) for detailed runtime knobs. For a benchmark, start at
the [measured run selector](LOCAL_RUN_PROFILES.md#measured-configurations-choose-by-the-stage-you-need):
it names configurations behind recorded best runs and separates venue-core
from full-projection results. Arena requires the explicit overlay; hosted operations
have separate runbooks under [`infra/`](../infra/README.md).

## Change behavior or architecture

### Calcify map

Start with the [program status and sprint plan](work/CALCIFY_PROGRAM_STATUS_AND_SPRINT_PLAN_2026-10-11.md): where Calcify
stands, whether the path is sound, decisions, and the next sprints. The [work board](WORK_PLAN.md#calcify-program--current-state-and-next-actions-october-11-2026)
carries the short execution view; dated checkpoint essays live in the [history log](work/CALCIFY_HISTORY_LOG.md). No document here
adopts financial authority: relational authority (D009) stays accepted until a separate decision.

| Role | Document | Status and use |
| --- | --- | --- |
| Direction | [System RFC](work/CALCIFY_SYSTEM_ARCHITECTURE_RFC.md#101-first-experiment-sprint) | **Proposed** architecture, proof plan and owner decisions; status snapshot at the top |
| Direction | [Single-server pressure test](research/CALCIFY_SINGLE_SERVER_ARCHITECTURE_REVIEW_2026-10-11.md), [aged contention](research/CALCIFY_AGED_CONTENTION_PROOF_2026-10-11.md), [matched control](research/CALCIFY_MATCHED_CONTROL_2026-10-11.md) | Research: keyed state, recovery as the 10k blocker (8.1k/s with cold restart, 10.3k/s without); component results do not qualify the API-visible goal |
| Goalpost | [Arena throughput contract](work/CALCIFY_ARENA_THROUGHPUT_CONTRACT.md), [arena credit policy](work/CALCIFY_ARENA_CREDIT_POLICY.md) | **Proposed**: 10k/s through the trading API, settlement lags; receivable credit with reference proofs; numbers await calibration and owner adoption |
| Build contract | [Finite P3 source contract](work/CALCIFY_FINITE_P3_SOURCE_CONTRACT.md), [contracts README](../contracts/calcify/README.md) | Normative for O1/O2: lifecycle facts, budgets, ingress, binding |
| Build design | [O3 packet](work/CALCIFY_FINITE_P3_O3_PACKET.md), [DB role definition](work/CALCIFY_FINITE_P3_DB_ROLES.md) | Decisions D1-D7 recorded; [role split signed October 13, 2026](work/CALCIFY_FINITE_P3_ROLE_SIGNOFF.md) |
| Implemented flow | [Phase 1/2 overview](CALCIFY_PHASES_OVERVIEW.md) | Production opt-in flow, source map, finite O1/O2/O3a status |
| Evidence | [O1 checkpoint](evidence/calcify-finite-p3/README.md), [O2 verification](evidence/calcify-finite-p3/2026-10-11-o2-verification.md), [financial checkpoint](evidence/calcify-financial-sprint1/README.md), [throughput ledger](THROUGHPUT_BASELINES.md#calcify-direct-path-d7-sustained-qualification-october-2-2026), [financial diagnostic](evidence/calcify-financial-sprint1/README.md#latest-empirical-checkpoint--october6-utc) | Scoped, dated results; each states what it does not establish |
| History and reviews | [Discovery](work/CALCIFY_DISCOVERY.md) (open questions, not an architecture), [research reconciliation](research/CALCIFY_SYSTEM_ARCHITECTURE_RESEARCH_2026-10-02.md), [independent](research/CALCIFY_SYSTEM_ARCHITECTURE_INDEPENDENT_REVIEW_2026-10-02.md), [blind](research/CALCIFY_SYSTEM_ARCHITECTURE_BLIND_REVIEW_2026-10-02.md) and [final](research/CALCIFY_SYSTEM_ARCHITECTURE_FINAL_REVIEW_2026-10-02.md) reviews, [review author handoff](work/handoffs/2026-10-02-calcify-system-architecture-author.md), [financial handoff](work/handoffs/2026-10-04-calcify-financial-sprint1-recovery.md), [overnight plan](work/CALCIFY_OVERNIGHT_SESSION_PLAN.md) | Read-only history of how the design was reviewed and executed; handoffs and the overnight plan describe dated sessions, not current tasks |

Detailed reviews and raw financial proof remain linked from checkpoint to immutable Records commits. Execution status stays in
`WORK_PLAN.md`; the RFC remains proposed.

- [AI working context](AI_CONTEXT.md) gives agents a task-specific reading path.
- [Steering index](steering/README.md) links normative architecture and language
  rules. Read the relevant sections for the area being changed.
- [Decisions](DECISIONS.md) records accepted direction; contracts live under
  [`contracts/`](../contracts/README.md).
- [Work plan](WORK_PLAN.md) is the execution board. Its stated alignment date
  is part of every status claim; verify against source and newer evidence.
- [Throughput ledger](THROUGHPUT_BASELINES.md) is mandatory before performance
  investigation, planning, benchmarking, or status claims. Read linked original
  successes and failures plus the active plan.

## Historical material

Current reading path keeps planning, session/ramp-up context and facts pertinent
to current system/design. Use Records for historical questions, reading only
relevant originals and corrections. [September 4 status snapshot](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/CURRENT_STATUS.md)
is historical comparison evidence, not default current-system orientation.

[Reef Records index](https://github.com/dills122/reef-records/blob/6b838e5287c5428c914f6577ff176c1cd03c44ec/INDEX.md) contains completed plans, dated audits,
benchmark evidence, and older research. [Research](research/) holds recent
investigations; its results remain scoped to their run and date. Use history
when a current claim cites it or a decision needs rechecking. Do not treat old
checklists as open work.

[Documentation lifecycle](DOCUMENTATION_CLEANUP_PLAN.md) defines how new docs
become active, reference, or historical.

[Records retention](RECORDS_RETENTION.md) defines focused local summaries/fixtures,
complete bulk evidence in Records and checksum-verified lookup/restore.
[Completion pass](RECORDS_RETENTION.md#required-completion-pass) is required for
every feature/code change: update affected docs/evidence/guidance, then archive old material.
