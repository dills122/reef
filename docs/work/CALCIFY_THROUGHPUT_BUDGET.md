# Calcify throughput budget (stage index)

Status: living index, created 2026-10-12. **It does not replace [`docs/THROUGHPUT_BASELINES.md`](../THROUGHPUT_BASELINES.md)**, which stays the
append-only evidence ledger and the required starting point for any throughput claim. This page answers one question the ledger does not:
*for each stage of the proposed arena path, what must it sustain, what was last measured, and what has never been measured?* Numbers here are
copies with a pointer to their ledger entry; if they disagree with the ledger, the ledger wins and this page is wrong.

## Why this exists

The finite P3 financial path waived the 50,000 decisions/s gate because its state is bounded. A waiver is easy to carry forward by accident into
code that is not bounded. This index, the guards below and the scheduled measurement keep the repo's real goal visible while finite work proceeds:
`CALCIFY_ARENA_THROUGHPUT_CONTRACT.md` proposes **10,000 distinct completed trades/s** to the trading view, settlement service rate of at least about
**9,091/s** for a 5-minute session to flush within 30 s of close (9,677/s for 15 minutes), settlement p99 within 5 s and the settlement backlog failing
above 300,000 trades. Those targets are *proposals*, not measured results.

## Stage table

Status: **measured** (a ledger entry covers the stage at or near the target workload), **partial** (a different workload, a component, or a lower
rate), **unmeasured**. "Gap" is the target divided by the last measured rate where both are in the same unit.

| Stage | Target (contract) | Last measured | Ledger entry / source | Status | Gap |
| --- | --- | --- | --- | --- | --- |
| Ingress, matching, capture and context resolution (single server) | 10,000 trades/s | 10,321 to 10,350 resolved/s lower bound over 300 s at 10,500 offered, 64 standing makers, **no cold restart** | "Calcify matched no-cold-restart control, October 11" | measured | none (lower bound) |
| Same, with a cold restart during the run | 10,000 trades/s | 8,107 to 8,145 resolved/s; 29 s drain upper bound | "Calcify aged single-server contention/recovery, October 11" | measured, **misses** | about 19% |
| Venue core only (hosted, 64 instruments) | 10,000 commands/s | about 10k venue-core commands/s, two samples | C5 | partial (excludes capture and all later stages) | n/a |
| Legacy full projection path | 10,000/s through projection | 2.5k/5m recorded baseline; 5k fails freshness; 10k full-system diagnostics fail (C4 to C43, F02) | C4 to C43, F02 | measured, **misses** | 4x |
| Settlement service (Calcify financial, not the finite lane) | at least about 9,091/s sustained | 1,051.73 settled/s empirical, under a different profile than the 2,500/s target; synthetic contender runs are not integrated | `docs/evidence/calcify-financial-sprint1/README.md`, as cited by the contract | partial | about 9x |
| **Finite P3 finance lane: Streams/RocksDB/changelog (free ledger)** | n/a (not a capacity lane) | about 14,000 inputs/s with ten 300-byte puts per input, 100,000-input run; 47,900/s with none. Short runs are ramp-limited (see Cautions) | CAL-FIN-S0 | partial, diagnostic | n/a |
| **Finite P3 finance lane: projector to Postgres** | n/a (not a capacity lane) | as written: 106 decisions/s one per call, 740/s at 120 per call (579/s after 12,000 decisions). With JDBC batches per statement class in one transaction (research variant, not product code): 3,688 to 4,313/s at 120 per call, 2,651 to 3,413/s after 12,000 decisions | CAL-FIN-S0 | partial, diagnostic | as written about 12x below the settlement need; batched about 2.7x below |
| Finite P3 finance lane: ledger `decide` at the pinned size (about 700 entities) | n/a | about 1.5 ms digest per decision at the ceiling; about 9 us per decision at about 20 entities (S2-A result) | `CALCIFY_FINITE_P3_S2A_RESULT.md` | partial, synthetic | n/a |
| Ledger `decide` at scale (10^4 to 10^5 entities) | would be required of any capacity engine | 25 to 344 ms digest and 118 ms to 9.6 s invariants per decision | `CALCIFY_FINANCE_STATE_COMMITMENT_DESIGN.md` section 1.1 (research spike) | measured, synthetic, **misses by orders of magnitude** | n/a |
| Restore | to be set by the owner (RTO) | store restore 1.8 s per 1,000,000 changelog records; changelog read 1.9 s and about 337 MB; restart to running about 42 s (cause unknown) | CAL-FIN-S0 | partial | unknown |
| Finance read routes under load | API p99 250 ms, 99.9% success | none | n/a | **unmeasured** | unknown |
| Trading-view visibility p99 | 1 s (p99.9 2 s) | none integrated | n/a | **unmeasured** | unknown |
| Eligible settlement to settlement-visible, p99 | 5 s (oldest 30 s) | none integrated | n/a | **unmeasured** | unknown |
| Session finalization (close + 30 s) | 30 s | none integrated | n/a | **unmeasured** | unknown |

What the table says plainly: the capture and context path meets 10k/s in the matched control, and everything after it (settlement, projection, read
routes, visibility, finalization) is either far below the target or has never been measured together. The finite P3 lane's own bottleneck, as measured, is
the projector, not the ledger or Streams.

## Guards (how the goal stays reachable while finite work proceeds)

1. **Finite artifacts are never the capacity path.** The finite ledger, store and binding stay frozen (the design's pinned-hash gate). No claim about a
   capacity lane may cite a finite-lane number, and a capacity lane may not reuse finite code to inherit the waiver.
2. **A headroom check before each new layer.** Before building a stage on top of another, measure the lower stage's row above on the workload the new stage
   will impose, with a free (stand-in) new stage. If the lower stage is already within 2x of its target, fix or redesign it first. This is the same stop rule
   as the S0 extension, made routine. The check is recorded as a ledger entry, not a chat message.
3. **Shape tests for new engine code.** Any new per-decision path in a capacity engine gets a test that runs it at n and 10n entities and fails if cost grows
   faster than about linearly in n (the invariant check in the finite ledger is quadratic, and a test like this would have caught it). The frozen finite
   ledger is exempt; new engines are not. Each new per-decision path also states its complexity in its design.
4. **Scheduled measurement, warn-only.** `.github/workflows/calcify-finance-throughput.yml` (manual, plus weekly) runs the S0 spikes on a disposable Redpanda
   and Postgres and compares with `docs/work/calcify-throughput-baseline.json` via `scripts/dev/calcify-throughput-check.mjs`. It warns at 30% worse and
   never fails the workflow, because hosted runners are noisier than the recorded laptop run. A human records any real change in the ledger.
5. **Every executed attempt is recorded, including diagnostics and corrections** (the ledger's rule). S0 was first reported in chat and a design PR without a
   ledger entry; CAL-FIN-S0 now records it, and the design document carries an explicit correction for its first Streams figure.

## Cautions that already bit us

- **Short runs understate throughput.** The same Streams stand-in measured 2.5k to 2.9k inputs/s at 5,000 inputs, 7.6k to 11k at 20,000 and 14k to 48k at
  100,000. Compare rates only at the same input count, quote the input count with the rate, and prefer sustained-duration runs. The baseline JSON is for
  100,000 inputs and may still understate steady state.
- **Lower bounds are lower bounds.** Several ledger entries report verified or resolved rates as lower bounds; do not subtract them from targets as if exact.
- **Different workloads are not comparable.** The matched control, the legacy projection path and the finite lane measure different things.
- **A stand-in is not the system.** S0 contains no ledger; the real processor adds `decide` and encoding.

## Next measurements, in order of value

1. A sustained-duration (minutes) version of the S0 Streams and projector runs, so the baseline stops being a short-run number.
2. The projector beyond JDBC batching (larger batches, `COPY`, fewer indexes, several bindings in parallel), since it is still the binding term at about 3k/s; and the research batching variant promoted to a reviewed product change if the finance lane is to be a capacity lane at all.
3. The real `FinanceProcessor` pre-aged to 10^4 to 10^5 entities (needs the lifted caps from the design's profile spec).
4. Finance read routes under a frozen concurrent read mix.
5. Explain the 42 s restart floor, then set the owner's restore-time objective.
