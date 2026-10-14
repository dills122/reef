# Finite P3 financial path: the single approved live recording (S3-A2 live part)

Status: checklist, prepared 2026-10-13. **Nothing in this document has been run.** The recording itself needs the owner's go-ahead for one attempt.

## What this is, and is not

The sprint plan asks for "test plus one recorded live run (single approved attempt)" of the O5 end-to-end flow on real infrastructure: three trades booked pending with zero legs, funding, three
distinct repairs settling exactly once, restore cuts at every start, exact API rows, and a restart that changes nothing. With the harness that exists today that is a recorded run of the
real-broker and Postgres suites, on a disposable Redpanda and Postgres, with every command, environment fact and result kept.

It is **not**: the O2 live replay (a separate window; its plan has not been re-read here), the warm-restart proof (a capture-lane and capacity question), any throughput or capacity claim, or a TLS or
production-deployment test. Owner decisions that bound it (2026-10-13): the role matrix is signed off; the transport is a **closed network** with TLS filed as follow-up issue 527; one attempt, a
2-hour timebox, a 20-minute cumulative blocker timebox, no custom monitors.

## Where it runs

| Option | What it takes | Notes |
| --- | --- | --- |
| **A. Local Docker (recommended first)** | Docker Desktop, JDK 21 with the Gradle wrapper, Node 22, this checkout | Everything the tests need is already here. Cheapest; the same shape CI uses. |
| B. DigitalOcean droplet | `infra/do-benchmark` (OpenTofu, `scripts/dev/do-benchmark-host.sh`) provisions a short-lived host with Docker, Node and Go | Not a ready fit: that harness runs the *benchmark* Compose profiles (stream-ack, materializer), the finite finance path is not one, and cloud-init does not install a JDK. It would need a JDK and a source or image sync, and costs droplet hours. Choose it only if you want the recording off your laptop; the commands below are the same once the host has JDK 21, Docker and the checkout. |

## Preflight (stop at the first failure)

1. `git status --short` shows no tracked changes; `git rev-parse HEAD` equals `origin/master` and is recorded.
2. Docker responds (`docker info`), no leftover containers (`docker ps -a` is empty), ports are free.
3. Never stage the owner's untracked files (`.planning/`, `.mcp.json`, `docs/research/KOTLIN_JSON_LIBRARY_RESEARCH_2026-09-30.md`); the recording writes under a private directory and evidence is committed by exact path only.
4. The owner has approved exactly one attempt, and the attempt id (`live-s3a2-1`) is unused.

## The attempt

```bash
# 1. disposable Postgres with the signed-off projector and reader roles (random credentials in a mode-700 directory)
scripts/dev/calcify-financial/finite-p3-live-fixture.sh up
source "$(scripts/dev/calcify-financial/finite-p3-live-fixture.sh env)"

# 2. one recorded attempt: the recorder keeps the command, head, environment, exit code and full stdout/stderr
export CALCIFY_FINANCIAL_ATTEMPT_DIR=.planning/calcify-finite-p3-live/attempts
node scripts/dev/calcify-financial/record-attempt.mjs live-s3a2-1 services/platform-runtime \
  ./gradlew test -PbrokerTests \
  --tests '*FiniteP3EndToEndTest' --tests '*FiniteFinanceLauncherCrashTest' \
  --tests '*FiniteFinanceRuntimeBrokerTest' --tests '*FiniteFinanceLauncherBrokerTest' \
  --tests '*FinanceProjectorPostgresTest' --tests '*JdbcFinanceReadStorePostgresTest'

# 3. always, pass or fail: remove the container with its volume and the credential directory
scripts/dev/calcify-financial/finite-p3-live-fixture.sh down
docker ps -a --format '{{.Names}}'   # must print nothing
```

The Postgres tests skip, and Gradle still reports success, if the fixture variables are missing. A skipped suite is **not** a pass: read the per-suite XML counts after the run.

## What counts as pass

All of these, from the recorded output and `services/platform-runtime/build/test-results/test/`:

- Every named suite has `tests >= 1`, `skipped="0"`, `failures="0"`, `errors="0"`.
- `FiniteP3EndToEndTest`: three captured trades pending with zero legs and no positions; after funding and three distinct repairs, eight decisions (three captures, two funds, three settles), every obligation settled with zero residuals, three SETTLED attempts, exact positions, the participant route scoped to its own account; the restore-cut restart leaves the projection and routes unchanged.
- `FiniteFinanceLauncherCrashTest`: the line `F8 OK kills=3 ... decisions=50`, with at least two kills after progress and the stored owner epoch equal to the number of launcher starts.
- `FiniteFinanceRuntimeBrokerTest`: the finance process role starts under only its minimal grants, a wrong observer credential starts nothing, a clean stop is not reported as a failure.
- Cleanup: no container left, the credential directory gone.

## What gets recorded

The attempt record from `record-attempt.mjs` (`attempts.jsonl`, `live-s3a2-1.stdout.log`, `.stderr.log`); the host (OS, CPU, memory, Docker version), `git rev-parse HEAD`, image tags (`redpandadata/redpanda:v26.2.3`, `postgres:16-alpine`) and their digests (`docker image inspect`); wall-clock per suite from the XML; the `F8-KILL` lines. Then an evidence directory `docs/evidence/calcify-finite-p3/<date>-s3a2-live/` committed **by exact path** with a short README: command, result, limits (single host, closed network, plaintext transport, no capacity claim, one attempt). Before committing, grep the logs for the contents of the credential files; they are never printed, but verify it.

## Abort and escalation

- **Timeboxes:** 2 hours for the attempt, 20 minutes cumulative on any blocker, then stop and escalate to the owner with the measured cause.
- **A failed attempt is evidence, not a retry:** record it as is. A second attempt needs a new id and the owner's approval; do not fix and rerun silently.
- **Do not** add monitors, change thresholds, or edit tests to make the attempt pass.
- If Docker or the VM disk fails (it has: leaked anonymous volumes, daemon stopped overnight), fix the environment, say so in the record, and count it against the blocker timebox, not as a product failure.

## After the recording

Write the result into `CALCIFY_FINITE_P3_S2A_RESULT.md` (the S3-A2 live part) and the WORK_PLAN row; file whatever it found. Still open afterwards, by design: TLS (issue 527), decisions 4 and 5 of the sign-off packet for a non-disposable deployment, the O2 replay window, the warm-restart proof, and Decision 0 for any capacity profile.
