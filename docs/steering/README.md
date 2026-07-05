# Reef Steering Index

These documents define how Reef should be built as the repository grows into the architecture described in the project overview and technical design.

## Documents

- [Onboarding](../ONBOARDING.md): contributor ramp-up path, prerequisites, first-run, and troubleshooting
- [Current Status](../CURRENT_STATUS.md): short current implementation snapshot, active forward path, and documentation cleanup policy
- [Engineering Delivery Policy](../ENGINEERING_DELIVERY_POLICY.md): delivery mode matrix, refactor triggers, and non-negotiable feature-test requirements
- [Repository Scope And Priorities](./repository-scope-and-priorities.md): repo-level correctness, throughput, audit, replay, and safe-change priorities
- [Architecture](./architecture.md): service boundaries, repo shape, contracts, persistence, and cross-cutting rules
- [Data Platform](./data-platform.md): canonical persistence posture, datatype standards, write-path rules, retention, and data pitfalls
- [Inter-Service Communication](./inter-service-communication.md): service transport, protobuf/gRPC direction, metadata/idempotency standards
- [External API Boundary](./external-api-boundary.md): user-facing API architecture, versioning, auth/idempotency/rate-limit standards
- [Repository](./repository.md): top-level folder conventions, naming, and documentation expectations
- [Go](./go.md): matching engine and systems-side coding guidance
- [Kotlin](./kotlin.md): platform runtime and workflow orchestration guidance
- [Angular](./angular.md): operational UI guidance
- [Astro](./astro.md): docs and marketing site guidance
- [Post-Match Standards](../POST_MATCH_STANDARDS.md): normative post-match roles, calendar/time config, observability, exception taxonomy, and ledger standards
- [Performance Learnings](../PERFORMANCE_LEARNINGS.md): performance budgets, benchmark discipline, and PR checklist for speed-impact changes
- [Performance Library Investigation](../PERFORMANCE_LIBRARY_INVESTIGATION.md): runtime/Go library benchmark candidates and acceptance criteria

## How To Use These

When adding a new module or service:

1. start with repository scope and architecture steering
2. follow the relevant language/framework steering
3. preserve domain boundaries even if implementation starts in a simplified form
4. evaluate hot-path changes against correctness, replay, and throughput evidence

If a local optimization conflicts with the steering, prefer the steering unless there is a documented reason to deviate.
