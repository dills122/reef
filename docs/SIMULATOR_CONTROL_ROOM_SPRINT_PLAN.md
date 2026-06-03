# Simulator Control Room Sprint Plan

## Purpose

Define the next high-value sprint for a local testing/admin UI around Reef's simulator.

The goal is to turn the existing CLI-driven simulator, stress harness, telemetry files, and reset scripts into an operator-style control room that makes test runs easier to configure, launch, monitor, compare, and reproduce.

This does not replace the CLI. The UI should orchestrate the same supported local commands and report artifacts so CLI, CI, and UI runs stay comparable.

## Current State Review

Existing strengths:
- `services/simulator/cmd/load-tester` already supports configurable load, modes, persona session configs, traces, reports, and pretty summaries.
- `make dev-stress`, `make dev-stress-diagnostics`, `make dev-sim`, `make dev-reset`, and replay-pack scripts already encode useful local workflows.
- stress reports already include throughput, accepted business ops, latency, reject taxonomy, trace checks, telemetry, and recommendations.
- persona/session config work gives the UI a real scenario model to build on.
- platform UI has a placeholder route direction for `simulation control room`.

Current gaps:
- no dedicated UI plan exists yet.
- `apps/platform-ui` is only a README placeholder.
- browser code cannot safely run local shell commands directly, so a local control API/BFF is required.
- reports live as files and are not indexed into a run history.
- test configuration is still mostly CLI flag knowledge.
- no visual run comparison, no run timeline, no quick "reset + run + compare" workflow.

## Hosting Model

There are two separate hosting tracks:

1. Project site / docs hosting.
- GitHub Pages is appropriate for project overview, docs, scenario examples, architecture notes, and public learning material.
- GitHub Pages should not be treated as the execution environment for simulations.

2. Platform control room hosting.
- Local developer mode runs the UI against a local `127.0.0.1` control API.
- Future public/demo mode requires a fully hosted Reef environment with a hosted control API, runtime, engine, database, worker orchestration, auth/safety controls, quotas, and artifact storage.
- The hosted simulation environment is a separate platform lift, not a static-site deployment.

Implication:
- the MVP should keep UI components portable, but all execution controls must go through an environment-specific backend.
- static docs can explain and showcase the simulator; the control room needs a live backend to run it.

## Sprint Pitch

Build a local-only Simulator Control Room MVP.

This is high value because it shortens the feedback loop for every future simulator, stability, and throughput sprint. Instead of hand-running commands and reading JSON manually, we get a repeatable operator workflow:

```text
connect to local env
  -> inspect health
  -> choose scenario/profile
  -> tune run parameters
  -> launch run
  -> stream status
  -> review KPIs and reject taxonomy
  -> compare with prior baseline
  -> export reproduction command/artifacts
```

## Product Principles

1. CLI parity first.
- Anything launched from the UI must map to a reproducible CLI command.

2. Local-safe control only.
- The first version binds to localhost and exposes only allowlisted dev commands.
- No arbitrary shell execution.

3. Operator workflow over generic charts.
- The UI should feel like a test control room: run state, health, controls, logs, KPIs, comparisons.

4. Artifacts are the source of truth.
- Reports remain JSON/NDJSON files first.
- The UI indexes and visualizes artifacts; it does not invent separate metrics.

5. Determinism remains visible.
- Scenario seed, session config, profile, reset state, runtime mode, and env toggles must be part of every run record.

6. Performance work stays comparable.
- UI-launched runs must produce the same report schema as CLI-launched runs.

## Proposed Architecture

```text
Angular Platform UI
  -> local simulator-control API
  -> allowlisted dev commands and artifact index
  -> existing scripts/dev/*.mjs and services/simulator/cmd/load-tester
  -> platform runtime / matching engine / Postgres
```

### UI App

Location:
- `apps/platform-ui`

Responsibilities:
- render local environment status
- configure test runs
- launch/stop runs through the control API
- stream run logs/status
- visualize report summaries
- compare runs and baseline drift
- link to raw artifacts and reproduction commands

Deployment stance:
- local dev first.
- reusable UI shell for future hosted environments.
- GitHub Pages is for project/docs pages, not for running simulations directly.

### Local Control API

Proposed location:
- `apps/simulator-control-api`

Runtime:
- Bun or Node using only repository-local scripts and built-in APIs initially.

Responsibilities:
- serve allowlisted control endpoints
- spawn known commands with explicit arguments
- track run process state
- index run artifacts
- expose report summaries
- stream stdout/stderr/log events
- enforce local-only binding and basic safety checks

Do not put shell orchestration directly in the browser or runtime domain service.

### Artifact Store

Initial location:
- `/tmp/reef-simulator-control`

Suggested layout:

```text
/tmp/reef-simulator-control/
  runs/
    <run-id>/
      run.json
      command.txt
      stdout.ndjson
      stderr.log
      report.json
      telemetry.ndjson
      diagnostics/
```

Run metadata should include:
- `runId`
- `createdAt`
- `status`
- `profile`
- `mode`
- `duration`
- `rate`
- `workers`
- `baseUrl`
- `runtimeInstanceCount`
- `resetBeforeRun`
- `captureDiagnostics`
- `sessionConfigPath`
- `seed`
- `gitBranch`
- `gitCommit`
- `command`
- artifact paths

## MVP Screens

### 1. Environment Overview

Purpose:
- answer "is local dev healthy enough to run a simulation?"

Widgets:
- platform runtime health
- matching engine health
- Postgres/container status if available
- current branch/commit
- base URL
- last reset time if known
- active run status

Actions:
- run smoke check
- open raw health response
- optional reset trigger behind confirmation

MVP source:
- existing `make dev-smoke`
- runtime health endpoints
- Docker status where safe/available

### 2. Run Builder

Purpose:
- configure a simulation without remembering flags.

Controls:
- mode: `chaos`, `strict-lifecycle`, `capacity-baseline`
- duration
- rate
- workers
- timeout
- trace check limit
- reset before run
- capture diagnostics
- pretty summary
- report name/tag
- session config picker
- persona/profile mix if using config-driven sessions

Output:
- generated CLI command preview
- validation warnings
- estimated intensity label: quality, capacity, ceiling, soak

### 3. Active Run Console

Purpose:
- monitor a running simulation.

Panels:
- run status
- elapsed time
- current stdout/stderr stream
- latest telemetry sample
- provisional throughput if available
- stop button

Important constraint:
- "stop" should terminate the simulator process cleanly and mark the run as stopped, not delete artifacts.

### 4. Run Results

Purpose:
- make reports readable immediately after a run.

Cards:
- total throughput
- accepted business ops rps
- success rate
- fail rate
- p50/p95/p99
- trace pass rate
- top reject reasons
- top transport/application failures

Tables:
- status codes
- reject taxonomy
- action mix outcomes
- actor/persona attribution where available

Links:
- raw `report.json`
- telemetry file
- reproduction command

### 5. Compare Runs

Purpose:
- make regression analysis cheap.

Initial comparison:
- selected run vs baseline run
- throughput delta
- accepted throughput delta
- p95/p99 delta
- success-rate delta
- trace-pass delta
- reject taxonomy delta

MVP does not need full charting. A clear delta table is enough.

### 6. Scenario Catalog

Purpose:
- make persona/session configs discoverable.

Initial scope:
- list known scenario files from `packages/scenario-definitions`
- show name, description, actors, strategies, instruments, seed
- launch a run using selected config

## Control API Endpoints

Initial endpoints:

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/health` | control API health |
| `GET` | `/env` | local branch, commit, runtime URLs, artifact root |
| `GET` | `/platform/health` | runtime/engine health summary |
| `POST` | `/commands/smoke` | run allowlisted smoke command |
| `POST` | `/commands/reset` | run allowlisted reset command with confirmation token |
| `POST` | `/runs` | start simulator/stress run |
| `GET` | `/runs` | list run history |
| `GET` | `/runs/:runId` | run metadata and status |
| `POST` | `/runs/:runId/stop` | stop active run |
| `GET` | `/runs/:runId/logs` | stream or fetch run logs |
| `GET` | `/runs/:runId/report` | parsed report summary |
| `GET` | `/runs/:runId/artifacts` | artifact file index |
| `GET` | `/compare?left=&right=` | run comparison summary |
| `GET` | `/scenarios` | known persona/session config files |

Safety rules:
- bind to `127.0.0.1` by default.
- command names are enum values, not arbitrary strings.
- arguments are validated and serialized by code.
- artifact paths must stay under the configured artifact root.
- reset requires explicit confirmation in the request.
- only one active run by default unless concurrency is explicitly enabled later.

## Sprint Scope

### In Scope

1. Scaffold local control API.
- health endpoint
- run index
- allowlisted command runner
- process tracking
- artifact directory creation

2. Scaffold platform UI.
- app shell
- simulator control room route
- typed API client
- basic visual style matching operational UI direction

3. Implement run builder and launch flow.
- form validation
- CLI command preview
- start run endpoint
- run metadata persistence

4. Implement active run and results views.
- run status polling
- stdout/stderr capture
- report summary parsing
- top KPI cards and reject taxonomy table

5. Implement compare MVP.
- two-run selector
- key metric delta table
- baseline marker

6. Update docs and scripts.
- `make dev-control` or equivalent
- README setup instructions
- simulator control room usage doc

### Out Of Scope

- production authentication
- multi-user collaboration
- hosted/cloud execution environment
- Kubernetes/job orchestration
- persisted database-backed run history
- complex charting library adoption
- replacing existing CLI scripts
- full scenario editor with drag/drop authoring

## Suggested Delivery Plan

### Phase 1: Control API Skeleton

Deliverables:
- local API server
- health/env endpoints
- artifact root config
- run metadata model
- command allowlist abstraction
- unit tests for argument validation and artifact path safety

Exit criteria:
- `make dev-control-api` starts a local server.
- API cannot execute arbitrary commands.

### Phase 2: Run Launch And Artifact Capture

Deliverables:
- `POST /runs`
- process state tracking
- stdout/stderr capture
- generated command preview
- report artifact discovery
- stop endpoint

Exit criteria:
- a capacity-baseline run can be started from API and produces the same JSON report as CLI.

### Phase 3: UI Shell And Run Builder

Deliverables:
- Angular app scaffold
- simulator control room route
- environment overview
- run builder form
- command preview
- launch button

Exit criteria:
- user can start a run from the browser against local dev.

### Phase 4: Results And Compare

Deliverables:
- run history table
- result summary cards
- reject taxonomy table
- raw artifact links
- run comparison table

Exit criteria:
- user can compare current run against a previous baseline without reading raw JSON manually.

### Phase 5: Scenario Catalog

Deliverables:
- scenario file listing
- scenario summary extraction
- run builder integration

Exit criteria:
- persona/session configs are discoverable and runnable from the UI.

## Testing Plan

Control API:
- unit tests for request validation
- unit tests for command construction
- unit tests for artifact path guardrails
- process runner tests using a fake command
- report parser tests using saved fixture JSON

UI:
- component tests for run builder validation
- component tests for metric formatting and delta states
- API client tests with mocked responses
- smoke test that app renders the control room route

Integration:
- start control API
- run a short fake or real simulator command
- verify run metadata and report parsing
- verify stop behavior

Manual acceptance:
- start dev stack
- open control room
- launch `30s` capacity-baseline run
- view results
- compare against prior run
- copy reproduction command and run it from CLI

## Definition Of Done

- UI can launch at least one real simulator run through the control API.
- every UI-launched run writes a reproducible command and report artifact.
- run history survives control API restart through artifact metadata files.
- reset/smoke controls are allowlisted and local-only.
- results view exposes accepted rps, success rate, p95/p99, trace pass, and reject taxonomy.
- compare view shows deltas for the same core metrics.
- docs explain setup and local safety model.
- tests cover command validation, path safety, report parsing, and key UI states.

## Recommended Next Sprint Name

`simulator-control-room-mvp`

## Why This Should Precede The Next 5k Architecture Sprint

The 5k-per-instance architecture work needs faster feedback and better visibility. A simulator control room gives the project:
- repeatable run setup
- better artifact hygiene
- easier run comparison
- lower barrier to stress testing
- a natural place to expose future phase timing and DB diagnostics
- a foundation for scenario catalogs and long-soak monitoring

This sprint does not abandon the throughput track. It creates the operator surface that makes the throughput track easier to execute and review.
