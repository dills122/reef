# Reef steering index

These are normative rules for the area being changed. Start at the
[documentation map](../README.md) and read the relevant sections below. This
index is not a request to load every linked file for every task.

## Repository and architecture

- [Scope and priorities](repository-scope-and-priorities.md) — correctness,
  throughput, replay, audit, and safe-change priorities.
- [Architecture](architecture.md) — service boundaries and persistence rules.
- [Repository](repository.md) — layout, naming, scripting, and documentation.
- [Engineering delivery policy](../ENGINEERING_DELIVERY_POLICY.md) — tests,
  refactor triggers, and delivery gates.
- [Decisions](../DECISIONS.md) — accepted direction and amendments.

For product framing or system-wide design changes, also read the relevant
sections of [project overview](../../REEF_PROJECT_OVERVIEW.md) and
[technical design](../../REEF_TECHNICAL_DESIGN.md).

## By boundary or language

- [Data platform](data-platform.md) and
  [data-domain schema blueprint](../DATA_DOMAIN_SCHEMA_BLUEPRINT.md).
- [Inter-service communication](inter-service-communication.md) and
  [API boundary storage decisions](../API_BOUNDARY_STORAGE_DECISIONS.md).
- [External API boundary](external-api-boundary.md) and
  [API surface policy](../API_SURFACE_POLICY.md).
- [Post-match standards](../POST_MATCH_STANDARDS.md).
- [Go](go.md), [Kotlin](kotlin.md), and [Astro](astro.md) for their owner areas.

## Operations and evidence

- [Local configuration](../LOCAL_CONFIGURATION.md) and
  [onboarding](../ONBOARDING.md) for setup and teardown.
- [CI operations](../CI_OPERATIONS.md) for required gates and scheduled checks.
- [Work plan](../WORK_PLAN.md) for the dated execution board;
  [September 4 status](../CURRENT_STATUS.md) for the implementation snapshot.
- [Throughput baselines](../THROUGHPUT_BASELINES.md) and
  [performance learnings](../PERFORMANCE_LEARNINGS.md) before any throughput
  work. Follow their original success/failure and active-plan links.
- [Arena release readiness](../BOT_ARENA_RELEASE_READINESS.md) and
  [invite-preview sprint](../BOT_ARENA_INVITE_PREVIEW_SPRINT.md) for that release.
- [Post-trade lifecycle sprint](../POST_TRADE_LIFECYCLE_SPRINT.md) for remaining
  post-trade evidence and operator work.

Completed plans, dated audits, research, and benchmark records are indexed in
[the archive](../archive/README.md). The
[documentation lifecycle](../DOCUMENTATION_CLEANUP_PLAN.md) governs promotion
and archival.
