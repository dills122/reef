# Reef And Bot Arena Separation Promotion

## Decision

**Promoted for the invite-preview prerequisite on 2026-07-19.** Reef can be
built, tested, started, and exercised without Arena code or storage; the
explicit Arena overlay restores its optional control plane without changing the
Reef P1 facts or matching-engine image. This is local release-equivalent
Compose evidence, not a hosted deployment rehearsal.

The implementation base is `c7a139a0`; the promotion evidence and its
repeatable comparator are on `ec752361`.

## Evidence Matrix

| Gate | Evidence | Result |
| --- | --- | --- |
| Reef artifact and classpath | `make test-reef-core`; `make build-reef-core` rejects `arena` entries in `platform-runtime.jar` | Pass |
| Arena-owned artifact | `make test-arena-control-plane` builds and tests `services/arena-control-plane` after the Reef artifact | Pass |
| Dependency/Compose boundary | `make test-reef-core`, `make dev-compose-config ARGS='--services'`, and `DEV_COMPOSE_FILES='compose.base.yml,compose.local.yml,compose.arena.yml' make dev-compose-config ARGS='--services'` | Pass; base has no Arena service, storage, or migration dependency |
| Reef-only runtime | `make dev-smoke-reef` on `compose.base.yml,compose.local.yml` | Pass; normal venue smoke works and Arena leaderboard is absent (`404`) |
| Arena overlay runtime | `make dev-smoke-arena` with `compose.arena.yml` | Pass; normal venue smoke works and Arena routes are present |
| Failure isolation | `make dev-smoke-arena-isolation` after stopping Arena Postgres | Pass; Reef health and venue smoke remain available, then Arena storage and route recover |
| Same canonical behavior | Clean P1 live assertions for `separation-reef-p1-20260719` and `separation-arena-p1-20260719`, compared by `make dev-compare-reef-arena-separation` | Pass; deterministic proof hash `250c0400c25f692662b42d903930d2834b88acf5d1751c7e2aa505455577ec29` matches |
| Matching-engine identity | Local Compose image id in both profiles | Pass; `sha256:084c768e290669759fa84a0e0d2a80aa5775d19edafa3d6ec9921e3d899e283e` |

The P1 comparison verifies all 28 assertions including command completion,
hidden resting-depth non-disclosure, two trades totaling 100 at the expected
price, participant-scoped fill privacy, public tape privacy, declared read
sources, and zero required projection lag.

## Repeatable Local Proof

Run the normal static gates first:

```sh
make test-reef-core
make test-arena-control-plane
make dev-compose-config ARGS='--services'
DEV_COMPOSE_FILES='compose.base.yml,compose.local.yml,compose.arena.yml' make dev-compose-config ARGS='--services'
```

Then run each profile from a clean local stack, with the normal local admin
authentication and P1 projectors configured. Capture one report per profile
using distinct `--scenario-run-id` values and compare them:

```sh
make dev-compare-reef-arena-separation ARGS='REEF_REPORT.json ARENA_REPORT.json --out=COMPARISON.json'
```

The comparator intentionally excludes run identifiers and observation
timestamps. It requires both reports to pass and compares deterministic P1
identity, assertion evidence, projection lag/watermarks, and hidden-depth
visibility evidence exactly. Its focused regression check is:

```sh
bun scripts/dev/compare-reef-arena-separation-reports.test.mjs
```

## Scope And Follow-up

This promotion satisfies the separation prerequisite for the invite-preview
sprint. It does not approve public submission or replace its fork-safe trusted
provisioning, admission-window, external-account, hosted, backup/recovery, or
security gates. Those remain governed by
[`BOT_ARENA_INVITE_PREVIEW_SPRINT.md`](./BOT_ARENA_INVITE_PREVIEW_SPRINT.md)
and [`BOT_ARENA_RELEASE_READINESS.md`](./BOT_ARENA_RELEASE_READINESS.md).
