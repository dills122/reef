# Kotlin Runtime Stack Recommendation

## Purpose

This note records the recommended near-term stack for Reef's Kotlin runtime based on the current architecture and a targeted review of primary documentation.

## Recommendation

Use:

- **Ktor server** as the runtime framework
- **kotlinx.serialization** for request/response and event payload serialization
- **Ktor test host** for application and route testing
- **Flyway** for database migrations once Postgres is introduced
- keep persistence adapters thin and domain code framework-light

## Why This Fits Reef

This combination is the lightest path that still matches Reef's architecture goals:

- Ktor keeps the runtime thin and modular, which fits the steering and technical design
- kotlinx.serialization stays close to Kotlin data models and avoids pulling in a heavier object-mapping stack early
- Ktor's test support keeps HTTP-layer testing local and fast
- Flyway provides explicit migration discipline without forcing a larger ORM-first architecture

This supports a modular monolith cleanly and leaves room to adopt stronger inter-service contracts later.

## Recommended Shape

Use the runtime with these module boundaries:

- `api/` for Ktor routing and DTO mapping
- `application/` for command handlers and orchestration
- `domain/` for state transitions, events, and value objects
- `infrastructure/persistence/` for repositories and event storage
- `infrastructure/engine/` for the Go engine adapter
- `readmodel/` for query projections

## Serialization Recommendation

Prefer **kotlinx.serialization** over introducing Jackson right now.

Reason:

- it is first-party Kotlin tooling
- it integrates directly with Ktor content negotiation
- it keeps the runtime closer to explicit Kotlin models
- it is sufficient for Reef's current contract and event needs

Implication:

- the current handwritten JSON parsing in the runtime should be replaced soon
- request/response DTOs should become `@Serializable` types
- event payloads should use the same serialization strategy

## Testing Recommendation

Use a layered test strategy:

- pure unit tests for domain and application logic
- Ktor application tests for route behavior
- persistence integration tests once Postgres is added
- contract tests around runtime-to-engine interactions

This keeps the current fast test feedback loop while improving confidence at the runtime boundary.

## Migration Recommendation

Use **Flyway** when Postgres is introduced into the runtime.

Reason:

- explicit versioned SQL fits Reef's auditability and inspectability goals
- it avoids hiding schema evolution behind ORM generation
- it works well with Gradle-based JVM projects

## Immediate Follow-Up Changes

The next runtime cleanup should be:

1. replace manual JSON parsing with Ktor plus kotlinx.serialization
2. introduce proper runtime route modules for submit and query APIs
3. add a Postgres-ready persistence adapter boundary
4. add Flyway scaffolding when the first durable schema lands

## Research Summary

Primary sources reviewed:

- Ktor server documentation and plugin guidance: [ktor.io](https://ktor.io/)
- Kotlin serialization documentation: [kotlinlang.org](https://kotlinlang.org/docs/serialization.html)
- Flyway documentation: [documentation.red-gate.com/fd](https://documentation.red-gate.com/fd)

## Decision

Proceed without broader framework research for now.
The design space is narrow enough that Reef should standardize on Ktor + kotlinx.serialization + Ktor testing + Flyway and move back into implementation.
