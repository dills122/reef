# Script Surface Analysis

Date: 2026-07-08

## Summary

The repository has a large script surface, but most of it is intentional operational automation rather than accidental clutter.

- `113` JavaScript/TypeScript/shell script files under `scripts/`
- `74` SQL migrations under `scripts/dev/db/migrations/`
- `69` top-level dev entrypoints
- `28` script test files
- `9` shared script libraries
- `3` deploy entrypoints and `3` CI helper scripts

The safe direction is to preserve existing Makefile/package entrypoint names and consolidate implementation behind them. Many commands are documented operational workflows, so deleting or renaming them would create more churn than value.

## Script Families

| Family | Examples | Recommendation |
| --- | --- | --- |
| Dev stack lifecycle | `up.mjs`, `down.mjs`, `reset.mjs`, `runtime-nodb-up.mjs`, `captured-ack-up.mjs`, `stream-ack-up.mjs`, `stream-direct-nodb-up.mjs` | Keep entrypoints; move profile-specific env defaults into config-driven helpers. |
| Stress and performance profiles | `stress.mjs`, `runtime-nodb-stress.mjs`, `stream-ack-stress.mjs`, `stream-direct-nodb-stress.mjs`, `venue-event-materializer-stress.mjs`, `throughput-campaign.mjs` | Keep domain workflows; consolidate repeated profile/session config generation. |
| Bot SDK and arena tooling | `bot-sdk-*.mjs`, `arena-*.mjs` | Keep. These are contract and runner tools with package/docs references. Refactor only behind stable commands. |
| Command-log maintenance | `command-log-pin.mjs`, `command-log-prune.mjs`, `command-log-integrity-check.mjs` | Keep separate entrypoints because the operations have different blast radius. Share SQL/CLI helpers if they grow. |
| Replay and simulation export | `replay-pack.mjs`, `sim-run.mjs`, `sim-batch.mjs`, `export-simulation-run.mjs` | Keep. These preserve scenario/replay evidence workflows. |
| Kube and remote infra | `kube.mjs`, `do-benchmark-host.sh`, `scripts/deploy/*.mjs` | Keep, but harden checks. `do-benchmark-host.sh` is the main shell-heavy exception. |
| Setup links | `setup-codex-links.mjs`, `setup-claude-links.mjs` | Candidate for one config-driven link setup script while retaining aliases. |

## Best Consolidation Candidates

1. **Profile-driven stack wrappers**

   The `*-up.mjs` files mainly set environment variables and call the shared stack runner. Keep the command names, but move profile definitions into a shared profile map, for example:

   - `runtime-nodb`
   - `captured-ack`
   - `stream-ack`
   - `stream-direct-nodb`

   This would reduce repeated `setDefault`, `setValue`, provider normalization, and summary printing logic.

2. **Shared generated stress session config**

   `stream-ack-stress.mjs` and `stream-direct-nodb-stress.mjs` both generate spread-equity submit scenarios with similar actors, market fields, and mix settings. Move that into a small shared builder such as `scripts/dev/lib/stress-session-config.mjs`.

3. **Bot/arena worker children stay hidden behind parents**

   Files such as `arena-runner-pool-worker.mjs`, `arena-runner-bench-node.mjs`, and `arena-runner-bench-deno.ts` are implementation children. Keep them as separate files because worker isolation is useful, but they should remain referenced by parent commands rather than exposed as new Make targets.

4. **Setup link scripts**

   `setup-codex-links.mjs` and `setup-claude-links.mjs` can probably share a single implementation with per-agent config. This is low risk and low priority.

## Do Not Slim Yet

- Do not collapse command-log pin/prune/integrity into one script. Their safety profiles differ, and separate commands make reviews and runbooks clearer.
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

The next low-risk implementation cleanup should be a profile helper for stack wrappers. That should be done as a compatibility-preserving refactor:

1. Add a shared profile module.
2. Convert one wrapper, likely `runtime-nodb-up.mjs`, with tests.
3. Convert the remaining wrappers after behavior diff is clear.
4. Leave every current Makefile/package command in place.
