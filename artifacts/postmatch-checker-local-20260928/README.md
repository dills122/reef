# PM10 stopped-source checker local timing — 2026-09-28

## Scope and method

Read-only exact checker comparison on two frozen synthetic cohorts. Source,
post-match, and settlement PostgreSQL 16 databases were isolated from `reef`
and `reef_pm10_test`. The 512k cohort used database
`reef_pm10_checker_pilot_20260928`; the 3m cohort used
`reef_pm10_checker_20260928` on each of the three existing local containers.
Both used stream `PM10_CHECKER_20260928`, generation
`00000000-0000-4000-8000-000000000010`, and partitions 0–15. Seed SQL is in
`seed/`. The source database required the container's
`/docker-entrypoint-initdb.d/001_create_domain_schemas.sql` before repository
migrations. Migrations used `scripts/dev/db/migrate.mjs` with the matching DB
name for source, post-match, and settlement, plus projection and boundary
migrations disabled. No existing volumes or named test databases were reset.

`checker-copies/baseline/` is PR #402 base `3d4f180a` and
`checker-copies/optimized/` is checker commit `e62b14e8`; each differs from its
respective product script in exactly one database-selection expression:
`"-d", process.env.PM10_DB || "reef"` instead of `"-d", "reef"`.
From repository root, each checker copy ran with `PM10_DB` set to its named
database, stream above, partitions `0,1,...,15`, a distinct JSON output path,
and `WAIT_SECONDS=0`. Each baseline/optimized checker ran three times serially
on each frozen cohort with `/usr/bin/time -l`; raw logs and JSON reports are
in `512k/` and `3m/`. Full command shape:

```sh
PM10_DB=reef_pm10_checker_20260928 /usr/bin/time -l node \
  artifacts/postmatch-checker-local-20260928/checker-copies/optimized/settlement-shadow-check.mjs \
  PM10_CHECKER_20260928 0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15 \
  /private/tmp/settlement-result.json 0
```

Each baseline/optimized JSON pair is identical after removing only
`checkedAt`. All 24 reports have `status: pass` and empty failures. The 3m
source/live/after-source SHA-256 is
`10783d13282adeda3f8ce93c409d8d2d2f313fcd987bcd651390afe0bb849348`;
source/intake/obligation/after-source trade SHA-256 is
`39b8e9151d9207e769240b7cb7e84a067d708035c7a653f73a8f15f05a3491d4`.
Settlement has zero pending, breaks, wrong ledger legs, and wrong attempts.

| Cohort | Outcomes | Trades | Ledger legs | Checker | Baseline wall seconds | Optimized wall seconds |
| --- | ---: | ---: | ---: | --- | --- | --- |
| Pilot | 512,000 | 225,280 | 901,120 | Live | 4.60 / 4.42 / 4.43 | 4.80 / 4.87 / 4.37 |
| Pilot | 512,000 | 225,280 | 901,120 | Settlement | 11.23 / 10.85 / 10.74 | 10.25 / 9.77 / 9.74 |
| Full | 3,000,000 | 1,320,000 | 5,280,000 | Live | 18.04 / 17.98 / 16.00 | 16.87 / 17.74 / 16.99 |
| Full | 3,000,000 | 1,320,000 | 5,280,000 | Settlement | 36.10 / 34.42 / 33.80 | 38.36 / 31.80 / 27.79 |

The 3m optimized settlement median is 31.80s versus 34.42s baseline; its
slowest pass is 38.36s versus 36.10s baseline. Wall times vary with local host
cache and I/O, so this evidence supports a bounded checker cost, not a stable
percentage speedup. The first full-size setup took 454.23s for migrations and
seed; the six baseline exact proofs took 156.34s. `3m/baseline-resource-report.md`
records database, WAL, temp, memory, and disk measurements. Its temp counters
are cumulative bytes generated, not retained disk usage. The saved SQL plans in
`plans/` show 2,451.778ms for source-trade membership and 52.933ms for one
settled-obligation count; these are single-query times. Psql header padding
and extra EOF blank lines were trimmed from copied plan text for Git
whitespace checks; plan rows and measured values are unchanged.

At 150 lagged settlement polls, old code path issues at least 750 Docker/psql
commands and eleven whole-cohort count scans on every poll (1,650 scans).
Optimized path issues one bounded frontier command per poll, then one exact
aggregate containing six cohort scans plus final ledger/attempt and membership
proofs. The frozen runs begin with matching frontiers, so they measure final
proof cost; lagged query behavior is verified in focused CLI tests.

## Limits

Seeded workflow/admission digests are placeholders. This artifact verifies
checker membership, ledger shape, and time on a stopped synthetic source; it
does not prove business replay, in-load freshness, drain from backlog, hosted
resource contention, or 10k/s integrated capacity. PM-S3 attempt 3 had no
completed exact-checker timing and a canonical source gap. The lead must still
budget setup, 300-second load, drain, proof, artifact capture, and teardown in
each disposable-host arm. `CHECKSUMS.sha256` covers retained files.
