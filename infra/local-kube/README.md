# Reef Local Kubernetes Prototype

This is an experimental local Kubernetes runtime profile for Reef. It is not a
replacement for the root Compose workflow yet.

Use it to validate Kubernetes-shaped behavior that Compose does not model well:

- service discovery and readiness
- pod restarts and rollout status
- persistent volume behavior
- deployment/container environment shape
- localhost access through `kubectl port-forward`

The first slice supports the synchronous API smoke path:

- primary, boundary, projection, and arena Postgres databases
- NATS with JetStream enabled
- matching-engine
- platform-api

The materializer slice additionally supports:

- Redpanda
- matching-engine direct stream consume/publish
- platform materializer
- one platform projector worker plus a projection-backed read API

The stream-ack slice additionally supports:

- Redpanda-backed command intake
- four partition-owned platform worker deployments
- four partition-owned platform projector deployments
- the same 64-partition worker/projector split used by the root Compose
  `stream-ack` profile

Autoscaling is intentionally opt-in and limited to roles that are safe to add
replicas to locally. Do not autoscale Postgres, NATS, Redpanda, matching-engine,
or fixed partition worker/projector deployments without a partition ownership
design change.

Backbone services are not part of this profile. Use `make backbone-local-up`
for the local version of the always-on admin/secrets/control-plane stack. Keep
local kube focused on the venue runtime/run-plane shape.

Observability and hosted security hardening remain follow-up slices.

## Prerequisites

- Docker-compatible image build backend
- `k3d`
- `kubectl`
- `bun` or `node` via `JS_RUNTIME=node`

## Commands

```sh
make kube-up
make kube-smoke
make kube-reset
make kube-stream-ack-up
make kube-smoke-stream-ack
make kube-materializer-up
make kube-materializer-scale
make kube-autoscale-apply
make kube-smoke-venue-event-materializer
make kube-status
make kube-port-forward
make kube-down
```

`make kube-up` creates a `k3d` cluster named `reef-local`, builds the runtime
and engine images, imports them into the cluster, applies datastore manifests,
runs the existing migration set through `kubectl exec`, then applies the app
deployments.

`make kube-smoke` starts temporary port-forwards and reuses
`scripts/dev/smoke.mjs` against:

- `http://127.0.0.1:8080` for `platform-api`
- `http://127.0.0.1:8081` for `matching-engine`

`make kube-smoke-venue-event-materializer` starts the Redpanda direct-stream
materializer profile, then reuses the Compose materializer proof with kube
URLs and `kubectl exec -i` Postgres queries.

`make kube-stream-ack-up` starts a Redpanda-backed stream-ack profile with four
partition-owned workers and four partition-owned projectors. This mirrors the
root Compose `stream-ack` worker/projector partition split while keeping each
partition range explicit in Kubernetes manifests.

`make kube-smoke-stream-ack` starts the same stream-ack profile, submits one
isolated command through the public API, verifies the durable stream reference
from `/api/v1/commands/{commandId}`, waits for the owning partition worker to
complete it, then checks the canonical submit result and projected submit row
through `kubectl exec`.

`make kube-materializer-scale` starts the materializer profile and scales the
`platform-materializer` deployment. Override the replica count with
`KUBE_MATERIALIZER_REPLICAS`.

`make kube-autoscale-apply` applies local HPA rules for `platform-materializer`
and `platform-read-api`. k3d does not provide metrics-server by default; install
or enable metrics-server in the cluster before expecting HPA decisions to move
past `<unknown>` CPU metrics.

## Useful Environment

```sh
JS_RUNTIME=node make kube-up
KUBE_BUILD_IMAGES=0 make kube-up
KUBE_BUILD_IMAGES=0 KUBE_IMPORT_IMAGES=0 make kube-apply
KUBE_WAIT_TIMEOUT_SECONDS=600 make kube-up
KUBE_MATERIALIZER_REPLICAS=4 make kube-materializer-scale
REEF_PLATFORM_API_HOST_PORT=18080 REEF_MATCHING_ENGINE_HOST_PORT=18081 make kube-smoke
```

Use `KUBE_BUILD_IMAGES=0` to reuse existing local Docker images while still
importing them into a fresh k3d cluster. Use `KUBE_IMPORT_IMAGES=0` only when
the cluster already has the desired local image tags.

## Local Resource Notes

The manifests set pod resource requests, limits, ephemeral-storage limits,
`automountServiceAccountToken: false`, baseline namespace pod security
enforcement, and compatible container security contexts for app, NATS, and
Redpanda pods. The app images run as a non-root user and mount a bounded
`/tmp` volume so their root filesystems can stay read-only.

The local Postgres StatefulSets intentionally remain a local exception: the
official image performs first-run data directory permission setup against the
PVC before PostgreSQL drops privileges. Keep these databases isolated to the
k3d development namespace and do not treat this manifest as a hosted production
database baseline.

The k3d cluster disables its load balancer container, Traefik, and ServiceLB
because this workflow uses local port-forwards rather than ingress or load
balancer services.

Autoscaling guardrails:

- HPA manifests live in `80-autoscaling.yaml` and are not applied by default.
- HPA targets only `platform-materializer` and `platform-read-api`.
- Fixed partition workers/projectors scale by adding explicit deployments with
  non-overlapping partition ranges, not by increasing replicas behind one
  deployment.
- The materializer profile uses Redpanda consumer-group assignment, so multiple
  materializer replicas can share the same topic and group safely.
- The direct no-DB API profile uses in-memory intake/idempotency state; do not
  scale `platform-api` in that profile.

If pods are repeatedly evicted with `DiskPressure`, inspect Docker VM usage
before retrying:

```sh
docker system df
kubectl --context k3d-reef-local describe node k3d-reef-local-server-0
```

`docker builder prune` is usually safe for reclaiming build cache. Pruning
unused Docker volumes can reclaim much more space, but may delete unrelated
local database or broker data, so keep it a manual operator decision.

## Scope Guardrails

- Keep `make dev-up` on Compose until this path runs the same smoke and stress
  evidence reliably.
- Keep Kubernetes manifests explicit and inspectable while the profile is
  experimental.
- Add new profiles incrementally; do not copy every Compose service into
  Kubernetes before the base smoke path is stable.
