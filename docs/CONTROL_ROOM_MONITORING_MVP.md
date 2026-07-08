# Control Room Monitoring MVP

## Decision

Start with a dependency-free local Control Room served by a small Bun/Node
control API. The first slice should make stress and simulation-run tests easier
to observe without changing the existing CLI or report formats.

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
  and alerting, but not ideal as the first run-control surface because Reef's
  domain state is already in JSON reports and internal endpoints.

The MVP intentionally avoids adding frontend dependencies until the control API
contract stabilizes. It uses plain HTML, CSS, and browser APIs with SVG
sparklines. That keeps local setup fast and makes the remote bridge work first.

## Initial User Flow

```text
open Control Room
  -> inspect runtime diagnostic snapshot
  -> launch a small local stress run
  -> watch live logs and sampled telemetry
  -> inspect generated report evidence
  -> launch the same shape through make simulation-run for DigitalOcean
  -> watch harness logs and fetched artifacts
```

## Safety Boundaries

- The control API binds to `127.0.0.1` by default.
- Only allowlisted commands are supported.
- Run artifacts are isolated under `/tmp/reef-control-room/runs/<run-id>/`.
- Remote tests use the existing `make simulation-run` wrapper and do not expose
  worker service ports directly.

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
3. Launch a local stress run with conservative defaults:
   - rate `100`
   - workers `8`
   - duration `15s`
4. Confirm live logs, snapshot counters, and report evidence update.
5. Launch a DigitalOcean simulation smoke through the same UI:
   - rate `1000`
   - workers `128`
   - duration `60s`
6. Confirm harness logs stream and fetched artifacts appear in run history.
