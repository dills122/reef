# Script Surface Analysis

Date: 2026-07-08

## Summary

The repository has a large script surface, but most of it is intentional operational automation rather than accidental clutter.

- `125` JavaScript/TypeScript/shell script files under `scripts/` after the `reef-dev.mjs` consolidation pass
- `80` SQL migrations under `scripts/dev/db/migrations/`
- `scripts/dev/reef-dev.mjs` now groups stack, stress, and local link setup profiles
- `3` deploy entrypoints and `3` CI helper scripts

The safe direction is to preserve existing Makefile/package entrypoint names and consolidate implementation behind them. Many commands are documented operational workflows, so deleting or renaming them without a compatibility layer would create more churn than value. For live counts, run `node scripts/dev/script-surface-check.mjs`.

## Script Families

| Family | Examples | Recommendation |
| --- | --- | --- |
| Dev stack lifecycle | `reef-dev.mjs stack up [profile]`, `reef-dev.mjs stack down`, `reef-dev.mjs stack reset` | Keep Makefile/package entrypoint names; keep profile-specific env defaults in `scripts/dev/lib/dev-stack-profiles.mjs`. |
| Stress and performance profiles | `reef-dev.mjs stress run [profile]`, `stress.mjs`, `venue-event-materializer-stress.mjs`, `throughput-campaign.mjs` | Keep domain workflows; consolidate repeated profile/session config generation. |
| Bot SDK and arena tooling | `bot-sdk-*.mjs`, `arena-*.mjs` | Keep. These are contract and runner tools with package/docs references. Refactor only behind stable commands. |
| Command-log maintenance | `command-log-pin.mjs`, `command-log-archive.mjs`, `command-log-archive-partitions.mjs`, `command-log-prune.mjs`, `command-log-integrity-check.mjs` | Keep separate entrypoints because the operations have different blast radius. Share SQL/CLI helpers if they grow. |
| Replay and simulation export | `replay-pack.mjs`, `sim-run.mjs`, `sim-batch.mjs`, `export-simulation-run.mjs` | Keep. These preserve scenario/replay evidence workflows. |
| Kube and remote infra | `kube.mjs`, `do-benchmark-host.sh`, `scripts/deploy/*.mjs` | Keep, but harden checks. `do-benchmark-host.sh` is the main shell-heavy exception. |
| Setup links | `reef-dev.mjs links codex|claude` | Keep package aliases; keep link logic in `scripts/dev/lib/dev-links.mjs`. |

## Best Consolidation Candidates

1. **Profile-driven stack wrappers** — Done (2026-07-08 cleanup pass). The repeated `setDefault`/`setValue` helpers across all `*-up.mjs`/`*-stress.mjs` files now live once in `scripts/dev/lib/dev-utils.mjs`. Profile-specific env defaults and summary printing remain per-file (they legitimately differ by profile), only the shared primitives moved.

2. **Shared generated stress session config** — Done. `scripts/dev/lib/stress-session-config.mjs` now generates the shared spread-equity submit session used by the stream stress profiles.

3. **Bot/arena worker children stay hidden behind parents**

   Files such as `arena-runner-pool-worker.mjs`, `arena-runner-bench-node.mjs`, and `arena-runner-bench-deno.ts` are implementation children. Keep them as separate files because worker isolation is useful, but they should remain referenced by parent commands rather than exposed as new Make targets.

4. **Setup link scripts** — Done. `reef-dev.mjs links codex|claude` now uses `scripts/dev/lib/dev-links.mjs` and `scripts/dev/lib/symlink-sync.mjs`; package aliases remain stable.

## Do Not Slim Yet

- Do not collapse command-log pin/archive/archive-partitions/prune/integrity into one script. Their safety profiles differ, and separate commands make reviews and runbooks clearer.
- Do not delete smoke scripts just because they overlap with stress scripts. Smoke gates encode operational readiness assumptions; stress scripts encode load evidence.
- Do not move bot SDK contract tests out of scripts until package-level test ownership is redesigned. They are heavily referenced by docs, Makefile, and package scripts.
- Do not rename Makefile targets without a compatibility layer.

## Hardening Added

This change adds `scripts/dev/script-surface-check.mjs` and wires it into:

- `make lint`
- the CI `node-dev-tooling` job

The gate now checks:

- JavaScript module syntax for all `.js` and `.mjs` files under `scripts/`
- shell syntax for all `.sh` files under `scripts/`
- script entrypoints are referenced by Makefile, package scripts, CI, docs, or parent scripts
- `.test.mjs` script tests are wired into Makefile, package scripts, or CI

The CI Node dev-tooling job now also runs the previously unwired focused tests:

- `scripts/dev/command-log-integrity-check.test.mjs`
- `scripts/dev/export-simulation-run.test.mjs`
- `scripts/dev/stream-profile-guard.test.mjs`

## Next Practical Cleanup

The next low-risk implementation cleanup should continue moving small wrapper-only entrypoints into domain CLIs while leaving Makefile/package names stable:

1. Prefer adding profiles to `reef-dev.mjs` over adding new one-off `scripts/dev/*-up.mjs`, `scripts/dev/*-stress.mjs`, or local setup wrapper files.
2. Keep high-risk workflows such as command-log maintenance as separate entrypoints until their safety profiles are unified.
3. Leave every current Makefile/package command in place.
