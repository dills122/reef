# Reef API Surface Policy

## Purpose

This policy defines which Reef interfaces may be exposed to users, bots, operators, partners, and service-to-service callers.

Hard rule: raw internal HTTP routes are not a product surface. Anything intended for internal communication should use gRPC/protobuf, durable messaging, or another private operator transport. Anything externally reachable must be deliberately exposed through a versioned, authenticated, authorized, audited contract.

## Product-Facing Surfaces

Reef has two product-facing API families.

### 1. Venue Intake And Trading Information

This family is for simulation actors, manual users, bot SDKs, and venue-facing clients.

Allowed responsibilities:
- order submit, cancel, and modify intake
- command acceptance and command status lookup
- participant-scoped current and historical own-order state
- execution and trade information that is safe for the caller's visibility scope
- current market-data views such as top-of-book, depth, trade tape, and bars
- data availability and freshness metadata for those trading surfaces

Contract requirements:
- versioned routes, currently `/api/v1/...`
- identity, idempotency, rate-limit, validation, abuse, risk, and circuit-breaker checks where relevant
- stable error envelopes with correlation IDs
- explicit visibility rules for public, participant-scoped, and operator-scoped data

### 2. Admin/Data

This family is for operator-approved administration and intraday/historical data access.

Allowed responsibilities:
- reference, calendar, scenario, risk-control, circuit-breaker, price-collar, and policy administration
- run, replay, analytics-export, and leaderboard control-plane operations
- intraday and historical venue data exposed through explicit read contracts
- maintenance or repair actions that have actor identity, authorization, and audit records

Contract requirements:
- gateway-backed HTTP/JSON or another deliberate external adapter
- authentication and authorization before access
- audit event emission for mutating actions and sensitive reads
- versioned request and response contracts
- clear separation from venue hot-path command intake

## Internal Surfaces

Internal surfaces are for trusted Reef services and private operator tooling, not users, bots, SDKs, or partners.

Default internal transports:
- gRPC/protobuf for synchronous service/control/admin/health/diagnostic interfaces
- durable command/event streams for high-throughput or replay-sensitive paths
- private operator transports for local-only maintenance where gRPC or streams would be unnecessary overhead

Internal gRPC is a contract and transport decision, not a security control by itself. Internal surfaces still need network isolation, TLS or mTLS where appropriate, service identity, authorization, timeouts, audit, and observability.

## Forbidden Shapes

Do not:
- expose raw `/internal/*` HTTP routes to users, bots, SDKs, partners, or public networks
- treat `/internal/*` routes as stable product contracts
- add new externally reachable internal HTTP routes instead of a gRPC/protobuf service or gateway-backed admin/data API
- let simulations or bots bypass the same venue intake paths used by manual users
- expose engine endpoints directly to external callers
- rely on "internal network" as the only access control

## Existing `/internal/*` Routes

Current `/internal/*` HTTP routes are legacy local/migration adapters for operator tooling, diagnostics, smoke tests, and development workflows. They may remain temporarily while the platform migrates, but deployment must block raw access outside private local/operator networks.

Existing route categories:
- `/internal/admin/*`: local admin/control-plane migration adapters
- `/internal/boundary/*`: boundary diagnostics and guardrail visibility
- `/internal/commands/*`: command accounting and worker diagnostics
- `/internal/perf/*`: performance diagnostics
- `/internal/*/projector/status`, `/internal/stream-ack/*`, `/internal/venue-event-materializer/*`: worker/materializer/projector health and lag

Migration target:
1. Define protobuf contracts for internal control, health, diagnostics, and administration.
2. Move internal callers and scripts to gRPC or durable-message contracts.
3. Expose only required operator/user capabilities through admin/data gateway contracts.
4. Block raw `/internal/*` access at reverse proxy, firewall, or service-binding level.
5. Retire HTTP adapters once no local workflow or migration gate needs them.

## Gateway Rule

A gateway may expose an operation backed by an internal service, but the gateway contract becomes the product surface.

Gateway obligations:
- stable versioned route or schema
- caller authentication
- authorization scoped to action and data
- request validation
- audit logging
- rate limits and resource bounds
- error envelope appropriate for external callers
- no leakage of internal transport details such as partitions, offsets, stream sequence numbers, or worker topology unless explicitly diagnostic and operator-scoped

## External Guidance Alignment

This policy aligns with common external guidance:
- gRPC's own documentation describes service definitions in `.proto` files, generated clients/servers, and protocol buffers as the default structured message format: <https://grpc.io/docs/what-is-grpc/introduction/>
- Microsoft recommends gRPC for low-latency, high-throughput microservice and inter-process communication, while noting raw browser/public API support limitations: <https://learn.microsoft.com/en-us/aspnet/core/grpc/comparison>
- OWASP API Security API9 recommends inventorying API hosts, documenting who should have access, and avoiding unnecessarily exposed API hosts: <https://owasp.org/API-Security/editions/2023/en/0xa9-improper-inventory-management/>
- NIST SP 800-207 says zero trust avoids implicit trust based only on network location and requires authentication and authorization before resource access: <https://csrc.nist.gov/pubs/sp/800/207/final>
