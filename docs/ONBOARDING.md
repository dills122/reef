# Reef Onboarding

This guide is the quickest path to a working local Reef environment.

## 1. Prerequisites

Install and verify:

- Git
- Docker Desktop (or Docker Engine + Compose plugin)
- Bun (preferred JS runtime for repo automation)
- Go 1.24+ (needed for `make dev-stress`; `services/matching-engine` requires `go 1.24.0`, `services/simulator` requires `go 1.23.4` — install the higher 1.24+ floor to build both)
- Java 21 (needed for direct local runtime development outside Docker)

Quick checks:

```bash
git --version
docker --version
docker compose version
bun --version
go version
java -version
```

If Bun is not available yet, you can temporarily run dev automation with:
- `JS_RUNTIME=node`

## 2. Clone And Enter Repo

```bash
git clone https://github.com/dills122/reef.git
cd reef
cp .env.example .env
```

## 3. Read Core Docs First

- [Technical Design](../REEF_TECHNICAL_DESIGN.md)
- [Roadmap (historical)](./archive/ROADMAP.md)
- [Steering Index](./steering/README.md)
- [Dev Environment Runbook](./DEV_ENV.md)
- [Engineering Delivery Policy](./ENGINEERING_DELIVERY_POLICY.md)

## 4. Optional Codex Local Links

Repo-owned agent guidance lives in `AGENTS.md`, `docs/steering/`, and tracked files under `.codex/steering/`.

User-local Codex skill and template links are intentionally not committed because they point at machine-specific template checkouts. To recreate them locally:

```bash
bun run codex:links
```

The package alias runs `bun scripts/dev/reef-dev.mjs links codex`. Claude skill links use the same grouped CLI:

```bash
bun scripts/dev/reef-dev.mjs links claude
```

If your template checkout is somewhere other than `~/Documents/ai-central/templates`:

```bash
AI_CENTRAL_HOME=/path/to/ai-central/templates bun run codex:links
```

The command only creates or updates symlinks. It skips real files so repository-owned steering files are not overwritten.

## 5. Start Local Stack

Default path:

```bash
make dev-up
make dev-smoke
```

`make dev-up` starts Postgres first, applies DB migrations, then starts the full stack.

The underlying grouped CLI is available for discovery and lower-level profile work:

```bash
bun scripts/dev/reef-dev.mjs list
bun scripts/dev/reef-dev.mjs stack up stream-ack
bun scripts/dev/reef-dev.mjs stress run stream-direct-nodb
```

Prefer `make` for common daily workflows and `reef-dev.mjs` when adding or inspecting grouped script profiles.

The default local stack is resolved from `compose.base.yml` plus `compose.local.yml`. To inspect it without starting containers:

```bash
make dev-compose-config ARGS="--services"
bun scripts/dev/reef-dev.mjs stack compose-config --services
```

If Bun is missing locally:

```bash
JS_RUNTIME=node make dev-up
JS_RUNTIME=node make dev-smoke
```

## 6. Common Daily Commands

```bash
make dev-down
make dev-reset
make dev-db-migrate
make test
```

Reset + verify flow (recommended):

```bash
make dev-reset
make dev-smoke
```

Note:
- `dev-reset` now performs clean rebuild + migration apply + compose health wait.
- `dev-db-migrate` remains available as an explicit repair/debug command.
- inline smoke during reset is opt-in: `DEV_RESET_RUN_SMOKE=1 make dev-reset`.

Load baseline:

```bash
make dev-stress
```

Replay baseline drift check:

```bash
make dev-replay
```

Throughput campaign baseline (quality + capacity lanes):

```bash
make dev-throughput-campaign
```

Throughput campaign with deterministic pre-run reset (best for apples-to-apples comparisons):

```bash
DEV_CAMPAIGN_RESET_STACK=1 make dev-throughput-campaign
```

Admin commands against active dev env:

```bash
make dev-admin CMD="events 10"
```

Simulator run against active dev env:

```bash
make dev-sim ARGS="--duration 20s --workers 6 --rate 80 --mode strict-lifecycle --pretty-summary"
bun scripts/dev/reef-dev.mjs sim run --duration 20s --workers 6 --rate 80 --mode strict-lifecycle --pretty-summary
```

## 7. Optional Compose Profiles

Redis:

```bash
DEV_COMPOSE_PROFILES=redis make dev-up
```

Observability:

```bash
DEV_COMPOSE_PROFILES=observability make dev-up
```

Multiple:

```bash
DEV_COMPOSE_PROFILES=redis,observability make dev-up
```

## 8. Port Override Pattern

If local defaults are occupied:

```bash
REEF_PLATFORM_API_HOST_PORT=18080 \
REEF_MATCHING_ENGINE_HOST_PORT=18081 \
REEF_POSTGRES_HOST_PORT=15432 \
make dev-up
```

Use matching smoke endpoints:

```bash
RUNTIME_BASE_URL=http://localhost:18080 \
ENGINE_BASE_URL=http://localhost:18081 \
make dev-smoke
```

## 9. Architecture Basics For Contributors

- Keep domain boundaries clean (runtime modules first, extraction later).
- Simulation/scenario behavior must use real command paths.
- New repo automation should be Bun-based scripts under `scripts/`.
- `make` targets are wrappers around versioned scripts, not logic centers.

## 10. Troubleshooting

`bun: command not found`
- Install Bun or run commands with `JS_RUNTIME=node` temporarily.

`bind: address already in use`
- Use host port overrides (see section 7).

`smoke` fails at submit/cancel
- Check service health:
  - `curl http://localhost:8080/health`
  - `curl http://localhost:8081/health`
- Ensure reference seeding endpoints are reachable.

Docker build failure
- Run `make dev-down` and retry `make dev-up`.
- If needed: `docker system prune` (careful: removes unused artifacts).
