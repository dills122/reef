# Arena Control Plane

This optional Kotlin artifact contains Bot Arena product behavior that must not
be compiled into or required by the Reef-only platform runtime.

## Responsibilities

- Arena bot registry, version lifecycle, qualification, and operator decisions
- Arena-owned user/bot entitlements and submission admissions
- SHA-bound invite approval and trusted provisioning use cases
- Arena run, result, enforcement, and leaderboard routes
- owner-scoped OpenBao bot configuration and provisioning adapters
- bot-version account-risk extension supplied through Reef's product-neutral port
- Arena schema validation and persistence integration

The module depends on `services/platform-runtime` contracts. The reverse
dependency is forbidden. Matching and settlement behavior remain Reef-owned,
and Arena actors submit through normal Reef `/api/v1` paths.

## Build And Test

From the repository root:

```bash
make build-arena-control-plane
make test-arena-control-plane
make check-reef-arena-boundaries
```

`build-arena-control-plane` first builds the Reef core jar, then packages the
Arena service-loader extensions into the Arena artifact.

## Local Profile

The default `make dev-up` stack is Reef-only. Start the explicit Arena overlay
with:

```bash
make dev-up-arena
make dev-smoke-arena
```

`compose.arena.yml` replaces the API image with the Arena-enabled artifact and
adds the Arena datasource/migrations. `PLATFORM_ARENA_ADMIN_ENABLED=true` enables
route registration; `ARENA_POSTGRES_JDBC_URL`, `ARENA_POSTGRES_USER`, and
`ARENA_POSTGRES_PASSWORD` configure Arena persistence.

Use `make dev-smoke-reef` to prove Arena routes are absent from the Reef-only
profile and `make dev-smoke-arena-isolation` to prove Arena storage failure does
not compromise Reef venue behavior.

## Current Release Boundary

Fork submissions can enter `pending_invite_review`, and a maintainer-only
base-branch workflow can approve the exact head SHA before dispatching trusted
provisioning. This is an invite-only path, not open self-service intake. See
[`../../docs/BOT_ARENA_RELEASE_READINESS.md`](../../docs/BOT_ARENA_RELEASE_READINESS.md)
for the current external-proof and launch gates.
