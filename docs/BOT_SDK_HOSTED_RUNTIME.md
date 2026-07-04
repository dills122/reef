# Bot SDK Hosted Runtime Notes

## Purpose

Define the intended hosted execution model for untrusted `ReefBotV1` code.

The current sidecar registration harness is a preflight and qualification layer. It is not the full sandbox.

## Current Sidecar Coverage

Implemented now:

- single-file TypeScript bot contract
- required metadata validation
- basic git author email capture
- source hash reporting
- registry validation
- forbidden hosted API source scan
- fixture-backed lifecycle qualification
- action and data-call limit checks
- proposed-action to venue-command mapping tests

This is enough to reject obvious bad submissions and prove SDK shape. It is not enough to safely run arbitrary public code by itself.

## Target Hosted Sandbox

Hosted execution should combine JavaScript-level confinement with an outer process or container boundary.

Recommended layers:

1. Compile/typecheck bot source against the pinned SDK.
2. Run static source and dependency scans.
3. Load bot code into a restricted SES compartment.
4. Expose only the SDK capability object.
5. Disable or omit network, filesystem, timers, child processes, worker threads, and native modules.
6. Run each bot execution context inside an OS/container boundary.
7. Enforce memory, CPU, wall-time, output, and log limits outside the JS runtime.
8. Kill, freeze, quarantine, or ban bot versions that violate policy.

SES is useful for controlling globals and imports. It should not be treated as the only security boundary.

## V1 Hosted Globals

Hosted v1 should not expose:

- `fetch`
- `WebSocket`
- `setTimeout`
- `setInterval`
- `process`
- `Buffer`
- Node built-in module imports
- dynamic `import()` outside the approved loader
- filesystem APIs
- child process APIs
- worker thread APIs

Hosted v1 may expose:

- deterministic `ctx.clock`
- deterministic `ctx.random`
- bounded `ctx.log`
- approved `ctx.orders`, `ctx.marketData`, and `ctx.historical` clients
- immutable in-memory `ctx.config`

## Dependency Policy

V1 starts with only the SDK import. Future allowlisted packages need:

- explicit package and version allowlist
- lockfile or vendored artifact hash
- license review
- static scan
- native-module rejection unless explicitly approved
- deterministic/replay review
- resource usage qualification

## Runtime Enforcement

The runtime should enforce:

- per-tick wall-time budget
- max order actions per tick
- max trade commands per second
- max data calls per tick
- max log bytes per tick/run
- max retained bot state size
- max memory
- CPU quota
- operator kill switch
- bot-version freeze/quarantine/ban state

All limits should be configuration-driven by arena/runtime policy.

## Local Development

Local development may run with a lighter harness, but it should still use proposed actions and the same SDK clients. A future local adapter can submit approved actions to `/api/v1`, but bot code should not create its own Reef client.

Keeping local and hosted execution aligned prevents bots from passing local tests while relying on capabilities that hosted mode denies.

