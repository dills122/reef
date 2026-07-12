# Internal HTTP Caller Inventory

Last aligned: 2026-07-12

Raw `/internal/*` HTTP routes are local/migration adapters, not product APIs or stable operator contracts. Hosted, CI, public, bot, SDK, and partner flows must use `/api/v1/...`, `/admin/v1/...`, gRPC, CLI, or durable-message contracts.

## Migrated Or Gateway-Backed

- `.github/workflows/bot-submission.yml`: bot-submission CI uses `/admin/v1/arena/bots/openbao-provision`.
- `.github/workflows/bot-registry-sync.yml`: post-merge bot registry sync uses `/admin/v1/arena/bots` and `/admin/v1/arena/bot-versions`.
- `scripts/dev/bot-submission-register-merged.mjs`: post-merge bot registry sync client for `/admin/v1/arena/bots` and `/admin/v1/arena/bot-versions`.
- `scripts/dev/bot-submission-provision-openbao.mjs`: uses `/admin/v1/arena/bots/openbao-provision`.
- `scripts/dev/bot-submission-registry-diff.mjs`: uses `/admin/v1/arena/bots`.
- `scripts/dev/arena-ingest-bot-run-result.mjs`: posts run-bot results through `/admin/v1/arena/run-bot-results`, with loopback-only internal fallback for old local stacks.
- `scripts/dev/arena-run-result-ingestion-smoke.mjs`: registers arena bots, versions, runs, run results, and leaderboard readback through `/admin/v1/arena/...`, with loopback-only internal fallback for old local stacks.
- `scripts/dev/arena-persist-report-local.mjs`: persists local arena report evidence through `/admin/v1/arena/...` from inside the `platform-api` container, with loopback-only internal fallback for old local stacks.
- `scripts/dev/arena-local-tick-run.mjs`: `--persist-results` writes arena run/result/enforcement evidence through `/admin/v1/arena/...`, with loopback-only internal fallback for old local stacks.
- `scripts/dev/export-simulation-run.mjs`: posts analytics exports to `/admin/v1/analytics/run-exports`.
- `scripts/dev/admin.mjs`: account-risk, circuit-breaker, and price-collar writes and list reads use runtime gateway routes under `/admin/v1/risk/...`.
- `scripts/dev/protective-controls-smoke.mjs`: account-risk, circuit-breaker, and price-collar smoke setup/reads use `/admin/v1/risk/...`, with loopback-only internal fallback for old local stacks; reference/auth seeding still uses local legacy setup routes.
- `scripts/dev/seed-p2-settlement-facts.mjs`: uses `/admin/v1/settlement/facts` when `ADMIN_API_TOKEN` or `--admin-gateway` is present; otherwise posts `/internal/admin/settlement/facts` and falls back to Docker container loopback when host-to-container traffic fails the local-only guard.
- `infra/hetzner-core/server/Caddyfile`: proxies `/admin/v1/*` through the runtime admin gateway; raw `/internal/*` is not proxied.

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

- `scripts/dev/arena-bot-risk-smoke.mjs`: local reference/auth seed setup still uses legacy setup routes; arena bot/version calls are gateway-first.

## Next Moves

- Decide whether to expose `/admin/v1/risk/...` through hosted Caddy if protective controls become a remote website/operator workflow; the runtime gateway routes already exist for local/admin CLI use and are served by both the JDK and Netty runtime adapters.
- Move `scripts/dev/arena-bot-risk-smoke.mjs` fully off raw setup routes once reference/auth seed setup has a gateway or CLI adapter; its arena bot/version calls are already gateway-first.
- Keep diagnostic reads (`/internal/commands/*`, `/internal/stream-ack/*`, `/internal/perf/*`, projector/materializer stats) loopback-only unless an explicit operator observability gateway is designed.
