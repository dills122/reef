# Internal HTTP Caller Inventory

Last aligned: 2026-07-09

Raw `/internal/*` HTTP routes are local/migration adapters, not product APIs or stable operator contracts. Hosted, CI, public, bot, SDK, and partner flows must use `/api/v1/...`, `/admin/v1/...`, gRPC, CLI, or durable-message contracts.

## Migrated Or Gateway-Backed

- `.github/workflows/bot-submission.yml`: bot-submission CI uses `/admin/v1/arena/bots/openbao-provision`.
- `scripts/dev/bot-submission-provision-openbao.mjs`: uses `/admin/v1/arena/bots/openbao-provision`.
- `scripts/dev/bot-submission-registry-diff.mjs`: uses `/admin/v1/arena/bots`.
- `scripts/dev/export-simulation-run.mjs`: posts analytics exports to `/admin/v1/analytics/run-exports`.
- `scripts/dev/admin.mjs`: account-risk, circuit-breaker, and price-collar writes use runtime gateway routes under `/admin/v1/risk/...`.
- `infra/hetzner-core/server/Caddyfile`: exposes only `/admin/v1/arena/...` and `/admin/v1/analytics/...`; raw `/internal/*` and `/admin/v1/risk/...` are not proxied.

## Local-Only Callers

These callers may keep raw `/internal/*` while they run against loopback, compose networks, SSH tunnels, or developer machines:

- `scripts/dev/stress.mjs`: captures command accounting, stream health, worker stats, materializer stats, projector status, hot-path metrics, DB pool metrics, and matching-engine stream-direct stats.
- `scripts/dev/intake-bench.mjs`: captures command accounting, stream health, materializer stats, projector status, and lifecycle projector status.
- `scripts/dev/venue-event-materializer-smoke.mjs`: validates materializer/projector/lifecycle/market-data local drain surfaces.
- `scripts/dev/venue-event-materializer-stress.mjs` and `scripts/dev/reef-dev.mjs stress run stream-ack|stream-direct-nodb`: local throughput and diagnostics workflows.
- `scripts/dev/throughput-campaign.mjs`: local abuse stats capture.
- `infra/hetzner-core/server/scripts/start-stream-ack.sh`: operator-side local health probe from inside the server.
- `infra/hetzner-core/server/scripts/verify-runtime.sh`, `infra/hetzner-core/server/scripts/run-soak.sh`: local loopback health/soak checks.

## Migration Candidates Before Hosted Use

These callers still use raw `/internal/*` for local workflows. Do not reuse them from hosted CI, public admin, bot, SDK, or partner surfaces until they move behind a gateway, CLI, gRPC, or durable-message adapter:

- `scripts/dev/protective-controls-smoke.mjs`: account-risk, circuit-breaker, and price-collar smoke setup/reads.
- `scripts/dev/seed-p2-settlement-facts.mjs`: settlement fact seeding.
- `scripts/dev/arena-bot-risk-smoke.mjs`: arena bot/version setup for local risk smoke.
- `scripts/dev/arena-run-result-ingestion-smoke.mjs`: arena run/result/leaderboard local smoke.
- `scripts/dev/arena-ingest-bot-run-result.mjs`: local arena run-result ingestion helper.

## Next Moves

- Decide whether to expose `/admin/v1/risk/...` through hosted Caddy if protective controls become a remote website/operator workflow; the runtime gateway routes already exist for local/admin CLI use.
- Add `/admin/v1` or CLI wrappers for settlement fact seeding before CI or hosted replay uses it.
- Move arena run/result local helpers to `/admin/v1` when leaderboard ingestion becomes a hosted admin path.
- Keep diagnostic reads (`/internal/commands/*`, `/internal/stream-ack/*`, `/internal/perf/*`, projector/materializer stats) loopback-only unless an explicit operator observability gateway is designed.
