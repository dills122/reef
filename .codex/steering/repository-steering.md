# Repository Scope And Priorities

This repository builds Reef, a simulation-first institutional trading venue and post-trade platform.

Primary deliverables:

- Astro documentation/marketing site
- Kotlin platform runtime for APIs, workflow orchestration, persistence, read models, and admin modules
- Go matching engine for order book and matching behavior
- Simulator for deterministic scenarios, replay, synthetic participants, and traffic generation
- Versionable contracts and shared models across runtime, engine, and simulator

Core priorities:

- realistic domain boundaries for execution, trade processing, post-trade workflow, settlement, exceptions, simulation, audit, and analytics
- deterministic scenario execution with seedable, replayable, explainable outcomes
- audit-friendly event trails and reconstructible trade lifecycle history
- stable typed contracts between modules and services
- local-first development with reliable setup, reset, smoke, stress, and replay workflows

## Active Boundaries

- `apps/docs-site/` owns public/static documentation and should not contain runtime logic.
- `services/platform-runtime/` owns API boundaries, workflow orchestration, persistence, read models, and admin behavior.
- `services/matching-engine/` owns matching and execution logic, not platform workflow orchestration.
- `services/simulator/` owns scenario control, seeded activity, replay, and synthetic actors.
- `contracts/proto/` owns versionable inter-service contract definitions.
- `packages/ui-models/` owns shared UI-facing model definitions.
- `packages/scenario-definitions/` owns reusable scenario inputs.
- `scripts/` owns local development and operational automation.
- `docs/steering/` owns normative direction for architecture, languages, APIs, repository shape, and communication boundaries.

## Safe Refactor Boundaries

Do not refactor these without explicit instruction:

- top-level project shape: `apps/`, `services/`, `contracts/`, `packages/`, `docs/`, `scripts/`
- public `/api/v1` boundary direction
- runtime-to-engine contract semantics
- event names and lifecycle semantics used for audit, replay, and read models
- scenario determinism inputs such as seeds, clocks, actor identity, and correlation IDs
- local environment commands in `Makefile` and `scripts/dev/`
- benchmark and performance evidence workflows documented in `docs/PERFORMANCE_LEARNINGS.md`

Safe default changes:

- feature-scoped improvements within one bounded context
- validation and endpoint hardening
- focused test additions
- typed contract clarification
- documentation updates that keep steering aligned with actual behavior

## Normative References

- `REEF_PROJECT_OVERVIEW.md`
- `REEF_TECHNICAL_DESIGN.md`
- `docs/steering/architecture.md`
- `docs/steering/repository.md`
- `docs/steering/inter-service-communication.md`
- `docs/steering/external-api-boundary.md`
- `docs/ENGINEERING_DELIVERY_POLICY.md`
- `docs/PERFORMANCE_LEARNINGS.md`
