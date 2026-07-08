---
title: Internal & Admin Routes
description: Operator/diagnostic routes under /internal — not part of the public client contract.
banner:
  content: Internal-only migration/local tooling. Do not expose these routes raw, and do not build client integrations against them.
---

Routes under `/internal/*` exist for local operators, admin CLI migration tooling, smoke tests, and diagnostics. They are not versioned like `/api/v1`, can change without notice, and must not be exposed raw as a public or partner-facing surface.

Hardline policy:
- internal service/control capabilities should be gRPC/protobuf or durable-message interfaces
- externally reachable admin/data capabilities must be gateway-backed, authenticated, authorized, audited, and versioned
- product-facing simulation access uses two API families: venue intake/trading information and admin/data
- `/internal/*` is not a product API, SDK target, or stable integration contract

Canonical policy: `docs/API_SURFACE_POLICY.md`.

| Route | Purpose |
|---|---|
| `/internal/admin/account-risk/controls` | Manage account/bot risk pre-check controls |
| `/internal/admin/arena/bots` | Register arena bot identity |
| `/internal/admin/arena/bot-versions` | Register arena bot versions |
| `/internal/admin/arena/bot-versions/transition` | Transition a bot version's approval-lifecycle state |
| `/internal/admin/circuit-breakers` | Manage command circuit breakers |
| `/internal/admin/price-collars` | Manage instrument price collars |
| `/internal/admin/settlement/facts` | Append scenario settlement fact bundle for smoke/evidence setup |
| `/internal/admin/settlement/obligations/materialize` | Materialize trade-to-settlement obligations for a scenario run |
| `/internal/admin/settlement/repairs/cash` | Post cash resource repair plus settlement repair fact |
| `/internal/admin/settlement/repairs/security` | Post security resource repair plus settlement repair fact |
| `/internal/boundary/abuse/stats` | Abuse-protection hook stats |
| `/internal/boundary/account-risk/controls` | Boundary-side account-risk control read |
| `/internal/boundary/account-risk/decisions/recent` | Recent account-risk decisions |
| `/internal/boundary/circuit-breakers` | Boundary-side circuit-breaker read |
| `/internal/boundary/price-collars` | Boundary-side price-collar read |
| `/internal/commands/accounting` | Command accounting by run |
| `/internal/commands/async/stats` | Async command worker stats |
| `/internal/market-data/projector/status` | Market-data projector loop status |
| `/internal/perf/db-pools` | DB connection pool stats |
| `/internal/perf/hot-path` | Hot-path latency metrics |
| `/internal/projector/status` | Canonical projector status |
| `/internal/stream-ack/health` | Stream-ack ingress health |
| `/internal/stream-ack/worker/stats` | Stream-ack worker stats |
| `/internal/venue-event-materializer/stats` | Venue event batch materializer stats |

Arena admin routes require the separate arena datasource (`ARENA_POSTGRES_JDBC_URL`, `ARENA_POSTGRES_USER`, `ARENA_POSTGRES_PASSWORD`) and `PLATFORM_ARENA_ADMIN_ENABLED=1` — see [How The Game Works](../../arena/how-the-game-works/).

## Learn More

- [API Overview](../overview/) — the public `/api/v1` contract these routes are explicitly separate from
- `docs/BOT_ARENA_PLAN.md` — arena control-plane detail
- `docs/SETTLEMENT_CLEARING_STRATEGY.md` — settlement materialization and repair route detail
