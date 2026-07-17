---
title: How The Game Works
description: Arena architecture, action pipeline, storage boundaries, and replay/audit model.
banner:
  content: Describes the design target from the arena planning document. Sandbox execution at scale and modular game modes are not built yet — see Overview for what's live today.
---

## Architecture Fit

```text
bot artifact -> sandbox runtime -> Bot SDK interface -> arena runtime protocol
  -> arena adapter/orchestrator -> schema validation -> arena risk gate
  -> venue command gateway -> platform runtime -> matching engine
  -> events, read models, analytics, leaderboard
```

The matching engine stays venue-focused and unaware of game mechanics. The arena adapter is the only thing that knows a command came from a bot, and it attaches actor identity, command/trace/causation IDs, run ID, and bot version metadata before the command enters the normal `/api/v1` boundary — full validation, idempotency, rate limits, abuse controls, and audit metadata apply exactly as they would for a manual order.

## Storage Ownership

Arena data lives in its own boundary, separate from trading hot-path state:

**Trading-side** (hot path): orders, executions, trades, core venue lifecycle events, venue command/idempotency state.

**Arena-side**: bot registry and version metadata, artifact references and validation results, tournament/season/run metadata, game-mode definitions and policy versions, score results and leaderboard read models, replay indexes, ban/quarantine/watch/throttle lists, operator audit records.

Locally this can share one Postgres instance via separate schemas/databases (arena uses a distinct `ARENA_POSTGRES_*` datasource today); the design preserves a clean path to a fully separate arena Postgres instance later. The trading hot path never synchronously depends on arena analytics writes — if arena scoring or leaderboard updates fall behind, venue command handling keeps working under normal risk/boundary rules.

## Replay & Audit

Every run persists enough to reproduce and explain results: run ID, tournament/season ID, game-mode ID+version, scenario ID+seed, bot IDs/versions/artifact hashes, visible-data/action/risk/scoring policy versions, controlled-actor versions, start/end timestamps, event-timeline pointer, final rankings.

Per-decision audit tracks: bot ID/version/hash, input snapshot (hash or reference), callback invoked, output actions, validation/risk results, accepted command IDs, rejected-action reasons, runtime duration, resource usage, exceptions/timeouts. Replay should always answer: what did the bot see, what did it decide, what was accepted or rejected, what venue events followed, and why did the score change.

## Sandbox & Abuse Controls

Bot code is never trusted. Minimum controls: isolated execution context per bot, no default network/filesystem access, deterministic simulator-controlled clock, deterministic randomness only if explicitly provided, per-decision wall-time timeout, CPU/memory/output/state budgets, immutable artifact hash recorded per run, and a kill switch for timeout/crash/invalid-output/abuse. SES is the first JavaScript-level confinement layer; container/WASM isolation is a likely later addition for stronger guarantees.

## Control Plane (Built Today)

`ArenaControlPlaneService` is the first arena-owned registry boundary in the platform runtime, backed by `PostgresArenaBotRegistryStore` for durable registry/qualification/operator-decision/run-record facts. Bot versions carry explicit states (`draft`, `submitted`, `checks_passed`, `approved`, `active`, `suspended`, `quarantined`, `banned`, `archived`); a quarantined or non-approved bot version is rejected before order acceptance. Operator decisions record actor, reason, correlation ID, timestamp, and lifecycle transition.

Local run evidence now covers positive and negative persisted arena gates, static operator report rendering, report-index rendering, shared-time multi-instrument runs, and hosted/local report metadata that records whether reads came from fixtures or live platform read APIs. Public submission and hosted/backbone scale still need their own named smoke before the arena is described as open.

## Learn More

- `docs/BOT_ARENA_PLAN.md` — full plan: game-mode module contract, infrastructure/scale-out design, open questions (source for this page)
- `docs/archive/STREAM_ACK_ARCHITECTURE_PLAN.md` — target high-throughput ingress path for bot-arena scale
- [Arena Overview](../overview/) — current build status
