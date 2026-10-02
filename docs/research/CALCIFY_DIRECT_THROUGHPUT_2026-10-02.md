# Calcify direct-path sustained qualification — October 2, 2026

## Result and scope

**PASS: 10,425.19 verified matched trades/s over 300 seconds; final non-SQL Calcify resolver delivered 10,440.92 full-fact trade contexts/s over same window.** Primary path record is lower stage rate, 10,425.19/s. Independent final reconciliation confirms 3,142,846 matched trades, verified commitments and resolved contexts, ordered and unique, with complete fact parity. Source contains 3,142,910 accepted order commands: 64 untimed maker seeds plus 3,142,846 timed aggressors. No HTTP failures or retries.

Run D7 started October 2 at 06:58:10.871 UTC. Fresh user-authorized two-hour campaign began 05:54:53 UTC, deadline 07:54:53 UTC. Earlier SQL-backed campaign is separately retained and explicitly corrected; its PostgreSQL waits did not establish intended fast-path ceiling.

This is local Docker, single Redpanda broker/RF1 with actual write caching disabled, current Phase 1 policy-version-1 stub verifier and Phase 2 managed RocksDB resolver. It qualifies this standing-liquidity workload, not hosted/RF3 resilience, all trading workloads, real financial verification, SQL receipts or settlement. No JSON library replacement involved.

## Fixed policy and accounting

[Frozen D7 policy](../evidence/calcify-direct-throughput-2026-10-02/d7-300s-standing-liquidity/frozen-policy.json), [complete results](../evidence/calcify-direct-throughput-2026-10-02/d7-300s-standing-liquidity/results.json), [independent full-fact audit](../evidence/calcify-direct-throughput-2026-10-02/d7-300s-standing-liquidity/full-facts.log).

| Measure | D7 result |
| --- | ---: |
| Requested load duration | 300 s |
| Offered trade jobs | 3,150,000 at 10,500/s |
| Dispatched/completed matched trades | 3,142,846 |
| Scheduler jobs dropped before dispatch | 7,154 (0.2271%) |
| Durable HTTP ACKs before Go fixed deadline | 3,142,677; 10,475.59 order commands/s |
| Verified observed before conservative parent deadline | 3,127,558; 10,425.19 trades/s |
| Resolved observed before same conservative deadline | 3,132,276; 10,440.92 trades/s |
| Final source/verified/resolved trade count | 3,142,846 each |
| Full source accepted order count | 3,142,910 including 64 seeds |
| HTTP elapsed including completion | 300,023 ms |
| Verified completion drain upper bound | 1,107.80 ms after HTTP completion lower bound |
| Resolved completion drain upper bound | 1,200.30 ms after HTTP completion lower bound |
| Failures/retries/duplicate resolved contexts | 0 / 0 / 0 |

Predeclared pass requires at least 300s, both stage rates >=10,000/s, valid loader gate, zero HTTP failures/retries, <=1s HTTP overrun, <=5s completion drain, fresh baseline, exact source/order accounting and independent ordered full-fact parity. All gates passed. A sustained average does not require every one-second observer window to exceed 10,000/s.

Rates use last cumulative observation received before parent monotonic loader-spawn+300s. Parent starts before Go load start, and observer windows are approximately one second; these are conservative lower bounds, not interpolated actual completion rates. Independent observers can report different lower bounds even though every resolved context follows verification. Final counts reconcile. No untimed maker contributes to throughput. Final drain is a conservative cohort bound, not individual trade latency.

Workload: 64 instruments, exactly four per each of 16 actual SHA256 routing lanes. One standing BUY maker per instrument, quantity sufficient for full window plus headroom. Each timed SELL of 100 units crosses at fixed price, creating one trade, one verified commitment and one resolved context. Seed orders travel through regular API/durable ingress/matching; zero seed trades. Both source and output baselines proved before timed load. Legacy loader fields named pairs count jobs; authoritative trade counts come from canonical source and independent output audit.

## Critical latency

All 3,142,846 trades included, no sampling. [Verified observer output](../evidence/calcify-direct-throughput-2026-10-02/d7-300s-standing-liquidity/verified.log).

| Interval | p50 upper | p95 upper | p99 upper | Observed max |
| --- | ---: | ---: | ---: | ---: |
| Batch entry before matching -> read-committed verified visibility | 221 ms | 348 ms | **433 ms** | 673.166 ms |
| Matching work finished -> read-committed verified visibility | 212 ms | 336 ms | 416 ms | 628.809 ms |
| Batch entry -> matching work finished | 6 ms | 31 ms | 50 ms | 148.315 ms |

Individual matching acceptance has no dedicated wall-clock timestamp. Canonical acceptedAt is simulated event time and cannot measure elapsed wall time. Batch createdAt precedes matching execution; therefore entry-to-verified visibility provides conservative upper bound for requested matching-acceptance-to-verified interval. Do not label 433ms exact acceptance p99. Histogram buckets round upward to one millisecond; no overflow. WorkFinishedAt timing checksum validated. CreatedAt is batch metadata excluded from payload/timing checksums: bound assumes trusted matching clock/source metadata. Matching/observer clocks share local host; maximum observed wall-vs-monotonic step ~4.46ms, so wall clock precision remains practical limit.

Read-committed visibility includes checksum/source publication, extractor/verifier transactions, broker commit, fetch and observer scheduling. Kafka producer CreateTime is not durable visibility. D7 CreateTime-minus-work-finished p99=321ms; visibility-minus-CreateTime p99=181ms includes transaction commit and delivery, not pure observer overhead. No timestamp subtraction used for throughput.

## Baseline and configuration differences

Historic C5 accepted 9,998.74 and 9,999.45 commands/s over two 300s windows on hosted c-16 with 16 partitions, 64 instruments, 1,024 workers, matching batch 500 and projections disabled. It measured mixed submit/modify/cancel command intake with PostgreSQL canonical materializers, not Calcify trades/s or critical verified latency. See [baseline ledger](../THROUGHPUT_BASELINES.md) and [original C5 evidence](../evidence/throughput-core-baseline-2026-09-24.json).

This campaign reuses C5 ingress/matching controls, attaches Calcify, changes workload explicitly and runs local Docker guest with 10 CPUs/16,745,824,256 bytes (~15.6GiB). Hot path: HTTP Netty -> in-memory bounded intake -> durable Redpanda ACK -> direct Go matching -> canonical event batch -> transactional Calcify extraction -> stub verification -> managed resolver -> read-committed full-fact output. PostgreSQL registers source generation/topic identities at startup/rebalance only; API JDBC URLs blank, persistence noop, settlement facts disabled. No per-order PostgreSQL intake, receipt worker, legacy materializer or projection.

[Actual container settings/images](../evidence/calcify-direct-throughput-2026-10-02/d7-300s-standing-liquidity/actual-profile.json), [source/class provenance](../evidence/calcify-direct-throughput-2026-10-02/d7-300s-standing-liquidity/source-class-manifest.json), [setup and instruments](../evidence/calcify-direct-throughput-2026-10-02/d7-300s-standing-liquidity/setup.json).

- API: 512MiB heap, 2 active CPUs; in-memory idempotency/intake, intake cap100k/shards256, publish pipeline queue8192/inflight256 per lane/batch1/linger0; HTTP workers1024.
- Matching: direct lanes0..15, batch500, fetch100ms/poll1ms/maxAckPending16000/ackWait60000ms, no producer compression. Optional Sarama max-message limit4MiB; default remains1MiB.
- Terminal retention: fresh workload uses3,906 terminals/book, aggregate249,984 over64 books plus64 active makers. Historical250k parameter now means per-book, which would retain16m terminals across64 books. Only test configuration changed.
- Calcify: extractor poll100, verifier poll1000, resolver2threads/maxPending1000/standbys0, 1GiB heap/2activeCPUs per sidecar; source budget4MiB and target16MiB.
- Redpanda: SMP2/memory2G; all five source/cohort topics actual write.caching=false, defaultfalse, RF1. Source max.message.bytes4MiB. Local broker durability does not qualify replica failover.
- Runtime image: sha256:212c08152521449235b365368902f8bfacf4d8310056f033701d0ea97dd9f2f3. Actual D7 matcher image: sha256:720663db604d132a41453aaa81dedd28edbbda050b709b3ce036265619bf03f6. Images built from tested source overlay on cached runtime bases; snapshot records dirty source hashes over base080f0eda, not a claim that base commit alone contains candidate.

Bounded terminal retention narrows lifecycle lookup/idempotence horizon: retained terminal cancel returns INVALID_STATE, evicted ID lookup NOT_FOUND, evicted order ID may be reused. Equal fixture occurredAt uses lexical-ID tie breaks, not wall-time expiry. V4 matcher state rejects retention-limit mismatch; do not replay prior cohort under changed limit. Standing makers also improve state locality relative to fresh two-sided orders; no all-workloads transfer claim.

## Bottleneck and paired comparison

Extractor previously committed Kafka transaction for every canonical source batch, despite consumer poll100. D4 logical source-offset lag grew2,199->13,126 over52s while verifier/resolver lag stayed much smaller. Verified producer timestamps also accumulated40s behind matching: backlog was real extraction/publication work, not only observer cost. Logical offsets include transaction markers and are not trade counts.

Fix processes bounded valid partition prefixes from one poll in one Kafka transaction. Outputs and corresponding source offsets remain atomic. Zero-trade batches advance checkpoint; malformed whole batch contributes no partial outputs; poison blocks only suffix in its partition while healthy partitions continue. Existing transactional helper performs send completion, offsets-to-transaction and commit. Canonical JSON/checksum bytes, 21-byte commitment protocol and same-lane offset/ordinal ordering preserved.

| Run | Seconds | Workload | Verified trades/s | Resolved trades/s | Interpretation |
| --- | ---: | --- | ---: | ---: | --- |
| D3 | 60 | Fresh BUY+SELL per trade, 5,250 offered trades/s | 2,204.85 | 2,161.25 | Actual durable direct ingress, extractor per-batch transactions |
| D4 | 60 | Same paired controls | 2,044.72 | 2,048.25 | Confirmed extractor lag; ~41s completion drain |
| D5 | 60 | Same paired controls, bounded poll transaction | 5,166.97 | 5,080.08 | 312,585 exact trades; p99 after-work visibility848ms, drain<=2.1s |
| D6 | 60 | Standing makers;10,500 offered aggressor trades/s | 10,296.10 | 10,202.45 | 625,780 exact trades; short diagnostic only |
| D7 | 300 | Fresh standing makers; same candidate/control profile as D6 | 10,425.19 | 10,440.92 | Sustained qualification passed |

D3/D4->D5 is relevant paired comparison for transaction batching. D5->D6 changes workload, offered rate, and fresh per-book retention; do not assign entire difference to extractor fix. D7 is one successful full qualification, not repeatability confidence interval. Raw D3-D7 evidence retained under [campaign evidence](../evidence/calcify-direct-throughput-2026-10-02/).

## Corrections and resource closure

Earlier campaign selected SQL smoke profile incorrectly; preserved [correction](CALCIFY_10K_TIMEBOX_2026-10-02.md#configuration-correction--october2-after-campaign). D1 bootstrap mistakenly passed redpanda.write.caching=false; broker ignored unsupported topic key and actual write.caching was true. D1 is diagnostic only, never durable capacity evidence. Correct key write.caching verified for D3-D7. Historical C5 actual caching value was not retained; do not infer it from D1. Redpanda documents [topic write.caching and max.message.bytes](https://docs.redpanda.com/streaming/current/reference/properties/topic-properties/).

D7 initial pre-load snapshot assertion expected bare false but rpk returned JSON string "false". Assertion failed before load; parser fixed, actual settings checked, fresh snapshot then passed. No throughput result taken under failed gate. No builds or class changes overlapped D7 timed load; independent full-fact audit ran after live observers finished. At closure all seven owned services stopped; D7 broker topics and managed state volumes preserved, as recorded in [closure manifest](../evidence/calcify-direct-throughput-2026-10-02/d7-300s-standing-liquidity/closure.json).

User-authorized cleanup removed21 Reef test containers and107 owned test volumes; Docker volume usage128.7GB->15.03GB, guest free space~109GB. Unrelated/anonymous volumes retained, cached build images reused. [Ownership cleanup manifest](../evidence/calcify-direct-throughput-2026-10-02/docker-cleanup.json) contains names only; raw Docker environment inventory excluded from repository. Other test PostgreSQL consuming CPU paused for measurement; subsequently absent at closure, no replacement created.

Collected D7 samples show matcher~630-640MiB, API~660MiB RSS, broker~1.88GiB, resolver~750MiB; PostgreSQL idle. Initial monitor started~62s before load and ended before final~30s of load; tail monitor covers drain/audit. This monitoring gap is disclosed and does not affect complete 300s live throughput observations. Do not claim continuous memory/lag coverage for entire load.

## Checks and remaining work

Full platform check/installDist passed before candidate runtime image build. Final platform check and coverage gate passed after audit:727tests, zero failures/errors/skips. Independent read-only delivery review found no material blockers. Matcher streamdirect race tests passed; loader race tests passed. Focused extractor tests cover checkpoint movement, poison-prefix isolation, zero-trade and malformed-batch behavior. Observer tests cover timestamp precision/overflow, checksum timing and strict maker baseline. Runner tests cover deadline exclusion, seeded count exclusion, accounting/mode mismatch, missing proof and drain gates. First pushed head missed registration of new test in Node CI job; script-surface gate caught omission, follow-up wires test into coverage job and preserves red/green logs. No timed candidate source/class change. [Saved verification logs](../evidence/calcify-direct-throughput-2026-10-02/tests/).

Next scope, prioritized:
1. Repeat unchanged300s candidate; then fresh paired/hot/skew/aged workloads with predeclared caps. Separate command capacity from trade yield and state-locality effects.
2. Failure-at-load proof for abort/rebalance/poison valid prefixes; confirm no partial links or offset advance, bounded memory under worst-case trade fanout. Current poll100 bounds records, not aggregate expanded trade bytes.
3. Hosted/RF3 sustained qualification and managed state-loss/recovery at measured load. Existing earlier recovery evidence does not transfer automatically to10k concurrent intake.
4. Dedicated integrity-covered monotonic/wall acceptance timestamp and shared-clock validation if exact critical-path p99 becomes required; current bound deliberately conservative.
5. Keep JSON codec benchmarking as separate allocation/CPU experiment. This run identifies transaction granularity as concrete bottleneck; no evidence requires library swap to reach scoped10k goal.

## Reproduction boundary

Scripts require fresh IDs/generation, isolated fixed Compose project and explicit prebuilt images/classes/loader. They do not perform global cleanup or infer actual durability from requested settings. Stop any conflicting stack deliberately, inspect container ownership and available guest disk, then inspect all actual broker/container settings before load. Bootstrap alone is not capacity qualification.

Build current matching/runtime images with repository Dockerfiles; compile platform test classes and loader before observation. Select fresh ID, generation>=2, duration300, pace10500, workers1024, workload aggressor, terminal limit3906, candidate images. Run scripts/dev/calcify-direct-bootstrap.mjs, use setup.json instrument list and preflight accepted orders64 with contexts0, then scripts/dev/calcify-direct-capacity.mjs. Preserve D7 rather than reuse its source namespace. Frozen policy, source UUID, class/image hashes and independent full-fact parity mandatory for comparison. See [handoff](../work/handoffs/2026-10-02-calcify-direct-throughput.md) for concrete continuation commands.
