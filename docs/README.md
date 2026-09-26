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

Use [DEV_ENV](DEV_ENV.md) for detailed runtime knobs and
[Local Run Profiles](LOCAL_RUN_PROFILES.md) only when selecting a demo or
measurement profile. Arena requires the explicit overlay; hosted operations
have separate runbooks under [`infra/`](../infra/README.md).

## Change behavior or architecture

- [AI working context](AI_CONTEXT.md) gives agents a task-specific reading path.
- [Steering index](steering/README.md) links normative architecture and language
  rules. Read the relevant sections for the area being changed.
- [Decisions](DECISIONS.md) records accepted direction; contracts live under
  [`contracts/`](../contracts/README.md).
- [Work plan](WORK_PLAN.md) is the execution board. Its stated alignment date
  is part of every status claim; verify against source and newer evidence.
- [Current status](CURRENT_STATUS.md) is the September 4 snapshot, with its
  recorded limits. It is orientation, not a live release or capacity report.
- [Throughput ledger](THROUGHPUT_BASELINES.md) is mandatory before performance
  investigation, planning, benchmarking, or status claims. Read linked original
  successes and failures plus the active plan.

## Historical material

[Archive index](archive/README.md) contains completed plans, dated audits,
benchmark evidence, and older research. [Research](research/) holds recent
investigations; its results remain scoped to their run and date. Use history
when a current claim cites it or a decision needs rechecking. Do not treat old
checklists as open work.

[Documentation lifecycle](DOCUMENTATION_CLEANUP_PLAN.md) defines how new docs
become active, reference, or historical.
