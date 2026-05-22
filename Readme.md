# Reef

Reef is a simulation-first institutional trading venue and post-trade platform.

The project is being rebuilt from an early prototype into a production-shaped local platform for:

- hidden-liquidity order flow and matching
- trade lifecycle and post-trade workflow modeling
- deterministic scenario execution and replay
- audit-friendly event trails and operational views

## Current State

The repository is in early Phase 1 implementation (API-first venue slice).

What exists now:

- project direction in [`REEF_PROJECT_OVERVIEW.md`](./REEF_PROJECT_OVERVIEW.md)
- technical design in [`REEF_TECHNICAL_DESIGN.md`](./REEF_TECHNICAL_DESIGN.md)
- implementation roadmap in [`docs/ROADMAP.md`](./docs/ROADMAP.md)
- architecture and language steering in [`docs/steering/`](./docs/steering/)
- load/simulation CLI in [`services/simulator/`](./services/simulator/)

The old Go prototype has been retired so the repository can be rebuilt around the current architecture intentionally.

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

1. Stand up the repo skeleton and service/app shells.
2. Define the first runtime-to-engine contracts.
3. Implement the first end-to-end venue slice:
   - reference data
   - order submission
   - matching
   - executions and trades
   - event timeline views
4. Extend into post-trade workflows and simulation control.

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

## Current Defaults

- runtime-to-engine transport currently defaults to HTTP adapter, with gRPC direction documented for migration
- user-facing API boundary direction is versioned `/api/v1` contracts
- admin surface direction is CLI-first using reusable runtime admin application modules

Related docs:
- [`docs/steering/inter-service-communication.md`](./docs/steering/inter-service-communication.md)
- [`docs/steering/external-api-boundary.md`](./docs/steering/external-api-boundary.md)
- [`docs/SPRINT_COMMUNICATION_API_ADMIN.md`](./docs/SPRINT_COMMUNICATION_API_ADMIN.md)
- [`docs/DECISIONS.md`](./docs/DECISIONS.md)

## Steering

Start here before adding code:

- [`docs/steering/architecture.md`](./docs/steering/architecture.md)
- [`docs/steering/repository.md`](./docs/steering/repository.md)
- [`docs/steering/kotlin.md`](./docs/steering/kotlin.md)
- [`docs/steering/go.md`](./docs/steering/go.md)
- [`docs/steering/angular.md`](./docs/steering/angular.md)
- [`docs/steering/astro.md`](./docs/steering/astro.md)
