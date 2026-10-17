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

Finite P0 source work uses isolated matching process/stack with
`MATCHING_ENGINE_CALCIFY_SOURCE_PROFILE` set to JSON from
[`finite-source-profile-v1.json`](../contracts/calcify/finite-source-profile-v1.json)
and `MATCHING_ENGINE_TERMINAL_ORDER_RETENTION_LIMIT=0`. Compose forwards profile;
unset/empty preserves ordinary dev behavior. Do not enable on shared legacy stack:
undeclared run/session/instrument commands reject. [Matching profile contract and
checks](../services/matching-engine/README.md#finite-calcify-p0-source-profile)
describe finite identity/quantity limits and restore. This is source acceptance,
not financial settlement or throughput qualification.

Apply forward-only migrations, then add `compose.calcify.yml` to `REEF_COMPOSE_FILES`
and select profiles `redpanda,calcify-phase1`. This runs independent extractor,
stub verifier, and receipt worker beside existing post-matching services.
`CALCIFY_STAGE` is set only inside optional sidecars; default stack behavior
does not change. [Contract](../contracts/calcify/README.md) and
[local diagnostic evidence](https://github.com/dills122/reef-records/blob/ecd00e479853bcb9fd842b33f017289f37499226/records/reef/docs/work/CALCIFY_PHASE1_IMPLEMENTATION.md) describe
link semantics, tests, and replay-retention limits.

`CALCIFY_VERIFIER_MAX_POLL_RECORDS` and `CALCIFY_RECEIPT_MAX_POLL_RECORDS`
accept1–1000, default100, and reject malformed explicit values before startup.
They bound verifier transaction and receipt batch sizes; extractor remains100
source records per poll and publishes valid partition prefixes plus source offsets
in one transaction. Zero-trade source records checkpoint without producing links.
Larger values require measured backlog/latency and poison-prefix replay checks.


For durable direct-ingress capacity work, use `scripts/dev/calcify-direct-bootstrap.mjs`
and `scripts/dev/calcify-direct-capacity.mjs`; their frozen evidence and exact setup
are recorded in [direct throughput campaign](https://github.com/dills122/reef-records/blob/f3c9941d5cd35df81b32970e9873ad8ca9f86391/records/reef/docs/research/CALCIFY_DIRECT_THROUGHPUT_2026-10-02.md).
This route reuses C5 in-memory intake and direct matching controls, runs Calcify
through managed resolved output, and omits receipt/legacy SQL settlement workers.
Source generation/topic identity SQL remains startup/rebalance control work.
For isolated research bootstrap, optional `CALCIFY_DIRECT_PROJECT` assigns unique
container names and proof labels; `CALCIFY_DIRECT_SUBNET` selects an inspected unused
subnet and `CALCIFY_DIRECT_BROKER_IMAGE` records explicit broker-version override.
Defaults remain unchanged; host ports must still be free. See
[aged contention proof](https://github.com/dills122/reef-records/blob/f3c9941d5cd35df81b32970e9873ad8ca9f86391/records/reef/docs/research/CALCIFY_AGED_CONTENTION_PROOF_2026-10-11.md) for
frozen setup, synthetic financial contender and measured limits.
The October 11 contention runner and synthetic SQL contender are preserved with
the frozen setup in Records. That dated runner hardcodes its original container
project and image; use the current direct or full-chain profiles below for new
measurements rather than treating its historical command as a supported profile.
[Matched control](https://github.com/dills122/reef-records/blob/f3c9941d5cd35df81b32970e9873ad8ca9f86391/records/reef/docs/research/CALCIFY_MATCHED_CONTROL_2026-10-11.md)
records configuration equality and sequential-run limits. Do not reinterpret
no-restart capacity as a successful recovery proof.
`MATCHING_ENGINE_KAFKA_MAX_MESSAGE_BYTES` defaults to 1 MiB; explicit larger values
must coordinate source topic `max.message.bytes` and consumer source-byte budgets.
This setting changes neither command batch size nor transaction ordering.

**One supported way to run Calcify locally.** Every Calcify run starts from `scripts/dev/calcify-direct-bootstrap.mjs`, the profile that held 10k trades/s (October 2, D7): Netty HTTP, in-memory bounded intake with a durable Redpanda acknowledgement, 16 command partitions and 16 direct matching lanes, the Phase 1 extractor and verifier, the managed Phase 2 resolver, and standing BUY makers so that one timed SELL is one trade. Do not assemble another stack by hand: a per-order PostgreSQL intake on the JDK HTTP server with four partitions is a different, much slower profile, and two campaigns reported false limits from it.

- `make calcify-fullchain-soak FC_RATE=1000 FC_SECONDS=60` (`scripts/dev/calcify-fullchain-soak.mjs`): the whole path including the capacity finance lanes and their projection, paced, with a sampler for every stage and an exact decision count at the end. Builds the images from the checkout and tears the stack down. See docs/work/CALCIFY_CAPACITY_RUNTIME.md, "Full-chain paced run".
- `scripts/dev/calcify-direct-capacity.mjs`: the capture-stage qualification driver (frozen policy, independent full-fact parity oracle); see [direct throughput](https://github.com/dills122/reef-records/blob/f3c9941d5cd35df81b32970e9873ad8ca9f86391/records/reef/docs/research/CALCIFY_DIRECT_THROUGHPUT_2026-10-02.md) for its reproduction recipe.
- Dedicated hosts: `make calcify-droplet-run MATRIX=fullchainsmoke` (paid; needs the owner's go with size and cost).

On October 16, 2026 the older entry points were archived to Reef Records ([import note](https://github.com/dills122/reef-records/blob/fc291fce3931fe644101da534c4d17b0d677d722/docs/imports/2026-10-16-reef-script-cleanup.md)): `calcify-full-path-smoke.mjs` and the Make targets that wrapped it (`dev-smoke-calcify-full-path`, `dev-stress-calcify-basic`, `dev-soak-calcify-phase1`, `dev-smoke-calcify-high-rate`, `dev-soak-calcify-high-rate-load`, `dev-verify-calcify-high-rate`, `dev-smoke-calcify-phase2`), `calcify-joined-capacity.mjs`, `calcify-verify-existing.mjs` and the October 1 resolver runners. The Go load tool (`services/simulator/cmd/calcify-load`) stays; its `-instrument-ids`, `-workload-mode` and `-quantity` flags are the supported knobs.
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
