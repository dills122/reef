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
- one platform projector/read API

Stream-ack workers, scaled projectors/materializers, observability, and hosted
security hardening are follow-up slices.

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
make kube-materializer-up
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

## Useful Environment

```sh
JS_RUNTIME=node make kube-up
KUBE_BUILD_IMAGES=0 make kube-up
KUBE_BUILD_IMAGES=0 KUBE_IMPORT_IMAGES=0 make kube-apply
KUBE_WAIT_TIMEOUT_SECONDS=600 make kube-up
REEF_PLATFORM_API_HOST_PORT=18080 REEF_MATCHING_ENGINE_HOST_PORT=18081 make kube-smoke
```

Use `KUBE_BUILD_IMAGES=0` to reuse existing local Docker images while still
importing them into a fresh k3d cluster. Use `KUBE_IMPORT_IMAGES=0` only when
the cluster already has the desired local image tags.

## Local Resource Notes

The manifests set pod resource requests, limits, ephemeral-storage limits,
`automountServiceAccountToken: false`, baseline namespace pod security
enforcement, and compatible container security contexts for app pods. The k3d
cluster disables its load balancer container, Traefik, and ServiceLB because
this workflow uses local port-forwards rather than ingress or load balancer
services.

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
