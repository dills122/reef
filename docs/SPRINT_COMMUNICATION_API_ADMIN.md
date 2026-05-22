# Reef Sprint: Communication, External API Boundary, Admin CLI

## Purpose

Define and execute a contained implementation sprint that introduces:
1. service communication hardening direction (gRPC/proto path)
2. external user-facing API boundary foundation
3. admin layer using CLI-first adapters with reusable application logic

Related standards:
- [`docs/POST_MATCH_STANDARDS.md`](./POST_MATCH_STANDARDS.md)

## Sprint Scope

In scope:
- communication-layer implementation kickoff (transport abstraction + gRPC scaffolding)
- `/api/v1` boundary module scaffolding with idempotency + auth/rate-limit hooks
- admin application layer + CLI adapter skeleton
- baseline policy implementation and documentation for:
  - idempotency key scope and storage TTL classes
  - event schema evolution governance (additive-first + deprecation path)
  - injectable clock usage policy for deterministic workflows
  - manual override governance skeleton (reason codes + audit markers)
  - baseline stage SLO target definitions for observability
- tests and docs for the above

Out of scope:
- full production-grade auth provider integration
- full distributed deployment/orchestration
- complete admin HTTP API surface

## Sprint Outcomes

By end of sprint, Reef should have:
- explicit runtime transport selection path (`http|grpc`) with gRPC scaffold in place
- versioned external boundary routes and middleware hooks
- admin use-case modules callable from CLI without HTTP coupling
- audit/trace/idempotency standards enforced in new modules

## Workstreams

### Workstream A: Inter-Service Communication Foundation

Goal:
start migration from ad hoc HTTP/JSON internals to protobuf/gRPC-ready communication.

Tasks:
1. Extend protobuf contracts for submit/modify/cancel parity.
2. Add matching-engine gRPC server scaffold (parallel with existing HTTP adapter).
3. Add runtime gRPC client scaffold behind transport flag.
4. Keep HTTP adapter as fallback path.
5. Add contract parity tests across transports.

Exit criteria:
- runtime can be configured to call engine via either HTTP or gRPC
- command result parity validated in tests

### Workstream B: External API Boundary Foundation

Goal:
establish stable user-facing boundary architecture under `/api/v1`.

Tasks:
1. Add versioned boundary route namespace.
2. Add auth-token/API-key validation hook interface (stubbed implementation).
3. Add idempotency-key extraction/validation middleware for writes.
4. Add rate-limit hook interface and local default implementation.
5. Standardize boundary error envelope (code/message/correlationId).
6. Define and enforce idempotency key scope model (`clientId + route + idempotencyKey`) with retention policy hooks.

Exit criteria:
- write endpoints under `/api/v1` enforce idempotency-key requirement
- auth and rate-limit hooks execute consistently
- responses follow standard error envelope
- idempotency scope and retention behavior are documented and test-covered

### Workstream C: Admin Layer (CLI First)

Goal:
deliver reusable admin application services with CLI adapter first.

Tasks:
1. Create `application/admin` command handlers and DTOs.
2. Add core admin commands:
   - reference data upsert/list
   - calendar profile management (default U.S. holiday profile + configurable country profiles)
   - role and permission administration for core participant personas
   - simulation controls (start/stop/pause hooks)
   - trace/event inspection commands
   - override reason code management (for governed manual interventions)
3. Add CLI adapter module (invokes admin application layer).
4. Ensure all admin commands emit audit events with actor metadata.

Exit criteria:
- admin operations available via CLI
- no admin business logic embedded in CLI parsing layer
- admin command audit events are persisted
- calendar configuration and role/permission configuration are executable admin paths
- override-governance seed model is executable and audited

### Workstream D: Cross-Cutting Validation and Docs

Goal:
lock quality and adoption guidance for new architecture seams.

Tasks:
1. Add integration tests covering:
   - transport fallback
   - boundary idempotency
   - admin CLI command flow
2. Add observability assertions:
   - trace/correlation propagation
   - structured logs include operation metadata
3. Add contract governance checks:
   - additive-first schema evolution guardrails for protobuf contracts
4. Add determinism guardrails:
   - document injectable clock requirement in workflow modules
5. Define baseline stage SLO targets for current and near-term post-match stages.
6. Update README/runbooks with new startup flags and API usage patterns.

Exit criteria:
- CI covers new paths
- docs support local execution and testing
- schema-governance and determinism policies are explicitly documented for implementation

## Delivery Sequence (Recommended)

1. Workstream A (transport foundation)
2. Workstream B (public boundary)
3. Workstream C (admin CLI)
4. Workstream D (hardening/documentation)

## Risks and Mitigations

Risk:
transport migration introduces behavior drift between HTTP and gRPC.

Mitigation:
run parity contract tests on same fixtures and compare structured outcomes.

Risk:
boundary concerns leak into domain modules.

Mitigation:
enforce adapter/application separation in module layout and code review.

Risk:
admin logic becomes CLI-specific and non-reusable.

Mitigation:
CLI only maps input/output; command logic stays in `application/admin`.
