# Reef And Bot Arena Separation Sprint

## Purpose

Make Reef a first-class standalone product at build, test, runtime, and
deployment boundaries before implementing invite-only Bot Arena intake.

This sprint was implemented and promoted on 2026-07-19. The recorded evidence
and repeatable comparison are in
[`REEF_BOT_ARENA_SEPARATION_PROMOTION.md`](./REEF_BOT_ARENA_SEPARATION_PROMOTION.md).
This document remains the implementation contract and design record.

The observed pre-sprint implementation inventory is recorded in
[`REEF_BOT_ARENA_SEPARATION_READINESS.md`](./REEF_BOT_ARENA_SEPARATION_READINESS.md).

The sprint closes the gap between the intended product relationship and the
current packaging:

- Reef owns the venue, matching, market data, post-trade, settlement, replay,
  audit, and general simulation platform.
- Bot Arena is an optional downstream game that consumes Reef contracts.
- Bot Arena may require Reef to run games. Reef must never require Bot Arena to
  build, start, test, or provide its supported standalone behavior.

## Sprint Length And Outcome

Plan for **10 working days**.

Sprint outcome:

> A Reef-only checkout/build target produces and starts a Reef runtime with no
> Arena classes, routes, database, environment variables, migrations, SDK,
> runner, UI, or control-plane process. An explicit Arena overlay restores the
> complete game stack through versioned Reef contracts without changing Reef
> venue semantics or the matching-engine artifact.

This sprint completed with Reef-only and Arena-enabled evidence recorded from
the same promotion commit; see the promotion record above.

## Locked Separation Decisions

- The repository remains a monorepo.
- Separation is enforced by build artifacts, dependency direction, route
  ownership, database ownership, and deployment composition—not by naming or
  feature flags alone.
- `services/platform-runtime` becomes Reef-only application code.
- Arena registry, ownership/config orchestration, leaderboard, admission,
  run-result ingestion, and Arena HTTP routes belong to an Arena-owned module
  or artifact outside the Reef runtime artifact.
- A separate network service is not required merely to complete this sprint.
  The module/artifact seam must allow that extraction later without moving
  domain behavior again.
- Reef core may expose generic extension contracts such as actor admission or
  account-risk checks. Those contracts cannot mention Arena types or statuses.
- Arena may implement or call those generic contracts from an Arena-owned
  adapter. Reef cannot import an Arena implementation.
- `/api/v1/arena/*` and Arena-specific `/admin/v1/*` routes are absent from the
  Reef-only application. Disabled routes returning `503` do not count as
  separation.
- General scenario execution stays in Reef. Arena modes, competitors, house
  actors, game economics, scoring, and admission windows stay Arena-owned.
- `make test` may remain a repository-wide aggregate. A separate Reef-only
  build and test gate must exist and must not install or execute Arena tooling.
- Artifact exclusion, not JVM tree shaking, is the release mechanism.
- The matching-engine source, binary, image, and behavior are identical between
  Reef-only and Arena-enabled deployments for the same commit.
- Existing public contracts and deterministic/replay semantics cannot change
  merely to make file movement easier.

## Boundary Map

| Capability | Reef owns | Arena owns | Allowed dependency |
| --- | --- | --- | --- |
| Order commands, status, matching, executions | Yes | No | Arena -> Reef public contracts |
| Market data and participant-scoped reads | Yes | No | Arena -> Reef public contracts |
| Post-trade, settlement, ledger, replay | Yes | No | Arena -> Reef public contracts |
| General scenarios, seeds, clocks, fault injection | Yes | No | Arena definitions may reference Reef scenario contracts |
| Generic actor/account admission extension | Yes | No | Reef defines; optional adapters implement |
| Bot registry, ownership, trust, invite workflow | No | Yes | Arena control plane -> generic Reef/admin contracts where needed |
| Bot config descriptors and OpenBao slices | No | Yes | Arena control plane owns orchestration; secrets remain in OpenBao |
| Game modes, roster, cutoffs, personas | No | Yes | Arena -> Reef scenario/run contracts |
| Economic and scoring policies | No | Yes | Arena interprets immutable Reef facts |
| Leaderboard and game-result publication | No | Yes | Arena reads exported Reef/run evidence |
| Bot SDK, sandbox workers, runner | No | Yes | SDK/runner -> versioned Reef bot/runtime protocol |
| Arena web application | No | Yes | UI -> Arena/public Reef gateways as explicitly documented |

## Target Dependency Direction

```text
apps/arena-admin
packages/bot-sdk
packages/scenario-definitions/arena
Arena control-plane modules, adapters, and runner
                 |
                 v
versioned Reef public/admin/protobuf contracts
                 |
                 v
Reef platform runtime -> matching engine -> canonical Reef facts
```

Forbidden edges:

- matching engine -> Arena
- Reef domain/application code -> Arena implementation
- Reef migrations or boot requirements -> Arena database
- Reef Compose base -> Arena services or volumes
- general scenario definitions -> Arena modes, personas, scoring, or economics
- Reef release artifact -> Bot SDK, Arena UI, runner, or Arena migrations

## Target Repository And Deployment Shape

The exact final directory names may be adjusted during implementation review,
but ownership must be equivalent to this shape:

```text
services/platform-runtime/                 # Reef-only runtime artifact
services/arena-control-plane/              # Arena-owned Kotlin module/artifact
services/matching-engine/                  # unchanged Reef engine
services/simulator/                        # general Reef simulator
packages/scenario-definitions/core/        # general Reef scenarios/personas
packages/scenario-definitions/arena/       # Arena-only modes/actors/policies
packages/bot-sdk/                          # Arena authoring/runtime surface
apps/arena-admin/                          # Arena web surface
compose.base.yml                           # Reef-only base
compose.arena.yml                          # explicit Arena overlay
```

If implementation keeps Arena modules in one Gradle multi-project build, the
Reef application project must not depend on them. The Arena-enabled artifact or
launcher may depend on Reef projects, never the reverse.

## Current Violations To Resolve

- Arena application and gateway classes compile into the single
  `platform-runtime` artifact.
- `PlatformHttpServer` constructs an `ArenaAdminGateway` and registers Arena
  routes even when Arena backing services are disabled.
- `ExternalApiBoundary` imports `ArenaBotVersionRiskCheck` and
  `PostgresArenaBotRegistryStore` directly.
- platform bootstrap constructs an Arena registry store in the Reef runtime.
- default Compose supplies `ARENA_POSTGRES_*`, waits for `arena-postgres`, and
  creates its service and volume.
- there is no named Reef-only build, test, start, or smoke contract.
- repository-wide tests include Bot SDK tests without an independent Reef-only
  proof.

These are packaging and ownership findings. They do not imply that the matching
engine or canonical venue behavior currently contains Arena game logic.

## Workstreams

### A. Contract And Dependency Guardrails

Deliverables:

- inventory every Arena import, route, migration, environment variable,
  database dependency, Compose service, test target, and build dependency
- define generic Reef-side extension interfaces needed by optional consumers
- add automated forbidden-import/dependency checks
- record the supported Reef-only public/admin/protobuf contract surface
- document which existing Arena calls must be adapted rather than moved into
  Reef

Acceptance:

- dependency report has no Reef application/domain -> Arena edge
- matching-engine and general simulator guards reject Arena imports
- generic extension contracts contain no `Arena*`, bot-version, leaderboard,
  scoring, roster, or invite terminology

### B. Runtime And Route Ownership

Deliverables:

- extract Arena application services, persistence adapters, gateway routes, and
  bootstrap wiring from the Reef runtime artifact
- replace direct Arena risk imports with a generic optional admission/risk seam
- move `/api/v1/arena/*` and Arena-specific admin routes to the Arena-owned
  application/gateway
- keep shared authentication or gateway utilities framework-level and
  product-neutral
- preserve versioned external behavior for the Arena-enabled deployment

Acceptance:

- Reef artifact/classpath contains no Arena implementation packages
- Reef-only route inventory contains no Arena route
- Arena-enabled contract tests prove existing route behavior or explicitly
  version any intentional change
- disabled optional admission integration fails according to a documented Reef
  policy, never through an Arena-specific default

### C. Persistence And Migration Ownership

Deliverables:

- remove Arena datasource creation and health/readiness requirements from Reef
  bootstrap
- make Arena migrations and database lifecycle Arena-owned
- keep Reef runtime, boundary, projection, and post-trade stores independent
- document backup, restore, and local reset ownership for both profiles

Acceptance:

- Reef starts and becomes ready with no Arena database address or credentials
- Reef migrations run with the Arena migration tree unavailable
- Arena overlay initializes and verifies its database independently
- stopping or corrupting Arena persistence cannot make Reef venue readiness fail

### D. Build, Compose, And Release Artifacts

Deliverables:

- `make build-reef-core` and `make test-reef-core`
- `make dev-up-reef`, `make dev-smoke-reef`, and matching teardown/reset paths
- Reef-only `compose.base.yml`
- explicit `compose.arena.yml` overlay and named Arena commands
- artifact/content check that rejects Arena classes or resources in Reef images
- image-digest comparison for the matching engine

Acceptance:

- Reef-only commands require no Arena Node/Bun dependencies
- Reef Compose config contains no Arena service, environment variable, volume,
  health dependency, or route
- Arena overlay composes cleanly on top of the exact Reef base
- full repository tests can still aggregate both products without introducing a
  build dependency from Reef to Arena

### E. Behavioral And Operational Proof

Deliverables:

- recorded Reef-only smoke covering submit, cancel, modify, match, command
  status, market data, replay, settlement, and scenario execution
- recorded Arena-enabled smoke covering registry, risk/admission integration,
  run persistence, public leaderboard, and existing positive/negative run gates
- deterministic comparison proving Reef facts are unchanged when Arena is
  enabled but idle
- deployment and rollback notes for the split
- documentation and diagrams showing both profiles

Acceptance:

- Reef-only and Arena-enabled Reef fixture runs produce identical canonical
  command outcomes, executions, replay checksums, and settlement facts
- Arena process/database loss does not interrupt an already-running Reef venue
- Arena-enabled tests continue to use Reef's normal commands and reads
- no code path enables Arena by silently changing Reef defaults

## Ten-Day Plan

### Days 1-2: Inventory And Contract Lock

- produce import, route, datasource, migration, Compose, and artifact inventory
- lock generic extension contracts and forbidden dependency edges
- write failing architecture and artifact-content tests
- agree final module names before moving files

### Days 3-4: Runtime Extraction

- extract Arena application/persistence/gateway code behind the new module seam
- replace direct Arena boundary imports with generic Reef contracts
- separate bootstrap and route registration
- preserve Arena-enabled API compatibility tests

### Days 5-6: Persistence And Compose Split

- remove Arena datasource/readiness requirements from Reef
- make Arena migration lifecycle independently runnable
- make Compose base Reef-only and add the Arena overlay
- add Reef-only and Arena-enabled Make targets

### Days 7-8: Proof And Regression

- run Reef-only unit, integration, smoke, replay, and settlement gates
- run Arena positive/negative and leaderboard gates through the overlay
- compare canonical facts and matching-engine image digests
- test Arena database/process failure while Reef remains ready

### Day 9: Hosted/Packaging Confidence

- build release-shaped Reef-only and Arena-enabled artifacts from one commit
- inspect contents and dependency reports
- perform a bounded hosted or release-equivalent deployment rehearsal
- verify rollback and profile-selection instructions

### Day 10: Review And Promotion Decision

- publish evidence matrix and remaining deviations
- update architecture, operations, onboarding, and release docs
- require a clean review before starting invite-admission implementation
- make a go/no-go decision for the invite-preview sprint

## Required Evidence Bundle

- before/after dependency graph
- Reef and Arena artifact manifests
- route inventories for both profiles
- Compose rendered service/env/volume inventories
- migration ownership inventory
- Reef-only smoke report
- Arena-enabled positive/negative smoke reports
- canonical fact/replay comparison
- matching-engine image digest comparison
- Arena-loss isolation result
- build/test commands, commit SHA, image tags, environment, and timestamps
- documented deviations with owners and follow-up gates

## Definition Of Done

- Reef builds, tests, starts, becomes ready, and passes its supported E2E smoke
  with Arena directories and infrastructure unavailable
- Reef release artifacts contain no Arena implementation classes or resources
- Reef exposes no Arena routes in its standalone profile
- Reef requires no Arena database, migrations, environment, SDK, runner, or UI
- Arena-enabled deployment depends on Reef only through documented contracts
- matching engine is identical between profiles
- deterministic Reef facts and replay evidence are identical with Arena idle
- repository dependency checks prevent the forbidden edges from returning
- active architecture/status/onboarding docs describe the real split
- invite-preview implementation remains paused until this sprint is reviewed,
  merged, executed, and passes its promotion gate

## No-Go Conditions

- Reef still imports or packages an Arena implementation
- Arena routes merely return `503` instead of being absent from Reef-only mode
- Reef readiness depends on Arena storage or process health
- the split changes public Reef semantics, deterministic ordering, audit, or
  replay output without an explicit contract decision
- the matching engine differs between deployment profiles
- the Arena overlay bypasses Reef public/admin/protobuf contracts
- passing tests requires an undocumented manual profile combination

## Review Questions Before Implementation

These are useful design-review questions, but none blocks merging this planning
document:

- final Gradle project and artifact names
- whether the first Arena-owned artifact runs in the existing JVM process or as
  a separate process; dependency rules and acceptance gates are the same
- whether generic optional actor admission is local injection or a versioned
  gRPC client in the first implementation
- whether `compose.base.yml` is renamed or simply made Reef-only
- which bounded hosted environment provides the Day 9 release-equivalent proof

Do not expand this sprint into invite admission, economic tuning, scoring
changes, public SDK publication, or a broad control-plane rewrite.
