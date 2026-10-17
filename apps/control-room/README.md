# Control Room

Local, read-only runtime and throughput monitor. A dependency-free Node/Bun
HTTP server (`server.mjs`) serves a static page (`public/`) and polls the
platform's `/internal/*` endpoints. It reports `mode: "monitor-only"`: it does not
submit commands or start runs.

Not part of the public product surface. It is a local inspection tool for
stream-ack, materializer-soak and direct-nodb run profiles.

## Run

```bash
make dev-control-room      # or: bun run control-room
```

Open `http://127.0.0.1:3015`. For run profiles and which URLs to set, see
[Control Room in `docs/LOCAL_RUN_PROFILES.md`](../../docs/LOCAL_RUN_PROFILES.md#control-room).

`bun run control-room:check` only runs `node --check` on `server.mjs` and
`public/app.js`; there are no other tests for this app.

## API

| Route | Purpose |
|---|---|
| `GET /api/config` | resolved URLs, profile, state directory |
| `GET /api/snapshot?runId=` | probes runtime, engine, worker, materializer and projector status endpoints and returns normalized metrics |
| `GET /api/runs` | runs found under the state directory |
| `GET /api/runs/:id` | one run record |
| `GET /api/runs/:id/events` | event stream for a run |

## Configuration

| Variable | Default |
|---|---|
| `REEF_CONTROL_ROOM_HOST` / `REEF_CONTROL_ROOM_PORT` | `127.0.0.1` / `3015` |
| `REEF_CONTROL_ROOM_RUNTIME_URL`, `REEF_CONTROL_ROOM_ENGINE_URL` | derived from the repo `.env` (`deriveDevUrls`) |
| `REEF_CONTROL_ROOM_WORKER_URLS`, `REEF_CONTROL_ROOM_PROJECTOR_URLS` | local role ports `8082-8089` |
| `REEF_CONTROL_ROOM_MATERIALIZER_URLS` | unset (no materializer rows) |
| `REEF_CONTROL_ROOM_PROFILE` | `materializer-soak` if materializer URLs are set, else `stream-ack` |
| `REEF_CONTROL_ROOM_STATE_DIR` | `/tmp/reef-control-room` (run records under `runs/`) |

It reuses helpers from `scripts/dev/lib/` (`dev-utils.mjs`, `report-taxonomy.mjs`,
`run-profile-roles.mjs`), so it runs from a full repo checkout only.
