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
- typed hosted sandbox policy and source scanner

This is enough to reject obvious bad submissions and prove SDK shape. It is not enough to safely run arbitrary public code by itself.

## Current Hosted Runner Slice

`packages/bot-sdk/src/hosted-runner.ts` is the first executable hosted-runner boundary. It expects a compiled single-file JavaScript bot artifact, scans that artifact with `reefBotHostedSandboxPolicyV1`, evaluates it in a SES-compatible compartment, and passes the loaded default bot class into the deterministic scenario runner.

The default factory uses a global SES `Compartment` after lockdown/bootstrap. Tests inject a VM-backed compartment so the contract can run locally without adding a package install step. That VM test compartment is not the production security boundary; it only keeps the hosted runner API, SDK endowment, and scanner gate under regression coverage.

Hosted bundles receive a single SDK endowment:

```js
const { ReefBotV1 } = __reefBotSdk;
```

They do not receive Reef transports, network clients, filesystem APIs, timers, child processes, worker threads, or ambient process access. The runner/orchestrator owns venue communication and injects only SDK capabilities.

The hosted runner wraps loaded bot classes with host-owned execution guards. Current defaults are `1000ms` for lifecycle hooks and `1000ms` for each tick, overrideable through `executionLimits`. These guards catch async hangs and report `do_not_merge`; they do not replace worker/container CPU isolation for synchronous infinite loops.

## Local Hosted Artifact Runner

Use the hosted artifact runner for compiled single-file JavaScript bot bundles:

```bash
bun scripts/dev/bot-sdk-hosted-run.mjs packages/bot-sdk/examples/hosted-simple-market-maker.bundle.js packages/bot-sdk/fixtures/aapl-multi-tick.json --unsafe-vm-for-local-dev
```

Without `--unsafe-vm-for-local-dev`, the script uses the default SES-compatible path and expects `globalThis.Compartment` to be available after SES lockdown/bootstrap. The unsafe VM flag is local-only test plumbing; it is not a hosted security boundary.

Add `--venue-url=http://127.0.0.1:8080` to submit approved actions through the adapter-owned venue client, just like the deterministic runner and live smoke wrapper.

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

The sidecar policy is represented by `reefBotHostedSandboxPolicyV1` and checked by `scanBotSourceForSandboxViolationsV1`. That policy is the source-level preflight contract. The future SES/container runtime still needs to enforce the same decisions at execution time.

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
