# AGENTS

AI coding guidance for this repository.

## Purpose

Reef is a simulation-first institutional trading venue and post-trade platform.

Optimize for:

- realistic market-infrastructure domain boundaries
- deterministic scenario execution, replay, and auditability
- high-throughput command intake and lifecycle processing with measured evidence
- low write amplification, partitionable processing, and async projections on hot paths
- local-first development with inspectable workflows
- small, explicit changes over broad refactors
- tests and documentation when behavior, contracts, setup, or commands change

Performance work must never weaken correctness, determinism, auditability, idempotency, or replay semantics.

## Canonical Docs

Read these before changing architecture or behavior:

- `REEF_PROJECT_OVERVIEW.md`
- `REEF_TECHNICAL_DESIGN.md`
- `docs/steering/README.md`
- `docs/steering/repository-scope-and-priorities.md`
- `docs/steering/architecture.md`
- `docs/steering/repository.md`
- `docs/PERFORMANCE_LEARNINGS.md`
- `docs/ENGINEERING_DELIVERY_POLICY.md`
- `docs/DECISIONS.md`

Language and surface-specific steering:

- `docs/steering/go.md`
- `docs/steering/kotlin.md`
- `docs/steering/astro.md`
- `docs/steering/data-platform.md`
- `docs/steering/inter-service-communication.md`
- `docs/steering/external-api-boundary.md`

## Architecture Boundaries

Primary areas:

- `apps/docs-site/`: Astro documentation/marketing surface
- `services/platform-runtime/`: Kotlin API/runtime, workflow orchestration, persistence, read models, and admin modules
- `services/matching-engine/`: Go matching and execution engine behavior
- `services/simulator/`: scenario execution, seeded simulation, replay, and traffic generation
- `contracts/proto/`: versionable inter-service contracts
- `packages/ui-models/`: shared frontend-facing model definitions
- `packages/scenario-definitions/`: reusable scenario definitions and simulation inputs
- `docs/`: roadmap, delivery policy, architecture, decisions, steering, and operational notes
- `scripts/`: local development, smoke, stress, admin, replay, and throughput automation

When a change spans areas, preserve ownership boundaries and update shared contracts first.

## Contract-First Files

Treat these as interface contracts before implementation details:

- `contracts/proto/`
- `docs/steering/inter-service-communication.md`
- `docs/steering/external-api-boundary.md`
- `docs/API_BOUNDARY_STORAGE_DECISIONS.md`
- `docs/DATA_DOMAIN_SCHEMA_BLUEPRINT.md`
- `docs/DECISIONS.md`

If behavior changes, update the relevant contract and docs in the same change.

## Scope Control

- Keep simulation actors on the same command/API paths as manual users.
- Keep domain logic framework-light and outside adapters.
- Keep Go matching-engine behavior isolated from Kotlin runtime orchestration.
- Keep UI projections from leaking into core write-model logic.
- Keep canonical command/event facts separate from rebuildable projections.
- Keep matching-sensitive submit/cancel/modify commands for the same venue session and instrument on the same deterministic processing lane.
- Do not return `202 Accepted` until the configured durable ingress mechanism has acknowledged acceptance.
- Avoid premature service extraction; bounded contexts can begin as modules.
- Avoid unrelated refactors and generated artifact churn.
- Do not change public API routes, event semantics, storage formats, or scenario determinism without explicit intent.
- Do not add synchronous hot-path writes, table scans, or read-model updates without a clear reason and, where relevant, benchmark evidence.

## Repository Conventions

- Follow `docs/steering/repository.md` for repo layout, naming, scripting, and documentation expectations.
- Prefer `bun` scripts under `scripts/` for repository automation; keep `make` as thin wrappers.
- Add focused tests for behavior changes.
- Update docs when setup steps, commands, contracts, workflows, or architecture direction change.
- Preserve local-first setup/reset/smoke flows.

## Useful Commands

- Local setup: `cp .env.example .env`
- Start local stack: `make dev-up`
- Stop local stack: `make dev-down`
- Reset local stack: `make dev-reset`
- Smoke test local stack: `make dev-smoke`
- Repository tests: `make test`
- Go matching engine tests: `make test-go`
- Kotlin platform runtime tests: `make test-platform-runtime`
- Proto compatibility check: `make check-proto-additive`
- Simulator run: `make dev-sim`
- Admin command: `make dev-admin CMD="instrument-upsert AAPL AAPL"`

## Branch And PR Metadata

- Use feature branches for behavior, contract, test, or documentation changes.
- Do not commit directly to `main`.
- When work is ready, provide:
  - branch name
  - PR title
  - PR summary
  - test evidence
