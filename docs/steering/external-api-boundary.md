# Reef External API Boundary Steering

## Purpose

This document defines the user/consumer-facing API architecture for Reef.
It exists to ensure we can support future clients/integrators without exposing internal service seams directly.

## Philosophy

### 1. Separate public API boundary from internal service interfaces

External clients should integrate with a stable API boundary.
Internal runtime/engine contracts are optimized for service communication and should not be treated as public API.

### 2. Stable external API, evolving internals

The public API should change deliberately and versioned.
Internal services may evolve independently behind the boundary.

### 3. Security and abuse controls are product requirements

Authn, authz hooks, and rate limiting are part of API design, not optional add-ons.

## Basic Architecture (Near Term)

For the current project phase:
- keep external API as HTTP/JSON (`/api/v1/...`)
- implement boundary logic in the Kotlin runtime first
- preserve a clean module boundary so this can be extracted into a dedicated gateway later

Current implementation checkpoint:
- `/api/v1/orders/submit`, `/api/v1/orders/cancel`, and `/api/v1/orders/modify` exist
- writes require `X-Client-Id` and `Idempotency-Key`
- auth, rate-limit, idempotency, abuse-protection, and command-capture hooks exist in the runtime boundary layer
- durable boundary storage is still in transition toward the domain-schema migration model

Target deployable shape (later):
- API boundary/gateway service (edge)
- platform runtime service (orchestration)
- matching engine service

## Language Choice

Use Kotlin for the boundary in the near term.

Rationale:
- aligns with runtime ownership
- minimizes early operational complexity
- keeps auth, validation, and workflow orchestration in one ecosystem

## Must-Have Features To Bake In Now

### 1. API versioning

- all external endpoints namespaced under `/api/v1`
- additive changes by default

### 2. Authentication hook

- support bearer token/API key verification hook now
- real provider integration can be added later without redesigning handlers

### 3. Idempotency

- require idempotency key for write commands
- map idempotency key to command ID for deduplication

### 4. Rate limiting

- enforce per-client limits (token/key scoped)
- return explicit throttling responses

### 5. Request validation

- validate schema and semantic rules at boundary
- return structured error responses

### 6. Correlation metadata propagation

- generate/accept correlation and trace IDs
- pass through to internal command metadata

### 7. Audit-safe logging

- structured logs with client ID, operation, command ID, trace ID
- avoid logging sensitive token material

## Recommended Response Standards

- consistent error envelope:
  - code
  - message
  - correlationId
- explicit domain rejection vs transport failure distinction
- predictable status code usage

## Scalability Standards

### 1. Stateless boundary instances

No in-memory client session dependence for command acceptance paths.
State needed for idempotency/rate limits should be store-backed.

### 2. Backpressure and timeouts

- explicit upstream timeouts
- bounded concurrency and queueing
- fail fast on overload

### 3. Read/write separation

Keep write command handling and read/query models distinct to avoid coupling UI-style projections into command path scalability.

## Architecture Constraints

- do not expose engine endpoints directly to users
- do not bypass boundary for client-originated writes
- do not hard-wire auth logic deep into domain modules

## Incremental Rollout

1. Add `/api/v1` boundary routes and DTOs.
2. Add auth-token/API-key validation hook.
3. Add idempotency-key requirement for writes.
4. Add rate limiter and client quota config.
5. Add API contract tests and compatibility checks.
