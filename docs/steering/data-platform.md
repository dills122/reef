# Reef Data Platform Steering

## Purpose

Define practical, production-shaped guidance for Reef persistence, event logging, data types, retention, and failure handling.

This steering is intentionally strict on correctness and auditability, while staying lightweight enough for a toy/simulator project.

## Authoritative Posture

1. Canonical write store is Postgres.
- lifecycle writes persist in real time
- EOD jobs are derivative (archive + analytics), not first persistence

2. Event transport is the Kafka-compatible durable log (Redpanda) as the active hot-ingress/event-transport target, with NATS JetStream retained as a fallback/comparison provider.
- distribution and replay window
- not the sole source of truth

3. Procedure-first critical writes.
- use schema-owned Postgres routines for write-path mutations and job state transitions

## Data Type Standards

1. Identifiers
- use `uuid` for global identifiers (`event_id`, `run_id`, `command_id`, etc.)
- current baseline on Postgres 16 is UUIDv4 via `gen_random_uuid()`
- revisit UUIDv7 adoption on Postgres upgrade or with explicit app-generated UUIDv7 rollout

2. Time
- use `timestamptz` for event and lifecycle timestamps
- persist market/business date separately as `date` when required by calendar semantics
- never store operational timestamps as free-form text

3. Money and quantities
- use `numeric(p,s)` for monetary and exact business quantities
- do not use floating-point (`real`/`double precision`) for financial amounts
- avoid Postgres `money` type due to locale/casting operational friction

4. Enums and statuses
- prefer `text` + `check` constraints (or controlled lookup tables) for workflow statuses
- avoid hard-to-evolve enum coupling in early phases unless lifecycle is truly stable

5. JSON usage
- use `jsonb` for envelopes, metadata, and flexible auxiliary payloads
- do not hide core relational keys/constraints inside `jsonb`

## Write-Path and Concurrency Standards

1. Atomic write pattern
- commit domain state + runtime event + outbox row in one transaction

2. Delivery semantics
- assume at-least-once publication/consumption
- enforce idempotency in consumers using `event_id` uniqueness or dedupe tables

3. Queue claiming
- outbox/job-runner claim operations should use row locking patterns (`FOR UPDATE SKIP LOCKED`)
- avoid app-level race-prone "select then update" patterns

4. Isolation and retries
- default to `READ COMMITTED` for most write-path operations unless proven insufficient
- where `SERIALIZABLE` is required, code must retry serialization failures explicitly

## Schema and Boundary Standards

Current and planned logical schemas:

- `runtime`
- `boundary`
- `auth`
- `admin`
- `orchestration` (scheduled jobs, run state machine)
- `analytics` (transformed, query-optimized surfaces)

Rules:

1. No cross-domain foreign keys.
2. No cross-domain repository coupling.
3. No cross-schema joins in runtime hot write paths.
4. Cross-domain dependencies should use APIs/events/read-model replication.

## Indexing and Partitioning Standards

1. Hot tables
- keep indexes minimal and write-path justified
- mandatory index additions require measured impact notes

2. Event/outbox growth
- design for time-based partitioning once volume justifies it
- partition keys should align with common filters and retention windows

3. Retention
- durable log retention (Redpanda/Kafka-compatible topics, or NATS JetStream streams where used as fallback) short-lived (days)
- runtime canonical history medium/long horizon with partition strategy
- archive files long-term immutable

## Observability Requirements

Every command/event workflow must preserve:

- `commandId`
- `traceId`
- `correlationId`
- `causationId`
- `actorId`
- `occurredAt`

Operational metrics required:

- write transaction latency
- outbox backlog size and max age
- job-runner lag and retry counts
- dead-letter counts

## Biggest Pitfalls to Avoid

1. EOD-first persistence
- deferring writes until close risks catastrophic intraday data loss

2. Treating the durable log (Redpanda/Kafka-compatible, or NATS JetStream in fallback/comparison use) as primary system-of-record
- retention/consumer policies are transport concerns, not canonical audit storage

3. Over-indexing hot tables early
- write throughput collapses before query value is proven

4. Status/state encoded only in app logic
- missing DB constraints leads to invalid transitions and hard-to-debug data drift

5. Unbounded `jsonb` payload growth
- storage and query costs rise quickly without explicit constraints and lifecycle policies

6. Missing idempotency at consumers
- duplicate deliveries become duplicate business effects

7. Running analytics workloads on hot operational paths
- lock contention and unpredictable tail latency

8. Cross-domain SQL shortcuts
- makes future DB extraction expensive and risky

## References

- Postgres partitioning best practices: https://www.postgresql.org/docs/current/ddl-partitioning.html
- Postgres date/time types (`timestamptz` guidance): https://www.postgresql.org/docs/current/datatype-datetime.html
- Postgres numeric types (exact numeric for money): https://www.postgresql.org/docs/current/datatype-numeric.html
- Postgres JSON/JSONB guidance: https://www.postgresql.org/docs/current/datatype-json.html
- Postgres UUID type/functions: https://www.postgresql.org/docs/current/datatype-uuid.html and https://www.postgresql.org/docs/current/functions-uuid.html
- Postgres 16 UUID functions baseline: https://www.postgresql.org/docs/16/functions-uuid.html
- Postgres transaction isolation: https://www.postgresql.org/docs/current/transaction-iso.html
- Postgres locking and `FOR UPDATE`: https://www.postgresql.org/docs/current/sql-select.html
- Redpanda/Kafka-compatible log concepts and retention semantics: https://docs.redpanda.com/current/get-started/architecture/
- NATS JetStream concepts and retention semantics (fallback/comparison provider): https://docs.nats.io/nats-concepts/jetstream
