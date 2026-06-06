# Code Quality and Performance Findings Dump (2026-06-05)

## Status

This is a findings dump from an ad hoc deep review of code quality,
correctness, auditability, determinism, and performance risk.

This document is not a committed backlog, roadmap, work-item list, ADR, or
delivery commitment. It preserves review context so the team can decide later
which findings should become scoped work.

## Review Scope

Reviewed areas:

- Matching engine core behavior and benchmarks.
- Platform runtime command path, API boundary, persistence, and schema setup.
- Simulator and load-tester behavior.
- Proto contracts and generated transport usage.
- Repository test, benchmark, and CI coverage.
- Steering documents and architecture expectations.

Primary reference docs:

- `REEF_PROJECT_OVERVIEW.md`
- `REEF_TECHNICAL_DESIGN.md`
- `docs/steering/README.md`
- `docs/steering/architecture.md`
- `docs/steering/repository.md`
- `docs/ENGINEERING_DELIVERY_POLICY.md`
- `docs/DECISIONS.md`
- `docs/steering/inter-service-communication.md`
- `docs/steering/external-api-boundary.md`
- `docs/steering/data-platform.md`
- `docs/API_BOUNDARY_STORAGE_DECISIONS.md`
- `docs/DATA_DOMAIN_SCHEMA_BLUEPRINT.md`
- `docs/PERFORMANCE_LEARNINGS.md`

## High-Severity Findings

### Command idempotency is not atomic around engine side effects

The runtime command path checks for an existing idempotency result before
calling the engine, then persists the result after the engine returns:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/OrderApplicationService.kt:29`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/OrderApplicationService.kt:62`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/OrderApplicationService.kt:141`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/OrderApplicationService.kt:151`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/OrderApplicationService.kt:157`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/OrderApplicationService.kt:159`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/OrderApplicationService.kt:173`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/OrderApplicationService.kt:179`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/OrderApplicationService.kt:181`

The persistence layer uses an upsert that overwrites the prior command result:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt:245`
- `scripts/dev/postgres/migrations/runtime/0003_live_runtime_persistence.sql:153`

The matching engine does not cache outcomes by command ID. Submit only reserves
by order ID:

- `services/matching-engine/internal/app/service.go:94`
- `services/matching-engine/internal/app/service.go:455`

The external idempotency path has the same shape: check before operation, save
after operation:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServer.kt:343`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServer.kt:374`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServer.kt:414`

Why this matters:

- Concurrent retries with the same command ID or idempotency key can both miss
  the cache and call the engine.
- Submit duplicates can become one accepted result and one duplicate-order
  rejection for the same logical command.
- Cancel and modify retries can produce repeated state transitions or repeated
  lifecycle events.
- This conflicts with the repository steering that command IDs are idempotency
  keys for command handlers.

Potential remediation direction:

- Introduce command reservation before engine side effects.
- Preserve the first canonical command outcome instead of overwriting it.
- Add a `RECEIVED` / `PROCESSING` / `COMPLETED` command lifecycle.
- Make duplicate in-flight commands return a stable in-progress or prior result.
- Consider command-ID idempotency at both runtime and engine boundaries.

### Deterministic replay is weakened by wall-clock time

The matching engine stamps events with wall-clock time rather than command
metadata:

- `services/matching-engine/internal/app/service.go:45`
- `services/matching-engine/internal/app/service.go:117`
- `services/matching-engine/internal/app/service.go:153`

The simulator uses wall-clock values for session IDs and command timestamps:

- `services/simulator/cmd/load-tester/main.go:221`
- `services/simulator/cmd/load-tester/main.go:605`
- `services/simulator/cmd/load-tester/main.go:606`
- `services/simulator/cmd/load-tester/main.go:1263`

Scenario definitions expect deterministic replay and stable expected events:

- `packages/scenario-definitions/scenarios/v1/P1_GOLDEN_HIDDEN_CROSS_T1.yaml:113`
- `packages/scenario-definitions/README.md:43`

Why this matters:

- The same scenario seed and same input commands cannot reliably produce
  byte-stable event timelines.
- Replay assertions are forced to be loose around timestamps and run identity.
- Audit comparison between two runs becomes harder than it needs to be.

Potential remediation direction:

- Inject a clock into the matching engine service.
- Prefer command `occurredAt` when present.
- Use a seeded simulated clock for replay/scenario execution.
- Derive session IDs from `scenarioRunId` and seed when supplied.
- Add golden replay tests that assert exact event timelines.

### Runtime event schema regresses event-backbone and audit expectations

The event backbone migration defines typed events with actor, payload, and
outbox support:

- `scripts/dev/postgres/migrations/runtime/0002_event_backbone.sql:5`
- `scripts/dev/postgres/migrations/runtime/0002_event_backbone.sql:30`

The live runtime persistence migration changes or keeps runtime fields as text
and does not write actor, payload, or outbox data:

- `scripts/dev/postgres/migrations/runtime/0003_live_runtime_persistence.sql:60`
- `scripts/dev/postgres/migrations/runtime/0003_live_runtime_persistence.sql:271`

The Kotlin bootstrap path creates similar text-heavy runtime tables:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt:83`

The runtime event model does not carry actor ID or payload:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/domain/Models.kt:121`

The schema validator checks table/function/column presence, but not column
types, constraints, or semantic compatibility:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresBootstrap.kt:124`

Why this matters:

- Event timestamps stored as text are fragile for ordering and range queries.
- Event IDs stored as text lose database-level type guarantees.
- Actor attribution is incomplete in the runtime event stream.
- Outbox integration exists structurally, but the live persistence path does not
  use it.
- Audit and data-platform expectations drift from the implementation.

Potential remediation direction:

- Decide whether `0002` or `0003` is the canonical event model.
- Move runtime event storage toward `uuid`, `timestamptz`, and typed numeric
  values where appropriate.
- Carry `actor_id` and structured payloads through `RuntimeEvent`.
- Write outbox rows in the same transaction as persisted domain events.
- Extend schema validation to verify types and constraints.

### API boundary validation is too permissive, and legacy write routes bypass it

JSON parse failures are swallowed and converted to an empty object:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/ExternalApiBoundary.kt:20`

Command parsers therefore allow malformed JSON to become empty or partial
commands:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/ExternalApiBoundary.kt:11`

The HTTP server still exposes unversioned mutation routes that bypass the
stronger `/api/v1` boundary path:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServer.kt:62`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServer.kt:114`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServer.kt:133`

The `/api/v1` path has useful boundary checks, but body parsing remains lenient:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServer.kt:287`

Why this matters:

- Invalid JSON can turn into business-level rejects instead of API-level schema
  errors.
- Reject-rate metrics and abuse controls can be polluted by malformed payloads.
- External clients and simulators can bypass public-boundary semantics through
  legacy routes.

Potential remediation direction:

- Parse JSON strictly for `/api/v1`.
- Return structured `400` validation envelopes for malformed JSON, missing
  required fields, and unknown fields where appropriate.
- Mark legacy write routes internal, dev-only, or remove them after migration.
- Add boundary tests for malformed JSON and unknown-field behavior.

### gRPC runtime client has no call deadline

The HTTP engine client has explicit connect and request timeouts:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/engine/EngineTransport.kt:22`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/engine/EngineTransport.kt:92`

The gRPC client uses `CallOptions.DEFAULT`:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/engine/EngineTransport.kt:60`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/engine/EngineTransport.kt:71`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/engine/EngineTransport.kt:83`

Why this matters:

- A stalled engine can hold platform runtime request threads indefinitely.
- Thread pool and database pool pressure can cascade under load.
- This conflicts with inter-service steering that calls should have explicit
  deadlines.

Potential remediation direction:

- Use `withDeadlineAfter` on gRPC call options.
- Make the deadline configurable through the same operational config surface as
  the HTTP engine timeout.
- Only add retries after command idempotency semantics are made atomic.

### Order command authorization is incomplete

Admin commands enforce actor-role permissions:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/AdminApplicationService.kt:183`

Order submit, cancel, and modify do not enforce actor role bindings in the
application service:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/OrderApplicationService.kt:27`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/OrderApplicationService.kt:151`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/OrderApplicationService.kt:173`

Why this matters:

- A boundary client that passes authentication and has a client ID can submit,
  cancel, or modify using arbitrary actor IDs unless another layer prevents it.
- Simulation actors should still travel through the same command/API paths, but
  those paths need a clear authorization policy.

Potential remediation direction:

- Add application-level authorization for order commands.
- Tie actor IDs to authenticated client identity and role bindings.
- Keep simulator traffic on the same authorization path, with explicit seeded
  actors and roles.

## Medium-Severity Findings

### Default in-memory runtime persistence is not thread-safe

The HTTP server runs request handling through a fixed thread pool:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServer.kt:40`

The default runtime persistence path is in-memory unless Postgres is explicitly
selected:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/OrderApplicationService.kt:340`

The in-memory implementation uses unsynchronized mutable maps and lists:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/InMemoryRuntimePersistence.kt:15`

Why this matters:

- Standalone local runtime usage can race under concurrent HTTP requests.
- Race behavior can produce inconsistent reads, duplicate writes, or collection
  corruption.
- Docker-based local development may use Postgres, but the default server mode
  is still easy to run unsafely.

Potential remediation direction:

- Make in-memory persistence synchronized or backed by concurrent structures.
- Make server mode require explicit persistence selection.
- Keep an in-memory mode for tests, but make concurrency expectations explicit.

### Hot read endpoints are unbounded and Postgres limit binding is fragile

The API exposes all orders, trades, and events through unbounded reads:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformApi.kt:94`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformApi.kt:109`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformApi.kt:113`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServer.kt:179`

The Postgres helper binds all parameters as strings, including `LIMIT ?` values:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt:857`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt:911`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt:959`

Why this matters:

- Dashboards and tailing tools can accidentally full-scan growing runtime
  tables.
- Recent trades/events queries may fail in Postgres mode or rely on implicit
  casts.
- Performance behavior will degrade as persisted runtime data grows.

Potential remediation direction:

- Require bounded pagination for hot read endpoints.
- Bind numeric limits with `setInt`.
- Add Postgres-mode integration tests for `/events?limit=` and `/trades?limit=`.

### Simulator load-tester memory model does not scale to long soaks

The load tester stores every request result:

- `services/simulator/cmd/load-tester/main.go:260`

The summary builder then constructs many latency slices by action, profile,
actor, persona, and strategy:

- `services/simulator/cmd/load-tester/main.go:831`

Why this matters:

- At documented soak-test volumes, this can become millions of rich structs and
  strings retained until the end of the run.
- The harness can distort latency and memory observations for the system under
  test.

Potential remediation direction:

- Stream counts and latency metrics during execution.
- Use HDR histograms, t-digest, or reservoir sampling.
- Make full request-result retention an opt-in trace mode.
- Write detailed samples to NDJSON when needed instead of retaining them all in
  memory.

### Simulator trace validation can hide ordering defects

Trace validation sorts events by sequence number before checking monotonicity:

- `services/simulator/cmd/load-tester/main.go:1147`

The monotonicity check uses `<`, not `<=`, so duplicate sequence numbers can
pass:

- `services/simulator/cmd/load-tester/main.go:1150`

Why this matters:

- Returned-order regressions can be hidden by the local sort.
- Duplicate sequence numbers may pass trace validation.
- The trace integrity signal can be weaker than intended.

Potential remediation direction:

- Validate returned order as-is before sorting.
- Require strict increasing sequence numbers when checking an ordered stream.
- Keep gap-tolerant behavior as a separate mode if gaps are expected.

### Simulator tests are not part of default test or CI gates

The repository default test target runs matching-engine and platform-runtime
tests:

- `Makefile:11`
- `Makefile:16`
- `Makefile:29`

CI has proto governance, matching engine, Kotlin runtime, and Postgres schema
placement jobs, but no simulator test job:

- `.github/workflows/ci.yml:21`
- `.github/workflows/ci.yml:59`

Manual simulator tests passed during review:

- `go test ./...` in `services/simulator`

Why this matters:

- Simulator changes can break while default checks still pass.
- This weakens confidence in performance, scenario, and replay evidence.

Potential remediation direction:

- Add a `test-simulator` make target.
- Include simulator tests in `make test`.
- Add a simulator CI job using `go test ./...`.

### Contract metadata is incomplete relative to steering

Proto command metadata includes command, correlation, actor, actor type, and
occurred-at fields:

- `contracts/proto/order_execution.proto:9`

It does not include trace ID or causation ID, so the Kotlin gRPC mapper cannot
carry those values:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/engine/EngineTransport.kt:118`

The proto README also suggests generation is future work even though generated
Java and Go sources exist in the repository:

- `contracts/proto/README.md:16`

Why this matters:

- The default gRPC path loses metadata available in HTTP/domain models.
- Trace and causation chains can fragment across service boundaries.
- Contract documentation does not fully describe current generated-code reality.

Potential remediation direction:

- Add trace ID and causation ID to the proto additively.
- Regenerate contract outputs deterministically.
- Update proto README to reflect the current generated-source workflow.
- Add transport parity tests for metadata propagation.

### Modify gRPC hard-codes price currency to USD

The gRPC modify path builds a `Price` with currency `"USD"`:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/engine/EngineTransport.kt:80`

The domain modify command does not carry currency:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/domain/Models.kt:33`

Why this matters:

- This is harmless while the engine ignores modify price currency.
- It becomes a latent bug if the engine or contract later validates price
  currency for modify commands.

Potential remediation direction:

- Remove currency from modify price if it is not meaningful.
- Or carry instrument/current-order currency explicitly when modifying price.

### Matching-engine order-book structure has a known scaling ceiling

The current book path uses sorted slices with insertion by copy:

- `services/matching-engine/internal/app/service.go:382`

Current benchmark results are within guardrails:

- Submit resting: `1329 ns/op`, `576 B/op`, `13 allocs/op`
- Submit match against resting: `2692 ns/op`, `1498 B/op`, `31 allocs/op`
- Modify order: `116221 ns/op`, `199 B/op`, `7 allocs/op`

Why this matters:

- The current shape is appropriate for the present phase.
- Large book depth or higher modify rates will eventually expose O(n)
  insertion/removal costs.

Potential remediation direction:

- Keep the current implementation until measured need appears.
- Add depth-sensitive benchmarks before changing the data structure.
- Consider price levels and indexed queues when modify latency becomes a real
  bottleneck.

## Maintainability Findings

### Several files are doing too much

Large files observed during review:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt`: 1052 LOC
- `services/simulator/cmd/load-tester/main.go`: 1459 LOC
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/ExternalApiBoundary.kt`: 674 LOC
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServer.kt`: 476 LOC
- `services/simulator/cmd/load-tester/action_policy.go`: 486 LOC

Why this matters:

- Persistence DDL, SQL functions, JSON serialization, queries, and mapping are
  concentrated in one Kotlin file.
- Simulator config, workers, HTTP calls, reporting, trace checks, and output are
  concentrated in one Go command file.
- Large mixed-responsibility files slow review and make targeted performance
  changes riskier.

Potential remediation direction:

- Split Postgres persistence by SQL/bootstrap, mappers, write operations, and
  read queries.
- Split simulator load tester by config, actions, state, HTTP client, reporting,
  and trace validation.
- Treat this as maintainability cleanup, not a prerequisite for the correctness
  fixes above.

## Positive Signals

- Architecture and steering documents are explicit and useful.
- Broad service boundaries are respected: matching engine, runtime, simulator,
  contracts, and docs have clear ownership.
- Matching-engine behavior is isolated in Go and has focused tests,
  fuzz-adjacent behavior coverage, race testing, and benchmarks.
- Runtime has useful performance work already: Hikari pooling, batched insert
  paths, DB-side submit persistence, and counter-based trace sequences.
- API boundary code already has useful hooks for authentication, idempotency,
  rate limiting, abuse detection, and command capture.
- Scripts are mostly local-first and inspectable.
- The previous performance audit left clear benchmark guardrails and deferred
  candidates.

## Verification Evidence From Review

Commands run:

- `make test`: passed.
- `go test -race ./internal/app` in `services/matching-engine`: passed.
- `go vet ./...` in `services/matching-engine`: passed.
- `go test ./...` in `services/simulator`: passed.
- `make bench-matching-engine-check`: passed.
- `make bench-platform-runtime-check`: passed after rerun with sandbox/network
  approval.
- `make check-proto-additive`: did not validate because `origin/main` was not
  available, so the additive check skipped.

Benchmark snapshot:

```text
BenchmarkSubmitOrderResting-10              1329 ns/op      576 B/op    13 allocs/op
BenchmarkSubmitOrderMatchAgainstResting-10  2692 ns/op     1498 B/op    31 allocs/op
BenchmarkModifyOrder-10                   116221 ns/op      199 B/op     7 allocs/op
```

## Overall Assessment

The codebase is stronger than a typical early-stage platform. The main risk is
not raw matching-engine speed. The highest-leverage quality work is around
correctness and operational trust:

- Atomic command idempotency.
- Deterministic clocks and replay identity.
- Typed, auditable event storage.
- Strict API validation.
- Explicit inter-service deadlines.
- Authorization on order command paths.
- Simulator and soak coverage in default gates.

These findings should be triaged before being converted into formal issues,
roadmap items, ADRs, or implementation work.

## Application Quality Review Addendum

This addendum records the broader staff-level application-quality review from
the same day. It expands the initial quality/performance dump into correctness,
architecture, security, persistence, observability, and production-readiness
risks.

### Consolidated Prioritized Findings

#### 1. Atomic command idempotency is missing

Severity:
Critical

Category:
correctness, reliability, data consistency

Location:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/OrderApplicationService.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServer.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt`
- `services/matching-engine/internal/app/service.go`

What is wrong:
runtime and boundary idempotency are check-then-act. The runtime looks up a
command result, calls the engine, then persists the result. The external
idempotency path does the same around the `/api/v1` operation. Submit results
are also upserted in a way that can overwrite prior command outcomes.

Why it matters:
concurrent retries can both execute side effects. A single logical command can
produce divergent accepted/rejected responses, duplicate lifecycle events, or
mutated order state.

How to fix:
reserve command IDs/idempotency keys before engine side effects, preserve the
first canonical result, and model command state as `RECEIVED`, `PROCESSING`,
`COMPLETED`, or `FAILED`. Duplicate in-flight commands should return a stable
in-progress response or canonical prior outcome.

Tradeoffs:
this adds command-state complexity and may force clearer separation between
business rejection and infrastructure failure. That complexity is necessary for
safe retries.

Suggested validation:
concurrent duplicate submit/cancel/modify tests against Postgres and in-memory
stores, including duplicate idempotency keys with different payloads.

#### 2. Deterministic replay is weakened by wall-clock time

Severity:
Critical

Category:
determinism, replay, architecture

Location:

- `services/matching-engine/internal/app/service.go`
- `services/simulator/cmd/load-tester/main.go`
- `packages/scenario-definitions/scenarios/v1/P1_GOLDEN_HIDDEN_CROSS_T1.yaml`

What is wrong:
the engine stamps events with `time.Now()`, and the simulator uses wall-clock
values for session IDs and command `occurredAt` fields.

Why it matters:
Reef's simulation-first goal depends on repeatable scenario execution. The same
seed and command sequence should be able to produce stable event timelines.

How to fix:
inject clocks into the matching engine and workflow modules, use command
`occurredAt` where appropriate, and derive replay session identity from
`scenarioRunId` and seed.

Tradeoffs:
live mode still needs real clocks. The fix should make clock policy explicit by
mode rather than forcing every environment into simulated time.

Suggested validation:
golden replay tests that assert exact event IDs, timestamps, sequence numbers,
and final states for `P1_GOLDEN_HIDDEN_CROSS_T1`.

#### 3. Runtime event schema and live persistence diverge from audit goals

Severity:
Critical

Category:
data model, persistence, auditability

Location:

- `scripts/dev/db/migrations/runtime/0002_event_backbone.sql`
- `scripts/dev/db/migrations/runtime/0003_live_runtime_persistence.sql`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/domain/Models.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt`

What is wrong:
the event backbone migration defines typed events with UUID IDs,
`TIMESTAMPTZ`, actor IDs, payload JSON, and an outbox. The live runtime write
path stores event IDs and timestamps as text and does not carry actor IDs,
payload JSON, or outbox rows.

Why it matters:
auditability is a product requirement. Text timestamps and incomplete event
payloads weaken ordering, attribution, replay, and eventual event distribution.

How to fix:
choose one canonical event schema, restore typed event fields, carry actor and
payload data through `RuntimeEvent`, and write domain state, event log, and
outbox rows atomically.

Tradeoffs:
full schema repair is bigger than a quick patch. A short sprint can at least
lock the canonical decision and stop new drift; full outbox rollout may follow.

Suggested validation:
schema type checks, event/outbox atomicity tests, and replay queries over typed
timestamps.

#### 4. API boundary parsing is too permissive and legacy write routes bypass it

Severity:
High

Category:
security, trust boundaries, API design

Location:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/JsonCodec.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformCommandParsers.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServer.kt`

What is wrong:
invalid JSON is converted to an empty object, and legacy mutation routes such
as `/orders/submit`, `/orders/cancel`, `/orders/modify`, and reference-data
POST routes bypass the stronger `/api/v1` boundary.

Why it matters:
malformed input becomes business rejection instead of validation failure. Legacy
routes also allow users or simulator traffic to skip auth, rate-limit,
idempotency, and command-capture hooks.

How to fix:
strictly parse versioned API DTOs, return structured `400` errors for malformed
or invalid payloads, and gate or remove legacy mutation routes after scripts are
migrated.

Tradeoffs:
smoke scripts and simulator defaults may need route updates. This is worth it
because the public boundary becomes authoritative.

Suggested validation:
malformed JSON, unknown field, missing field, body-too-large, and legacy-route
rejection tests.

#### 5. Order command authorization is incomplete

Severity:
High

Category:
security, authorization, domain integrity

Location:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/ExternalApiBoundary.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/OrderApplicationService.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/admin/AdminApplicationService.kt`

What is wrong:
admin commands enforce actor-role permissions, but order submit/cancel/modify
do not. Boundary auth defaults to allow-all unless configured otherwise.

Why it matters:
an accepted client can claim arbitrary actor IDs and act on orders unless
external deployment configuration compensates.

How to fix:
bind authenticated client identity to permitted actors, enforce order-command
permissions in application service, and require non-allow-all auth outside an
explicit dev mode.

Tradeoffs:
local setup gets slightly more explicit. Simulation actors can still use the
same command path if their roles are seeded.

Suggested validation:
client A cannot submit/cancel/modify as actor B; unauthorized cancel/modify
rejects; non-dev startup rejects allow-all auth.

#### 6. Transport failures are conflated with business rejections

Severity:
High

Category:
reliability, retry semantics, runtime behavior

Location:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/engine/EngineClient.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/engine/EngineTransport.kt`

What is wrong:
the HTTP engine client catches transport exceptions and converts them into
`ENGINE_UNAVAILABLE` domain rejections. The gRPC client has no explicit
deadline and uses plaintext by default.

Why it matters:
an engine outage can be cached as a final command result. gRPC stalls can tie
up runtime request threads and hide infrastructure failure as normal domain
behavior.

How to fix:
return infrastructure errors separately from business rejections, do not cache
transport failures as idempotent final results, add gRPC deadlines, and close
channels during service shutdown.

Tradeoffs:
callers must handle retryable infrastructure errors explicitly. That is required
for correctness.

Suggested validation:
engine-down tests that return 503/non-cached behavior, hanging gRPC server
deadline tests, and retry-after-idempotency tests.

#### 7. Proto/gRPC contracts are not yet a trustworthy equivalent path

Severity:
High

Category:
contracts, inter-service communication

Location:

- `contracts/proto/order_execution.proto`
- `contracts/proto/README.md`
- `services/matching-engine/internal/transport/grpc/server.go`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/engine/EngineTransport.kt`

What is wrong:
proto metadata lacks trace and causation IDs, the README is stale about code
generation, and the Go gRPC transport maps unspecified side values to buy.

Why it matters:
the default gRPC path can lose metadata and can normalize invalid input into
valid business behavior.

How to fix:
add trace and causation IDs additively, regenerate generated code
deterministically, update proto docs, and reject unspecified enum values.

Tradeoffs:
generated code churn should be isolated in a contract PR.

Suggested validation:
HTTP/gRPC parity tests for metadata propagation, invalid enum tests, and proto
additive checks with a real base ref.

#### 8. Account-participant ownership is not validated on order submit

Severity:
High

Category:
domain correctness, reference data, integrity

Location:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/OrderApplicationService.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt`

What is wrong:
reference validation checks that instrument, participant, and account exist
independently, but not that the account belongs to the submitted participant.

Why it matters:
orders can be submitted with a valid participant and another participant's
valid account.

How to fix:
validate the `(account_id, participant_id)` relationship in the database-backed
validation routine and in the in-memory implementation.

Tradeoffs:
fixtures and scripts must seed realistic ownership relationships.

Suggested validation:
submit with mismatched participant/account rejects with a stable code.

#### 9. Hot read endpoints are unbounded

Severity:
Medium

Category:
performance, API design, persistence

Location:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformApi.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServer.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt`

What is wrong:
orders, trades, and events can be returned without pagination. Recent queries
also bind `LIMIT ?` through a string-only helper.

Why it matters:
audit/event data grows quickly. Unbounded reads will eventually dominate memory
and response latency.

How to fix:
require bounded pagination or explicit admin-only full export paths, bind
integer limits with `setInt`, and add indexes that match query order.

Tradeoffs:
clients need pagination. This is required before real run histories grow.

Suggested validation:
Postgres integration tests for `limit`, pagination, and query plans with large
seeded data.

#### 10. Default in-memory runtime persistence is unsafe under concurrent HTTP

Severity:
Medium

Category:
concurrency, local reliability, developer experience

Location:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServer.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/application/OrderApplicationService.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/InMemoryRuntimePersistence.kt`

What is wrong:
the HTTP server is multi-threaded, but in-memory runtime persistence uses
unsynchronized mutable maps and lists.

Why it matters:
standalone local runs can race, corrupt state, or produce misleading
simulation/test behavior.

How to fix:
synchronize in-memory persistence or require Postgres for multi-threaded server
mode.

Tradeoffs:
synchronized in-memory mode is slower but deterministic.

Suggested validation:
concurrent submit/query stress tests against in-memory persistence.

#### 11. Matching-engine book structure is simple but has known scaling limits

Severity:
Medium

Category:
performance, hot path, scalability

Location:

- `services/matching-engine/internal/app/service.go`

What is wrong:
each instrument has one book mutex, sorted slices, and linear removal. Current
benchmarks pass, but deep books and heavy modify/cancel traffic will expose
contention and O(n) behavior.

Why it matters:
throughput targets can move the bottleneck from runtime persistence to engine
book operations as workloads deepen.

How to fix:
measure before replacing. Add depth-sensitive benchmarks and lock-contention
profiles. Consider price levels with indexed queues only after evidence.

Tradeoffs:
the current implementation is easy to reason about and should not be replaced
prematurely.

Suggested validation:
benchmarks by book depth, modify/cancel mix, and multi-instrument concurrency.

#### 12. Simulator reporting can become the bottleneck and trace checks are weak

Severity:
Medium

Category:
performance verification, testing quality

Location:

- `services/simulator/cmd/load-tester/main.go`

What is wrong:
the load tester stores every request result and creates many latency slices.
Trace validation sorts events before checking order and allows duplicate
sequence numbers.

Why it matters:
long soaks can measure harness memory/GC instead of system behavior. Trace
checks can pass while API ordering is wrong.

How to fix:
stream aggregate metrics using histograms or reservoirs, make full-result
retention opt-in, validate returned trace order before sorting, and require
strictly increasing sequence numbers where expected.

Tradeoffs:
debug mode can keep full samples, but normal soak mode should stay bounded.

Suggested validation:
load-harness memory benchmark and trace-order regression tests.

#### 13. Observability is documented but not implemented in runtime paths

Severity:
Medium

Category:
operations, diagnostics, production readiness

Location:

- `docker-compose.yml`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServer.kt`
- `services/matching-engine/cmd/matching-engine/main.go`
- `scripts/dev/stress.mjs`

What is wrong:
compose sets OTEL environment variables and stress scripts probe metrics
endpoints, but the services do not expose real application metrics or traces.
Logs are mostly `println`, `System.err.println`, or Go `log`.

Why it matters:
under load, it will be hard to distinguish runtime queueing, DB pool pressure,
engine round-trip latency, idempotency overhead, and simulator behavior.

How to fix:
add structured logs, request and command latency histograms, engine-call
metrics, Hikari pool metrics, and real readiness/metrics endpoints.

Tradeoffs:
instrumentation should be minimal and stable to avoid noise.

Suggested validation:
stress diagnostics that show phase timings, pool state, and engine/runtime
latency breakdown.

#### 14. Critical simulator and governance checks are outside default gates

Severity:
Medium

Category:
testing, CI, release confidence

Location:

- `Makefile`
- `.github/workflows/ci.yml`
- `scripts/check-proto-additive.sh`

What is wrong:
`make test` and CI do not run simulator tests. The local proto additive check
can skip when `origin/main` is missing.

Why it matters:
simulator/replay is core to the product, so it should not be optional
verification. Contract governance should not silently provide false confidence
in release contexts.

How to fix:
add simulator tests to default and CI gates, and make proto governance fail or
require an explicit base ref in release/CI contexts.

Tradeoffs:
CI gets slower. Keep long soaks scheduled or manual, but keep simulator unit
tests in the default gate.

Suggested validation:
CI job for `services/simulator`, proto check with a known base branch, and
replay smoke in the dev gate.

#### 15. Maintainability risk is concentrated in a few large files

Severity:
Low to Medium

Category:
maintainability, developer experience

Location:

- `services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/ExternalApiBoundary.kt`
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/PlatformHttpServer.kt`
- `services/simulator/cmd/load-tester/main.go`

What is wrong:
these files combine routing, parsing, persistence, SQL bootstrap, JSON mapping,
policy, reporting, and trace checks.

Why it matters:
future changes will be harder to review and easier to regress.

How to fix:
split along already established boundaries while touching behavior: persistence
reads/writes/mappers/schema, API validation/auth/idempotency, simulator
config/workers/reporting/trace.

Tradeoffs:
do not run a broad refactor before fixing the critical correctness issues.

Suggested validation:
behavior-preserving tests before and after each extraction.

### Updated Verification Evidence

Additional commands run during the expanded review:

- `make test`: passed.
- `go test ./...` in `services/simulator`: passed.
- `go test -race ./internal/app` in `services/matching-engine`: passed.
- `make bench-matching-engine-check`: passed.
- `make bench-platform-runtime-check`: passed after sandbox escalation for
  Gradle file-lock socket behavior.
- `make check-proto-additive`: skipped because `origin/main` was unavailable.

Current matching-engine benchmark snapshot:

```text
BenchmarkSubmitOrderResting-10              1353 ns/op      576 B/op    13 allocs/op
BenchmarkSubmitOrderMatchAgainstResting-10  2659 ns/op     1501 B/op    31 allocs/op
BenchmarkModifyOrder-10                   112023 ns/op      199 B/op     7 allocs/op
```

## Planning Integration Recommendation

### Conclusion

Run a short critical quality sprint before expanding post-match modules or
building more UI/control-room surface area.

Reason:
the current working plan already points toward schema convergence, venue
projection completion, deterministic replay, and simulator control-room work.
However, the audit found several foundation issues that can invalidate those
tracks if left unresolved:

- idempotency is not atomic
- replay clocks are not deterministic
- event storage has schema/audit drift
- boundary validation is permissive
- actor authorization does not protect order commands
- transport errors can be cached as business outcomes

These are not polish items. They are trust boundaries and correctness
invariants for every later scenario, post-trade engine, and control-room run.

### Best Fit In The Existing Plan

Integrate the findings as a blocking pre-sprint between the current
communication/API/admin foundation and the next planned post-match or
control-room sprint.

Recommended placement:

- `WORK_PLAN.md`: add a critical quality gate before the "Next planned sprint
  block".
- `ROADMAP.md`: keep the broad phase order unchanged, but treat this gate as
  part of the current execution checkpoint.
- `ARCHITECTURE_THROUGHPUT_TRACKER.md`: keep performance investigations there,
  but do not start async batching or read-model isolation until idempotency and
  event schema are corrected.
- `SPRINT_POST_MATCH_ENGINES.md`: do not start as the next implementation
  sprint until the critical gate is complete.
- `SIMULATOR_CONTROL_ROOM_SPRINT_PLAN.md`: can proceed after the gate, because
  it relies on trustworthy replay, traces, and boundary behavior.

### Proposed Critical Sprint Scope

Name:
Critical Correctness and Boundary Hardening

Goal:
make the existing venue slice safe enough to serve as the foundation for
scenario replay, control-room workflows, and post-match expansion.

In scope:

1. Atomic command/idempotency reservation
- reserve command IDs before engine side effects
- preserve first result
- reject or report duplicate in-flight commands deterministically
- tests for concurrent duplicate submit/cancel/modify

2. Deterministic clock and replay identity
- inject clock into matching engine and runtime workflow paths
- use command timestamps or seeded scenario clock in replay mode
- derive simulator session identity from scenario run metadata when present
- golden replay test for the first scenario path

3. Strict API boundary and legacy-route gating
- strict parse/validate `/api/v1` commands
- structured `400` validation errors
- gate legacy mutation routes behind explicit dev/internal config
- migrate simulator/smoke defaults to `/api/v1`

4. Authorization and reference-data integrity
- enforce actor/client permission checks for submit/cancel/modify
- validate account belongs to participant
- seed simulator actors with explicit roles

5. Transport and contract hardening
- do not turn engine transport failures into cached business rejections
- add gRPC deadlines
- reject invalid/unspecified proto enum values
- add trace and causation metadata to proto additively

6. Event schema decision
- lock canonical event schema and migration direction
- stop adding new writes to the text-only event shape
- decide whether full outbox implementation lands in this sprint or immediately
  after it

Out of scope:

- full post-trade engines
- full simulator control-room UI
- broad file-splitting refactors
- async batched persistence
- NATS integration

### Critical Sprint Exit Criteria

- Duplicate commands are safe under concurrency.
- Scenario replay can assert a stable ordered event timeline.
- `/api/v1` rejects malformed commands before application logic.
- Order commands enforce actor/client authorization.
- Engine unavailability is retryable infrastructure failure, not cached domain
  rejection.
- gRPC carries the same essential metadata as HTTP.
- Event schema direction is documented and tests prevent further drift.
- Default CI includes simulator tests or has an explicit follow-up gate.

### After The Critical Sprint

Resume the existing plan in this order:

1. Complete persistence/schema alignment and venue lifecycle projections.
2. Build simulator control-room MVP over existing CLI/report artifacts.
3. Lock `P1_GOLDEN_HIDDEN_CROSS_T1` and `P2_SETTLEMENT_BREAK_REPAIR`.
4. Start `SPRINT_POST_MATCH_ENGINES.md`.

This preserves the roadmap direction while preventing post-match and UI work
from building on unstable command, replay, and audit foundations.
