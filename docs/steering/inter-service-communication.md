# Reef Inter-Service Communication Steering

## Purpose

This document defines how backend services communicate in Reef, with emphasis on runtime-to-engine boundaries and future service extraction.

## Principles

### 1. Contracts before transport

Domain commands and outcomes are defined in versioned contracts first.
Transport adapters must implement those contracts, not redefine them.

### 2. Protobuf is the default inter-service schema

For service-to-service boundaries, use Protobuf message contracts in `contracts/proto/`.
Do not allow ad hoc JSON payloads to become the long-term source of truth.

### 3. gRPC is the default synchronous service transport, not the final matching hot path

Use gRPC for synchronous backend service interactions, admin/control surfaces, health checks, parity tests, and fallback paths.
HTTP/JSON adapters may exist during migration.
Internal-only capabilities should not be modeled as raw HTTP routes. If a capability is for service-to-service, operator control, health, diagnostics, or private administration, define it as a protobuf-backed interface or durable message flow first. HTTP adapters may exist during migration or local tooling, but they are not the internal contract.
Canonical API-surface policy: [`../API_SURFACE_POLICY.md`](../API_SURFACE_POLICY.md).

For high-throughput matching ingestion, do not treat per-command unary gRPC calls as the steady-state architecture. Prefer partition-owned batch/stream processing, and where durable stream/topic ingress is active, prefer matching-engine shards that consume assigned command partitions, publish canonical venue event batches, and ack or commit consumed commands after durable event publication.

### 4. Compatibility by feature flag during migration

When replacing transports, support dual-path operation behind configuration flags.
Do not perform large transport rewrites as a single cutover.

### 5. Metadata is mandatory

Every inter-service command/event must carry:
- command or event ID
- trace ID
- causation ID
- correlation ID
- actor identity context where available
- occurred-at timestamp

### 6. Idempotency is required

Command handlers must treat command IDs as idempotency keys.
Retries must not create duplicate business effects.

## Current and Target State

Current state:
- runtime and engine support HTTP/JSON and gRPC adapter paths behind transport configuration
- protobuf contracts cover submit, cancel, modify, and health-check paths
- HTTP remains useful for local fallback and parity comparisons
- stream-backed command intake can durably accept commands before asynchronous downstream processing
- some operator and diagnostic HTTP routes still exist as migration/local tooling surface

Target state:
- synchronous compatibility, admin, and direct benchmark paths communicate via gRPC + Protobuf contracts
- HTTP/JSON path remains optional fallback until parity is verified
- raw internal HTTP routes are not reachable from public clients or third-party integrations
- any externally reachable admin/data capability is exposed through a gateway-backed, versioned, authenticated, audited contract
- high-throughput matching paths avoid generic workers issuing one unary engine request per command
- the preferred venue-core shape is command stream/topic -> partition-owning engine shard -> canonical venue event batch -> command ack or offset commit

## Contract Standards

### Versioning

- version contracts by package namespace (`...v1`)
- prefer additive evolution
- preserve backward compatibility within a major version

### Message design

- use explicit command/result messages
- avoid ambiguous generic maps
- use enums for constrained state
- keep monetary/quantity fields explicit and consistent

### Error model

- transport-level failures and domain rejections must be distinct
- domain rejections must return structured code + reason

## Transport Standards

### Runtime to engine

- gRPC unary methods may remain for:
  - submit order compatibility and direct benchmarks
  - modify order compatibility and direct benchmarks
  - cancel order compatibility and direct benchmarks
  - health, admin, snapshots, debugging, and partition ownership control
- high-throughput matching paths should use one of these shapes:
  - matching-engine shard directly consumes assigned durable command stream/topic partitions
  - batch RPC per deterministic partition lane
  - bidirectional stream per deterministic partition lane
- generic workers issuing one unary `SubmitOrder` call per command are transitional scaffolding, not the target hot path.

### Health and readiness

- lightweight health checks are required on each service
- avoid coupling health checks to heavy downstream calls

### Timeouts and retries

- all inter-service calls must have explicit deadlines
- retries must be bounded and idempotency-safe
- transient transport retries must be protected by deterministic `commandId`, partition sequence, and event IDs so duplicate delivery cannot create duplicate executions or trades
- engine-health and per-partition lag must feed intake backpressure before durable acceptance outruns safe downstream drain

## Observability Standards

- propagate trace/correlation metadata end-to-end
- include service + operation name in structured logs
- capture latency/error counters per method and transport

## Non-Goals (Early Phase)

- no mandatory service mesh
- no premature streaming complexity where unary calls suffice
- no cross-service transactional coupling
