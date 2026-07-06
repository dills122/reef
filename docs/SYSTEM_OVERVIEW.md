# Reef System Overview

Last aligned: 2026-07-06.

## The Short Version

Reef has three layers that are easy to mix up:

| Layer | What it is | Where it runs | Keeps durable state? |
|---|---|---|---|
| Infrastructure backbone | The always-on control-plane server. It keeps admin, secrets, and durable meta/analytics state. | Hetzner | Yes |
| Run plane | Temporary compute for heavy simulations and benchmarks. | DigitalOcean | Only for the lifetime of a run; exports results back |
| Venue runtime stack | The actual trading venue services: API, matching engine, streams, DBs, materializers, projectors. | Local dev, DO run plane, or lightly on Hetzner for admin/smoke | Yes, inside that stack |

The simulator is not a fourth platform. It is a driver that sends commands into
the venue runtime stack through the same API path as users and bots.

## Mental Model

```text
Always-on backbone, Hetzner
  OpenBao, Admin API, Caddy, admin DB, analytics DB, backups
  durable home for bot registry, secrets, admin config, analytics summaries

Ephemeral run plane, DigitalOcean
  starts only for simulation/soak work
  runs the full venue runtime stack under load
  exports summaries/artifacts back to the backbone
  then gets destroyed

Venue runtime stack
  platform-api, matching-engine, durable streams, Postgres stores
  materializer and projectors
  this is where order commands become venue facts and read models

Simulator/tooling
  scenario-plan, scenario-smoke, load-tester, intake-bench, Bot SDK harness
  drives the venue runtime stack through /api/v1
```

## What Runs Where

| Thing | Always-on backbone | DO run plane | Local dev |
|---|---:|---:|---:|
| OpenBao | Yes | No | Optional/no |
| Admin API | Yes | No, except test stacks | Yes, as platform runtime |
| Caddy public narrow routes | Yes | No | No |
| Admin DB | Yes | No | Local equivalent only |
| Analytics DB | Yes | No | Local equivalent only |
| Full trading runtime stack | Light/admin/smoke only | Yes | Yes |
| Matching engine under load | No | Yes | Yes |
| Simulator/load generator | Manual/light only | Yes | Yes |
| Long soak tests | No | Yes | Sometimes, but DO preferred |
| Backup to R2 | Yes | Artifacts/debug only | No |

## Main Data Flow

```text
bot / simulator / user
  -> venue runtime API
  -> durable command stream/topic
  -> matching engine
  -> durable venue event batch
  -> materializer writes canonical Postgres facts
  -> projectors build read models
  -> reports/replay/analytics
  -> run-plane export sends summaries/artifacts back to backbone
```

The backbone should not be the hot trading database for a heavy run. The DO run
plane should not directly mutate the backbone databases. Results move back
through an API/export boundary.

## Docs Map

Read in this order:

1. [`SYSTEM_OVERVIEW.md`](./SYSTEM_OVERVIEW.md) - this file.
2. [`SYSTEM_INFRASTRUCTURE_BACKBONE.md`](./SYSTEM_INFRASTRUCTURE_BACKBONE.md) - Hetzner always-on control plane.
3. [`SYSTEM_BACKBONE_SIMULATOR_TOPOLOGY.md`](./SYSTEM_BACKBONE_SIMULATOR_TOPOLOGY.md) - how backbone, run plane, runtime, and simulator connect.
4. [`SYSTEM_BACKBONE_SERVICES.md`](./SYSTEM_BACKBONE_SERVICES.md) - services inside a running venue runtime stack.
5. [`SYSTEM_SIMULATOR_ENVIRONMENT.md`](./SYSTEM_SIMULATOR_ENVIRONMENT.md) - simulator tools, profiles, reports, and evidence.

## Terms

| Term | Meaning |
|---|---|
| Backbone | In these docs, means the always-on Hetzner infrastructure backbone unless qualified. |
| Runtime stack | The API/engine/stream/Postgres/materializer/projector services that run a venue. |
| Run plane | The temporary DO host used for heavier simulation runs. |
| Admin API | `platform-runtime` running on the backbone for registry/admin/OpenBao work. |
| Materializer | Runtime role that turns durable venue event batches into canonical Postgres facts. |
| Projector | Runtime role that turns canonical facts into read models. |
| No-op/no-DB profile | Diagnostic profile only; not a durable production-like claim. |

## Current Truth

- Hetzner backbone exists under `infra/hetzner-core`.
- OpenBao, Caddy, `platform-runtime`, `postgres`, `postgres-admin`, and
  `postgres-analytics` are represented in the backbone compose stack.
- DO simulation infrastructure still needs cleanup to fully mirror the Hetzner
  OpenTofu + compose + deploy-script pattern.
- The export/cleanup service that pushes finished run summaries back to the
  backbone is planned, not complete.
- The venue runtime stack has strong local evidence for the direct
  command-stream -> matching-engine -> event-batch -> materializer path, but
  still needs longer remote durability/recovery proof.
