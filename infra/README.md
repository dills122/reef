# Reef Infrastructure

This directory owns hosted and remote-run infrastructure. The root Compose files
own local development only.

## Ownership Map

| Area | Path | Status | Purpose |
|---|---|---|---|
| Local dev stack | `../compose.base.yml`, `../compose.local.yml` | Active | Local developer workflow, smoke tests, stress profiles, and CI-style local validation. |
| Local Kubernetes | `local-kube/` | Experimental | k3d/kubectl prototype for Kubernetes-shaped local runtime validation. |
| Hetzner backbone | `hetzner-core/` | Active | Always-on control plane: OpenBao, Admin API, Caddy, runtime/admin/analytics Postgres, backups, and lightweight smoke/admin workflows. |
| DO benchmark harness | `do-benchmark/` | Active bridge | Current ephemeral DigitalOcean worker implementation used by `scripts/deploy/simulation-run.mjs`. |
| Simulation runner | `simulation-runner/` | Planned target | Dedicated run-plane shape that should eventually own its own OpenTofu, server Compose bundle, and deploy script. |

## Compose Boundaries

Hosted backbone Compose is intentionally separate from local developer Compose:

- local dev uses the root layered Compose files through `make dev-up`,
  `make dev-up-stream-ack`, and related targets
- local Kubernetes uses `infra/local-kube/` through `make kube-up` and related
  targets while it is experimental
- Hetzner backbone uses `hetzner-core/server/docker-compose.yml` plus
  `docker-compose.stream-ack.yml` when the stream-ack profile is needed
- the future DO simulation run plane should get its own
  `simulation-runner/server/docker-compose.yml`

Do not make hosted infra import the root local Compose files as a shared base.
They have different defaults, secrets posture, lifecycle, and operator
expectations.

## Current Bridge

`scripts/deploy/simulation-run.mjs` still wraps
`scripts/dev/do-benchmark-host.sh`. That path provisions a disposable
DigitalOcean worker, syncs the repository, starts the selected root local
benchmark profile on the worker, collects artifacts, optionally exports
compressed debug artifacts to R2, and destroys the worker unless retained.

Treat that as bridge tooling, not the final hosted run-plane layout.
