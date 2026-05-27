# Reef Sprint: Local Dev Environment Foundation

## Purpose

Define and execute a short sprint between the communication/API/admin sprint and post-match sprint to establish a reliable, repeatable Docker-based local development environment.

## Sprint Scope

In scope:
- Docker Compose baseline for:
  - `platform-runtime`
  - `matching-engine`
  - `postgres`
- optional Docker Compose profile for:
  - `redis` (rate-limit store target path)
- optional Docker Compose profile for:
  - observability stack (`otel-collector` + `jaeger`)
- `make`-first developer workflows:
  - `make dev-up`
  - `make dev-down`
  - `make dev-reset`
  - `make dev-smoke`
- deterministic bootstrap:
  - database initialization and migrations
  - seed/reference data baseline
- health/readiness gating and startup ordering
- runbook documentation and troubleshooting notes
- single-Postgres, split-ready persistence architecture:
  - domain schema isolation (`runtime`, `auth`, `admin`, `boundary`)
  - per-domain migration organization
  - unified DB env var contract
  - extraction-ready guardrails (no cross-domain DB coupling)

Out of scope:
- Kubernetes manifests and production orchestration
- advanced horizontal scaling tuning
- external cloud-managed service setup

## Sprint Outcomes

By end of sprint, Reef should have:
- one-command local startup and shutdown
- deterministic reset path with optional inline smoke verification
- optional Redis and observability profiles that are test-verified
- smoke coverage for core API flow under Docker
- repeatable stress-test profile and baseline performance report under Docker

## Workstreams

### Workstream A: Compose Topology and Service Contracts

Goal:
define stable local container topology and service wiring.

Tasks:
1. Add base `docker-compose.yml` for runtime/engine/postgres.
2. Add optional `redis` profile.
3. Add optional observability profile (collector + Jaeger).
4. Standardize container network aliases and env var contracts.

Exit criteria:
- base stack starts with one command
- optional profiles can be toggled without editing compose files

### Workstream B: Bootstrap and Data Lifecycle

Goal:
ensure repeatable local state setup.

Tasks:
1. Add DB initialization/migration scripts for required runtime tables.
2. Add seed scripts for required reference data.
3. Add `dev-reset` path (down + volume cleanup + rebuild + compose health wait).
4. Create domain schemas (`runtime`, `auth`, `admin`, `boundary`) in one Postgres instance.
5. Organize migrations by domain (separate folders, forward-only).
6. Define canonical DB env var contract and map all runtime/admin/boundary persistence to it.
7. Enforce split-readiness guardrails:
   - no cross-schema foreign keys across domain boundaries
   - no cross-domain repository coupling in application code

Exit criteria:
- clean machine can bootstrap to working state deterministically
- single-instance Postgres runs with explicit domain schemas and split-ready migration boundaries

### Workstream C: Developer Entry Points and Smoke Validation

Goal:
make the environment fast to use and easy to verify.

Tasks:
1. Add `make` targets (`dev-up/down/reset/smoke`).
2. Add smoke test script for:
   - health checks
   - submit/cancel/modify flow through runtime
   - `/api/v1` boundary required headers path
3. Add readiness waits and clear failure messaging.

Exit criteria:
- a contributor can run one command and verify core flow in minutes

### Workstream D: Observability Profile Hardening

Goal:
validate optional observability path before making it default later.

Tasks:
1. Wire runtime and engine OTEL env vars for local profile.
2. Validate trace visibility in Jaeger for one command flow.
3. Document expected telemetry and troubleshooting.

Exit criteria:
- observability profile can be enabled and verified reliably
- clear criteria documented for future default-on switch

### Workstream E: Stress and Performance Baseline

Goal:
establish realistic performance baselines in the Dockerized dev environment before post-match expansion.

Tasks:
1. Add `make dev-stress` load-test entrypoint using the simulator load tester.
2. Define stepped load profile (for example: 100 -> 200 -> 300 -> 400 rps).
3. Capture a baseline report artifact (`/tmp` + docs summary) with throughput, latency, and reject distribution.
4. Document pass/fail thresholds and “degradation” trigger points for local runs.

Exit criteria:
- stress profile runs reproducibly against the Docker stack
- baseline report is recorded and linked from sprint artifacts
- break-point and acceptable operating envelope are documented

## Delivery Sequence (Recommended)

1. Workstream A
2. Workstream B
3. Workstream C
4. Workstream D
5. Workstream E

## Risks and Mitigations

Risk:
container startup race conditions create flaky first-run behavior.

Mitigation:
explicit readiness checks and retries before smoke assertions.

Risk:
optional profiles drift from base stack.

Mitigation:
add CI/dev checks that run base and profiled smoke paths regularly.

Risk:
single-DB implementation creates hidden coupling that blocks future scoped DB extraction.

Mitigation:
enforce domain schemas, per-domain migrations, and repository boundary rules in this sprint.

Risk:
dev commands become too bespoke.

Mitigation:
keep `make` targets thin wrappers around versioned scripts.
