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

Prefer a structure like:

```text
services/platform-runtime/
  src/main/kotlin/.../
    api/
    application/
    domain/
    infrastructure/
    readmodel/
    simulation/           # optional runtime-side control/orchestration hooks
```

Guidance:

- `domain/` contains business rules, entities, value objects, and events
- `application/` contains command handlers, workflow orchestration, and use cases
- `api/` contains HTTP or WebSocket endpoints and DTO mapping
- `infrastructure/` contains persistence, messaging, and engine adapters
- `readmodel/` contains query-side projections
- `simulation/` contains only runtime-side control/orchestration hooks when needed; simulator actors still use platform commands/APIs

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
