# Dev Compose Configuration Overhaul Plan

## Purpose

Reduce local-development configuration noise without removing the throughput and deployment tuning knobs Reef needs for deterministic replay, durable command intake, and performance evidence.

The local stack is expressed as layered Compose configuration with explicit operational intent.

## External Check

Docker's current Compose guidance supports this direction:

- Multiple Compose files can be merged with repeated `-f` flags.
- Environment-specific overrides should contain only the changes from the base file.
- Profiles are appropriate for optional services and use-case-specific services.
- `docker compose config` is the intended way to inspect the resolved application model.
- All relative paths in merged files resolve relative to the first Compose file, so Reef should keep the base file at the repository root unless every path is reviewed.

## Target Shape

Keep the service-owned Dockerfiles in their current service directories. Split Compose by operational intent:

```text
compose.base.yml                shared service topology and health checks
compose.local.yml               local ports, local data services, dev credentials
compose.local.stream-ack.yml    local durable stream-ack topology
compose.local.benchmark.yml     no-DB/direct-stream/capacity-test overrides
compose.hosted.yml              deployment-shaped defaults with external stores
compose.hosted.<provider>.yml   provider-specific overrides only when needed
```

Provider-specific files should wait for real differences. Avoid splitting by subsystem alone; too many small files would make the stack harder to reason about than the monolith.

## Completed Foundation

The initial implementation added central Compose-file selection:

- `REEF_COMPOSE_FILES` or `DEV_COMPOSE_FILES` can opt into an ordered file list.
- The default local dev stack is `compose.base.yml,compose.local.yml`.
- `make dev-compose-config ARGS="--services"` or `bun scripts/dev/reef-dev.mjs stack compose-config --services` prints the resolved configuration.
- Dev stack scripts call the shared Compose helper instead of hard-coding Compose files.

## Migration Phases

1. Centralize Compose invocation in scripts. Done: `make dev-compose-config` delegates to `reef-dev.mjs stack compose-config`, and dev scripts call the shared Compose helper.
2. Establish the Reef-only base/local split and the explicit Arena overlay. Done: `compose.base.yml` and `compose.local.yml` are canonical; `compose.arena.yml` is opt-in. The root compatibility monolith was retired to prevent optional-product coupling and configuration drift.
3. Move stream-ack and materializer shape into local overlays. Not started: no `compose.local.stream-ack.yml` exists.
4. Move benchmark-only no-DB/direct-stream settings into a benchmark overlay. Not started: no `compose.local.benchmark.yml` exists.
5. Add hosted overlays only after the local split is stable. Not started: no `compose.hosted*.yml` files exist.
6. Add a guard that compares or validates resolved configs for the common Make targets. Not started as its own phase: the phase-2 parity script only checks the base/local split against the monolith; it does not yet cover the stream-ack, benchmark, or hosted overlays from phases 3-5.

## Guardrails

- Do not weaken `202 Accepted` durability semantics in stream-backed modes.
- Do not hide benchmark-only settings behind names that look production-safe.
- Keep normal local setup small: `cp .env.example .env`, `make dev-up`, `make dev-smoke`.
- Treat `.env.example` as an operator guide, not a dump of every tuning knob.
- Validate every split with `docker compose config` before changing runtime behavior.
