# Calcify matched no-cold-restart control — October 11, 2026

**Control passes:10,321.21–10,349.52 full contexts/s. Cold-restart treatment
remains failed at8,106.99–8,145.11/s.** Both final capture audits and both
independent financial/SQL replay audits pass. Current host has demonstrated
steady aged capture with financial/SQL contention; cold recovery remains an
unmet operating requirement.

[Compact comparison](../evidence/calcify-matched-control/comparison.json) preserves
interval bounds, events and final source/financial receipts.

## Results

| Measure | Cold restart treatment | No-cold-restart control |
| --- | ---: | ---: |
| Verified rate lower bound | 10,172.16/s | 10,404.61/s |
| Full-context rate bounds,0–300s | 8,106.99–8,145.11/s | **10,321.21–10,349.52/s** |
| Aged full-context bounds,60–300s | 7,527.79–7,619.27/s | **10,295.95–10,375.07/s** |
| Final full-fact parity and ordered uniqueness | 3,063,383 pass | 3,131,951 pass |
| HTTP failures/retries | 0/0 | 0/0 |
| Scheduler drops | 86,617 | 18,049 |
| Full-context end-gap upper bound | 631,285 | 35,589 |
| HTTP-completion-to-full-context drain upper bound | 29,118.95ms | 2,404.86ms |
| Final synthetic financial/SQL audit | 366,625 trades/37domains pass | 361,075 trades/37domains pass |
| Financial committed count around300s | 118,850–119,675 | 137,950–139,150 |

Full financial totals include post-load work and differing audit duration; they
are not timed throughput. Timed financial brackets use Docker log wall timestamps
against host epoch without a measured clock-error bound; diagnostic comparison
only. Policy and financial inputs match, but achieved financial work does not.
Control did more financial work during load, so improvement is not explained by
simply removing that contender. Process recovered at nextTrade50,650 with967ms
history replay versus41,200/1,289ms; those exclude JVM startup and are different
recovery-state sizes.

## What comparison isolates, and what it does not

| Full-context window | Treatment bounds/s | Control bounds/s | Control minus treatment bounds/s |
| --- | ---: | ---: | ---: |
| 0–60s | 10,248.48–10,423.82 | 10,247.32–10,422.23 | −176.50 to173.75 |
| 60–150s | 9,836.78–10,054.63 | 10,121.49–10,345.92 | 66.86–509.14 |
| 150–180s | 7,967.20–8,557.27 | 9,676.57–10,357.50 | 1,119.30–2,390.30 |
| 180–300s | 5,538.68–5,705.76 | 10,411.41–10,571.55 | **4,705.65–5,032.88** |

First-minute ranges overlap almost exactly. Control already leads by382.29–489.73/s
over0–180s. Subtracting that preceding difference from post180 difference yields
4,215.92–4,650.59/s. This is a descriptive difference of sampling bounds, **not a
causal estimate or statistical confidence interval**. Financial crash timing,
replayed financial prefix, broker/Postgres startup, disk/cache state and arrivals
also differ. One sequential pair cannot apportion cost among state restoration,
JIT/cache warming, compaction, scheduling and shared I/O.

Treatment observer saw unchanged resolved count1,814,430 from187.60–203.93s.
Health observations show replacement resolver REBALANCING at196.11s and RUNNING
by211.11s, yet full-context coverage still misses deadline. Control retains same
container ID/start time, zero Docker restarts and sampled RUNNING throughout.
Process readiness is therefore insufficient recovery completion evidence.

## Changes now justified

1. **Prioritize recovery path on current server.** Ordinary process restart should
   reuse mounted, compatible managed state directory; prove it under same aged
   financial/SQL workload next. Keep empty-directory restore as explicit cold
   recovery test. This control demonstrates feasibility without cold restart;
   it does not yet prove a warm restart, standby failover or cold recovery fix.
2. **Require catch-up capacity, not just10k steady service.** At10k arrivals/s,
   a20s pause creates200k backlog; draining it in60s needs13,333/s service, or
  16,667/s in30s. These are illustrative conservation calculations, not measured
   capacity or adopted recovery SLOs. Treatment was567,902 contexts short of3m
   deadline target. Freeze permitted interruption, oldest-context age and drain
   deadline; measure recovery service rate while arrivals continue.
3. **Evaluate reserve/parallelism or warm standby only against measured deficit.**
   Frozen profile uses2 stream threads and no standby; current
   [resolver configuration](../../services/platform-runtime/src/main/kotlin/com/reef/platform/calcify/CalcifyResolverRuntime.kt)
   uses100ms commit interval and
  8MiB Streams cache. Preserve correctness/durability while isolating one change.
   If persistent-state restart fails gate, measure restore progress, GC/compilation,
   disk latency and whole-host CPU before selecting reserve/thread/cache changes
   or larger server. Same-host standby needs its own memory/I/O budget and failover
   proof. No new infrastructure or production tuning adopted here.
4. **Keep identity and storage limits explicit.** Control matching sampled peak
  4.71GiB, higher than treatment4.2GiB; resolver final logical state2,352,406,848B.
   Those observations do not establish unbounded lifetime or bytes-per-trade law.
   Cap supported run population or prove certified pruning. Minimum guest free
  15.880GB stayed above8GB reserve; this run consumed roughly12.7GB from preflight.
   Another comparable run needs additional disk headroom before launch.

Named-container sampled CPU mean over180–300s was636.9% in control versus758.6%
in treatment, with observers/host driver excluded. These are snapshot means,
not CPU time or evidence of whole-host spare cores. SQL database2,539,518,999B
includes previous proofs; no control-only physical write-cost claim.

## Frozen setup and limitations


Purpose: compare against completed [aged contention treatment](CALCIFY_AGED_CONTENTION_PROOF_2026-10-11.md),
source baseline b9a8756e8d0efc5e8b665a5c62da1ab064d01539. Intended treatment
difference: omit cold resolver restart at180s; preserve financial start at60s and
kill/replay at150s. Same300s@10,500 offered trades/s,1,024 workers,64 seed makers,
terminal retention0/no eviction. Finite lifecycle profile remains off. Financial
inputs remain independent synthetic trades; production materializer not exercised.

Control repeats state growth from fresh source/topic/application IDs, generation5.
It does not start with previous run's3m identities. Images, binary/source hashes,
normalized Compose settings, VM10CPU/16,745,824,256B allowance and actual5topic
RF1/16partition/write.caching=false settings match treatment. Preserved earlier
proof raises idle storage occupancy; guest free before control28,588,769,280B.
Same8GB reserve applies. Sequential run and different arrivals/cache/checkpoint
state prevent exact causal attribution from a single pair. Actual financial
progress and fault times must accompany capture rate comparison.

Source/configuration and raw outputs frozen under
`/private/tmp/calcify-control-20261011`. Existing original proof stays immutable.

Reproduction uses existing research images and owned project; inspect current
free disk, ports and source profile before repeating:

```sh
CALCIFY_CONTENTION_PLAN=/absolute/path/plan.json bun scripts/dev/calcify-financial/research/run-contention.mjs SETUP_DIRECTORY NEW_OUTPUT_DIRECTORY control
bun scripts/dev/calcify-financial/research/summarize-contention.mjs OUTPUT_DIRECTORY
bun scripts/dev/calcify-financial/research/compare-contention.mjs TREATMENT_DIRECTORY CONTROL_DIRECTORY NEW_COMPARISON_JSON
node --test scripts/dev/calcify-financial/research/compare-contention.test.mjs
```

Additional measurement differences: broker/Postgres restarted before control;
treatment reused their running processes. Health sampler starts near control
onset versus116s in treatment. Lightweight local harness checks ran during
control timed window, adding unmeasured host overhead. These differences are
retained with actual manifests rather than hidden in a clean A/B claim.

Comparison subtracts interval bounds (control lower minus treatment upper, and
control upper minus treatment lower), never just two lower bounds. Count at
interval start excludes prior aged population. Exterior observations bound upper
count; absent bracketing leaves result unproven. Five focused regression tests
cover uncertainty, monotone counts, aged population and exact boundaries.

## Verification and retention

Full source/context and all-domain SQL replay/balance audits pass.22 focused
Node tests (11 capacity,6 summary,5 comparison) and script-surface check pass.
Comparison generator reproduces frozen data and refuses missing financial/fault
execution. All owned services stopped after audits; volumes retained. Runtime
images and production source unchanged. New code adds control mode and bounded
comparison/reporting only. 59 exact control records/2,040,363 bytes published at
[Records be2a834a](https://github.com/dills122/reef-records/tree/be2a834af72234785afff8eadf504349aa2d52b1/records/reef/docs/evidence/calcify-matched-control/run-2026-10-11-b9a8756e), appended to
[draft PR12](https://github.com/dills122/reef-records/pull/12). All62 changed blobs/2,216,834 bytes
remotely byte-verified; prior treatment records/manifests unchanged.
[Retention receipt](../records/2026-10-11-calcify-matched-control-retention.json) owns
publication/check status. Exact-head Records integrity, append-only, hygiene and4
unit tests pass. No tracked Reef source removed; archive remains unmerged.
Reef hosted CI not run for local research changes.
