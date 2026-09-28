# PM-S3 attempt 3 — corrected settlement connection budget, failed capacity

Disposable `sfo3` `c-32` droplet `604195292`; source tree contained the
benchmark correction later committed as `9c637dcb`. The droplet was destroyed
at the two-hour cutoff; OpenTofu state was empty afterward.

## Run sequence

- `postmatch-capacity-control-pg320-20260928T0110Z`: source PostgreSQL 320;
  intentionally stopped during measured setup to reserve remaining droplet time
  for treatment. No traffic or control result. `aborted-control-log.gz`.
- `postmatch-capacity-treatment-pg320-20260928T0122Z`: source PostgreSQL 320,
  settlement PostgreSQL default 100. Smoke passed; settlement seed failed
  before traffic. Seed executes `psql` inside `settlement-postgres`, which was
  at 100/100 clients; source had about 194/320. Previous source-only diagnosis
  was wrong. `treatment-settlement100-failure-log.gz`.
- `postmatch-capacity-treatment-settlement240-20260928T0139Z`: source 320,
  settlement 240, six materializers, sixteen projector owners, four dedicated
  live workers, four dedicated settlement workers, 384 load workers. Volumes
  reset. Repeat smoke skipped because the same code had passed smoke in the
  preceding run. Settlement seed succeeded; 10,000/s target ran for 300s.

## Measured result

Load accepted and direct-acked 2,776,548 commands, 9,254.07/s, with no
request failures; p95 93.15 ms, p99 158.15 ms. This fails the 9,900/s gate.
The load report's materializer snapshot had 2,620,026 durable canonical
items, a 156,522 accepted/materialized gap. Downstream cohort proof failed.
The `projected` count in the load report is not proof that canonical outcomes
or read models caught up.

Offline stage check over the retained nine samples passed its *measurement*
gate: four in-load intervals, maximum observer duty 3.44% total and 1.80%
settlement. In those intervals, approximate source insert statistics measured
6,255–7,415 outcomes/s; settlement insert statistics measured 2,018–2,215
intake trades/s, 39–41 admission windows/s, 2.1–2.6 completion windows/s,
and 76–96 transition attempts/s. At the final retained sample, about three
minutes after load, insert statistics showed 1,220,848 intake trades,
22,211 admissions, 1,300 completions, and 52,564 attempts. These are
`pg_stat_user_tables.n_tup_ins` estimates, not exact closed-cohort counts.

Settlement SQL time is concentrated in the shared admission-counter row:
22,465 counter updates accumulated 3.41 million ms of PostgreSQL execution
time, and 22,474 `INSERT ... ON CONFLICT DO NOTHING` calls accumulated 2.05
million ms. A live snapshot found ten tuple-lock waits and two transaction-ID
waits on settlement PostgreSQL. These are **summed concurrent SQL times**,
not elapsed latency. They strongly support counter-row contention as a
settlement admission bottleneck. Worker logs also show blocked readiness and
much slower execution than admission: each of the four settlement JVMs had
about 4,800–4,950 admission writes but only 285–293 applied windows at the
live log snapshot. Each recorded 776–790 seconds of cumulative counter-call
time and a maximum single call of 1.3–1.55 seconds. Roughly 77–80% of each
worker's readiness checks were blocked, with only 285–293 ready/executed
windows per worker. This identifies execution readiness as a second, larger
backlog after admission; faster rank allocation alone cannot close it.
Source materialization's
top SQL call accumulated 1.74 million ms and projection submit persistence
4.14 million ms; those are additional costs on the same host. Nested SQL
function timings must not be added to their caller's timings. This evidence
does not assign the full ingress-rate loss solely to settlement.

The exact post-match and settlement closed-cohort checks could not finish
before the droplet cutoff. No correctness or final catch-up claim follows from
this run. `treatment-stage-summary.json` was generated offline from the
original samples and load report by `postmatch-stage-check.mjs` after teardown.

Retained evidence: compressed load report, DB diagnostics summary, KPI, stage
samples/summary, measured-stage log, live four-DB wait/top-SQL snapshot, worker
logs, failed setup log, aborted control log, and checksums. Raw recovered
artifacts also remain under `/private/tmp/reef-settlement240-recovery/` on the
local host.

The three verbose JSON summaries are stored as `.json.gz` to keep the PR's
code-review diff bounded. Use `gzip -dc <filename>.json.gz` to inspect them;
compression is lossless and the checksums cover the compressed files.
