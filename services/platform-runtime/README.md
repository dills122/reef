# Platform Runtime

This service will be the Kotlin-based central platform runtime for Reef.

Responsibilities:

- expose HTTP and WebSocket APIs
- handle platform commands
- orchestrate domain workflows
- persist canonical state
- publish domain events
- build read models for UI consumption
- integrate with the matching engine
- host simulation control initially, unless extracted later

Build guidance:

- follow [`docs/steering/architecture.md`](../../docs/steering/architecture.md)
- follow [`docs/steering/kotlin.md`](../../docs/steering/kotlin.md)
- start as a modular monolith with clear bounded contexts
