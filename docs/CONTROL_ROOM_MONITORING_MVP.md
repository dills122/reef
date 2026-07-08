# Control Room Monitoring MVP

## Decision

Start with a dependency-free local Control Room served by a small Bun/Node
read-only monitoring API. The first slice should make stress and simulation-run
tests easier to observe without changing the existing CLI, report formats, or
run launch workflows.

## Why This Shape

Reef already exposes the most useful run signals as internal JSON endpoints:

- `/internal/commands/accounting?runId=...`
- `/internal/stream-ack/health`
- `/internal/stream-ack/worker/stats`
- `/internal/venue-event-materializer/stats`
- `/internal/projector/status`
- `/internal/perf/hot-path`
- `/internal/perf/db-pools`

The stress harness also writes canonical reports and uses
`scripts/dev/lib/report-taxonomy.mjs` for normalized evidence. The control room
should consume those same sources instead of creating a competing metrics
model.

## Dashboard Tech Research

Good candidates for a later richer UI:

- React + Vite: familiar app structure and easy migration path if the UI grows.
- shadcn/ui: strong operator-dashboard building blocks, but it brings Tailwind
  and generated component ownership.
- Tremor Raw: dashboard-first components, but also assumes React and Tailwind.
- uPlot: a very small high-performance time-series chart library. It is a good
  fit if live run charts become dense.
- Grafana + Prometheus: good for longer-term infrastructure metrics, history,
  and alerting, but not ideal as the first Reef-specific monitor because the
  domain state is already in JSON reports and internal endpoints.

The MVP intentionally avoids adding frontend dependencies until the control API
contract stabilizes. It uses plain HTML, CSS, and browser APIs with SVG
sparklines. That keeps local setup fast while preserving a narrow monitor-only
boundary.

## Initial User Flow

```text
open Control Room
  -> connect or reconnect to the configured runtime URL
  -> inspect runtime diagnostic snapshot
  -> watch sampled telemetry trend
  -> inspect report evidence already written by CLI or remote harness runs
  -> select a run artifact directory to tail existing logs
```

## Safety Boundaries

- The control API binds to `127.0.0.1` by default.
- The monitor does not expose any run-launch, shell, provisioning, or mutation
  endpoints.
- Local and remote tests still start from the existing CLI workflows.
- Run artifacts are read from `/tmp/reef-control-room/runs/<run-id>/` by
  default. Directories with `run.json` use that metadata; directories with only
  stress/report JSON files are shown as observed artifact runs. Set
  `REEF_CONTROL_ROOM_STATE_DIR` if the monitor should read a different artifact
  root.
- Remote tests use the existing `make simulation-run` wrapper outside the
  monitor. The monitor can read fetched artifacts after the harness writes them.

## Run

```bash
bun apps/control-room/server.mjs
```

Open:

```text
http://127.0.0.1:3015
```

Optional overrides:

```bash
REEF_CONTROL_ROOM_PORT=3020 \
REEF_CONTROL_ROOM_RUNTIME_URL=http://127.0.0.1:8080 \
bun apps/control-room/server.mjs
```

If runtime health is online but internal diagnostic widgets show blocked
probes, restart the platform profile with:

```bash
PLATFORM_INTERNAL_HTTP_MODE=enabled
```

This is already set by the stream/materializer stress helpers, but not by every
plain local stack startup.

## First Verification Targets

1. Start the local stack with the profile being tested.
2. Start the control room.
3. Click connect or reconnect and confirm runtime health updates.
4. Run a local stress test from the existing CLI.
5. Confirm snapshot counters, existing run logs, and report evidence update.
6. Run a DigitalOcean simulation smoke from the existing CLI.
7. Confirm fetched artifacts appear in run history once the harness writes them
   under the configured artifact root.
