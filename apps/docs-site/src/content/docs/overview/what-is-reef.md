---
title: What Is Reef
description: Project overview — what Reef is, who it's for, and how the pieces fit.
banner:
  content: Pre-release. Architecture and scope described here are the target direction, not a finished product — see Current Status for what's actually built.
---

Reef is a **simulation-first institutional trading venue and post-trade platform**, modeled after dark-pool venue design and the broader post-trade lifecycle (allocation, confirmation, settlement, exceptions). It is not a retail trading app, and it is not aiming at real exchange connectivity, real broker/custodian integrations, or regulatory completeness.

The goal is a platform that is realistic enough in architecture to teach real market-infrastructure concepts, while staying local-first, inspectable, and replayable.

## Two Layers

**Core platform** — order intake, hidden order book and matching, trade creation, allocations, confirmations, settlement, exceptions, audit trails. Behaves like a real system; no demo-only shortcuts.

**Simulation control plane** — scenario definitions, participant bots, seeded randomness, market clock, replay/reset, fault injection. Drives the core platform through the *same* command/API paths a manual user would use — simulation actors never bypass the boundary or write to domain tables directly.

## Operating Modes

- **Manual mode** — a person submits orders, reviews trades, works exceptions through the UI.
- **Scenario mode** — a predefined, seeded scenario drives deterministic, replayable activity.
- **Live simulation mode** — bots and synthetic actors generate continuous market activity, including the emerging Bot Arena competitors.

## Where The Bot Arena Fits

The [Bot Arena](../../arena/overview/) is an exploratory extension of the simulation control plane: user-authored bots compete in deterministic simulated markets, using the same venue command paths as everything else. It is planning-stage/early-build, not a finished game — see [Arena Overview](../../arena/overview/) for current status.

## Technology Direction

- **Astro** — this documentation/marketing site
- **Kotlin** (Ktor-leaning) — platform runtime: API boundary, workflow orchestration, persistence, read models
- **Go** — matching/execution engine, and the current simulator/load-testing CLI
- **Postgres** — canonical relational state, compact canonical event materialization
- **Kafka-compatible durable streams** (NATS JetStream as fallback/comparison) — high-throughput command ingress and venue event batches
- **Protobuf** — cross-language contracts between Kotlin and Go

## Learn More

- `REEF_PROJECT_OVERVIEW.md` — full product vision, personas, functional scope
- `REEF_TECHNICAL_DESIGN.md` — full technical design, bounded contexts, event model
- [Architecture](../architecture/) — service boundaries and repo shape on this site
- [Current Status](../status/) — what's actually built today
