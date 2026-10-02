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
Use the same overlay or profile on teardown that was used at startup; Arena has
`make dev-down-arena`. For a clean local database, `make dev-reset` removes
local Compose volumes, reapplies migrations, and starts the stack; run
`make dev-smoke` afterward. Arena uses `make dev-reset-arena` and
`make dev-smoke-arena`. Reset destroys local data only.

For full contributor dependencies, first-run troubleshooting, endpoints, and
module tests, use [Onboarding](ONBOARDING.md). For exact active services and
merged configuration, inspect `make dev-compose-config` before running.

## Calcify Phase 1 sidecars

Apply forward-only migrations, then add `compose.calcify.yml` to `REEF_COMPOSE_FILES`
and select profiles `redpanda,calcify-phase1`. This runs independent extractor,
stub verifier, and receipt worker beside existing post-matching services.
`CALCIFY_STAGE` is set only inside optional sidecars; default stack behavior
does not change. [Contract](../contracts/calcify/README.md) and
[local diagnostic evidence](work/CALCIFY_PHASE1_IMPLEMENTATION.md) describe
link semantics, tests, and replay-retention limits.

`CALCIFY_VERIFIER_MAX_POLL_RECORDS` and `CALCIFY_RECEIPT_MAX_POLL_RECORDS`
accept1–1000, default100, and reject malformed explicit values before startup.
They bound verifier transaction and receipt batch sizes; extractor remains100.
Larger values require measured backlog/latency and poison-prefix replay checks.


Run `make dev-smoke-calcify-full-path` for a local functional check through
PostgreSQL-backed HTTP intake, Redpanda command log, Go matching, and all three
Calcify stages. It creates isolated topics and registers a local source
generation, then checks a zero-trade resting batch and one crossing trade against
its exact receipt. It leaves stack running; use matching `REEF_COMPOSE_FILES` and
`DEV_COMPOSE_PROFILES` on `make dev-down` when finished. This smoke does not
measure capacity or exercise financial settlement.

Run `make dev-stress-calcify-basic PAIRS=1000` for a bounded local burst through
the same path. It checks intake, matched source commands/trades, both link
streams, final receipts, and stage-end timestamps; [diagnostic evidence](work/CALCIFY_PHASE1_IMPLEMENTATION.md#bounded-full-path-load-cal-p1-l2)
records the observed rate, drain, and limits. This is not a sustained gate.

Run `make dev-soak-calcify-phase1 DURATION_SECONDS=300 PAIRS_PER_SECOND=100`
for a five-minute paced local diagnostic. Optional `OUT=/absolute/path.json`
saves five-second receipt-gap samples and exact final stage counts. This local
gate requires at least 95% of requested intake rate, sampled receipt gap at
most two seconds at p95 and five seconds at peak, exact final counts, and final
drain within five seconds. Compare with the separate burst; neither qualifies
hosted or production capacity.

For higher-rate single-lane Phase 1 pressure, first create fresh source
generation with `SMOKE_ID=calcify-highrate-local make dev-smoke-calcify-high-rate`.
Note generation printed by smoke, then run
`make dev-soak-calcify-high-rate-load SMOKE_ID=calcify-highrate-local GENERATION=21 DURATION=300s PAIRS_PER_SECOND=2500 WORKERS=512 OUT=/private/tmp/calcify-highrate-load.json`
with actual printed generation in place of `21`. Check source and both link
streams with
`make dev-verify-calcify-high-rate LOAD_REPORT=/private/tmp/calcify-highrate-load.json OUT=/private/tmp/calcify-highrate-verify.json`.
Load command reports offered, dropped, accepted, five-second conservative
upper receipt gap, and drain; its gate requires at least 95% requested
acceptance, at most 5% offered pairs dropped, exact receipts, bounded gap,
and drain within five seconds. Verification command enforces
exact intake, source, commitment, verification, receipt, and matching counts.
Use fresh smoke ID/generation for each run; do not reuse pair ID ranges.
Run short higher-rate probe and five-minute gate after each material phase.
Loader now classifies every validated HTTP acknowledgement against fixed monotonic
deadline, excluding late/equal acknowledgements. Receipt drain starts at HTTP
worker completion, before waiting for final progress query. Reports include
start/deadline/completion epoch timestamps and definitions. Older artifacts retain
their original sampled-deadline and post-sampler-drain measurements.

Joined Phase1/2 measurements use `scripts/dev/calcify-joined-capacity.mjs`,
a prebuilt Go load binary, and compiled test observers. Each run requires fresh
evidence directory, source generation and topics. Lightweight resolved counter
runs during load; independent source-to-Protobuf full-fact audit and exact Phase1
accounting run afterward. Scope and actual broker durability must be frozen;
RF1 local results do not qualify RF3/hosted capacity.
Optional `--instrument-ids` CSV on Go loader spreads complete pairs across instruments;
seed extras with `DEV_CALCIFY_FULL_PATH_INSTRUMENT_IDS` and pass same list via
`CALCIFY_JOINED_INSTRUMENT_IDS`. Default remains single `AAPL-<smoke-id>` lane.
Observe actual partition spread; distinct instruments need not map to distinct partitions.
`REEF_BOUNDARY_PG_SHARED_BUFFERS` defaults128MB; `REEF_BOUNDARY_PG_COMMIT_DELAY_MICROS`
defaults0. Nonzero commit delay groups concurrent WAL flushes while keeping
`fsync` and `synchronous_commit` on; record actual settings and latency tradeoff.
See [PostgreSQL WAL configuration](https://www.postgresql.org/docs/16/runtime-config-wal.html).

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

Calcify Phase 2 adds `calcify-phase2` profile from same overlay. Start extractor
first so registered generation binds source topic UUID, then start
`calcify-resolver`. It publishes Protobuf `REEF_MATCH_CONTEXT_RESOLVED_V1`, using
named `calcify-resolver-state` volume; local replication factor1 is diagnostic.
`CALCIFY_RESOLVER_REPLICATION_FACTOR=3` requires RF3 source/verified/output and
Redpanda `write.caching=false` on canonical topics; Kafka backend instead requires minimum ISR2. Runtime validates actual settings. [Implementation and operations](work/CALCIFY_PHASE2_IMPLEMENTATION.md)
cover memory budgets, fault disposition, source retention, health and recovery.
