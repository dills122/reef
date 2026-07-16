# Internal HTTP Caller Inventory

Last aligned: 2026-07-12

Raw `/internal/*` HTTP routes are local/migration adapters, not product APIs or stable operator contracts. Hosted, CI, public, bot, SDK, and partner flows must use `/api/v1/...`, `/admin/v1/...`, gRPC, CLI, or durable-message contracts.

## Migrated Or Gateway-Backed

- `.github/workflows/bot-submission-provision.yml`: trusted bot-submission provisioning handoff uses `/admin/v1/arena/bots/openbao-provision` after the unprivileged PR workflow succeeds.
- `.github/workflows/bot-registry-sync.yml`: post-merge bot registry sync uses `/admin/v1/arena/bots` and `/admin/v1/arena/bot-versions`.
- `scripts/dev/bot-submission-register-merged.mjs`: post-merge bot registry sync client for `/admin/v1/arena/bots` and `/admin/v1/arena/bot-versions`.
- `scripts/dev/bot-submission-provision-openbao.mjs`: uses `/admin/v1/arena/bots/openbao-provision`.
- `scripts/dev/bot-submission-registry-diff.mjs`: uses `/admin/v1/arena/bots`.
- `scripts/dev/arena-ingest-bot-run-result.mjs`: posts run-bot results through `/admin/v1/arena/run-bot-results`.
- `scripts/dev/arena-run-result-ingestion-smoke.mjs`: registers arena bots, versions, runs, run results, and leaderboard readback through `/admin/v1/arena/...`.
- `scripts/dev/arena-persist-report-local.mjs`: persists local arena report evidence through `/admin/v1/arena/...` from inside the `platform-api` container.
- `scripts/dev/arena-local-tick-run.mjs`: `--seed-reference` uses `/admin/v1/reference/...` and `/admin/v1/auth/...`; `--persist-results` writes arena run/result/enforcement evidence through `/admin/v1/arena/...`.
- `scripts/dev/export-simulation-run.mjs`: posts analytics exports to `/admin/v1/analytics/run-exports`.
- `scripts/dev/admin.mjs`: reference-data setup, auth role setup, account-risk, circuit-breaker, and price-collar commands use runtime gateway routes under `/admin/v1/reference/...`, `/admin/v1/auth/...`, and `/admin/v1/risk/...`.
- `scripts/dev/arena-bot-risk-smoke.mjs`: reference/auth setup uses `/admin/v1/reference/...` and `/admin/v1/auth/...`; arena bot/version calls use `/admin/v1/arena/...`.
- `scripts/dev/protective-controls-smoke.mjs`: reference/auth setup uses `/admin/v1/reference/...` and `/admin/v1/auth/...`; account-risk, circuit-breaker, and price-collar smoke setup/reads use `/admin/v1/risk/...`.
- `scripts/dev/seed-p2-settlement-facts.mjs`: posts settlement facts through `/admin/v1/settlement/facts`; local authenticated runs should pass `ADMIN_API_TOKEN` when admin auth is enabled.
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

These callers still use raw `/internal/*` or legacy internal-marker setup routes for local workflows. Do not reuse them from hosted CI, public admin, bot, SDK, or partner surfaces until they move behind a gateway, CLI, gRPC, or durable-message adapter:

None currently tracked in this category. Keep this section for future audit entries rather than deleting the migration checkpoint.

## Next Moves

- Decide whether to expose `/admin/v1/risk/...` through hosted Caddy if protective controls become a remote website/operator workflow; the runtime gateway routes already exist for local/admin CLI use and are served by both the JDK and Netty runtime adapters.
- Keep diagnostic reads (`/internal/commands/*`, `/internal/stream-ack/*`, `/internal/perf/*`, projector/materializer stats) loopback-only unless an explicit operator observability gateway is designed.
