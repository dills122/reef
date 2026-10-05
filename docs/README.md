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

### Calcify reading order

| Order | Owner | Read for |
| --- | --- | --- |
| 1 | [Current work](WORK_PLAN.md#calcify-heap-protection-component--october-5-2026) | Merged work, verified heap component and remaining E4 gates |
| 2 | [Phase 1/2 overview](CALCIFY_PHASES_OVERVIEW.md) | Implemented production flow and source map |
| 3 | [System RFC](work/CALCIFY_SYSTEM_ARCHITECTURE_RFC.md#101-first-experiment-sprint) | Proposed financial authority, experiment acceptance and owner decisions |
| 4 | [Financial checkpoint](evidence/calcify-financial-sprint1/README.md) and [handoff](work/handoffs/2026-10-04-calcify-financial-sprint1-recovery.md) | Test-only proof, exact software scope, resources and continuation |
| 5 | [Throughput ledger](THROUGHPUT_BASELINES.md#calcify-direct-path-d7-sustained-qualification-october-2-2026) | Scoped D7 pass; financial capacity remains unmeasured |

Detailed reviews and raw financial proof remain linked from checkpoint to immutable
Records commits. Execution status stays in `WORK_PLAN.md`; RFC remains proposed.

- [Calcify Phases 1 and 2](CALCIFY_PHASES_OVERVIEW.md) explains implemented flow, worker wiring, source facts, managed state, and capacity boundaries.
- [Calcify discovery](work/CALCIFY_DISCOVERY.md) records open post-match redesign questions, proposed logical flow, and phased proof gates; it is not an approved architecture.
- [Calcify system RFC](work/CALCIFY_SYSTEM_ARCHITECTURE_RFC.md) proposes full post-trade architecture and bounded financial proofs; [research and review reconciliation](research/CALCIFY_SYSTEM_ARCHITECTURE_RESEARCH_2026-10-02.md) compares authorities and corrects historical findings against October 2 master. Neither accepts a new architecture or claims capacity qualification.
- [Calcify system independent review](research/CALCIFY_SYSTEM_ARCHITECTURE_INDEPENDENT_REVIEW_2026-10-02.md) retains first fresh-context findings and bounded corrections. Author testimony is separate in [review handoff](work/handoffs/2026-10-02-calcify-system-architecture-author.md).
- [Calcify full blind review](research/CALCIFY_SYSTEM_ARCHITECTURE_BLIND_REVIEW_2026-10-02.md) records second-pass critique and correction separating delivery history from stable financial identity.
- [Calcify final review](research/CALCIFY_SYSTEM_ARCHITECTURE_FINAL_REVIEW_2026-10-02.md) checks corrected identity/replay contract, closes review loop at3of3 and retains source-attribution limits. Verdict applies to proposed documentation publication.
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
