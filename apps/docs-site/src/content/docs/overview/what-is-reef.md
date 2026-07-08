---
title: What Is Reef
description: Project overview — what Reef is, who it's for, and how the pieces fit.
banner:
  content: Pre-release. Architecture and scope described here are the target direction, not a finished product — see Current Status for what's actually built.
---

Reef is a **simulation-first institutional trading venue and post-trade platform**. Think of it as a local market-infrastructure lab: orders enter through a controlled API, match inside a hidden book, create trades, then leave a trail that can be replayed and audited.

It is not a retail trading app. It is also not trying to connect to real exchanges, brokers, or custodians. The point is to make the shape of a serious trading venue understandable without losing the ability to run everything locally.

## Two Layers

**Core platform** - accepts orders, runs matching, records trades, builds settlement evidence, and keeps audit trails. It should behave like a real system, even when a feature starts small.

**Simulation control plane** - creates the action: scenario definitions, participant bots, seeded randomness, market clock, replay/reset, and fault injection. It drives the core platform through the *same* command/API paths a manual user would use. No hidden table writes, no demo shortcuts.

## Operating Modes

- **Manual mode** - a person submits orders, reviews trades, and eventually works exception queues.
- **Scenario mode** - a predefined, seeded story drives deterministic activity that can be replayed.
- **Live simulation mode** - bots and synthetic actors generate ongoing market activity, including Bot Arena competitors.

## Where The Bot Arena Fits

The [Bot Arena](../../arena/overview/) turns the simulation layer into a game: user-authored bots compete in deterministic markets, but their orders still enter through the real venue boundary. That keeps the game fun without turning it into a separate toy system. It is early-build, not a finished game.

## Technology Direction

- **Astro** - this docs site.
- **Kotlin** - platform runtime: API boundary, workflow orchestration, persistence, read models.
- **Go** - matching engine plus simulator/load tooling.
- **Postgres** - durable facts, projections, and audit-friendly storage.
- **Kafka-compatible streams** - target durable command intake and event-batch backbone.
- **Protobuf** - shared contracts between Kotlin and Go.
- **Stock-data service** - seed-time external price snapshots that are persisted before replay.

## Learn More

- `REEF_PROJECT_OVERVIEW.md` — full product vision, personas, functional scope
- `REEF_TECHNICAL_DESIGN.md` — full technical design, bounded contexts, event model
- [Architecture](../architecture/) — service boundaries and repo shape on this site
- [Current Status](../status/) — what's actually built today
