# Reef Bot Arena App

SvelteKit static application for the Bot Arena public landing page, game-mode
descriptions, public leaderboard, participant bot configuration, and
GitHub-authenticated operator workflows.

The deployed app is an operational backbone surface, not the source of trading
or scoring truth. It reads versioned `/api/v1/arena/...` public routes and
authenticated `/admin/v1/...` routes from `platform-runtime` through Caddy.

## Local Development

From the repository root:

```bash
bun install --frozen-lockfile
bun run arena-admin:dev
```

Copy `.env.example` to `.env` in this directory when a non-default API base or
explicit local fixture mode is required. Local fixture flags are development
only and must not appear in production output.

## Verification

```bash
bun run arena-admin:check
bun run arena-admin:build:guarded
bun run arena-admin:ui-audit
```

The guarded build scans the static output for local fixture markers. CI also
runs the Svelte typecheck and static build.

For the real GitHub OAuth/Admin DB local smoke, environment requirements, and
owner-config setup, see
[`docs/BOT_ARENA_AUTH_AND_PROVISIONING.md`](../../docs/BOT_ARENA_AUTH_AND_PROVISIONING.md).
For launch status and external-submission blockers, see
[`docs/BOT_ARENA_RELEASE_READINESS.md`](../../docs/BOT_ARENA_RELEASE_READINESS.md).

## Release Status

The app and same-repository test-submission path are in limited preview. Open
fork-based bot submissions are not available yet.
