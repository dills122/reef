# F04 full-pipeline latency — September 26 stopping point

Status: investigation branch `codex/projection-latency-f04` starts at default-branch commit `ffc5b08a`. No F04 work-reduction candidate selected or throughput qualification claimed. Tracks issue #360 and audit issue #367 F04.

## Fresh evidence available

The F02 queue-storage pair provides a no-profiler, C43-shaped `10k/300s` full-pipeline control with fixed observer settings, six materializers, 16 canonical projector owners, and four lifecycle workers plus the nested caller. Its runtime source was `de93d526` and both arms shared matching event-ID correction `fabc556c`; these differ from this branch's default source. Fresh `sfo3` host, current migrations, and observer timing also differ from C43. Use the pair for interval diagnosis, not as a matched control for a new F04 code change.

UNLOGGED control: 2,999,880 accepted/direct-acked/materialized/projected; projected rate 9,999.20/s at later collection, lag zero, exact authoritative cohort and rollback-only business reference pass. Frozen checker still failed lifecycle/market freshness. Command-weighted conservative source→canonical p95/p99 was 358/632ms, source→lifecycle 42,289/45,934ms, and source→market 44,418/53,006ms. These are covering-observation bounds, not actual per-order visibility times.

Reproduced same-prefix control p95 intervals: durable materializer commit→observed lifecycle prefix 10,849ms; observed prefix→covering lifecycle marker 34,027ms. Market equivalents: 12,445ms and 34,054ms. Percentiles are not additive. The first interval mixes canonical queueing/execution with frontier polling. The second mixes lifecycle execution, dirty-queue barriers, and observation. The existing evidence does not separate those components, so it does not identify a safe code lever. The LOGGED treatment added 9.04% projection WAL and did not qualify downstream freshness either.

Raw local evidence: `artifacts/projection-dirty-f02-20260926/hosted-control/`, `hosted-treatment/`, and the reproduced `same-prefix-analysis.json` in each; their SHA256 evidence manifests passed. The initial failed control under `failed-control/` is invalid because of event-ID collisions. Committed run summary: `docs/THROUGHPUT_BASELINES.md` on F02 draft PR #372.

## Next bounded session

1. Reconcile issue #360 measurement contract with the current default branch and draft PRs #371/#372. Preserve same fixture, image hashes, 35 worker settings, clean volumes, observer cadence, exact cohort, and full business/replay checks.
2. Add low-rate, bounded in-load samples that distinguish active SQL execution, connection-pool wait, PostgreSQL wait event/blocker, dirty-marker age/queue, and frontier-observer delay. Pair each canonical batch's post-commit watermark/time with durable and downstream prefix markers. Keep this diagnostic run separate from the unprofiled comparison.
3. Explain the larger prefix→coverage interval first, including lifecycle work versus queue/barrier/observation. Inspect nested status/fill and timeline plans at the same workload shape, plus read/WAL/checkpoint/pool counters. Do not repeat C29 worker doubling or C31 index removal without new evidence; those prior trials missed their gates.
4. Choose one supported work-reduction lever, then run a fresh matched no-profiler control/treatment on the same source and topology. Keep frozen 5k/7.5k/10k five-minute freshness, exact cohort/replay/business, zero failure/queue, and independent 20% stopped-source drain gates. Record failures as failures; do not promote postdrain parity to in-load freshness.

No new F04 hosted run was started after this stopping point. F02 droplet `603811601` and firewall `0e3d8cf7-d244-4546-a6db-8310a7f8b7c0` were destroyed after local SHA256 evidence verification; provider lookups returned 404 and OpenTofu state was empty. Provision a fresh host for the next controlled trial.
