# Reef

[![CI](https://github.com/dills122/reef/actions/workflows/ci.yml/badge.svg)](https://github.com/dills122/reef/actions/workflows/ci.yml)
[![Throughput Stress](https://github.com/dills122/reef/actions/workflows/throughput-stress.yml/badge.svg)](https://github.com/dills122/reef/actions/workflows/throughput-stress.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](./LICENSE)
[![Kotlin](https://img.shields.io/badge/platform--runtime-Kotlin-7F52FF.svg)](./services/platform-runtime)
[![Go](https://img.shields.io/badge/matching--engine-Go-00ADD8.svg)](./services/matching-engine)

<p align="center">
  <img src="./reef-logo-main.png" alt="Reef project logo" width="260" height="260" />
</p>

Reef is a simulation-first institutional trading venue and post-trade platform. It is built to model market-infrastructure workflows locally, replay them deterministically, and measure command-intake and lifecycle behavior with evidence instead of assumptions.

The current system focuses on:

- hidden-liquidity order intake, matching, cancel, modify, fill, and reject behavior
- deterministic scenario execution, replay, and audit-friendly command/event trails
- high-throughput command ingress with explicit hot-path guardrails
- partitionable processing lanes for matching-sensitive commands
- async projections and rebuildable read models outside canonical write facts
- local-first Docker workflows for development, smoke, stress, and diagnostics

## Live Sites

| Site | URL | Purpose |
| --- | --- | --- |
| Admin / Bot Arena | <https://reef-arena-admin.shrimpworks.dev/> | Hosted backbone surface for admin workflows, bot submission/provisioning paths, and simulator-facing integration checks. This is backed by the permanent Hetzner stack and should be treated as an operational environment. |
| Docs / Project Site | <https://dills122.github.io/reef/> | Public project documentation site for Reef architecture, development context, and project-facing reference material. This is the GitHub Pages documentation surface. |

Operational details for the hosted backbone live under
[`infra/hetzner-core/`](./infra/hetzner-core/), especially the current state,
runbook, and secrets checklist.

## System Shape

```text
apps/
  docs-site/                   Astro documentation surface
  arena-admin/                 SvelteKit Bot Arena public/admin UI
  control-room/                Local runtime and throughput inspection UI
services/
  platform-runtime/            Kotlin Reef API/runtime, command intake, persistence, projections, admin contracts
  arena-control-plane/         Optional Kotlin Arena routes, registry, admission, provisioning, and risk extension
  matching-engine/             Go matching engine, HTTP/gRPC transports, direct stream ingestion
  simulator/                   Go load, scenario, replay, and stress tooling
contracts/
  proto/                       Versionable inter-service contracts
packages/
  scenario-definitions/        Reusable simulation inputs and scenario files
  bot-sdk/                     Repository-local ReefBotV1 authoring contract, examples, and fixtures
bots/                          Submitted bot manifests and source accepted through the repository workflow
scripts/
  dev/                         Local stack, smoke, stress, replay, admin, and migration automation
  ci/                          CI guardrails and coverage helpers
docs/
  steering/                    Architecture, repo, language, and boundary guidance
```

The main runtime path is API-first: manual users and simulation actors go through the same command/API surfaces. Matching-engine behavior stays isolated in Go, while the Kotlin runtime owns Reef orchestration, persistence adapters, read models, and generic administrative workflows. Bot Arena is an optional product extension: its Kotlin artifact and `compose.arena.yml` overlay depend on Reef contracts, while the Reef-only artifact, routes, migrations, and default Compose profile do not depend on Arena.

## Quick Start

```bash
cp .env.example .env
make dev-up
make dev-smoke
```

This starts and verifies the Reef-only profile. For Arena-owned work, use the
explicit overlay:

```bash
make dev-up-arena
make dev-smoke-arena
```

Common local commands:

```bash
make test
make test-go
make test-simulator
make test-platform-runtime
make test-reef-core
make test-arena-control-plane
make check-reef-arena-boundaries
make check-proto-additive
make dev-reset
make dev-stress
make dev-stress-runtime-nodb
make dev-throughput-campaign
make dev-admin CMD="instrument-upsert AAPL AAPL"
```

Script discoverability:

```bash
bun scripts/dev/reef-dev.mjs list
```

`make` targets remain the stable daily interface. `scripts/dev/reef-dev.mjs` groups lower-level stack, stress, and local link setup profiles behind one CLI so new automation does not need a new one-off wrapper file.

Use `JS_RUNTIME=node` when Bun is not installed:

```bash
JS_RUNTIME=node make dev-up
```

For setup and troubleshooting details, start with [`docs/ONBOARDING.md`](./docs/ONBOARDING.md) and [`docs/DEV_ENV.md`](./docs/DEV_ENV.md).

## CI And Quality Gates

Pull requests and branch pushes run:

- proto additive compatibility checks for contract safety
- Go formatting, tests, and coverage for `services/matching-engine`
- Go formatting, tests, and coverage for `services/simulator`
- Kotlin runtime tests with Jacoco coverage for `services/platform-runtime`
- Node 22 coverage for repository dev-tooling tests under `scripts/dev`
- deterministic replay validation for the golden persona session
- container image build checks for `platform-runtime` and `matching-engine`
- Go vulnerability scans for Go services
- matching-engine benchmark guardrails
- platform-runtime performance guardrails
- Postgres schema placement and migration integration checks
- Bot SDK typecheck/qualification, hosted container-isolation, and Arena admin app checks
- Reef-only artifact/route/Compose checks plus the optional Arena control-plane build and schema gate

Bot-submission branches also run manifest validation and container-isolated bot
qualification. Fork submissions now enter a persisted `pending_invite_review`
state; a trusted base-branch workflow binds maintainer identity and the exact
head SHA before provisioning. The path is still invite-only and has not yet
completed its named external-account E2E proof, so open/self-service submission
must not be advertised. See
[`docs/BOT_ARENA_RELEASE_READINESS.md`](./docs/BOT_ARENA_RELEASE_READINESS.md)
for the verified release matrix and blockers.

Coverage reports are uploaded as GitHub Actions artifacts and summarized in the workflow run. CI enforces hard per-module coverage minimums rather than one repository-wide percentage: `services/matching-engine` must stay at or above 76% (via `scripts/ci/go-coverage.sh`), `services/simulator` must stay at or above 71% (same script), and `services/platform-runtime` enforces a 58% instruction-coverage minimum through Gradle's `jacocoTestCoverageVerification` (`violationRules` block in `build.gradle.kts`).

## Throughput Stress

The [`Throughput Stress`](https://github.com/dills122/reef/actions/workflows/throughput-stress.yml) workflow can be run manually and also runs on Monday, Wednesday, and Friday. It performs two 90-second iterations for:

- the no-persistence runtime hot path (`make dev-up-runtime-nodb` plus `make dev-stress-runtime-nodb`)
- the default db-backed runtime path (`make dev-up` plus `make dev-stress`)

Each run uploads the raw stress reports, telemetry, KPI markdown, recommendation JSON, and db diagnostics when enabled. The README badge reports whether the scheduled/manual throughput gate is healthy; the current measured throughput number lives in the latest workflow summary and artifacts so the repo does not churn commits three times per week just to update a badge value.

Manual examples:

```bash
make dev-stress-runtime-nodb
make dev-stress
```

To tune a manual GitHub Actions run, use the workflow inputs for duration, target rates, and whether to include the db-backed profile.

## Canonical Docs

Read these before changing architecture, behavior, contracts, or delivery policy:

- [`REEF_PROJECT_OVERVIEW.md`](./REEF_PROJECT_OVERVIEW.md)
- [`REEF_TECHNICAL_DESIGN.md`](./REEF_TECHNICAL_DESIGN.md)
- [`docs/steering/README.md`](./docs/steering/README.md)
- [`docs/steering/repository-scope-and-priorities.md`](./docs/steering/repository-scope-and-priorities.md)
- [`docs/steering/architecture.md`](./docs/steering/architecture.md)
- [`docs/steering/repository.md`](./docs/steering/repository.md)
- [`docs/PERFORMANCE_LEARNINGS.md`](./docs/PERFORMANCE_LEARNINGS.md)
- [`docs/ENGINEERING_DELIVERY_POLICY.md`](./docs/ENGINEERING_DELIVERY_POLICY.md)
- [`docs/DECISIONS.md`](./docs/DECISIONS.md)

Surface-specific steering:

- [`docs/steering/go.md`](./docs/steering/go.md)
- [`docs/steering/kotlin.md`](./docs/steering/kotlin.md)
- [`docs/steering/astro.md`](./docs/steering/astro.md)
- [`docs/steering/data-platform.md`](./docs/steering/data-platform.md)
- [`docs/steering/inter-service-communication.md`](./docs/steering/inter-service-communication.md)
- [`docs/steering/external-api-boundary.md`](./docs/steering/external-api-boundary.md)

## Current Development Focus

The near-term execution ladder is tracked in [`docs/CURRENT_STATUS.md`](./docs/CURRENT_STATUS.md) and [`docs/WORK_PLAN.md`](./docs/WORK_PLAN.md). Reef/Arena separation is promoted and recorded in [`docs/REEF_BOT_ARENA_SEPARATION_PROMOTION.md`](./docs/REEF_BOT_ARENA_SEPARATION_PROMOTION.md). The active Arena milestone is the invite-preview campaign in [`docs/BOT_ARENA_INVITE_PREVIEW_SPRINT.md`](./docs/BOT_ARENA_INVITE_PREVIEW_SPRINT.md); release gates remain in [`docs/BOT_ARENA_RELEASE_READINESS.md`](./docs/BOT_ARENA_RELEASE_READINESS.md). At a high level:

1. Keep validating hot-ingress paths with durable command-log, direct stream, and explicit partition semantics.
2. Preserve deterministic lane assignment for matching-sensitive submit/cancel/modify commands.
3. Resume venue-core scaling only through the bounded-working-set and compact-canonical-storage gates; the current verified ceiling remains `10k commands/sec`.
4. Reduce projection write amplification while preserving the separate `5k/60s` full-projection freshness evidence.
5. Harden post-trade lifecycle and exception evidence without mutating matching history.
6. Complete the named external-account fork E2E, cutoff/roster policy, and recorded invite-preview campaign before advertising external submissions.

## Recommended Next Gates

Good candidates for PR gates:

- OpenAPI/API boundary contract diff once the external API spec is generated.
- Broader deterministic scenario replay tests as more golden scenarios become executable.
- Dependency review after GitHub dependency graph and Advanced Security support are available for the repository.
- License scans after dependency policy is written.

Good candidates for scheduled gates:

- Non-blocking migration compatibility audits for each schema family until release guarantees exist.
- Longer throughput sweeps with warmed caches and persisted trend artifacts.
- Replay determinism campaigns over seeded scenarios.
- Soak tests that include stream workers, projectors, and materializers.
- Database bloat/index/write-amplification diagnostics after stress runs.
- Fuzz tests with extended duration for matching and simulator config parsing.
