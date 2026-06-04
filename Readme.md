# Reef

<p align="center">
  <img src="./reef-logo-main.png" alt="Project Logo" width="300" height="300" />
</p>

Reef is a simulation-first institutional trading venue and post-trade platform.

The project is being rebuilt from an early prototype into a production-shaped local platform for:

- hidden-liquidity order flow and matching
- trade lifecycle and post-trade workflow modeling
- deterministic scenario execution and replay
- audit-friendly event trails and operational views

## Getting Started

For a full local setup and first-run path, start with:

- [`docs/ONBOARDING.md`](./docs/ONBOARDING.md)

For local environment operations and troubleshooting details:

- [`docs/DEV_ENV.md`](./docs/DEV_ENV.md)

Quick start:

```bash
cp .env.example .env
make dev-up
make dev-smoke
```

## Docs Map

- project pitch and product framing: [`docs/PROJECT_PITCH.md`](./docs/PROJECT_PITCH.md)
- onboarding and local setup: [`docs/ONBOARDING.md`](./docs/ONBOARDING.md)
- dev environment runbook: [`docs/DEV_ENV.md`](./docs/DEV_ENV.md)
- delivery policy and test expectations: [`docs/ENGINEERING_DELIVERY_POLICY.md`](./docs/ENGINEERING_DELIVERY_POLICY.md)
- roadmap and sequencing: [`docs/ROADMAP.md`](./docs/ROADMAP.md), [`docs/WORK_PLAN.md`](./docs/WORK_PLAN.md)
- performance library investigation: [`docs/PERFORMANCE_LIBRARY_INVESTIGATION.md`](./docs/PERFORMANCE_LIBRARY_INVESTIGATION.md)
- simulator persona/session plan: [`docs/SIMULATOR_PERSONA_CONFIG.md`](./docs/SIMULATOR_PERSONA_CONFIG.md)
- simulator backlog and execution policy: [`docs/SIMULATOR_UPGRADE_BACKLOG.md`](./docs/SIMULATOR_UPGRADE_BACKLOG.md)
- simulator control room sprint plan: [`docs/SIMULATOR_CONTROL_ROOM_SPRINT_PLAN.md`](./docs/SIMULATOR_CONTROL_ROOM_SPRINT_PLAN.md)

## Current State

The repository is in Phase 1 implementation with a working API-first venue slice and an increasingly capable local simulation/stress harness.

What exists now:

- project direction in [`REEF_PROJECT_OVERVIEW.md`](./REEF_PROJECT_OVERVIEW.md)
- technical design in [`REEF_TECHNICAL_DESIGN.md`](./REEF_TECHNICAL_DESIGN.md)
- implementation roadmap in [`docs/ROADMAP.md`](./docs/ROADMAP.md)
- architecture and language steering in [`docs/steering/`](./docs/steering/)
- Kotlin platform runtime with `/api/v1` submit/cancel/modify routes, idempotency hooks, query/read endpoints, reference data endpoints, abuse-protection telemetry, and admin CLI support
- Go matching engine with hidden-book matching, partial-fill/multi-match behavior, cancel/modify support, HTTP transport, and gRPC scaffold
- load/simulation CLI and replay/stress workflows in [`services/simulator/`](./services/simulator/)
- Docker-first local stack and smoke/stress/replay automation through `make` targets

Current planning review:

- [`docs/PROJECT_GOAL_PLAN_REVIEW.md`](./docs/PROJECT_GOAL_PLAN_REVIEW.md)

## Planned Repository Shape

```text
apps/
  platform-ui/
  docs-site/
services/
  platform-runtime/
  matching-engine/
  simulator/
contracts/
  proto/
packages/
  ui-models/
  scenario-definitions/
docs/
```

## Near-Term Build Plan

1. Align durable runtime/boundary/auth/admin persistence with the split-ready schema and migration plan.
2. Build the simulator control-room MVP over existing CLI scripts and report artifacts.
3. Complete venue lifecycle projections for submit/cancel/modify and expose stable query/timeline views.
4. Deliver the first deterministic lifecycle scenarios:
   - `P1_GOLDEN_HIDDEN_CROSS_T1`
   - `P2_SETTLEMENT_BREAK_REPAIR`
5. Extend into post-trade workflows once replay/timeline assertions are stable.

## Current Development Commands

Go matching engine:

```bash
cd services/matching-engine
GOCACHE=/tmp/reef-go-build-cache go test ./...
GOCACHE=/tmp/reef-go-build-cache go run ./cmd/matching-engine
```

Kotlin platform runtime:

```bash
cd services/platform-runtime
GRADLE_USER_HOME=/tmp/reef-gradle ./gradlew test
GRADLE_USER_HOME=/tmp/reef-gradle ./gradlew run
```

Repository check:

```bash
make test
```

Local DB migrations:

```bash
make dev-db-migrate
```

## Current Defaults

- runtime-to-engine transport currently defaults to HTTP adapter, with gRPC direction documented for migration
- user-facing API boundary direction is versioned `/api/v1` contracts
- admin surface direction is CLI-first using reusable runtime admin application modules

Related docs:

- [`docs/steering/inter-service-communication.md`](./docs/steering/inter-service-communication.md)
- [`docs/steering/external-api-boundary.md`](./docs/steering/external-api-boundary.md)
- [`docs/SPRINT_COMMUNICATION_API_ADMIN.md`](./docs/SPRINT_COMMUNICATION_API_ADMIN.md)
- [`docs/DECISIONS.md`](./docs/DECISIONS.md)
- [`docs/ONBOARDING.md`](./docs/ONBOARDING.md)

## Steering

Start here before adding code:

- [`docs/steering/architecture.md`](./docs/steering/architecture.md)
- [`docs/steering/repository.md`](./docs/steering/repository.md)
- [`docs/steering/kotlin.md`](./docs/steering/kotlin.md)
- [`docs/steering/go.md`](./docs/steering/go.md)
- [`docs/steering/angular.md`](./docs/steering/angular.md)
- [`docs/steering/astro.md`](./docs/steering/astro.md)
