# Matching Engine

This service will be the Go-based matching and execution engine for Reef.

Responsibilities:

- maintain hidden order books
- accept submit, cancel, and modify requests
- apply matching rules
- produce execution outcomes
- expose engine-local state needed for matching behavior

Build guidance:

- follow [`docs/steering/architecture.md`](../../docs/steering/architecture.md)
- follow [`docs/steering/go.md`](../../docs/steering/go.md)
- keep transport adapters thin and matching logic deterministic
