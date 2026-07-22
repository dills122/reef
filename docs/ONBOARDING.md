# Reef Developer Onboarding

This is the canonical clean-machine setup guide for Reef. It covers the normal
Docker-first workflow, the additional toolchains needed to change every part of
the repository, and the point where developer setup ends and hosted operations
begin.

If a command here disagrees with another setup document, fix this guide and the
other document together. [`DEV_ENV.md`](./DEV_ENV.md) is the detailed runtime
and performance runbook after onboarding, not a second first-run guide.

## Choose The Setup You Need

| Setup | Use it for | What you install |
|---|---|---|
| Core local stack | API, data, matching, smoke tests, and most cross-service work | Git, Make, curl, Docker Compose v2, Bun |
| Full contributor | Native Go/Kotlin work, Bot SDK, Arena UI, docs site, and the full test suite | Core tools plus Node 22, npm, Go 1.25+, Java 21, and repository dependencies |
| Optional infrastructure | Kubernetes prototypes, hosted backbone operations, or remote benchmark workers | Full setup plus the tools and access named by that infrastructure runbook |

Normal contributors do **not** need cloud tokens, production database
credentials, Tailscale access, or direct access to the hosted Reef box. Those
are operator-only capabilities.

## Supported Development Environment

The checked-in scripts assume a Unix-like shell. macOS and Linux are the normal
paths. On Windows, use WSL2 with Docker Desktop integration; native PowerShell
setup is not currently a verified path.

### Core prerequisites

| Tool | Repository expectation | Why |
|---|---|---|
| Git | Current supported release | Clone and change tracking |
| GNU Make | Any current version | Stable repository command surface |
| curl | Any current version | Smoke and health checks |
| Docker | Docker Engine/Desktop with a running daemon | Builds and runs the local services |
| Docker Compose | Compose v2 (`docker compose`) | Resolves the layered local stack |
| Bun | `1.3.14`, pinned in `package.json` | Repository automation and package workflows |

The default stack currently resolves to three Postgres containers, NATS, the
matching engine, one API role, four worker roles, and four projector roles.
Make sure Docker has enough free disk and memory for a multi-container build.

### Full-contributor prerequisites

| Tool | Repository expectation | Needed for |
|---|---|---|
| Node.js | `22.22.1` in `.node-version` (major 22 is the compatibility floor) | Docs site and frontend toolchains |
| npm | Bundled with Node 22 | Reproducible docs-site install from `package-lock.json` |
| Go | `1.25.0` or newer | Matching engine; also satisfies the simulator's Go 1.23.4 floor |
| Java/JDK | 21, recorded in `.java-version` | Platform runtime, Arena control plane, and stock-data Gradle builds |

Gradle itself does not need a separate install. The Kotlin services use their
checked-in Gradle wrappers. A newer shell-default JDK is fine when JDK 21 is
also installed and discoverable; the doctor checks for the required toolchain,
not only the default `java` command.

## Clean-Machine Setup

Clone the repository and create the ignored local environment file:

```bash
git clone https://github.com/dills122/reef.git
cd reef
cp .env.example .env
```

Check the Docker-first prerequisites before building anything:

```bash
make dev-doctor
```

For a full-contributor checkout, install all three JavaScript dependency sets
and run the expanded toolchain check:

```bash
make dev-bootstrap
make dev-doctor ARGS=--full
```

`make dev-bootstrap` is safe to rerun. It:

- creates `.env` from `.env.example` only when `.env` does not already exist
- runs the root `bun install --frozen-lockfile`
- installs `apps/arena-admin` from its own Bun lockfile
- installs `apps/docs-site` with `npm ci` from its own npm lockfile

It does not install Docker, Bun, Node, Go, or Java for you. Install those with
your operating-system package manager or version manager, then rerun the
doctor. Preview the bootstrap without changing files with:

```bash
make dev-bootstrap ARGS=--dry-run
```

## Start And Verify Reef

From the repository root:

```bash
make dev-up
make dev-smoke
```

`make dev-up` resolves `compose.base.yml` plus `compose.local.yml`, starts the
datastores first, applies forward-only migrations, builds the service images,
and waits for the application roles to become healthy. The first build is the
slowest because Docker and Gradle dependencies are not cached yet.

`make dev-smoke` uses the same public command path as a real client. A passing
smoke proves that reference setup, submit, cancel, runtime persistence, and the
matching-engine path are working together. Each invocation generates isolated
command, idempotency, run, session, and order identifiers, so the smoke is safe
to repeat against preserved local volumes. Set `DEV_SMOKE_EXECUTION_ID` only
when a stable identifier is useful for debugging.

Useful checks after startup:

```bash
curl http://127.0.0.1:8080/healthz
curl http://127.0.0.1:8080/readyz
curl http://127.0.0.1:8081/health
docker compose -f compose.base.yml -f compose.local.yml ps
```

The main local endpoints are:

| Surface | Default address | Notes |
|---|---|---|
| Platform API | `http://127.0.0.1:8080` | Public commands, reads, health, and local admin routes |
| Matching-engine HTTP | `http://127.0.0.1:8081` | Engine health and direct development endpoint |
| Matching-engine gRPC | `127.0.0.1:9081` | Default runtime-to-engine transport |
| Runtime Postgres | `127.0.0.1:5432` | Canonical runtime schemas; local credentials are `reef` / `reef` |
| Projection Postgres | `127.0.0.1:5433` | Rebuildable read-model storage |
| Boundary Postgres | `127.0.0.1:5434` | Boundary, capture, and idempotency storage |
| NATS / monitor | `127.0.0.1:4222` / `127.0.0.1:8222` | JetStream-backed profiles and diagnostics |

Worker and projector diagnostic ports are configurable in `.env.example`.
Inspect the exact resolved service list or full Compose model with:

```bash
make dev-compose-config ARGS="--services"
make dev-compose-config
```

## First Useful Workflows

Run an ordinary simulator session against the active stack:

```bash
make dev-sim ARGS="--duration 20s --workers 6 --rate 80 --mode strict-lifecycle --pretty-summary"
```

Inspect recent events or seed a reference instrument through the admin helper:

```bash
make dev-admin CMD="events 10"
make dev-admin CMD="instrument-upsert AAPL AAPL"
```

Start the local Control Room in another terminal:

```bash
make dev-control-room
```

Use `bun scripts/dev/reef-dev.mjs list` to discover the grouped stack, stress,
simulation, and local-link profiles behind the stable Make targets.

## Stop, Reset, And Preserve Data

Stop containers while preserving local volumes:

```bash
make dev-down
```

Rebuild from a clean database state:

```bash
make dev-reset
make dev-smoke
```

`make dev-reset` removes Reef's local Compose volumes. Treat it as destructive
to local development data. It does not affect hosted environments.

Migrations normally run during startup and reset. Use this only for migration
repair or development:

```bash
make dev-db-migrate
```

## Configuration And Secrets

`.env.example` is the documented local configuration surface. Copy it to the
gitignored `.env` and change only what your machine or task requires.

- Default local development requires no secret or hosted credential.
- Blank OAuth, stock-data, and admin token values mean the corresponding
  integration is disabled or uses the documented local mode.
- Never commit `.env`, cloud tokens, OAuth secrets, OpenBao recovery material,
  private SSH keys, or generated infrastructure state.
- Host ports are configurable. Prefer the named variables in `.env.example`
  when defaults collide.
- Creation-time infrastructure names and network values follow
  [`infra/CONFIGURATION.md`](../infra/CONFIGURATION.md); do not copy a current
  live hostname, IP, repository owner, or cloud resource name into new code.

Example port override:

```bash
REEF_PLATFORM_API_HOST_PORT=18080 \
REEF_MATCHING_ENGINE_HOST_PORT=18081 \
REEF_MATCHING_ENGINE_GRPC_HOST_PORT=19081 \
REEF_POSTGRES_HOST_PORT=15432 \
make dev-up

RUNTIME_BASE_URL=http://127.0.0.1:18080 \
ENGINE_BASE_URL=http://127.0.0.1:18081 \
make dev-smoke
```

## Work By Repository Area

### Go services

```bash
make test-go
make test-simulator
```

Use focused commands while iterating:

```bash
cd services/matching-engine && go test ./...
cd services/simulator && go test ./...
```

### Kotlin services

```bash
make test-platform-runtime
make test-reef-core
make test-arena-control-plane
```

The wrappers download the pinned Gradle distribution on first use. Run service
commands from their documented directory rather than using a global Gradle.

### Bot SDK and repository automation

Root dependencies must be installed with `make dev-bootstrap` or:

```bash
bun install --frozen-lockfile
make test-bot-sdk
```

### Arena admin UI

Arena is an optional product overlay, not a prerequisite for core Reef:

```bash
cd apps/arena-admin
bun install --frozen-lockfile
cd ../..
make dev-up-arena
bun run arena-admin:dev
```

Use `make dev-smoke-arena` for the overlay integration smoke. Real GitHub OAuth
setup is a separate optional flow documented in
[`BOT_ARENA_AUTH_AND_PROVISIONING.md`](./BOT_ARENA_AUTH_AND_PROVISIONING.md).

### Docs site

```bash
make docs-site-dev
make docs-site-build
```

The helper uses `.node-version` through `fnm` when available. Direct `npm`
commands are also supported after selecting that Node version. The docs site
uses its own npm lockfile intentionally.

### Contracts

Change shared contracts before their consumers, then run:

```bash
make check-proto-additive
```

Read [`steering/inter-service-communication.md`](./steering/inter-service-communication.md)
and [`steering/external-api-boundary.md`](./steering/external-api-boundary.md)
before changing a versioned service or public API contract.

## Optional Local Profiles

The default stack is the Reef-only, database-backed development profile.

```bash
DEV_COMPOSE_PROFILES=redis make dev-up
DEV_COMPOSE_PROFILES=observability make dev-up
DEV_COMPOSE_PROFILES=redis,observability make dev-up
```

Use [`LOCAL_RUN_PROFILES.md`](./LOCAL_RUN_PROFILES.md) before starting
captured-ack, stream-ack, direct no-DB, materializer, throughput, or soak work.
Those profiles have different correctness claims and success criteria.

## Optional Infrastructure And Hosted Access

Infrastructure is intentionally outside the normal contributor path:

| Task | Additional local tools/access | Start here |
|---|---|---|
| Local Kubernetes prototype | `k3d`, `kubectl`, Docker | [`infra/local-kube/README.md`](../infra/local-kube/README.md) |
| Local deploy-shaped backbone | Docker, Bun, `jq` | [`infra/hetzner-core/README.md`](../infra/hetzner-core/README.md#local-backbone-stack) |
| Hetzner backbone operations | OpenTofu, SSH, rsync, jq, authorized Tailscale account, cloud/operator credentials | [`infra/hetzner-core/OPERATIONS_RUNBOOK.md`](../infra/hetzner-core/OPERATIONS_RUNBOOK.md) |
| DigitalOcean benchmark worker | OpenTofu/Terraform, SSH, rsync, cloud token, explicit destroyable-resource confirmation | [`infra/do-benchmark/README.md`](../infra/do-benchmark/README.md) |
| Simulation run wrapper | Same DigitalOcean prerequisites until the bridge is replaced | [`infra/simulation-runner/README.md`](../infra/simulation-runner/README.md) |

Routine hosted SSH is private over Tailscale. Only designated operators should
follow [`infra/hetzner-core/TAILSCALE_ACCESS.md`](../infra/hetzner-core/TAILSCALE_ACCESS.md).
Application contributors should use the local stack and do not need a shared
server login.

## Read Before Your First Change

Start with:

- [`../REEF_PROJECT_OVERVIEW.md`](../REEF_PROJECT_OVERVIEW.md)
- [`../REEF_TECHNICAL_DESIGN.md`](../REEF_TECHNICAL_DESIGN.md)
- [`steering/README.md`](./steering/README.md)
- [`CURRENT_STATUS.md`](./CURRENT_STATUS.md)
- [`ENGINEERING_DELIVERY_POLICY.md`](./ENGINEERING_DELIVERY_POLICY.md)
- [`DECISIONS.md`](./DECISIONS.md)

Then read the language or surface-specific steering for the area you will
change. Preserve the contract and service boundaries in `AGENTS.md` even when
the local implementation is simpler than the target architecture.

Use a feature branch for behavior, contracts, tests, or documentation. Before
handoff, run the smallest focused tests that prove the change and include the
commands and results in the PR summary.

## Troubleshooting

### Doctor says the Docker daemon is unavailable

Start Docker Desktop or the Docker service, wait until it is ready, and rerun
`make dev-doctor`. Having the `docker` command installed is not enough.

### Bun version does not match

The root `package.json` pins Bun `1.3.14`. Select that version with your runtime
manager, then rerun the doctor and bootstrap. `JS_RUNTIME=node` is a temporary
fallback only for plain Node-compatible stack scripts; Bot SDK and Arena tasks
remain Bun-only.

### A JS package or frontend binary is missing

Run `make dev-bootstrap`, then `make dev-doctor ARGS=--full`. Reef has separate
dependency roots for the repository, Arena admin, and docs site.

If `node --version` is 22 but an npm script reports an older Node, your npm
launcher and Node selection are out of sync. Re-enter the repository after
enabling your version manager, select `.node-version`, and rerun
`make dev-doctor ARGS=--full`.

### A host port is already in use

Change the relevant `REEF_*_HOST_PORT` in `.env`. If a previous Reef stack is
still running, use `make dev-down` before starting another checkout.

### Startup or smoke fails

```bash
docker compose -f compose.base.yml -f compose.local.yml ps
docker compose -f compose.base.yml -f compose.local.yml logs --tail=200
curl http://127.0.0.1:8080/healthz
curl http://127.0.0.1:8081/health
```

Run `make dev-reset` only when discarding local database state is acceptable.
For deeper runtime and profile-specific diagnostics, continue with
[`DEV_ENV.md`](./DEV_ENV.md).

## Onboarding Complete

A core developer environment is ready when all of these pass:

```bash
make dev-doctor
make dev-up
make dev-smoke
```

A full contributor environment is ready when this also passes:

```bash
make dev-doctor ARGS=--full
make test-dev-tooling
```

Run the focused language, app, or contract tests for the area you plan to
change before attempting the entire repository test suite.
