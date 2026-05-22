# Reef Architecture Decisions

## Purpose

Track major architectural directions in one place so implementation changes can be traced back to explicit decisions.

## Active Decisions

### D-001: Inter-Service Communication Direction

Status: accepted

Summary:
- Protobuf is the canonical contract schema for inter-service boundaries.
- gRPC is the preferred runtime-to-engine synchronous transport.
- HTTP/JSON remains as migration fallback until parity is validated.

Primary references:
- [`docs/steering/inter-service-communication.md`](./steering/inter-service-communication.md)
- [`REEF_TECHNICAL_DESIGN.md`](../REEF_TECHNICAL_DESIGN.md)

### D-002: External API Boundary Direction

Status: accepted

Summary:
- User-facing APIs are versioned under `/api/v1`.
- Boundary concerns (auth hook, idempotency, rate limits, validation, error envelope) are explicit architecture requirements.
- Internal runtime/engine service contracts are not treated as public client contracts.

Primary references:
- [`docs/steering/external-api-boundary.md`](./steering/external-api-boundary.md)
- [`REEF_TECHNICAL_DESIGN.md`](../REEF_TECHNICAL_DESIGN.md)

### D-003: Admin Surface Direction

Status: accepted

Summary:
- Admin capabilities are implemented in application-layer modules first.
- CLI is the initial adapter for admin actions.
- Future admin HTTP APIs must reuse the same admin application modules.

Primary references:
- [`docs/SPRINT_COMMUNICATION_API_ADMIN.md`](./SPRINT_COMMUNICATION_API_ADMIN.md)
- [`REEF_TECHNICAL_DESIGN.md`](../REEF_TECHNICAL_DESIGN.md)
