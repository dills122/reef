# Reef Repository Conventions

## Purpose

This file captures repo-level conventions that apply regardless of language.

For product and architecture priorities that should guide every change, read [`repository-scope-and-priorities.md`](./repository-scope-and-priorities.md) alongside this file.

## Conventions

- use `apps/` for user-facing applications
- use `services/` for backend services and engines
- use `contracts/` for shared schemas and inter-service contracts
- use `packages/` for reusable internal libraries or shared models
- use `docs/` for roadmap, steering, architecture notes, and project documentation
- use `scripts/` for operational/dev automation entrypoints

## Ownership Boundaries

- `apps/docs-site/` owns public/static documentation and should not contain runtime logic.
- `services/platform-runtime/` owns API boundaries, workflow orchestration, durable command intake, persistence, read models, projections, and admin behavior.
- `services/matching-engine/` owns deterministic matching and execution behavior, not platform workflow orchestration or persistence.
- `services/simulator/` owns deterministic scenarios, seeded actors, replay, traffic generation, stress/load evidence, and trace validation.
- `contracts/proto/` owns versionable inter-service contracts.
- `packages/scenario-definitions/` owns reusable scenario inputs, seeds, clocks, actors, instruments, and correlation inputs.
- `scripts/` owns setup, reset, smoke, stress, replay, diagnostics, and performance comparison workflows.

## Scripting Standard

- prefer `bun`-executed JavaScript/TypeScript scripts for repository automation
- keep `make` targets as thin wrappers around versioned scripts in `scripts/`
- avoid adding new shell-heavy automation unless there is a clear platform/runtime reason
- when scripts call external tools (for example Docker/Go), keep argument handling explicit and cross-platform friendly
- performance scripts should report attempted, durably accepted, processed, and projected throughput separately when those stages apply
- stress/replay scripts should preserve comparable evidence artifacts across runs

## Naming

- name modules by business role, not by technology novelty
- prefer `platform-runtime` over generic names like `api-server`
- prefer `matching-engine` over ambiguous names like `core`
- prefer bounded-context terminology where it improves clarity

## Documentation Expectations

- significant architectural decisions should be reflected in `docs/`
- steering documents should change when the intended direction changes materially
- README content should describe the current project shape, not the original prototype only
- throughput-sensitive changes should update benchmark notes or link evidence when they change accepted/processed/projected capacity, latency, write amplification, or backlog behavior
- behavior changes that affect API routes, event semantics, storage formats, scenario determinism, partitioning, or command acceptance semantics must update the relevant contract and steering docs

## Safe Change Defaults

- keep feature changes scoped to one bounded context where possible
- add focused tests for behavior changes
- prefer projection/read-model improvements that do not alter canonical semantics
- preserve public `/api/v1` direction, idempotency behavior, and backpressure semantics unless the change explicitly targets those contracts
- avoid adding synchronous writes, table scans, or read-model updates to command hot paths without a clear reason and, where relevant, benchmark evidence

## Incremental Adoption

The current repo predates this structure.
When reshaping it, preserve useful history where practical, but do not force old prototype layouts to dictate the new platform architecture.
