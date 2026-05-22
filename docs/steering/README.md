# Reef Steering Index

These documents define how Reef should be built as the repository grows into the architecture described in the project overview and technical design.

## Documents

- [Architecture](./architecture.md): service boundaries, repo shape, contracts, persistence, and cross-cutting rules
- [Inter-Service Communication](./inter-service-communication.md): service transport, protobuf/gRPC direction, metadata/idempotency standards
- [External API Boundary](./external-api-boundary.md): user-facing API architecture, versioning, auth/idempotency/rate-limit standards
- [Repository](./repository.md): top-level folder conventions, naming, and documentation expectations
- [Go](./go.md): matching engine and systems-side coding guidance
- [Kotlin](./kotlin.md): platform runtime and simulator guidance
- [Angular](./angular.md): operational UI guidance
- [Astro](./astro.md): docs and marketing site guidance
- [Post-Match Standards](../POST_MATCH_STANDARDS.md): normative post-match roles, calendar/time config, observability, exception taxonomy, and ledger standards

## How To Use These

When adding a new module or service:

1. start with the architecture steering
2. follow the relevant language/framework steering
3. preserve domain boundaries even if implementation starts in a simplified form

If a local optimization conflicts with the steering, prefer the steering unless there is a documented reason to deviate.
