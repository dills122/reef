---
title: Admin & Internal Routes
description: Versioned operator gateways and local-only /internal adapters.
banner:
  content: Build operator integrations against authenticated /admin/v1 gateways. /internal routes are local/migration adapters and must not be exposed raw.
---

Routes under `/internal/*` exist for local operators, admin CLI migration tooling, smoke tests, and diagnostics. They are not versioned like `/api/v1`, can change without notice, and must not be exposed raw as a public or partner-facing surface.

Hardline policy:
- internal service/control capabilities should be gRPC/protobuf or durable-message interfaces
- externally reachable admin/data capabilities must be gateway-backed, authenticated, authorized, audited, and versioned
- product-facing simulation access uses two API families: venue intake/trading information and admin/data
- `/internal/*` is not a product API, SDK target, or stable integration contract

Canonical policy: `docs/API_SURFACE_POLICY.md`.

## Gateway Routes

These versioned admin/data routes are the externally reachable shape where explicitly exposed by the gateway:

| Route | Backs |
|---|---|
| `/admin/v1/reference/instruments`, `/admin/v1/reference/participants`, `/admin/v1/reference/accounts` | Reference-data reads and upserts |
| `/admin/v1/auth/roles`, `/admin/v1/auth/actor-roles` | Reef role and actor-role administration |
| `/admin/v1/access/users`, `/admin/v1/access/roles`, `/admin/v1/access/users/trust-state`, `/admin/v1/access/users/roles`, `/admin/v1/access/users/roles/revoke` | Admin identity, trust, and role operations |
| `/admin/v1/arena/bots` | Arena bot registry reads and registration |
| `/admin/v1/arena/my/bots` | Participant-scoped owned-bot read |
| `/admin/v1/arena/bots/openbao-provision` | Narrow OpenBao bot-secret provisioning operation |
| `/admin/v1/arena/bots/ownership` | Arena-owned user/bot entitlement assignment |
| `/admin/v1/arena/bots/config` | Participant bot OpenBao config status, replace, and clear operations |
| `/admin/v1/arena/submission-admissions`, `/admin/v1/arena/submission-admissions/approve` | Persist pending fork admission and record SHA-bound maintainer approval |
| `/admin/v1/arena/bot-versions`, `/admin/v1/arena/bot-versions/transition` | Bot-version registration and lifecycle decisions |
| `/admin/v1/arena/runs`, `/admin/v1/arena/runs/status` | Arena run reads/registration and status updates |
| `/admin/v1/arena/run-bot-results` | Per-run Arena result reads/writes |
| `/admin/v1/arena/run-enforcement-events` | Per-run Arena enforcement reads/writes |
| `/admin/v1/arena/leaderboard` | Authenticated arena leaderboard reads for operators |
| `/admin/v1/analytics/run-exports` | Simulation/arena run export ingestion and reads |
| `/admin/v1/analytics/run-bot-summaries` | Recent bot/run analytics summary reads |
| `/admin/v1/risk/account-controls` | Account/bot risk controls |
| `/admin/v1/risk/circuit-breakers` | Command circuit breakers |
| `/admin/v1/risk/price-collars` | Instrument price collars |
| `/admin/v1/settlement/facts`, `/admin/v1/settlement/obligations/materialize` | Scenario fact setup and post-trade lifecycle materialization |
| `/admin/v1/settlement/repairs/cash`, `/admin/v1/settlement/repairs/security` | Settlement resource repair actions |
| `/admin/v1/settlement/force-settle`, `/admin/v1/settlement/reverse-ledger-entry` | Controlled finality and compensating ledger operations |

## Local/Internal Routes

| Route | Purpose |
|---|---|
| `/internal/admin/account-risk/controls` | Manage account/bot risk pre-check controls |
| `/internal/admin/arena/bots` | Register arena bot identity |
| `/internal/admin/arena/my/bots` | Read the authenticated participant's owned bots |
| `/internal/admin/arena/bots/ownership` | Assign Arena-owned bot entitlement |
| `/internal/admin/arena/submission-admissions` | Persist pending fork admission |
| `/internal/admin/arena/submission-admissions/approve` | Record SHA-bound maintainer admission approval |
| `/internal/admin/arena/bot-versions` | Register arena bot versions |
| `/internal/admin/arena/bot-versions/transition` | Transition a bot version's approval-lifecycle state |
| `/internal/admin/arena/qualification-reports` | Read arena qualification reports |
| `/internal/admin/arena/operator-decisions` | Read arena operator decisions |
| `/internal/admin/arena/runtime-config-descriptors` | Read arena runtime configuration descriptors |
| `/internal/admin/arena/runs` | Register/read arena runs |
| `/internal/admin/arena/runs/status` | Update arena run status |
| `/internal/admin/arena/run-bot-results` | Persist/read arena bot run results |
| `/internal/admin/arena/run-enforcement-events` | Persist/read arena enforcement events |
| `/internal/admin/arena/leaderboard` | Read arena leaderboard entries |
| `/internal/admin/arena/bots/openbao-provision` | Provision arena bot secret slice through OpenBao |
| `/internal/admin/arena/bots/config` | Manage participant bot config blobs through OpenBao without returning secret values |
| `/internal/admin/analytics/run-exports` | Persist/read run export records |
| `/internal/admin/analytics/run-bot-summaries` | Read rebuilt bot/run analytics summary rows |
| `/internal/admin/circuit-breakers` | Manage command circuit breakers |
| `/internal/admin/price-collars` | Manage instrument price collars |
| `/internal/admin/settlement/facts` | Append scenario settlement fact bundle for smoke/evidence setup |
| `/internal/admin/settlement/obligations/materialize` | Materialize trade-to-settlement obligations for a scenario run |
| `/internal/admin/settlement/repairs/cash` | Post cash resource repair plus settlement repair fact |
| `/internal/admin/settlement/repairs/security` | Post security resource repair plus settlement repair fact |
| `/internal/admin/settlement/force-settle` | Force settlement finality for controlled repair/evidence paths |
| `/internal/admin/settlement/reverse-ledger-entry` | Append compensating reversal evidence for a ledger entry |
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
| `/internal/order-lifecycle/projector/status` | Order-lifecycle projector loop status |
| `/internal/stream-ack/health` | Stream-ack ingress health |
| `/internal/stream-ack/worker/stats` | Stream-ack worker stats |
| `/internal/venue-event-materializer/stats` | Venue event batch materializer stats |

Cheap liveness/readiness probes are `/health`, `/healthz`, and `/readyz`. `/readyz` is the dependency-aware readiness surface; `/healthz` is cheap liveness.

Arena routes are absent from the Reef-only artifact. They require the optional
`services/arena-control-plane` artifact, `compose.arena.yml`, the separate Arena
datasource (`ARENA_POSTGRES_JDBC_URL`, `ARENA_POSTGRES_USER`,
`ARENA_POSTGRES_PASSWORD`), and `PLATFORM_ARENA_ADMIN_ENABLED=1` — see
[How The Game Works](../../arena/how-the-game-works/).

## Learn More

- [API Overview](../overview/) — the public `/api/v1` contract these routes are explicitly separate from
- `docs/BOT_ARENA_PLAN.md` — arena control-plane detail
- `docs/SETTLEMENT_CLEARING_STRATEGY.md` — settlement materialization and repair route detail
