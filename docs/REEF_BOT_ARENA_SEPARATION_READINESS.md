# Reef And Bot Arena Separation Readiness

## Purpose

This is the implementation baseline for
[`REEF_BOT_ARENA_SEPARATION_SPRINT.md`](./REEF_BOT_ARENA_SEPARATION_SPRINT.md).
It records the observed seams on `master` after the 2026-07-18 documentation
alignment, before extraction work starts. It is an inventory and execution
checklist, not a new architectural decision.

## Review Result

The active product, architecture, status, decision, work-plan, release, and
invite-preview documents agree on the following order:

1. Reef is independently buildable and deployable.
2. Arena is restored only through an Arena-owned artifact and explicit overlay.
3. Invite-preview implementation begins only after the separation evidence is
   accepted.

This order is correct. It preserves the current deterministic venue contract:
Arena commands remain normal Reef commands, canonical Reef facts remain the
source for Arena scoring, and the matching-engine artifact must not vary by
profile.

## Observed Baseline

| Seam | Current state | Required destination |
| --- | --- | --- |
| Runtime artifact | `services/platform-runtime` is one Gradle project and compiles Arena application, API, persistence, and tests. | Reef-only runtime project/artifact plus Arena-owned project/artifact with one-way dependency. |
| Arena domain | `application/arena/` contains registry, lifecycle, risk, OpenBao, and Postgres implementations. | Arena control-plane artifact. Reef retains only product-neutral extension ports. |
| API routes | `PlatformHttpServer`, `PlatformAdminDataRoutes`, `PlatformApi`, and `ArenaAdminGateway` register `/api/v1/arena/leaderboard` and Arena admin routes. | Arena gateway/artifact only; routes absent, not disabled, from Reef-only route inventory. |
| Boundary integration | `ExternalApiBoundary` directly imports `ArenaBotVersionRiskCheck` and `PostgresArenaBotRegistryStore`. | Generic Reef admission/risk port; an Arena-owned adapter implements it. |
| Bootstrap | `PlatformHttpServerBootstrap` and `PlatformHttpServer` construct the Arena registry/gateway. | Reef bootstrap has no Arena construction; Arena launcher composes its own adapters. |
| Persistence | `PostgresArenaBotRegistryStore` and Arena schema references are compiled with the runtime. | Arena-owned migrations and datasource lifecycle, independently initialized. |
| Compose | `compose.base.yml` passes `ARENA_POSTGRES_*` and waits on `arena-postgres`; `compose.local.yml` creates the service and volume. | Reef-only base contains neither; an explicit Arena overlay supplies them. |
| Build/test | `make test` includes `test-bot-sdk`; no named Reef-only build, test, startup, or smoke target exists. | `build-reef-core`, `test-reef-core`, Reef-only dev/smoke/reset targets that do not install or execute Arena tooling. |
| Scenario assets | General scenario assets and `packages/scenario-definitions/arena/` already coexist, but core assets are not named as a separate `core/` package. | Preserve core/arena ownership and add guardrails preventing general scenarios from importing Arena modes or policies. |

## Confirmed Scope

The following current locations are in the initial extraction inventory:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/arena/`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/ArenaAdminGateway.kt`
- Arena-specific portions of `PlatformHttpServer.kt`,
  `PlatformAdminDataRoutes.kt`, `PlatformApi.kt`, and
  `PlatformHttpServerBootstrap.kt`
- Direct Arena dependencies in `ExternalApiBoundary.kt` and the admin/CLI
  adapters
- Arena-specific runtime tests, persistence schema tests, and test fixtures
- `compose.base.yml`, `compose.local.yml`, `scripts/dev/db/migrate.mjs`, and
  local-stack scripts that assume `arena-postgres`
- `packages/bot-sdk/`, `apps/arena-admin/`, and
  `packages/scenario-definitions/arena/` as Arena-owned consumers

The Go matching engine has no observed Arena production import. The simulator
has one Arena admin-token compatibility fallback; classify it during extraction
and remove it from the Reef-only path. No target calls for moving matching,
venue facts, settlement, replay, general scenario execution, or public Reef
command semantics.

## Contract Lock Before File Movement

Create and test a Reef-owned, Arena-neutral contract before moving callers.
The contract should express an optional actor/account admission decision using
Reef identifiers and a stable rejection taxonomy. It must not contain
`Arena`, bot-version, leaderboard, roster, scoring, invite, or OpenBao terms.

Decide and document these details in the first implementation PR:

- whether the absent adapter means allow, reject, or an explicitly configured
  policy; Reef must not silently instantiate an Arena default
- caller identity, correlation/idempotency context, and response taxonomy
- whether the first adapter is local composition or a versioned gRPC client
- the public/admin/protobuf contracts that the Arena artifact may consume

## Implementation Slices And Gates

1. **Guardrails first**
   - Add forbidden-import/dependency tests for Reef runtime, matching engine,
     and core scenarios.
   - Add a route-inventory test and artifact-content test that initially fail
     against the current shape.
   - Capture current route, migration, Compose, and artifact inventories as
     comparison fixtures.

2. **Extract ownership behind the contract**
   - Move Arena application, registry, persistence, OpenBao orchestration, and
     routes into an Arena-owned Gradle project/artifact.
   - Make the Arena launcher compose Reef with the optional adapter; keep the
     Reef runtime unaware of that implementation.
   - Move or replace Arena-coupled runtime tests so `test-reef-core` cannot
     compile them.

3. **Split persistence and profiles**
   - Make Reef migration/startup work when Arena migration files and database
     configuration are unavailable.
   - Make the Arena overlay own `arena-postgres`, migration, health, reset,
     backup, and restore behavior.
   - Render and assert separate base and overlay Compose inventories.

4. **Prove unchanged venue behavior**
   - Run the same seeded Reef fixture under Reef-only and Arena-idle profiles.
   - Compare canonical command outcomes, executions, replay checksums, and
     settlement facts; compare matching-engine image digests.
   - Demonstrate Arena database/process loss leaves an already-ready Reef
     venue ready and operable.

## Evidence Matrix

| Check | Reef-only expectation | Arena-enabled expectation |
| --- | --- | --- |
| Build/test | No Arena classpath, Node/Bun install, or Arena test execution. | Arena artifact and its tests run on top of the same Reef build. |
| Routes | No `/api/v1/arena/*` or Arena `/admin/v1/*` routes. | Existing Arena routes are available through the Arena gateway. |
| Persistence | No Arena credentials, database, migrations, health checks, or volumes. | Arena database/migrations initialize independently. |
| Core smoke | Submit, cancel, modify, match, status, market data, replay, settlement, scenario. | Same Reef smoke plus Arena registry/admission/run/leaderboard gates. |
| Determinism | Canonical-fact baseline recorded. | Idle Arena produces byte/equivalence-level approved comparison to baseline. |
| Failure isolation | Not applicable. | Arena process/database loss does not affect Reef readiness or venue operation. |

## Exit Criteria For Starting Invite Preview

Start invite-preview implementation only after the same commit provides the
evidence matrix above, no forbidden Reef-to-Arena dependency remains, base
Compose is Reef-only, and the reviewer accepts any explicit contract changes.
Do not fold admission workflow, scoring policy, economic tuning, SDK release,
or control-plane redesign into the separation PRs.
