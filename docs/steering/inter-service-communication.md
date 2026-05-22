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

### 3. gRPC is the default synchronous service transport

Use gRPC unary calls for command-style interactions between backend services.
HTTP/JSON adapters may exist during migration, but gRPC is the intended steady state.

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
- runtime and engine communicate via HTTP/JSON adapters

Target state:
- runtime and engine communicate via gRPC + Protobuf contracts
- HTTP/JSON path remains optional fallback until parity is verified

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

- gRPC unary methods for:
  - submit order
  - modify order
  - cancel order

### Health and readiness

- lightweight health checks are required on each service
- avoid coupling health checks to heavy downstream calls

### Timeouts and retries

- all inter-service calls must have explicit deadlines
- retries must be bounded and idempotency-safe

## Observability Standards

- propagate trace/correlation metadata end-to-end
- include service + operation name in structured logs
- capture latency/error counters per method and transport

## Non-Goals (Early Phase)

- no mandatory service mesh
- no premature streaming complexity where unary calls suffice
- no cross-service transactional coupling
