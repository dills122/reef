# Sprint Plan: Critical Correctness and Boundary Hardening

Status: complete (June 2026) — historical record; all tasks and checkpoints below are done. See [`WORK_PLAN.md`](./WORK_PLAN.md#historical-planning-documents) for the current active execution ladder.

## Purpose

This sprint hardens the current venue slice before broader simulator control
room or post-match expansion. It turns the application-quality audit into
small, verifiable work that protects command correctness, replay determinism,
API trust boundaries, authorization, and audit persistence.

Primary findings source:
- [`docs/research/CODE_QUALITY_PERFORMANCE_FINDINGS_2026-06-05.md`](./research/CODE_QUALITY_PERFORMANCE_FINDINGS_2026-06-05.md)

## Sprint Goal

Make the existing submit/cancel/modify venue path safe enough to act as the
foundation for deterministic scenarios, control-room workflows, and post-match
modules.

## In Scope

- atomic command and idempotency handling
- deterministic clock and replay identity foundations
- strict `/api/v1` validation and legacy mutation route gating
- order actor authorization and reference-data integrity checks
- engine transport failure semantics and gRPC deadlines
- proto metadata parity for trace and causation fields
- event schema decision and drift-prevention tests
- simulator tests in the default verification gate

## Out Of Scope

- full post-match engines
- simulator control-room UI
- broad file-splitting refactors
- async batched persistence
- NATS or outbox relay implementation beyond the event-schema decision
- physical database split

## Task List

### Task 1: Strict API Boundary Validation

Description:
Make `/api/v1` command parsing fail at the boundary for malformed JSON,
missing required fields, and unknown fields instead of letting malformed input
become empty commands or business rejections.

Acceptance criteria:
- [x] `/api/v1/orders/submit`, `/cancel`, and `/modify` return structured `400`
      errors for malformed JSON.
- [x] Required command fields are validated before application service calls.
- [x] Unknown fields are rejected for versioned public command DTOs.
- [x] Legacy non-versioned mutation routes are documented as internal until they
      are gated in Task 3.

Verification:
- [x] `./gradlew test --tests com.reef.platform.api.PlatformHttpServerBoundaryTest`
- [x] `./gradlew test --tests com.reef.platform.api.PlatformApiTest`

Dependencies:
None.

Files likely touched:
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/JsonCodec.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformCommandParsers.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServer.kt`
- `services/platform-runtime/src/test/kotlin/com/reef/platform/api/*`

Estimated scope:
Medium.

### Task 2: Atomic Command Reservation

Description:
Add a command reservation step before engine side effects so duplicate
command IDs or idempotency keys cannot race through the engine path.

Acceptance criteria:
- [x] A command can be reserved as `RECEIVED` or `PROCESSING` before engine
      invocation.
- [x] First completed outcome is immutable for a command ID.
- [x] Duplicate in-flight command attempts return a deterministic in-progress
      or conflict response.
- [x] Duplicate completed command attempts return the canonical first result.

Verification:
- [x] Runtime unit tests for duplicate submit/cancel/modify.
- [x] Postgres integration test for concurrent duplicate idempotency key.
- [x] `./gradlew test`
- [x] `make test`

Dependencies:
Task 1 recommended, but not strictly required.

Files likely touched:
- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/OrderApplicationService.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/*`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/*`
- `scripts/dev/db/migrations/command_log/*` or `runtime/*`

Estimated scope:
Large; split internally if needed.

### Task 3: Legacy Mutation Route Gate

Description:
Prevent non-versioned mutation routes from bypassing `/api/v1` controls except
when an explicit local/internal flag is enabled.

Acceptance criteria:
- [x] `/orders/submit`, `/orders/cancel`, `/orders/modify`, and reference-data
      POST routes are disabled by default or explicitly marked local/internal.
- [x] Smoke and simulator paths use `/api/v1` for command mutations.
- [x] Internal route behavior is covered by tests and documented.

Verification:
- [x] `make test-platform-runtime`
- [x] `go test ./...` in `services/simulator`
- [x] `make dev-smoke` when local stack is available

Dependencies:
Task 1.

Estimated scope:
Medium.

### Task 4: Deterministic Clock Foundation

Description:
Introduce explicit clock policy for engine, runtime, and simulator replay so
scenario runs can assert stable event timelines.

Acceptance criteria:
- [x] Matching engine can use an injected clock or command timestamp policy.
- [x] Simulator uses scenario run metadata for session identity when supplied.
- [x] Replay mode can produce stable command timestamps.
- [x] At least one golden scenario asserts ordered event timestamps.

Verification:
- [x] `go test ./...` in `services/matching-engine`
- [x] `go test ./...` in `services/simulator`
- [x] Fast replay-contract test for `P1_GOLDEN_HIDDEN_CROSS_T1`

Dependencies:
Task 1 recommended for strict scenario assertions.

Estimated scope:
Medium.

### Task 5: Order Authorization And Reference Integrity

Description:
Enforce actor/client permission checks on order commands and validate that an
order account belongs to the submitted participant.

Acceptance criteria:
- [x] Submit/cancel/modify enforce role-aware authorization.
- [x] Simulator actors can be seeded with the required roles.
- [x] Account-participant mismatch rejects before engine invocation.
- [x] Admin and reference-data flows remain usable.

Verification:
- [x] Authorization unit tests.
- [x] Account ownership validation tests.
- [x] `make test-platform-runtime`

Notes:
- This task now enforces command actor authorization from persisted
  role/binding data (`order.submit`, `order.cancel`, `order.modify`).
- A durable client-to-actor grant model is still not present; API client
  authentication remains delegated to the boundary auth hook.

Dependencies:
Task 1.

Estimated scope:
Medium.

### Task 6: Transport Failure And gRPC Deadline Hardening

Description:
Separate engine infrastructure failures from domain rejections and bound gRPC
call duration.

Acceptance criteria:
- [x] Engine transport failures surface as retryable runtime/API errors, not
      cached business rejections.
- [x] gRPC calls use explicit configurable deadlines.
- [x] Invalid or unspecified proto enum values are rejected by the engine
      transport.
- [x] gRPC channel lifecycle is explicit.

Verification:
- [x] HTTP engine-down test.
- [x] Hanging gRPC server deadline test.
- [x] HTTP/gRPC parity tests.

Dependencies:
Task 2 preferred before changing retry behavior.

Estimated scope:
Medium.

### Task 7: Proto Metadata Parity

Description:
Add trace and causation metadata to protobuf contracts and keep HTTP/gRPC
command semantics aligned.

Acceptance criteria:
- [x] `CommandMetadata` carries trace ID and causation ID additively.
- [x] Kotlin and Go generated sources are updated.
- [x] HTTP and gRPC parity tests assert metadata propagation.
- [x] Proto README reflects current generated-code workflow.

Verification:
- [x] `make check-proto-additive` with a real base ref.
- [x] `make test`

Dependencies:
Task 6 can be done in parallel if contract shape is agreed first.

Estimated scope:
Medium.

### Task 8: Event Schema Decision And Drift Guard

Description:
Lock the canonical runtime event schema direction and add tests that prevent
further drift between migrations, bootstrap validation, and write models.

Acceptance criteria:
- [x] Decision recorded for typed event IDs/timestamps, actor ID, payload JSON,
      and outbox timing.
- [x] Schema validation checks column types for critical runtime event fields.
- [x] Runtime write model no longer silently loses actor/payload data.
- [x] Follow-up outbox implementation scope is explicit if not completed here.

Verification:
- [x] Postgres schema integration test for column types.
- [x] Runtime event write/read test with actor and payload.

Dependencies:
None, but should coordinate with Task 2 persistence work.

Estimated scope:
Medium to Large.

### Task 9: Simulator And Governance Gates

Description:
Make simulator tests part of normal verification and reduce false confidence in
contract governance.

Acceptance criteria:
- [x] `make test` includes simulator tests.
- [x] CI has a simulator test job.
- [x] Proto additive check behavior is explicit when the base ref is missing.
- [x] Long soaks remain separate from fast gates.

Verification:
- [x] `make test`
- [x] CI workflow review

Dependencies:
None.

Estimated scope:
Small.

## Checkpoints

### Checkpoint 1: Boundary And Gate Baseline

After Tasks 1 and 9:
- [x] malformed public API commands fail before domain logic
- [x] simulator tests are in default verification
- [x] `make test` passes

### Checkpoint 2: Command Correctness

After Tasks 2, 5, and 6:
- [x] duplicate commands are race-safe
- [x] order commands enforce command actor authorization
- [x] engine outages are retryable infrastructure failures
- [x] `make test` passes

### Checkpoint 3: Replay And Audit Foundation

After Tasks 4, 7, and 8:
- [x] scenario replay has deterministic clock behavior
- [x] HTTP/gRPC metadata parity is test-covered
- [x] event schema drift is blocked by tests
- [x] `make test` and relevant replay checks pass

## Risks And Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Atomic idempotency grows too large | High | Split into reservation API, in-memory behavior, Postgres behavior, and boundary behavior. |
| Strict validation breaks simulator scripts | Medium | Migrate simulator defaults to `/api/v1` in the same checkpoint. |
| Event schema repair expands into full outbox delivery | Medium | Record the schema decision first; outbox relay can remain a follow-up. |
| Authorization blocks local development | Medium | Seed explicit local actors/roles and keep dev mode documented. |
| Transport failure changes alter existing tests | Medium | Update tests to distinguish business rejection from infrastructure failure. |

## Open Questions

- Should legacy non-versioned mutation routes default to disabled immediately,
  or stay enabled behind a clearly named local-only flag for one sprint?
- Should command reservation live in `command_log.commands`, `runtime.submit_results`,
  or a new runtime command table?
- Should the full outbox write path land in this sprint, or should this sprint
  only lock the typed event schema and add drift guards?
