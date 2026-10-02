# Calcify Phase 2 implementation evidence

Production classes replaced experimental JSON context codec with Protobuf full-fact output. Exact build/config scope remains part of each result.

- `broker-bf939b54`: first RF3/minISR2 cohort; state/forward crash, committed restart, disk-loss restore, poison/rebalance/promotion and broker-loss all reconcile exact full facts/zero duplicates. Default group timeout caused47–64s restart-plus-wave measurements. Disk-loss pre-restart observer accidentally asked for0 while130 committed records existed; final260 exact assertion passed. That observer expectation is corrected in next cohort; original record retained.
- `broker-78c66ac2`: shorter6000ms group session/1000ms heartbeat, same production topology plus idle UUID monitoring. Small-fixture crash/recovery7–11s measured restart+wave, not isolated restore. Nine boundaries include retention, live/restart recreation, stopped-owner expiry/resume. Topic/lane clones are synthetic broker fixtures; matching routing proof belongs to Go/full-path tests.
- `full-path/registry-build-failed.log`: normal Docker build hit registry metadata deadlines. No functional or throughput claim. `cached-image-stage-failed.json`: scratch engine image has no shell; diagnostic staging corrected to copy/commit without shell start. Cached-base diagnostic images are explicitly identified in manifest.
- `paired-source-fixture.jsonl`: current Go matcher produces200 successful submit outcomes/100 trades with complete facts and long IDs. Test proves byte-semantic replay. Used for balanced two-new-order resolver workloads; not an HTTP intake throughput result.
- Platform full test/coverage gate passed before final fault/observer additions; final gate rerun follows. Raw focused failures during TDD were expected and are not throughput attempts.

No prototype store-only rate, small RF3 recovery or fixture smoke establishes sustained10k durable resolved commitments/s, large-state recovery SLO or full-system20k intake commands/s.

## Durability correction (2026-09-30)

Original two cohort labels `RF3/minISR2` describe requested Kafka configuration. Redpanda26.2.3 does not expose Kafka ISR configuration; actual source/verified/output topics had `write.caching=true` (broker default). Those cohorts prove recorded crash/rebalance behavior only, not disk-flush durability. Preserve original results unchanged. Corrected cohort sets `write.caching=false` on all three topics and Streams changelogs; runtime now validates backend-specific acknowledgement settings and rejects unsupported/missing durability configuration. Redpanda uses majority Raft quorum; Kafka uses configured minimum ISR. No simultaneous power-loss test claimed.

Initial corrected-helper build command used repository-root Gradle wrapper path; exited127 before build. Correct service-local command passed focused durability/processor tests and installDist. Neither attempt is a throughput measurement.

- `broker-4cf80838`: corrected fsync cohort passed four crash/recovery cases, then topic creation timed out. Broker rp2 exited133 on `fallocate` ENOSPC. Many retained probe topics used default large segments. Fresh disposable cluster and explicit1MiB probe source/verified/output/changelog segments bound diagnostic disk usage; change is part of subsequent cohort configuration. Existing Reef volumes preserved.
- `full-path/null-key-validation-failed.log`: live verifier emits null Kafka keys; initial resolver rejected them. Regression now covers payload-derived identity for legacy null keys and rejection of conflicting supplied keys. No resolved output claimed for failed smoke.

- `broker-a9bc0494`: corrected RF3 fsync/1MiB-segment cohort passes nine fault boundaries. Later probe processes include durable linked queue change; this cohort spans build updates, so final unchanged-build repeat is required.
- `full-path/passed.log`: real PostgreSQL HTTP intake, Go matcher, Phase1 verification and production managed role emit one exact full-fact context (2,588B), both intake rows durable. RF1/cache-base functional diagnostic; before linked pending queue change. Stack stopped with volumes preserved.

## Capacity and storage follow-ups

Transaction/cache/checksum observations, all failed attempts and unchanged-build limitations: [baseline ledger](../../THROUGHPUT_BASELINES.md#cal-p2-i1-follow-ups-transaction-batching). Latest short hot100k result `capacity-64c101a6` is10,638.42/s with exact full-fact parity, zero duplicates/gaps; source injected from paired Go fixture, RF3/fsync/two threads, one active hot lane. No upstream HTTP rate claimed.

`sustained-1fadb3e2` failed when Docker VM filled; all three broker exit133 logs preserved. Image `VOLUME` created anonymous data volumes retained across plain down. Read-only exact-volume inventories establish six old probe volumes belonged to this task; exact cleanup record retained. Unrelated106.5GB user volumes preserved. New test Compose explicitly labels/names three test volumes and uses32MiB segments; sustained harness deletes only its exact four cohort topics after successful full-fact reconciliation. No whole-broker or Docker pruning.

Large recovery `recovery-5545850c`:1m full accepted rows,767,176,186B local state, observed RUNNING19,971ms after complete local-state loss, exact500,100 contexts after catch-up. Before latest batching/cache/fast path; final candidate repeat pending. Resolver probe uses production topology/source reader/state/gate but bypasses SQL role registration/startup configuration checks; actual role integration belongs to separate HTTP smoke. One-worker capacity setting requests one standby but cannot place a standby on same instance; two-instance fault case separately checks promotion.

Sustained attempts complete; none qualifies all four profiles. `sustained-53a64664` reconciles3.3m at10,605.80/s but fails127,510 end gap; `sustained-fa7fec51` reconciles3.15m but fails rate/end/covering gap. Latest `sustained-8ea6c8ce` reconciles3.15m at10,160.50/s with1,184 end gap/16,842 covering peak, but actual producer310.730s exceeds frozen301s limit. Each failure stops spread/skew/aged. Original results/policies/classes retained; changes in observer cadence and producer settings described in baseline ledger. Window rates include observer/transaction visibility; no actual per-trade latency claim.

Nightly checkpoint: `platform-check-handoff.log` records final local full tests and61% instruction coverage gate passing. Go race and Protobuf governance checks are repeated in `nightly-checkpoint/`. Independent read-only review identifies open verified-input retention and verified/output namespace identity defects in `review-findings.md`. Fixes are not implemented; source-only negative cases do not establish those guarantees. Final unchanged-candidate fault/recovery, production-role failure exit and fresh HTTP smoke remain required. See [handoff](../../work/handoffs/2026-09-30-calcify-phase2.md) for exact resume steps.

Profiler binaries (`*.jfr`) remain ignored local artifacts; summaries/hashes retained. All portable raw logs/results are retained unchanged. Task-owned disposable RF3 cluster shutdown is recorded in `nightly-checkpoint/`; Reef and unrelated user volumes are preserved.
