# Reef

Reef is a simulation-first institutional trading venue and post-trade platform.

The project is being rebuilt from an early prototype into a production-shaped local platform for:

- hidden-liquidity order flow and matching
- trade lifecycle and post-trade workflow modeling
- deterministic scenario execution and replay
- audit-friendly event trails and operational views

## Current State

The repository is in Phase 0 foundation work.

What exists now:

- project direction in [`REEF_PROJECT_OVERVIEW.md`](./REEF_PROJECT_OVERVIEW.md)
- technical design in [`REEF_TECHNICAL_DESIGN.md`](./REEF_TECHNICAL_DESIGN.md)
- implementation roadmap in [`docs/ROADMAP.md`](./docs/ROADMAP.md)
- architecture and language steering in [`docs/steering/`](./docs/steering/)

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

## Steering

Start here before adding code:

- [`docs/steering/architecture.md`](./docs/steering/architecture.md)
- [`docs/steering/repository.md`](./docs/steering/repository.md)
- [`docs/steering/kotlin.md`](./docs/steering/kotlin.md)
- [`docs/steering/go.md`](./docs/steering/go.md)
- [`docs/steering/angular.md`](./docs/steering/angular.md)
- [`docs/steering/astro.md`](./docs/steering/astro.md)
