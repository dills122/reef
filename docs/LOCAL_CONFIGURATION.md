# Local configuration and lifecycle

This is the short operating contract for a normal developer checkout. The
source of truth for values is [`.env.example`](../.env.example), the layered
Compose files, and [`scripts/dev/`](../scripts/dev/). Do not copy a benchmark
profile into `.env` as the normal application configuration.

## Accepted default local stack

`make dev-up` uses `compose.base.yml` plus `compose.local.yml` with an empty
`DEV_COMPOSE_PROFILES`. It starts runtime, boundary, and projection Postgres,
NATS, matching engine, `platform-api`, four worker roles, and four projector
roles. Arena, Redpanda, Redis, observability, and venue-event materializers are
opt-in profiles or overlays. The local API uses gRPC to the matching engine,
Postgres persistence, and `sync-result` command processing by default, as
specified in `.env.example` and `compose.base.yml`.

This is the ordinary development/smoke topology. The measured durable direct
stream topology uses Redpanda, matching-engine direct consumption, and canonical
materializers; it must be started with its named profile and interpreted using
[the measured run selector](LOCAL_RUN_PROFILES.md#measured-configurations-choose-by-the-stage-you-need) and the
[throughput ledger](THROUGHPUT_BASELINES.md). A `make dev-smoke` pass on the
default stack is not evidence for that throughput topology. The selector gives
the c-16, worker, partition, and projector shapes behind the best recorded
venue-core and projection runs, plus the current named gate commands.

## Start, inspect, stop

```bash
cp .env.example .env
make dev-doctor
make dev-up
make dev-smoke
make dev-compose-config ARGS="--services"
make dev-down
```

`dev-up` starts datastores, applies forward-only migrations, then builds and
waits for service health. `dev-down` stops containers and preserves volumes.
Set `DEV_COMPOSE_PROFILES=postmatch` to start isolated operational Postgres and
apply its schema. Set `POSTMATCH_SHADOW_WORKERS_ENABLED=true` and an explicit
`POSTMATCH_EVENT_STREAM` to run the isolated live and market consumers on
projector instances. Each instance uses its existing
`STREAM_ACK_PROJECTOR_PARTITIONS` assignment unless
`POSTMATCH_WORKER_PARTITIONS` overrides it. Keep the profile enabled while
running these workers. Existing live routes and materializers use their
current stores; the new consumers write shadow state only.
Use the same overlay or profile on teardown that was used at startup; Arena has
`make dev-down-arena`. For a clean local database, `make dev-reset` removes
local Compose volumes, reapplies migrations, and starts the stack; run
`make dev-smoke` afterward. Arena uses `make dev-reset-arena` and
`make dev-smoke-arena`. Reset destroys local data only.

For full contributor dependencies, first-run troubleshooting, endpoints, and
module tests, use [Onboarding](ONBOARDING.md). For exact active services and
merged configuration, inspect `make dev-compose-config` before running.

## Change configuration

Copy `.env.example` to ignored `.env`; use named host-port overrides there or
in the command environment. `DEV_COMPOSE_PROFILES` selects optional Compose
services. `REEF_COMPOSE_FILES` selects ordered files; the default is
`compose.base.yml,compose.local.yml`. Arena uses `make dev-up-arena`, which adds
`compose.arena.yml` and its separate database. Do not include the Arena overlay
for core Reef development.

`make dev-up-stream-ack`, `make dev-up-stream-direct-nodb`, and materializer
smoke/stress commands change the workload and durability shape. Choose them
from [Local Run Profiles](LOCAL_RUN_PROFILES.md), then record profile,
configuration, code revision, workload, and measured stage in any result.
Hosted configuration belongs to [`infra/CONFIGURATION.md`](../infra/CONFIGURATION.md)
and the specific infrastructure runbook; no hosted credential is needed for
normal local development.
