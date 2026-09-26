# C45 — cloud treatment attempt for `0067`, inconclusive (host CPU, not the selector)

Date: 2026-09-26. Droplet `603774865` (nyc3), firewall `5e9f76a2-f2fd-4e6d-819b-ff88edfc267d`, both destroyed at the end of this session. This document is the full record of what was set up, exactly how, what broke, what was measured, and why the result does not confirm or refute `0067`.

## 1. What changed and why

`scripts/dev/db/migrations/runtime/0067_bounded_canonical_selection.sql` replaces `runtime.runtime_project_canonical_command_outcomes` (the canonical projection selector, previously defined by `0060_canonical_sequence_prefix_guard.sql`). `0060` ranked and prefix-validated the **entire post-watermark backlog** per partition (`row_number`, `first_value`, `lead`, and a `bool_and` window, each scanning every unclaimed row) before slicing to the per-partition budget, regardless of how small that budget was. `0067` bounds the read with a `LATERAL ... LIMIT` per partition **before** ranking — the same approach as the previously-parked `.planning/sustained-10k/bounded-canonical-selection.candidate.sql` — while keeping every one of `0060`'s prefix-validity branches verbatim (including the pre-partition-encoding legacy-data compatibility branches) plus the adjacent-duplicate tie guard.

**Local proof** (documented as C44 in `docs/THROUGHPUT_BASELINES.md`, Docker Postgres 16, no cloud): `EXPLAIN (ANALYZE, BUFFERS)` against a synthetic 500,000-row backlog (16 partitions, empty watermarks, explicit partitions passed as production does) measured **839ms** for `0060`'s query (`Seq Scan` + an 18.6MB external-merge disk sort + two full-backlog `WindowAgg` passes) versus **1.05ms** for the bounded version (`Index Only Scan`, `Heap Fetches: 0`). Both selected the identical rows on a fixture with a deliberately punched sequence gap, and both stopped at the gap correctly. `0061`/`0062` had already made `(partition_id, stream_sequence)` globally unique, which is what the bounded candidate needed before it could be trusted.

**Expected outcome of this cloud run:** confirm the local EXPLAIN evidence translates into the actual C38–C43 10k/300s timed-gate check moving — specifically, that projector lag and the materialized/projected gap shrink versus C43's 34,201 lag / 33,201 gap, because C43's plateau was hypothesized to be partly caused by a selector whose cost grows with backlog rather than staying flat at the per-poll budget.

**What we cannot claim from this run:** whether `0067` fixes the timed gate at all. See §4 — the run never reached a state where the projection layer was under enough pressure to test that.

## 2. Exact environment setup

### 2.1 Droplet

- Provisioned via `scripts/dev/do-benchmark-host.sh up`, `REEF_DO_BENCHMARK_PROFILE=materializer-projection REEF_DO_SIZE=c-8 REEF_DO_CONFIRM_DESTROYABLE=1 REEF_DO_DROPLET_NAME=reef-dirty0067-benchmark`.
- **Region note:** the script's own default is `REEF_DO_REGION=sfo2`. The first provisioning attempt with that default failed: `Error creating droplet: ... 422 Size is not available in this region`. Queried `GET /v2/sizes` directly — `c-8` is currently only offered in `blr1, lon1, nyc3, sfo3, sgp1`, **not `sfo2`**. Re-ran with `REEF_DO_REGION=nyc3`. This means C43 (run under whatever the script's default was at the time) may well have run in `sfo2` before DO withdrew `c-8` there, i.e. **on different physical hardware than this run**. That is the leading suspect for §4.
- Resulting droplet: id `603774865`, public IP `159.203.166.75`, size `c-8` (8 vCPU, 16GB RAM, 96GB disk), CPU per `lscpu`: **Intel(R) Xeon(R) Platinum 8280 @ 2.70GHz** (Skylake-era; whether this matches what C43 ran on is unknown — no CPU model was recorded in C43's evidence).
- Repo synced with `scripts/dev/do-benchmark-host.sh sync` — plain `rsync -az --delete` of the working tree (excludes `.env`, build dirs), so it carries whatever is on disk regardless of git commit state. At the time of this run that included the uncommitted `0067` migration and the test/doc edits in this PR.

### 2.2 Toolchain installed on the droplet

Base droplet image ships Node v18.19.1 and no `bun`. To match C43's recorded toolchain exactly (avoid a runtime-version confound):

- Downloaded and extracted Node **v22.22.1** (linux-x64) to `/home/reefbench/benchmarks/dirty0067-six/node-v22.22.1-linux-x64/`.
- Downloaded and extracted bun **1.3.14** (linux-x64) to `/home/reefbench/benchmarks/dirty0067-six/bun-linux-x64/`.
- `PATH` in the bench env file puts both ahead of system tools: `export PATH="$bench/node-v22.22.1-linux-x64/bin:$bench/bun-linux-x64:$PATH"`.
- **Bun was not optional.** First attempt without it failed immediately: `scripts/dev/venue-event-materializer-stress.mjs` shells out to `bun scripts/dev/db/migrate.mjs` internally regardless of which runtime launched the top-level script, and failed with `Error: spawn bun ENOENT`. This did not run any load — it failed during the harness's own pre-flight migration check, before the 300s window started. Installing bun and re-running was the fix; this does not count against a "one real measurement" budget since no load was generated on the first attempt.
- Docker: 29.8.1. Docker Compose: v5.5.1 (already present on the provisioned image).

### 2.3 Images built

Built directly on the droplet from the synced source (which includes `0067`), tagged to match the project's established `dirtyNNNN-<variant>` convention from prior C-runs:

```
export REEF_PLATFORM_RUNTIME_IMAGE=reef-projection-runtime:dirty0067-six
export REEF_MATCHING_ENGINE_IMAGE=reef-projection-engine:dirty0067-six
docker compose -p reef-ladder-0056 -f compose.base.yml -f compose.local.yml \
  --profile redpanda --profile venue-event-materializer --profile venue-event-materializer-scaled build
```

Resulting image IDs are recorded in `artifacts/sustained-10k-20260926/dirty0067-six-materializers/run-10000-300s-c45/{runtime,engine}-image.txt`.

### 2.4 Topology: replicating C43's 16-projector / 6-materializer shape

`compose.base.yml`'s `venue-event-materializer` / `venue-event-materializer-scaled` profiles natively give 4 projector containers (each capable of covering a wide partition range) and 4 materializer containers — not 16/6. C43's actual topology (one partition per projector container, 16 of them, plus 6 materializers) was produced by a hand-built Python overlay, preserved at `artifacts/sustained-10k-20260925/index0066-six-materializers/protocol/`. This run reused that exact mechanism, renamed:

1. Brought up the **base** 4-projector/4-materializer topology first (`docker compose ... up -d`, no overlay yet).
2. Ran `capture-safe-overrides.py` against the healthy base stack — snapshots real (non-secret) resolved env vars from the live `reef-platform-projector-0` container into `safe-config-overrides.json`. **This is the file that surfaced the one real config gap** (see §2.5).
3. Ran `prepare-overlay.py` — clones `platform-projector-{4..15}` and `platform-materializer-{4,5}` service definitions from the already-resolved compose config, applies the safe overrides, assigns each projector exactly one partition (`STREAM_ACK_PROJECTOR_PARTITIONS=<index>`) and each new materializer a distinct Kafka client ID, writes `overlay.json`.
4. Re-ran `docker compose ... -f overlay.json up -d` to add the 12 extra projectors + 2 extra materializers alongside the original 4+4.
5. Ran `verify-workers.py` — asserts all 16 projectors have distinct correct partition assignments, all 6 materializers have distinct Kafka client IDs, and every one of 35 compared settings matches across all 22 containers. **Result: `{'projectors': 16, 'materializers': 6, 'settings': 35, 'mismatches': []}` — zero mismatches.**

Every file used for this (`prepare-overlay.py`, `capture-safe-overrides.py`, `verify-workers.py`, `postdrain.py`, `sample-flow.py`, `verify-reference.sh`, `safe-config-overrides.json`, `venue-event-materializer-spread.yaml`) is preserved, with only `dirty0066-six` → `dirty0067-six` and `run-10000-300s-c43` → `run-10000-300s-c45` renamed throughout, at `artifacts/sustained-10k-20260926/dirty0067-six-materializers/protocol/`. Nothing else in the topology, thresholds, or fixture was changed, so this is a controlled single-lever comparison against C43 in intent (see §4 for why it did not end up being one in practice).

### 2.5 The one real configuration gap found and fixed

`compose.base.yml` defaults `STREAM_ACK_LOG_PROVIDER` to `${STREAM_ACK_LOG_PROVIDER:-jetstream}` (NATS-based) — **not redpanda**. C43's own committed `env.sh` never exports `STREAM_ACK_LOG_PROVIDER` either, yet C43's `safe-config-overrides.json` shows `"STREAM_ACK_LOG_PROVIDER": "redpanda"`, and the whole protocol passes `--profile redpanda` to compose. Bringing the base stack up with the committed env-var set as-is left every app container defaulting to `jetstream`, which made `capture-safe-overrides.py`'s own assertion fail (`assert overrides['STREAM_ACK_LOG_PROVIDER'] == 'redpanda'`).

This means the version of `env.sh` preserved in the C43 evidence directory is **incomplete** relative to what was actually run — `STREAM_ACK_LOG_PROVIDER=redpanda` must have been exported some other way in the original session (e.g. typed directly into an interactive shell, or present in an untracked `.env` file on that now-destroyed droplet) and never made it into the committed copy. Added `export STREAM_ACK_LOG_PROVIDER=redpanda` to this run's `env.sh` (line 19, right after `STREAM_ACK_PROJECTOR_ENABLED`), recreated the base containers, and re-ran `capture-safe-overrides.py` successfully (`safeConfigKeys 35 no credential fields`).

**Action item for whoever owns this evidence trail next:** if C43's droplet still exists or its `.env`/shell history was captured anywhere, it's worth confirming this was the only silently-implied setting, in case there are others we got lucky on.

### 2.6 Full `env.sh` used

Everything else is byte-identical to C43's `env.sh` (`artifacts/sustained-10k-20260925/index0066-six-materializers/protocol/env.sh`) aside from the `dirty0066-six`→`dirty0067-six` / `c43`→`c45` renames and the two additions in §2.2/§2.5. `artifacts/` is gitignored repo-wide (matching every prior C-run — none of C43's protocol or evidence files are tracked in git either), so the exact file used here is **not** part of this PR's diff; it's preserved locally at `artifacts/sustained-10k-20260926/dirty0067-six-materializers/protocol/env.sh` on this machine for anyone with this checkout to diff directly. The settings worth knowing without opening it: `STREAM_ACK_PROJECTOR_BATCH_SIZE=500`, `ORDER_LIFECYCLE_PROJECTOR_WORKERS=4`, `REEF_PROJECTION_PG_SHARED_BUFFERS=2GB`, `JAVA_TOOL_OPTIONS='-Xms128m -Xmx512m -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError'` per app container, and the full 16-URL projector / 6-URL materializer endpoint lists — all unchanged from C43.

### 2.7 Database migration

Since this was a fresh droplet with a fresh `projection-postgres` container, app containers initially crash-looped (`RUNTIME_DB_BOOTSTRAP_MODE=validate` requires the schema to already exist — it does not create it). Ran `node scripts/dev/db/migrate.mjs` against the compose-mapped ports (`REEF_PROJECTION_POSTGRES_HOST_PORT=15433` etc. from `env.sh`) before the containers would start cleanly; all migrations through `0067` applied with no errors, confirmed via container logs after restart.

## 3. What was actually run

1. `node scripts/dev/venue-event-materializer-stress.mjs` (full env from `env.sh`: `DEV_STRESS_RATES=10000 DEV_STRESS_SWEEP_WORKERS=256 DEV_STRESS_DURATION=300s`), output to `run.log`.
2. `node scripts/dev/do-benchmark-check.mjs` on the resulting artifact dir (checker; not separately narrated below, its verdict is embedded in the stress tool's own failed-gate summary, see §4).
3. A follow-up **30-second diagnostic burst** (same 10k rate / 256 workers target, `DEV_STRESS_DURATION=30s`, separate artifact dir `diag-30s/`) run specifically to get live container CPU samples, since the first attempt didn't have the sampler running. This is diagnostic, not a second attempt at the real measurement.
4. `sample-flow.py` (the project's own live CPU/DB sampler) running in the background during the diagnostic burst, sampling every 10s.

All artifacts (full `run.log`, KPI JSON/MD, diagnostics summary, worker-config-proof, sampler output) are at `artifacts/sustained-10k-20260926/dirty0067-six-materializers/run-10000-300s-c45/` and `.../diag-30s/`.

## 4. What actually happened

The 300s run completed (stress tool exit code 1 — its own gate check failed) but at **3,523.87 rps**, not 10,000:

| | C43 (`0066`, prior baseline) | This run (`0067`) |
| --- | ---: | ---: |
| Target rate | 10,000 rps | 10,000 rps |
| Actual accepted throughput | 9,973.99/s | **3,523.87/s** |
| Intake p50 / p95 / p99 | not recorded / 78.06ms / 122.08ms | **59.93 / 178.36 / 254.24ms** |
| Scheduled vs target | 2,992,504 / 3,000,000 (99.7%) | **1,357,241 / 3,000,000 (45.2%)** |
| Completed | 2,992,504 | 1,057,244 |
| Projected | 2,967,975 | 264,191 |
| Projector lag at collection | 26,029 | 793,053 |

This is a large, across-the-board regression versus C43 that shows up **before** any request reaches the projection layer `0067` touches — intake p95/p99 alone are both worse than C43's numbers. A 30-second repeat at the same target (separate run, `diag-30s/`) reproduced almost identical numbers (3,755.86 rps, p50/p95/p99 55.43/174.53/241.82ms), ruling out a one-off warm-up fluke.

**Live container CPU during the diagnostic burst** (`sample-flow.py`, 8 vCPUs = 800% total available):

| Container | CPU% |
| --- | ---: |
| `reef-postgres` (main/boundary DB) | 149.84% |
| `reef-matching-engine` | 117.20% |
| `reef-platform-api` | 115.28% |
| `reef-projection-postgres` | 107.84% |
| `reef-redpanda` | 46.91% |
| all 16 projectors + 6 materializers combined | roughly 4–10% each |
| **sum across all containers** | **623% of 800%** |

The four hottest containers — main Postgres, the matching engine, the platform API, and projection-postgres — are all on the **intake/accept/match** path, upstream of the canonical projection selector entirely. The 22 projector/materializer containers, whose shared code path `0067` changes, were sitting nearly idle. Idle-baseline latency check (no load, plain `curl` to the platform API) was 2ms — ruling out a static network/proxy misconfiguration; the latency blowup only appears under real concurrent load, consistent with genuine CPU contention on the intake path rather than a broken setup.

**Conclusion: this run does not confirm or refute `0067`.** The system never got far enough for the projection layer to be under real pressure — intake itself couldn't sustain anywhere near 10k/s on this specific droplet, for reasons unrelated to anything `0067` changes. Every setting we could check against C43 (env vars, redpanda mode, resource limits, connection pool sizes, topology shape, per-container config) matches exactly (`verify-workers.py`: 35/35 settings, zero mismatches). The leading unverified suspect is the region/hardware difference in §2.1 — `sfo2` no longer offers `c-8` at all, so C43 almost certainly ran on different physical CPUs than this attempt's `nyc3` box.

## 5. What would make a real retest valid

- Confirm (or rule out) the hardware difference: try `c-8-intel` ("Premium Intel" size, also 8 vCPU, higher clock) in a region that offers it, or explicitly pin a region and compare idle single-request CPU throughput before committing to a full 300s run.
- Reinstate the live sampler (`sample-flow.py`) from the very start of the *actual* 300s attempt, not just a follow-up diagnostic, so a real attempt's CPU data is available without needing a second run.
- If intake/matching capacity turns out to be the real ceiling on whatever hardware is available, that is a separate, pre-existing problem from `0067` and shouldn't block promoting `0067` on its own local merits (§1's EXPLAIN evidence stands regardless of this run).
