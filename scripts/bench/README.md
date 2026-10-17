# Benchmark profiles and preflight

Benchmarking with the wrong configuration produces misleading numbers (JDK front door, Postgres intake,
JetStream instead of Redpanda, 64 partitions, a stale Calcify image, a busy Docker VM...). This directory pins
the right configuration per benchmark class and refuses to start a run on the wrong one.

| Class | What | Run | Profile |
|---|---|---|---|
| **A** | Non-Calcify platform benchmarks: CI guardrails, matching/runtime micro-benchmarks, non-DB stress sanity (1000/2500 rps). Tripwires, not capacity. | `make bench-matching-engine-check bench-platform-runtime-check` | [`class-a-ci-guardrails.env`](profiles/class-a-ci-guardrails.env) |
| **B** | Calcify: the one supported full-chain path (PR 579). Asserts exact decision counts, no throughput threshold. | `make calcify-fullchain-soak FC_RATE=1000 FC_SECONDS=60` | [`class-b-calcify-fullchain.env`](profiles/class-b-calcify-fullchain.env) |
| **C** | Ingestion at 10k/s (venue core, no Calcify). Official gate = DigitalOcean c-16 via `do-gate.mjs`. | `make do-materializer-10k-gate` (official, paid); local diagnostics `make dev-stress-venue-event-materializer`, `make dev-stress-stream-direct-nodb` | [`class-c-ingestion-10k.env`](profiles/class-c-ingestion-10k.env) |

Each profile header states the purpose, exact command, compose files/profiles, pass criteria and host. Every key
has a `# why:` and `# default:` (the value it overrides) comment, and a `# CONFLICTS` section records where repo
docs/scripts disagree and which value was chosen (the one the official gate / soak script actually uses).

## Laptop vs droplet

A laptop run (Docker VM >= 10 CPU / 15.6 GiB, quiet host) is **diagnostic only: do not quote it**. It overstates
the target hardware 2-2.7x. The preflight marks such runs `diagnosticOnly` and `claimable: false`. Only the DO c-16
gate (class C) is a 10k claim; no class-B run establishes a capacity claim on its own. Dedicated-host runs need a
clean tracked tree (`--target droplet`). This lane never creates droplets; paid runs need owner approval of size and cost.

## Run the preflight

```bash
make bench-preflight CLASS=c LAUNCHER=materializer        # or: node scripts/bench/preflight.mjs --class c --launcher materializer
```

To apply a class-C profile before a local run:

```bash
set -a; . scripts/bench/profiles/class-c-ingestion-10k.env; set +a
make dev-stress-venue-event-materializer
```

`make calcify-fullchain-soak`, `make dev-stress-venue-event-materializer` and `make dev-stress-stream-direct-nodb`
run the preflight first and refuse to start on failure. Defaults of the services and compose files are unchanged;
profiles set the right values explicitly.

Options: `--env-file X` (repeatable; default `.env`, `.env.local` like the dev scripts), `--json`,
`--launcher materializer|nodb|none` (which script will run; `none` = compose/DO host/manual: nothing is forced for you),
`--target local|droplet`, `--entry soak|bootstrap` (class B), `--overlay <out>/setup/compose-profile.json` (verify the
real generated overlay after a run), `--stamp-dir DIR`.

Exit codes: `0` pass, `1` configuration FAIL, `2` could not check (e.g. Docker daemon down: those checks are an
explicit `SKIPPED`, never a silent pass).

## Reading a failure

```
STATUS  CHECK                  EXPECTED  ACTUAL
FAIL    PLATFORM_HTTP_SERVER   netty     jdk
        why: JDK HttpServer tops out ~6k/s; Netty is the only front door with 10k/s support.
```

`PASS/WARN/FAIL/SKIPPED` per check, with expected vs actual and why it matters. The `source` of the actual value
(process env, env file, `compose default`, `launcher ... (forced|default when unset)`) is in the stamp. Fix the
setting (usually by sourcing the profile), re-run. `WARN` never blocks (advisory knobs, launcher overriding your
`.env`, diagnostic-only host).

What is checked: HTTP server, intake and idempotency stores, log provider, partition count, publish pipeline,
bounded intake/terminal retention, the 1 MiB producer cap without the paired 4 MiB producer + topic settings,
Calcify overlay values (statically against `calcify-direct-bootstrap.mjs`, or the real overlay with `--overlay`),
stale `calcify-10k-master461` image tags, Docker VM size, other running Reef stacks (quiet-host rule), dirty tracked
tree, host class, Redpanda `write.caching` / `max.message.bytes` when a broker is reachable (otherwise `SKIPPED`),
Go/JDK toolchain and unloosened CI thresholds for class A.

## Escape hatch

`BENCH_PREFLIGHT=skip make calcify-fullchain-soak ...` runs without checking. It prints a loud banner before and
after the run, writes `PREFLIGHT SKIPPED` into `preflight.json` and a `PREFLIGHT_SKIPPED.txt` marker next to the
output. Those numbers are invalid for comparison. `BENCH_ALLOW_RUNNING_STACK=1` downgrades the running-stack
failure to a warning (you own the contamination).

## Stamp (`preflight.json`)

Written to the run's output directory: `reports/calcify-fullchain/<FC_ID>/` (class B), the artifact directory
(`DEV_STRESS_ARTIFACT_DIR`, default `/tmp/reef-venue-event-materializer-stress` or
`reports/bench-preflight/c-<launcher>-<ts>/`, class C), or `--stamp-dir`.

```json
{
  "schema": "reef-bench-preflight-v1",
  "status": "PASS | FAIL | COULD_NOT_CHECK | PREFLIGHT SKIPPED",
  "exitCode": 0,
  "class": "c", "profile": "class-c-ingestion-10k", "launcher": "materializer", "target": "local",
  "timestamp": "...", "git": { "sha": "...", "branch": "...", "dirtyTrackedFiles": 0 },
  "host": { "platform": "darwin", "arch": "arm64", "cpus": 10, "cpuModel": "...", "memGiB": 32 },
  "docker": { "available": true, "ncpu": 10, "memGiB": 15.6, "serverVersion": "...", "operatingSystem": "..." },
  "redpanda": { "image": "redpandadata/redpanda:v26.2.4", "command": "redpanda start ... --smp 2 --memory 2G ..." },
  "diagnosticOnly": true, "claimable": false,
  "factsSource": "live | injected (fixture, not a live host)", "composeSource": "...",
  "resolvedSettings": { "env:PLATFORM_HTTP_SERVER": { "expected": "netty", "actual": "netty", "source": "...", "status": "PASS" } },
  "checks": [ { "id": "...", "status": "...", "title": "...", "expected": "...", "actual": "..." } ]
}
```

`claimable` is true only for a live PASS on a non-diagnostic host.

## Tests

```bash
node --test scripts/bench/preflight.test.mjs     # also part of `make test-dev-tooling`
```

The tests include drift guards: the profiles must equal the CI script defaults (class A), the launcher scripts'
`setValue/setDefault` calls (class C) and the Calcify bootstrap source (class B), so a changed script fails the test
until the profile is updated deliberately.
