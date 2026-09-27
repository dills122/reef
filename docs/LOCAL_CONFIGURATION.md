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
running these workers. By default, existing live routes and materializers use
their current stores; the new consumers write shadow state only.
Set `POSTMATCH_LIVE_READS_ENABLED=true` on an API instance only after the
isolated store has been migrated through `0005`, replayed for its current
source generation, and checked against the legacy participant responses.
Rebuild the isolated post-match database from canonical source after applying
`0005`; existing frontiers prevent same-generation replay and old rows lack
exact response text. API reads use `STREAM_ACK_PARTITION_COUNT` to check every
canonical partition, not a projector instance's assignment. Indexed source
heads are compared with target frontiers on every flagged read.
Flagged reads require all live frontiers to equal current canonical source
heads. A later relaxation needs a measured freshness budget and read-cost evidence.
Before enabling the flag, compare complete legacy and live responses for open,
filled, cancelled, modified, and aged participant histories; check query plans
and read latency with many closed orders and fills. The route path and
fail-closed gate do not constitute a capacity qualification.
`/api/v1/orders/current`, `/api/v1/orders/history`, and `/api/v1/orders/fills`
then read the isolated live store. Responses retain their participant fields
and add `meta.asOf` with source generation, source heads, and all partition frontiers.
Missing coverage, source lag, stale generations, or pre-replay rows return 503. Authorization
still runs before these reads. The flag is off by default.
Set `POSTMATCH_AUDIT_SHADOW_ENABLED=true` with `POSTMATCH_EVENT_STREAM` to run
the independent canonical audit consumer in projection PostgreSQL. It uses the
same partition assignment unless overridden, and writes retained outcomes,
ordered effects, coverage, and its own frontier. Public history still reads
the legacy mixed event store, including direct admin and protective events.
The audit worker does not require the `postmatch` Compose profile.
For controlled shadow validation, set `POSTMATCH_SETTLEMENT_INTAKE_ENABLED=true`
with `POSTMATCH_EVENT_STREAM` on projector instances. The intake uses
the assigned partitions and writes into the migrated `settlement` schema on
`SETTLEMENT_POSTGRES_JDBC_URL`. Set this URL to a database distinct from runtime
PostgreSQL; startup rejects a missing or identical URL.
Apply `settlement/0008` on the actual settlement target before enabling it.
For the local dedicated target, start the `postmatch` profile with
`SETTLEMENT_POSTGRES_JDBC_URL=jdbc:postgresql://settlement-postgres:5432/reef`
and run migrations with `REEF_SETTLEMENT_POSTGRES_MIGRATIONS=1`. The schema
placement CI job exercises this separate target.
Intake records keyed ownership, exact trade facts, receipts, coverage, and its
own frontier. It does not create obligations or ledger entries and does not
switch public settlement reads. `POSTMATCH_SETTLEMENT_BATCH_SIZE` and
`POSTMATCH_SETTLEMENT_POLL_MS` default to `500` and `50` respectively.
`POSTMATCH_SETTLEMENT_MAX_RESULT_BYTES` defaults to 16 MiB and
`POSTMATCH_SETTLEMENT_MAX_EFFECTS` to 20,000. The worker shrinks windows that
exceed the effect cap; a single oversized outcome fails closed. Keep
the flag off until the bounded policy and ledger transition is wired and its
parity gate passes.
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
