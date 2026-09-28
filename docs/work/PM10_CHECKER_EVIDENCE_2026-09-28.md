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

## Frozen local pilot

Research lane seeded three isolated PostgreSQL 16 databases named
`reef_pm10_checker_pilot_20260928` on the existing source, post-match, and
settlement containers. Same stream `PM10_CHECKER_20260928`, source generation
`00000000-0000-4000-8000-000000000010`, and partitions 0–15 were used in all
passes. Fixture has 512,000 canonical outcomes, 512,000 live receipts, 225,280
trade outcomes (44%), 225,280 intake rows, obligations, and attempts, 901,120
ledger legs, and 5,120 admissions and completions. It has checker membership
and ledger shape but placeholder workflow/admission digests, so it tests exact
checker cost and membership, not business replay.

Each checker was run three times serially with `/usr/bin/time -l`, wait zero,
and a report per pass. Baseline scripts came from merged PR #402; optimized
copies matched this branch except for `PM10_DB` selection of the isolated
database. All twelve reports passed. For each optimized pass, JSON was
identical to the respective baseline report after removal of `checkedAt`.

| Exact checker | Baseline wall seconds | Optimized wall seconds | Baseline max RSS | Optimized max RSS |
| --- | --- | --- | ---: | ---: |
| Live/market/receipt | 4.60 / 4.42 / 4.43 | 4.79 / 4.46 / 4.32 | 84–91 MB | 84–88 MB |
| Settlement/trade/ledger | 11.23 / 10.85 / 10.74 | 9.68 / 9.63 / 9.79 | 91–93 MB | 90–93 MB |

Settlement median fell from 10.85s to 9.68s (10.8%); live median changed from
4.43s to 4.46s (within this pilot's run variation). Exact source/live receipt
SHA-256 was `8ccebd821e336292f5eba0ae76f5dd607c35ae392c71aee1e229c3ea230abf49`
(74,596,894 CSV bytes); source/intake/obligation trade SHA-256 was
`a7bf8662552b74010660af4c5c7e5e6dbca0799da8425d529ea5869ed5a4f35c`
(20,833,496 bytes). Settlement metrics were 225,280 settled, zero pending or
breaks, 901,120 ledger entries, and zero mismatched ledger legs or attempts.
Original baseline logs, SQL plans, and both checker result sets are retained
under `/private/tmp/reef-pm10-checker-plan-20260928/` on the local host; the
pilot databases remain intact for integration.

Research lane measured source-trade membership SQL at 2,226.468ms (512,000
source rows, JSON expansion and sort) and one settled-obligation count at
34.277ms. These are single-query `EXPLAIN (ANALYZE, BUFFERS)` measurements,
not full-checker phase timings. No full 3 million outcome fixture was run.
Membership sorts, cache state, JSON expansion, and Docker I/O make linear
extrapolation unreliable. The lead's full-size local run gate still applies
before a two-hour disposable-host arm; this pilot alone cannot certify it.
