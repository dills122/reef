# Code Quality Audit - 2026-07-13

## Scope

Full repository code-smell audit focused on maintainability, bad abstractions, oversized modules, duplicated schema/contract ownership, hidden boundary risk, and throughput-adjacent design debt.

Inputs checked:

- repository steering and architecture documents
- Kotlin platform runtime and persistence code
- Go matching engine and simulator code
- JavaScript development scripts and control-room tooling
- bot SDK and arena admin frontend
- largest source and test files by line count
- suspicious patterns such as broad catches, silent JSON parsing, sleeps, legacy routes, direct internal HTTP calls, app-side DDL, and script sprawl

No code behavior was changed during the audit.

## Highest-Priority Findings

### 1. Platform HTTP server is a god object

File: `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServer.kt`

`PlatformHttpServer` owns too many unrelated responsibilities:

- environment-driven runtime configuration
- HTTP server construction
- route registration
- admin gateway mapping
- legacy route gates
- stream command intake
- idempotency handling
- backpressure checks
- command status lookup
- runtime worker/projector startup
- readiness JSON construction

This makes route changes high-blast-radius and obscures the product boundary rules from `docs/API_SURFACE_POLICY.md`.

Recommended cleanup:

- split route families into small handlers: public venue API, admin gateway, diagnostics, legacy setup, command status
- move runtime loop startup into a bootstrap module that has no HTTP route logic
- move readiness payload assembly into diagnostics code
- keep `PlatformHttpServer` as adapter wiring only

### 2. App code and migrations both own schema

Files:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/settlement/SettlementFactStore.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/arena/PostgresArenaBotRegistryStore.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/admin/PostgresAdminAuthStore.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/admin/PostgresAdminIdentityStore.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/analytics/SimulationRunExportStore.kt`
- `scripts/dev/db/migrations/**`

The repo has SQL migrations, but Kotlin stores still contain large `CREATE TABLE`, `ALTER TABLE`, and `CREATE INDEX` blocks. This creates two schema authorities and long-term drift risk.

Recommended cleanup:

- treat `scripts/dev/db/migrations/**` as canonical schema source
- keep app-side bootstrap only for in-memory/noop stores and optional local bootstrap mode
- default production-like Postgres startup to validate schema, not mutate it
- add a schema drift test that compares migration requirements against `PostgresSchemaRequirements`

### 3. Settlement materializer is unbounded and N+1-shaped

File: `services/platform-runtime/src/main/kotlin/com/reef/platform/application/settlement/TradeSettlementObligationMaterializer.kt`

`materialize()` loads all trades, loads all events, then performs per-trade order and profile lookups. It filters scenario/session after data is already pulled.

Risk:

- slow scenario runs as history grows
- poor DB split readiness
- unnecessary hot-path-adjacent read amplification
- harder replay debugging because skipped-trade reasons are collapsed into one counter

Recommended cleanup:

- add a scoped persistence query for candidate trades by `scenarioRunId` and optional `venueSessionId`
- return joined buy/sell order facts with trade rows
- return structured skip counts by reason
- keep materialization idempotency, but validate only changed obligation families

### 4. Settlement fact append validates the full scenario world

File: `services/platform-runtime/src/main/kotlin/com/reef/platform/application/settlement/SettlementFactStore.kt`

`appendFacts()` reads facts across many settlement tables for the scenario, merges with new facts, then validates the full bundle in memory before inserting.

This is strong for early correctness but scales poorly and makes append cost grow with scenario age.

Recommended cleanup:

- keep full-bundle validation in tests/replay proofs
- add incremental validators for append commands
- enforce identity uniqueness and reference constraints in DB where practical
- query only parent facts referenced by the incoming append

### 5. `JsonCodec.parseObjectOrEmpty` hides bad input

File: `services/platform-runtime/src/main/kotlin/com/reef/platform/api/JsonCodec.kt`

`parseObjectOrEmpty()` catches any exception and returns an empty JSON object. It is used in multiple request-handling paths.

Risk:

- malformed client input can become "missing field" instead of "invalid JSON"
- boundary failures become less precise
- security/audit investigation sees weaker rejection evidence

Recommended cleanup:

- use strict parsing for all external/admin request bodies
- keep forgiving parse only for legacy persisted payload compatibility
- rename forgiving method to show intent, such as `parseLegacyObjectOrEmpty`
- add tests proving malformed JSON gets a clean `400 invalid json payload`

### 6. API/service layering leaks JSON and persistence DTOs

Files:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformApi.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/OrderApplicationService.kt`

`PlatformApi` returns JSON strings and imports persistence projection DTOs. `OrderApplicationService` also exposes many persistence pass-through reads.

Risk:

- application use cases are harder to reuse from CLI/gRPC/message workers
- HTTP response shape changes can leak into application code
- tests must exercise too much stack for simple response behavior

Recommended cleanup:

- introduce typed response models per route family
- keep JSON serialization at adapter edge
- split read services from command services
- move projection/read-model formatting out of `PlatformApi`

### 7. Boundary test file is too large to maintain

File: `services/platform-runtime/src/test/kotlin/com/reef/platform/api/PlatformHttpServerBoundaryTest.kt`

This single test file is more than 7,000 lines and covers OAuth, admin gateway, arena, settlement, legacy route gates, Netty hot-path routing, command status, and public API boundary cases.

Risk:

- slow review and triage
- accidental fixture coupling
- small route changes touch a giant test surface

Recommended cleanup:

- extract shared server fixtures into test support
- split tests by boundary family
- keep one thin integration smoke for cross-family routing

### 8. Development scripts are becoming applications

Files:

- `scripts/dev/arena-local-tick-run.mjs`
- `scripts/dev/stress.mjs`

`arena-local-tick-run.mjs` handles scheduling, worker lifecycle, scoring, health sampling, persistence, seeding, HTTP, and secret resolution in one script. `stress.mjs` has a large top-level environment matrix and probe logic.

Risk:

- fragile local/CI behavior
- hard-to-test profile changes
- hidden coupling to internal HTTP routes

Recommended cleanup:

- move config parsing into tested modules
- move probe clients into small reusable libraries
- keep scripts as CLI wrappers around orchestration modules
- keep internal HTTP usage inventoried and time-bounded

### 9. Arena admin page owns too much state and behavior

File: `apps/arena-admin/src/routes/admin/+page.svelte`

The admin page mixes data loading, run/result state, risk state, OpenBao config editor behavior, and UI rendering.

Recommended cleanup:

- split data/state into Svelte stores or route-level modules
- extract bot roster, run list, config editor, and risk summary components
- keep fetch functions in `src/lib/api.ts`

### 10. Matching engine is healthier, but service still carries mixed roles

Files:

- `services/matching-engine/internal/book/book.go`
- `services/matching-engine/internal/app/service.go`
- `services/matching-engine/internal/app/order_index.go`

The hot book package is focused, and `order_index.go` includes benchmark-backed sharding rationale. The main smell is `Service` mixing validation, controls, order state, rollback hooks, matching orchestration, and result assembly.

Recommended cleanup:

- split validation/control checks from mutation path
- keep book mutation and rollback protocol explicit
- preserve current deterministic behavior and benchmark evidence before any refactor

## Suggested Cleanup Order

1. Split `PlatformHttpServer` route families and runtime loop bootstrap.
2. Make migrations the single schema authority, with app-side validation.
3. Replace strict request parsing paths that currently use `parseObjectOrEmpty`.
4. Add scoped settlement materialization query and structured skip reasons.
5. Split `PlatformHttpServerBoundaryTest.kt`.
6. Extract tested modules from `arena-local-tick-run.mjs` and `stress.mjs`.
7. Split arena admin page into state modules and components.
8. Refactor matching-engine `Service` only after benchmark/behavior guardrails are in place.

## Non-Issues / Strengths

- Matching engine book structure is compact and isolated.
- `order_index.go` documents performance motivation with concrete benchmark context.
- Settlement validation is intentionally rigorous; problem is growth shape, not correctness intent.
- Bot SDK core types are mostly clear and versioned.
- Many scripts and tests already encode useful operational knowledge; extraction should preserve behavior, not rewrite wholesale.
