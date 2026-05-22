# Sprint Tracker: Communication + External API + Admin CLI

Reference sprint doc:
- [`docs/SPRINT_COMMUNICATION_API_ADMIN.md`](./SPRINT_COMMUNICATION_API_ADMIN.md)

## Workstream A: Inter-Service Communication Foundation

- [ ] Extend protobuf contracts for submit/modify/cancel parity
- [ ] Add matching engine gRPC server scaffold
- [ ] Add runtime gRPC client scaffold behind `ENGINE_TRANSPORT`
- [ ] Keep HTTP fallback path active
- [ ] Add parity contract tests across transports

## Workstream B: External API Boundary Foundation

- [ ] Add `/api/v1` route namespace
- [ ] Add auth-token/API-key validation hook interface
- [ ] Add idempotency-key requirement for mutating writes
- [ ] Add rate-limit hook and local default implementation
- [ ] Add standard boundary error envelope (`code`, `message`, `correlationId`)

## Workstream C: Admin Layer (CLI First)

- [ ] Create runtime `application/admin` command handlers
- [ ] Add admin command DTOs and result models
- [ ] Add reference data admin CLI commands
- [ ] Add simulation control admin CLI hooks
- [ ] Add trace/event inspection admin CLI commands
- [ ] Ensure admin actions emit audit events

## Workstream D: Hardening and Docs

- [ ] Add integration tests for transport fallback and parity
- [ ] Add integration tests for boundary idempotency behavior
- [ ] Add integration tests for admin CLI command flow
- [ ] Update runbooks/startup docs for new flags and routes
- [ ] Record any additional major design decisions in `docs/DECISIONS.md`

## Sprint Exit Checklist

- [ ] Runtime supports HTTP and gRPC transport selection path
- [ ] `/api/v1` write endpoints enforce idempotency key handling
- [ ] Boundary auth and rate-limit hooks are executable paths
- [ ] Admin CLI runs through reusable application layer modules
- [ ] CI validates new paths
