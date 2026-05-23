# Sprint Tracker: Local Dev Environment Foundation

Reference sprint doc:
- [`docs/SPRINT_DEV_ENV.md`](./SPRINT_DEV_ENV.md)

## Workstream A: Compose Topology and Service Contracts

- [x] Add base `docker-compose.yml` for runtime/engine/postgres
- [x] Add optional `redis` profile
- [x] Add optional observability profile (`otel-collector` + `jaeger`)
- [x] Standardize network aliases and env contracts across services

## Workstream B: Bootstrap and Data Lifecycle

- [ ] Add runtime DB initialization/migration path for local startup
- [x] Add deterministic seed data path for required reference entities
- [x] Add `make dev-reset` with volume reset + reseed
- [x] Create one Postgres instance with domain schemas (`runtime`, `auth`, `admin`, `boundary`)
- [ ] Split migrations by domain folder with forward-only conventions
- [x] Unify DB env var contract across runtime/admin/boundary persistence
- [ ] Add guardrail checks/docs for split-readiness (no cross-domain FK coupling, no cross-domain repository coupling)

## Workstream C: Developer Entry Points and Smoke Validation

- [x] Add `make dev-up`
- [x] Add `make dev-down`
- [x] Add `make dev-smoke`
- [x] Add health/readiness waits for all required services
- [x] Add smoke assertions for `/health` and core order flow endpoints
- [x] Add `/api/v1` boundary header-path smoke assertions

## Workstream D: Observability Profile Hardening

- [ ] Wire OTEL env config for runtime and engine when observability profile is enabled
- [ ] Verify one full trace is visible in Jaeger from runtime -> engine flow
- [ ] Document profile enablement and troubleshooting
- [ ] Define explicit criteria to switch observability profile to default-on

## Workstream E: Stress and Performance Baseline

- [x] Add `make dev-stress` target for simulator load testing against Docker stack
- [ ] Add stepped load profile scenario (100 -> 200 -> 300 -> 400 rps baseline)
- [ ] Capture baseline stress report artifact and summary doc
- [ ] Define local pass/fail thresholds and degradation trigger criteria

## Sprint Exit Checklist

- [x] New contributor can run `make dev-up` and reach working stack quickly
- [x] `make dev-smoke` passes on base stack
- [x] `make dev-smoke` passes with optional redis profile enabled
- [ ] Optional observability profile validated and documented
- [ ] `make dev-reset` returns environment to deterministic clean state
- [ ] `make dev-stress` produces repeatable baseline envelope metrics
- [ ] Postgres setup remains single-instance now but is config/migration-ready for future scoped-instance extraction
