---
title: Current Status
description: What is actually built versus planned, as of the current implementation snapshot.
banner:
  content: This page is the fastest-changing page on the site. It reflects a point-in-time snapshot, not a live status feed.
---

Reef has moved past a repository skeleton, but most of the platform is still early. This page separates **built** from **planned** so the rest of the site doesn't market unbuilt features as live.

## Built Today

- Kotlin platform runtime with `/api/v1` submit, cancel, modify, and lifecycle-state order paths
- Boundary idempotency, auth/rate-limit hooks, abuse protection, command capture, command status lookup
- Explicit runtime, boundary, auth, admin, orchestration, analytics, and command-log schemas via local migrations
- Runtime query surfaces for orders, trades, events, and trace timelines
- Admin CLI scaffolding for reference data, roles, calendars, simulation controls, trace inspection
- Go matching engine: hidden-book matching, partial fills, multi-match, cancel, modify, HTTP/gRPC/direct-stream paths
- Protobuf contracts for order execution commands and results
- Go simulator/load tester: persona/session support, deterministic replay checks, stress reports, intake benchmarks
- Docker-first local setup, reset, smoke, stress, replay, and benchmark automation
- First arena control-plane slice: bot registry, bot-version approval lifecycle, operator decisions, run records (see [Arena Overview](/arena/overview/))
- First conservative market-data read slice: order lifecycle projection, top-of-book snapshots, bounded depth

## Not Yet Built (Planned)

- Platform UI and most post-trade lifecycle (allocation, confirmation, settlement, exceptions) — still early
- `account`, `settlement`, `market_data` (beyond the current slice), and `analytics` schemas — designed, not implemented (see [Planned Schema](/schema/planned-schema/))
- Public bot submissions and hosted sandbox execution at scale — arena is still built-in-bots/control-plane stage
- Leaderboards and scoring beyond the control-plane source facts
- Kafka-compatible durable command log as the default hot-ingress path — proven locally, not yet the default

## Active Architecture Direction

The high-throughput venue-ingress path is moving toward: durable command log → matching-engine direct partition consumer → durable venue event batch → command offset commit after batch publish → async Postgres materialization. `202 Accepted` means the durable ingress/log producer acknowledged the command — this contract does not weaken as throughput work continues.

## Learn More

- `docs/CURRENT_STATUS.md` — the full, most current snapshot (source of truth for this page)
- `docs/DECISIONS.md` — accepted architecture decisions
- `docs/WORK_PLAN.md` — active execution ladder
