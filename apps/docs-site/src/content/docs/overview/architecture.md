---
title: Architecture
description: Service boundaries, bounded contexts, and repository shape.
---

Reef is split by job, not by ceremony. One runtime handles the public API and workflow coordination, a Go engine handles matching, the simulator creates repeatable activity, and Postgres keeps the facts needed for audit and replay.

## Services

| Service | Language | Responsibility |
|---|---|---|
| `services/platform-runtime` | Kotlin | External API boundary (`/api/v1`), command handling, workflow orchestration, persistence, read models, admin surface |
| `services/matching-engine` | Go | Hidden order book, matching rules, execution/order events |
| `services/simulator` | Go | Deterministic scenario execution, seeded participant/bot traffic, replay checks, stress/load tooling |
| `services/stock-data` | Kotlin | Seed-time-only external stock reference snapshots for game/simulation creation |
| `apps/docs-site` | Astro | This site |
| `contracts/proto` | Protobuf | Versioned cross-language contracts (order execution today) |
| `packages/bot-sdk` | TypeScript | Public Bot SDK authoring surface — see [Bot Arena](../../arena/overview/) |
| `packages/scenario-definitions` | — | Reusable scenario/simulation input definitions |

## Deployment Shape (Current)

Current local shape is deliberately simple:

```text
Kotlin platform runtime  (one process)
  -> Postgres            (one instance)
  -> Go matching engine  (one process)
```

The target high-throughput path moves the hot lane away from synchronous database writes: commands land in a durable log, the matching engine consumes them by partition, event batches are published durably, and Postgres materializes facts afterward.

## Bounded Contexts

Reef uses explicit business contexts so the code keeps its shape as features grow. Most contexts start as modules inside the Kotlin runtime instead of separate deployed services:

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

Orders-and-execution, market-data reads, and the first settlement evidence/finality slice have meaningful implementation today; the rest remain architecture targets. See [Current Status](../status/) for the built/planned split.

## Core Rules

- Simulation and bot-arena actors submit through the same `/api/v1` command paths as manual users. No direct table writes.
- Domain/workflow logic stays framework-light, outside HTTP adapters.
- Every meaningful state transition is evented for audit, replay, and timeline reconstruction.
- Canonical venue facts (durable event batches, command outcomes) stay separate from rebuildable operational projections.
- Matching-sensitive submit/cancel/modify commands for the same venue session + instrument stay on one deterministic processing lane.
- Settlement consumes canonical trade facts downstream and writes append-only post-trade evidence; it must not mutate matching history.

## Learn More

- `docs/steering/architecture.md` — full architecture steering
- `docs/steering/external-api-boundary.md` — API boundary steering
- `docs/DATA_DOMAIN_SCHEMA_BLUEPRINT.md` — schema blueprint (see [Schema](../../schema/overview/) on this site)
- `docs/ARCHITECTURE_INFRASTRUCTURE_DIAGRAMS.md` — infrastructure diagrams
