# Reef Go Steering

## Scope

Go is the implementation language for the matching engine and other performance-sensitive platform components if needed later.

## Role In Reef

The Go engine owns:

- hidden order books
- order acceptance from the runtime
- cancel and modify handling
- matching logic
- execution generation
- session-state behavior relevant to matching

It does not own broader platform workflow concerns such as allocations, confirmation workflows, or settlement lifecycle logic.

## Design Rules

### Keep the engine narrow

The engine should focus on deterministic matching behavior and engine-local state.
Do not pull post-trade workflow logic into the engine.

### Prefer explicit domain types

Avoid stringly typed domain state when stable enums or typed values are available.
Order side, time-in-force, instrument ID, quantity, and price should have clear types.

### Separate transport from core logic

HTTP, gRPC, NATS, or protobuf adapters should sit outside the matching core.
The order book and matching rules should be testable without transport setup.

### Determinism over clever concurrency

Do not introduce concurrency inside the core matching path unless it is clearly needed and proven safe.
For a single book or partition, serial command processing is preferable to hard-to-reason-about locking.

## Code Organization

Prefer a structure like:

```text
services/matching-engine/
  cmd/
  internal/
    app/
    domain/
    transport/
    storage/
```

Guidance:

- `domain/` contains matching rules, books, and engine state transitions
- `app/` contains command handlers and orchestration
- `transport/` contains HTTP or gRPC adapters
- `storage/` contains persistence or snapshot details if needed

Use `internal/` by default unless code must be imported externally.

## Modeling Guidance

- use integers or fixed-point representations for money and quantity where precision matters
- do not use floating point for price or size in durable domain logic
- keep timestamps explicit and injectable for tests
- prefer immutable event payloads even if internal state is updated mutably
- generate IDs outside the deepest domain layer when possible

## Error Handling

- return explicit errors
- wrap errors with context
- do not panic for expected business failures
- distinguish validation failures from infrastructure failures

Rejected orders should produce structured outcomes, not only generic errors.

## Testing Guidance

Prioritize:

- order book state transition tests
- matching rule tests
- cancel/modify behavior tests
- deterministic replay tests
- transport contract tests against the Kotlin runtime contract

Tests should assert exact execution outcomes, not just that "something matched."

## Performance Guidance

- optimize after correctness and inspectability are established
- benchmark representative matching scenarios before redesigning data structures
- keep allocations and object churn visible, but do not sacrifice clarity too early

## Style Preferences

- keep packages small and cohesive
- prefer constructors over partially initialized structs
- export only what is required
- keep public API names domain-oriented, not transport-oriented
- use comments to explain non-obvious matching rules, not obvious code
