# Reef Kotlin Steering

## Scope

Kotlin is the implementation language for the platform runtime and workflow orchestration. Simulator tooling currently lives in the Go `services/simulator` CLI; any Kotlin simulation-control code should remain a platform client/orchestrator, not a shortcut around runtime command paths.

## Role In Reef

The Kotlin runtime owns:

- HTTP and WebSocket APIs
- command handling and orchestration
- persistence of canonical platform state
- event publication
- workflow state transitions outside the matching engine
- read-model creation for UI surfaces
- integration with the Go engine
- simulation-control APIs or orchestration hooks when they belong inside the runtime boundary

## Framework Guidance

- prefer Ktor over heavier frameworks unless project needs change materially
- keep framework adapters thin
- do not let routing, serialization, or DI frameworks absorb domain logic

Framework code should wire the application together, not define business behavior.

## Module Boundaries

Actual top-level structure:

```text
services/platform-runtime/
  src/main/kotlin/com/reef/platform/
    api/
    application/
      admin/
      analytics/
      arena/
      settlement/
    domain/
    infrastructure/
    tools/
```

Guidance:

- `domain/` contains business rules, entities, value objects, and events
- `application/` contains command handlers, workflow orchestration, use cases, and query-side projections, organized into subpackages by bounded context (`admin/`, `analytics/`, `arena/`, `settlement/`, and similar); projections live alongside the domain area they read from (e.g. `application/settlement/SettlementLedgerProjection.kt`) rather than in a separate top-level read-model package
- `api/` contains HTTP or WebSocket endpoints, DTO mapping, and projection workers that bridge events into read models (e.g. `CanonicalProjectionWorker`, `OrderLifecycleProjectionWorker`)
- `infrastructure/` contains persistence, messaging, and engine adapters
- `tools/` contains standalone operational utilities (e.g. benchmarks) that are not part of the runtime's request/command path

There is no dedicated `simulation/` package today; the Go `services/simulator` CLI is the simulation surface, and it only talks to the runtime through platform commands/APIs (see Scope above). If runtime-side simulation-control hooks are ever added, they should live under a bounded-context subpackage of `application/` following the same pattern, not bypass it.

## Design Rules

### Commands and events are first-class

Model core workflows through explicit commands and domain events.
Avoid service methods that hide business transitions inside arbitrary imperative code.

### Domain code should be portable

Keep domain logic independent from SQL mappers, HTTP request models, and Ktor-specific types.

### Read and write models should stay distinct

Operational UI projections can be optimized for the screen, but they should not become the source of truth.

### Simulation is a client of the platform

Simulation code should call application commands and APIs rather than mutating repositories directly.

### API layer stays a thin router

`api/` HTTP entrypoint classes (e.g. `PlatformHttpServer`) register routes and delegate; they should not accumulate business-adjacent JSON marshaling, gateway logic, or composition-root wiring as private methods over time.

- group routes by bounded context into their own class (one per context: settlement, arena admin, risk guardrails, diagnostics, etc.), each exposing the routes it owns and a `handle(method, path, query, body): PlatformHotPathResponse?` dispatcher — see `PlatformDiagnosticRoutes.kt` / `PlatformLegacySetupRoutes.kt` for the existing shape
- name each route-module class after what it actually serves, not a generic bucket it grew into — a class named `*DiagnosticRoutes` should hold diagnostic/health endpoints, not arena/analytics/settlement business routes riding along because that's where the pattern already existed
- prefer passing a route module a handful of cohesive service/gateway objects over a long list of individual method-reference lambdas in its constructor; a growing constructor param list of single-method lambdas is the same god-class smell relocated one file over, not a fix
- keep composition-root wiring (`default*()` factory functions, env-driven DataSource bootstrap) in a dedicated bootstrap file, not appended below the HTTP server class
- extract a shared Postgres-bootstrap helper once the same `RUNTIME_DB_URL`/user/password → `DataSource` shape appears in a second store or service constructor, instead of copy-pasting the triad again

## Persistence Guidance

- Postgres is canonical
- keep migrations explicit and versioned
- prefer transactional command handling where workflow consistency requires it
- append domain events as part of meaningful state transitions

Do not hide persistence boundaries behind overly generic repositories if that erases domain meaning.

## Kotlin Style Guidance

- prefer data classes for DTOs and immutable payloads
- use sealed classes or enums for constrained state
- keep coroutines structured and explicit
- avoid large service classes that mix orchestration, validation, persistence, and projection logic
- inject clocks, ID generators, and randomness sources where determinism matters

## Testing Guidance

Prioritize:

- domain workflow tests
- command handler tests
- persistence integration tests against Postgres
- projection tests for UI read models
- simulation determinism tests
- engine integration contract tests

Tests for workflows should assert emitted events and resulting state, not only return values.

## Operational Guidance

- every command path should carry actor identity and correlation metadata
- logs should be structured and tied to workflow IDs
- background processing should remain locally runnable and easy to inspect

## Avoid

- framework-driven anemic domain models
- leaking SQL schemas directly into API contracts
- mixing simulation shortcuts into production workflow code
- untracked randomness or wall-clock dependencies in scenario execution
