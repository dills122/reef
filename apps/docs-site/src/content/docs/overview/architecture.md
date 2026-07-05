---
title: Architecture
description: Service boundaries, bounded contexts, and repository shape.
---

## Services

| Service | Language | Responsibility |
|---|---|---|
| `services/platform-runtime` | Kotlin | External API boundary (`/api/v1`), command handling, workflow orchestration, persistence, read models, admin surface |
| `services/matching-engine` | Go | Hidden order book, matching rules, execution/order events |
| `services/simulator` | Go | Deterministic scenario execution, seeded participant/bot traffic, replay checks, stress/load tooling |
| `apps/docs-site` | Astro | This site |
| `contracts/proto` | Protobuf | Versioned cross-language contracts (order execution today) |
| `packages/bot-sdk` | TypeScript | Public Bot SDK authoring surface — see [Bot Arena](/arena/overview/) |
| `packages/scenario-definitions` | — | Reusable scenario/simulation input definitions |

## Deployment Shape (Current)

Phase 1 — modular monolith plus engine:

```text
Kotlin platform runtime  (one process)
  -> Postgres            (one instance)
  -> Go matching engine  (one process)
```

The target high-throughput ingress path (in progress, see Current Status) replaces the synchronous runtime-to-engine-to-Postgres hot path with a durable command log, matching-engine direct partition consumption, durable venue event batches, and async Postgres materialization.

## Bounded Contexts

Reef's domain is organized around explicit contexts, most starting as modules inside the Kotlin runtime rather than separate services:

- reference-data
- account-and-bot-ledger
- orders-and-execution
- market-data
- trade-processing
- post-trade-workflow
- clearing-and-netting
- settlement
- exceptions-and-operations
- simulation-control
- audit-and-analytics

Only orders-and-execution and market-data have meaningful implementation today; the rest are architecture targets. See [Current Status](/overview/status/) for the built/planned split.

## Core Rules

- Simulation and bot-arena actors submit through the same `/api/v1` command paths as manual users — never direct table writes.
- Domain/workflow logic stays framework-light, outside HTTP adapters.
- Every meaningful state transition is evented for audit, replay, and timeline reconstruction.
- Canonical venue facts (durable event batches, command outcomes) stay separate from rebuildable operational projections.
- Matching-sensitive submit/cancel/modify commands for the same venue session + instrument stay on one deterministic processing lane.

## Learn More

- `docs/steering/architecture.md` — full architecture steering
- `docs/steering/external-api-boundary.md` — API boundary steering
- `docs/DATA_DOMAIN_SCHEMA_BLUEPRINT.md` — schema blueprint (see [Schema](/schema/overview/) on this site)
- `docs/ARCHITECTURE_INFRASTRUCTURE_DIAGRAMS.md` — infrastructure diagrams
