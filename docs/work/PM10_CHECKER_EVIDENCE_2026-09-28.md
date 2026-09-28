# PM10 checker evidence — 2026-09-28

Scope: stopped-source checks in `scripts/dev/settlement-shadow-check.mjs` and
`scripts/dev/postmatch-shadow-check.mjs`. This is a local checker diagnostic, not
an in-load freshness or full-system 10k capacity claim. PM-S3 attempt 3 retained
no completed checker-stage wall time; its exact downstream cohort proof failed
after the canonical source gap. See `docs/THROUGHPUT_BASELINES.md` (PM-S3
attempt 3) and `artifacts/postmatch-capacity-20260928/attempt-3/`.

## Query work

For `P` settlement polls before a frontier is ready, the previous loop ran
four frontier SQL commands and one command containing eleven whole-cohort
aggregate subqueries on **every** poll. Once obligations were non-pending, it
also ran ledger and attempt mismatch queries every poll. The new loop runs one
frontier command (four indexed frontier reads joined with `UNION ALL`) per poll.
It runs the exact aggregate command once when the stopped-source frontier is
ready, or once at timeout for diagnostics. That command now scans obligations
once for five counts and admissions once for count plus maximum rank: six
cohort table scans instead of eleven. Final ledger/attempt mismatch queries and
four source/intake/obligation/source-again membership hashes remain in place.

Example at 150 lagged polls: old loop issued at least 750 Docker/psql commands
and 1,650 whole-cohort count scans; new loop issues 150 frontier commands,
then one aggregate and at most two mismatch commands, with six count scans.
These are code-path counts, not measured SQL plans or latency. Post-match
checker already used one frontier query per poll; it now requires exact source
sequence equality before running source/receipt membership hashes.

## Verification

- `node --test scripts/dev/postmatch-shadow-check.test.mjs scripts/dev/settlement-shadow-check.test.mjs`:
  12 passed, one optional PostgreSQL test skipped. CLI fixtures cover lagged
  frontier polling, one exact proof, timeout, missing intake trade, receipt
  mismatch, source partition outside assignment, and wrong ledger count.
- `REEF_TEST_SETTLEMENT_SQL=1 node --test scripts/dev/settlement-shadow-check.test.mjs`:
  eight passed on a disposable PostgreSQL 16 container. Fixture executes the
  grouped count SQL and proves wrong/missing ledger legs and attempt outcomes
  fail, including quantity and identity changes.
- `node --check` on both checkers and `git diff --check`: passed.

## Frozen local cohorts

[Tracked raw evidence](../../artifacts/postmatch-checker-local-20260928/README.md)
contains seed SQL, checker copies, baseline and optimized JSON, all `/usr/bin/time -l`
logs, SQL plans, resource report, and checksums. PostgreSQL 16 databases for
source, post-match, and settlement were isolated by named DB. Both cohorts used
stream `PM10_CHECKER_20260928`, generation
`00000000-0000-4000-8000-000000000010`, and partitions 0–15. The 512k pilot
has 225,280 trades; the full-size cohort has 3,000,000 outcomes, 1,320,000
trades, 3,000,000 live receipts, and 5,280,000 ledger legs (44% trade mix).
Workflow/admission digests are placeholders, so these measurements prove
checker cost and membership shape, not business replay.

Each baseline and optimized checker ran three times serially with wait zero.
Checker copies differ from product scripts only in named DB selection. All 24
reports passed; each optimized JSON matches its paired baseline after removing
only `checkedAt`.

| Cohort | Checker | Baseline wall seconds | Optimized wall seconds |
| --- | --- | --- | --- |
| 512k | Live/market/receipt | 4.60 / 4.42 / 4.43 | 4.80 / 4.87 / 4.37 |
| 512k | Settlement/trade/ledger | 11.23 / 10.85 / 10.74 | 10.25 / 9.77 / 9.74 |
| 3m | Live/market/receipt | 18.04 / 17.98 / 16.00 | 16.87 / 17.74 / 16.99 |
| 3m | Settlement/trade/ledger | 36.10 / 34.42 / 33.80 | 38.36 / 31.80 / 27.79 |

At 3m, settlement median fell from 34.42s to 31.80s, while optimized slowest
pass (38.36s) exceeded baseline slowest (36.10s). Report range and median,
not a stable speedup. Optimized full-size checker RSS stayed at or below
119,472,128 bytes. Source/receipt membership SHA-256 matched across all 3m
passes (`10783d13282adeda3f8ce93c409d8d2d2f313fcd987bcd651390afe0bb849348`);
source/intake/obligation trade membership matched
(`39b8e9151d9207e769240b7cb7e84a067d708035c7a653f73a8f15f05a3491d4`).
Settlement had zero pending, breaks, wrong ledger legs, and wrong attempts.

The full-size baseline setup (migrations and seed) took 454.23s; six serial
baseline exact proofs took 156.34s. This places checker proof cost below a
minute per checker on this frozen local host. It does not establish an entire
two-hour disposable run: 300-second load, backlog drain, cross-stage proof,
artifact capture, teardown, host contention, and business replay remain
separate gates. Saved `EXPLAIN (ANALYZE, BUFFERS)` plans measured 2,451.778ms
for source-trade membership and 52.933ms for one settled-obligation count;
those are single-query times, not full-checker phase attribution.
