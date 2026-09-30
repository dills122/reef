# Calcify Phase 1 implementation and local evidence

Status: additive opt-in diagnostic slice on `codex/calcify-phase1`, based on `origin/master` `56b882d4` (2026-09-29). No production cutover or financial settlement claim. [Discovery](CALCIFY_DISCOVERY.md) owns longer-horizon questions; [link contract](../../contracts/calcify/README.md) owns Phase 1 wire bytes.

## Implemented boundary

- Matching engine still writes committed `VenueEventBatch` to `REEF_VENUE_EVENTS`. Legacy materializer, projections, and trade-to-settlement path remain active.
- Extractor reads `read_committed`, checks semantic checksum, source metadata, outcome membership, and nested trade shape once per batch, then emits one 21-byte pointer per trade to `REEF_MATCH_COMMITMENTS_V1`. It sends output and consumed source offset in one Redpanda transaction; zero-trade batches commit offset with no output.
- Verifier reads links without rereading source. Stub policy version 1 checks wire shape and partition, then transactionally emits 23-byte passing links to `REEF_VERIFIED_COMMITMENTS_V1` with input checkpoint. Pass is not financial or participant approval.
- Receipt worker inserts `runtime.calcify_commitment_receipts` by commitment tuple. Replay keeps one row; conflicting policy version fails. PostgreSQL commit precedes Kafka offset commit. Row presence means recorded only: no `SETTLED` state, cash movement, or securities movement.
- Extractor consumer group includes source generation, so recreated topic starts at its own offset zero after generation advance. Topic UUID is bound in registry at startup and checked again on partition assignment. Malformed source or link pauses affected partition without committing bad offset; other partitions continue. Broker and database failures stop process for restart. Same-partition order follows consumer ownership and explicit output partition.
- `compose.calcify.yml` runs stages as optional local sidecars. Existing default Compose services are unchanged. Runtime selects stage only when `CALCIFY_STAGE` is set.

## Local diagnostic sequence, CAL-P1-L1

Base: C5 venue-core passed two 10,000/s, 300-second samples with projections off; C2 full projection failed sustained 5,000/s. This run uses new Calcify code, one local extractor/verifier/receipt worker, two source partitions, hand-seeded batches, and PostgreSQL receipt counts. No rate or latency comparison to C5/C2 is valid. Historical C5 and C2 artifacts and limits remain in [throughput ledger](../THROUGHPUT_BASELINES.md).

Environment: macOS local Docker 29.7.2, Redpanda v26.2.3, PostgreSQL 16; code from branch worktree with runtime migrations `0070` and `0071`. `docker compose -f compose.base.yml -f compose.local.yml --profile redpanda up -d postgres redpanda`; primary-only forward migrations applied. Docker image build attempt failed fetching Docker Hub base metadata (`DeadlineExceeded`), so `./gradlew installDist` output was mounted into cached `reef-platform-runtime` image for diagnostic. No hosted run or immutable runtime-image digest exists.

Evidence, observed with Redpanda group frontiers and PostgreSQL row queries:

| Step | Source | Result |
| --- | --- | --- |
| Contract and receipt tests | zero/one/many builders; local migrated PostgreSQL | focused Gradle tests pass; receipt replay one row and conflicting policy rejected |
| Assembled path | partition 0 offsets 0–2: 0, 1, 5 trades | six receipts at offsets 1–2; extractor source checkpoint 3, proving zero-trade offset advanced |
| Poison isolation | invalid checksum at partition 0 offset 3; later valid record at 4; valid partition 1 offset 0 | partition 0 checkpoint stayed 3, no row for offset 4; partition 1 checkpoint reached 1 and produced one receipt |
| Seeded verifier and receipt | direct valid commitment link at partition 1 offset 1000; direct passing link at 1001; replay of prior passing link | one receipt for each new link; replay did not add row |
| Fanout | one 9,863-byte source batch with 128 trades at partition 1 offset 1 | 128 receipts with flattened ordinals 0–127; source checkpoint reached 2 |
| Receipt restart | receipt worker stopped; 50 one-trade batches published on partition 1 offsets 2–51; worker restarted | 50 new unique rows, 187 total diagnostic rows, verified backlog drained; corrupt partition 0 remained stopped |
| Final rebuilt path | all workers restarted after producer batching edit; three-trade batch on partition 1 offset 52 | three new rows (ordinals 0–2), 190 total; source checkpoint 53 on partition 1 and 3 on poisoned partition 0 |
| Final receipt and topic identity | receipt worker restarted with reused JDBC connection; seeded verified link at partition 1 source offset 1002; registered topic UUID deliberately mismatched for one extractor startup | one new receipt (191 total); mismatched extractor exited before consumption, registry restored, valid extractor restarted |
| Final registered extraction | checksum-valid one-trade batch at partition 1 offset 53 after guard restoration | one new receipt (192 total); healthy source checkpoint 54, poisoned partition checkpoint 3 |
| Generation-scoped replay | extractor restarted with generation 1 consumer group after group-ID change | healthy partition replayed to checkpoint 54; 192 unique receipts unchanged, poisoned partition checkpoint 3 |

Full-suite correction: first 654-test run failed two pre-existing helper tests because Calcify JSON accessor names shadowed runtime extensions. Accessors were renamed; full suite rerun passed. This failed run is retained as setup evidence, not an application-capacity result.

`rpk group describe` log-end lag includes transaction control records; report relies on source checkpoints and exact receipt identities, not lag field as per-trade latency measure. Fixtures and command output lived under `/private/tmp/calcify-stage/`; retained evidence is this aggregate and committed fixture builders. No sustained capacity, payload-retention, source-to-link independent reconciliation, or source-restore proof was run.

## Full ingress and matching smoke, CAL-P1-E2E

A later local functional run joined the existing HTTP ingress and Go matching
path to Phase 1. `make dev-smoke-calcify-full-path` now creates fresh four-partition
Redpanda command, venue-event, commitment, and verified topics. It uses
PostgreSQL-backed intake and idempotency, direct matching consumption, and the
three Calcify sidecars. It sends a resting buy and a crossing sell in one venue
session, checks two PostgreSQL intake rows, reads committed matching batches,
then checks exactly one receipt for the real trade's broker partition and offset.
The resting batch must produce zero receipts. This is a small functional check;
no rate, latency, retention, or settlement claim follows from it.

Corrections are retained in run order:

| Attempt | Observation | Correction |
| --- | --- | --- |
| Initial manual full path, generation 2, `REEF_CALCIFY_E2E_EVENTS_0929` | Broker stored resting batch on partition 2 while batch declared command lane 1. Extractor stopped partition 2; no full-path receipt. | Sarama producer had default key-hash partitioner despite setting `ProducerMessage.Partition`. Configure manual partitioner; regression test first reproduced partition 2 and then passed with declared partition 1. |
| Manual retry, generation 3, `REEF_CALCIFY_E2E_EVENTS_0929_B` | Broker and batch both used partition 2; resting batch offset 1 had zero trades, crossing sell batch offset 4 had one trade, and extractor produced one link. Verifier failed because receipt consumer had auto-created verified topic with one partition before verifier's four-partition output setup. | Every Calcify stage now provisions and validates both link topics from source partition count before subscribing. Missing source topic fails startup. |
| First reusable smoke, generation 4 | Real trade reached matching and one receipt existed; harness compared `psql` tab-separated row with pipe-separated expected text and failed its own assertion. | Corrected expected delimiter; reran on fresh topics. |
| Reusable direct no-DB smoke, generation 5, `calcify-1790709807245` | Resting batch partition 3 offset 1, zero trades; crossing batch partition 3 offset 4, one trade; receipt `(5,3,4,0,1)` present exactly once. | Pass. This profile used in-memory intake metadata; it did not cover PostgreSQL ingress. |
| PostgreSQL-backed smoke, generation 6, `calcify-1790710028844` | Two command rows in `boundary.stream_command_intake`; resting batch partition 3 offset 1, zero trades; crossing batch partition 3 offset 4, one trade; receipt `(6,3,4,0,1)` present exactly once. | Pass. `make dev-smoke-calcify-full-path` now selects this profile. |
| Final Make entry point, generation 7, `calcify-1790710280736` | `make dev-smoke-calcify-full-path` passed: two PostgreSQL intake rows, resting batch partition 2 offset 1 with zero trades, crossing batch partition 2 offset 4 with one trade, exactly one receipt `(7,2,4,0,1)`. | Pass with final harness including boundary intake assertion. |

Environment: same local macOS Docker 29.7.2, Redpanda v26.2.3, and
PostgreSQL 16 as CAL-P1-L1. Corrected matching and runtime images were built
locally from branch worktree; no hosted or sustained load run. Workload: two
HTTP order submissions, one instrument and venue session, two participants,
one zero-trade batch, one one-trade batch. Observation: committed Redpanda
venue-event metadata and nested trades, PostgreSQL boundary intake rows and
Calcify receipt primary key. The smoke does not start the legacy materializer
or read projections; those remain covered by their separate path tests.

## Bounded full-path load, CAL-P1-L2

`make dev-stress-calcify-basic PAIRS=1000` repeats functional preflight, then
submits 1,000 crossing buy/sell pairs in waves of 10 pairs. Each wave waits for
durable HTTP acceptance of its buys before sending its sells. All orders share
one venue session and instrument, exercising one hot matching lane. The check
counts commands inside committed venue-event batches, trades inside outcomes,
and PostgreSQL receipts. [Run evidence](../evidence/calcify-phase1-basic-load-2026-09-29.json)
retains both attempts and correction.

Generation 8, `calcify-1790711354166`, accepted 2,000 load orders. First
observer incorrectly expected one source batch per order and timed out asking
`rpk` for 2,002 records. Post-run checks found 2,002 intake rows, 2,002
commands in 10 committed batches, 1,001 trades including preflight, and 1,001
receipts. Matching grouped commands into batches; timeout was an observer
error, not pipeline count gap. Observer now consumes to broker's current end
offset and sums `commandCount`.

Generation 9, `calcify-1790711684052`, passed corrected harness: 2,000 load
orders accepted in 1,402 ms (1,426.53 accepted orders/s during burst); 2,002
intake rows and source commands including preflight, 1,001 source trades,
1,001 committed commitment links, 1,001 verified links, and 1,001 receipts.
Halfway intake sample observed 40 receipts. All receipts existed 8,196 ms after
last acceptance, 9,598 ms after first load request. This measures whole-cohort
drain, not individual trade latency. No request latency distribution or precise
in-load stage-lag slope was captured.

Environment: macOS Docker 29.7.2, 10 CPUs/16.75 GB allocated, Redpanda v26.2.3,
PostgreSQL 16; `compose.base.yml`, `compose.local.yml`, `compose.calcify.yml`;
four command/source/link partitions, one extractor/verifier/receipt worker each,
PostgreSQL-backed ingress, matching direct stream enabled, legacy
materializer/projectors disabled. Source base `61d293ee`; load harness changed
in this branch. Both runs reused local volumes but used fresh topic identities.
These are short burst diagnostics, not sustained throughput or settlement
qualification. C5's hosted 10k/s venue-core samples used 64 instruments,
16 partitions, four materializers, and 300 seconds with no Calcify; C2's hosted
5k/s full-projection failure used a different downstream path. Neither is a
matched performance comparison. Next ladder steps: controlled rate and duration,
multiple lanes, in-load stage lag/storage, and restart/fault tests at load.

## Verifier backlog correction, CAL-P1-L3

Two retained L2 runs placed last verified output 6,799 and 7,001 ms after
last commitment output. Extractor followed last matching source batch by
325 and 50 ms, respectively. Code review found verifier opening and committing
one Kafka transaction per link. Receipt worker also commits each link to
PostgreSQL and checkpoints Kafka synchronously, but recorded receipt time
followed last verified output by about 511 ms on passing L2 run. Verifier was
measured slow stage.

Verifier now groups up to 100 polled links in one transaction, publishing all
passing links and checkpointing each partition's valid prefix together. A
malformed link pauses only its partition; valid prefix and other partitions
still commit. `CalcifyVerifierBatchTest` covers ordered 100-link batch and
malformed-link isolation. Full Kotlin tests passed.

Same local 1,000-pair hot-lane workload passed twice after change. Generation
10 (`calcify-1790712926245`) accepted 2,000 orders in 1,442 ms and drained
receipts 960 ms after last acceptance; last commitment-to-verified endpoint
difference was 31 ms. Generation 11 (`calcify-1790713215934`) accepted in
1,300 ms and drained in 942 ms; last commitment-to-verified difference was
54 ms. Each had exactly 2,002 intake/source commands, 1,001 source trades,
1,001 committed links, 1,001 verified links, and 1,001 receipts. Reusable
load harness now reports stage-end timestamps and link counts. [Exact runs](../evidence/calcify-phase1-verifier-batch-2026-09-29.json).

This is a diagnostic before/after observation. Source lane partition differed
between L2 and L3, local volumes were reused, and bursts were short. Stage-end
differences are not individual trade latency percentiles. Sustained and
multi-lane load, fault/restart under load, and independent identity-level
reconciliation remain open.

## Paired burst and five-minute run, CAL-P1-L4

Phase 1 now has separate repeatable local commands:
`make dev-stress-calcify-basic PAIRS=1000` and
`make dev-soak-calcify-phase1 DURATION_SECONDS=300 PAIRS_PER_SECOND=100`.
Optional `OUT=/absolute/path.json` saves exact counts and stage timestamps;
the paced run also saves five-second accepted-to-receipt gap samples. The
five-minute gate was fixed before the run: at least 95% requested intake rate,
gap p95 at most two seconds of requested trade rate, gap peak at most five
seconds, final drain at most five seconds, and exact stage counts. Gap is a
count of accepted crossing pairs without receipts at sampling time; it includes
matching and post-match work and is not per-trade latency.

Generation 12, `calcify-1790714410280`, repeated burst after harness changes:
2,000 load orders accepted in 1,542 ms; 2,002 source commands, 1,001 trades,
1,001 commitment links, 1,001 verified links, and 1,001 receipts; final drain
1,197 ms. [Raw burst report](../evidence/calcify-phase1-burst-repeat-2026-09-29.json).

Generation 13, `calcify-1790714556525`, ran five minutes at 100 crossing
pairs/s (200 orders/s requested). Actual 60,000 load orders accepted in
299,911 ms, 200.06 accepted orders/s; 60,002 intake/source commands and
30,001 source trades including preflight. Commitment links, verified links,
and receipts each counted 30,001. Across 59 five-second samples, accepted-to-
receipt gap p95 and peak were both 70 trades, with no rising trend. At last
acceptance, 29,931 receipts existed; all 30,001 existed 1,923 ms later. Last
source-to-commitment, commitment-to-verified, and verified-to-receipt endpoint
differences were 511, 509, and 513 ms. [Raw sustained report with all samples](../evidence/calcify-phase1-5m-2026-09-29.json).

One mid-run resource snapshot showed API 9.82%, matching 3.36%, extractor
1.72%, verifier 1.00%, receipt 2.09%, PostgreSQL 2.45%, boundary PostgreSQL
5.64%, and Redpanda 14.53% CPU, with memory under 1 GiB per listed service;
these are not maxima. Overall receipt table size was 2,260,992 bytes at
16,881 generation-13 rows and 3,571,712 bytes at 30,001 rows, including
earlier generations and indexes. No error, exception, or poisoned-partition
line appeared in matching/API/Calcify logs during the run.

Both runs used same local four-partition topology, one hot instrument lane,
PostgreSQL ingress, matching direct stream, and Phase 1 sidecars, with reused
volumes and fresh topics. Source lane partition differed between burst and
paced run. Runtime source base was `39f02b9ab7faab93e18083d4912fa9edba45a8e9`;
the load observer was uncommitted during measurement, with SHA-256
`cd234be4f083cf85b9e6291889567f41ca541cbfb20ed37425fa990f3ce3085c`.
Runtime image ID was `sha256:080af77b58cff7ecd55031e26707d0b3d11a76be6394cf166b7dd18d97180017`;
matching image ID was `sha256:d73740de8bf39dad8cea3a1db3db3708f0e83cfe8fe8e0a8862459afe46d30c8`.
Docker 29.7.2 allocated 10 CPUs and 16,745,824,256 bytes. This passes local
Phase 1 diagnostic gate at 100 pairs/s; it does
not qualify higher rates, multiple lanes, aged state, faults/restarts under
load, independent identity-level reconciliation, financial settlement, or
hosted capacity. Next phase needs its own workload/rate and same sustained
plus burst evidence before promotion.

## Three-times-rate five-minute run, CAL-P1-L5

To test whether L4's accepted-to-receipt tail grows under more sustained
traffic, repeated its full-path 300-second workload at 300 crossing pairs/s
(600 orders/s requested) with `make dev-soak-calcify-phase1
DURATION_SECONDS=300 PAIRS_PER_SECOND=300`. Same branch/runtime code
`687f04d1b45d12a420daf85a9f758a24cd5269d7`, four broker partitions,
one hot instrument lane, one worker per Calcify stage, PostgreSQL HTTP
intake, Go matching, local Docker host, and reused volumes; fresh run topics,
generation 14, source partition 3. L4's requested rate was 100 pairs/s and
its source partition differed. Gate and five-second sampling method were
unchanged, including 95% requested intake rate, p95 gap at most two seconds
of requested trade rate, peak gap at most five seconds, exact final counts,
and final drain at most five seconds. No endpoint timestamp is a matched
per-trade latency observation.

Run `calcify-1790715820375` accepted 180,000 load orders in 299,975 ms
(600.05 orders/s). All 180,002 intake/source commands and 90,001 source
trades, commitment links, verified links, and receipts reconciled, including
preflight. Fifty-nine in-load gap samples ranged from 110 to 200 trades;
p95 and peak were 200. First six samples averaged 175.2, last six 163.5;
first half averaged 171.1, last half 171.7. At last acceptance, 89,811
receipts existed and final 90,001 arrived within 1,943 ms. Last-record
source-to-commitment, commitment-to-verified, and verified-to-receipt
endpoint differences were 514, 507, and 546 ms. No error, exception, or
poison line appeared in matching or Calcify service logs; API keyword match
was only JVM `ExitOnOutOfMemoryError` option. [Raw report with all samples](../evidence/calcify-phase1-5m-300pps-2026-09-29.json).

At this rate and duration, tail is bounded and does not grow with elapsed
load or accumulated receipts. This is local single-lane evidence, not a
per-trade latency distribution, multi-lane or hosted capacity result, or a
guarantee for higher rates, longer runs, restarts, or future post-match
phases. Keep same sustained-plus-burst gate as each phase adds work.

## High-rate calibration and five-minute run, CAL-P1-L6/L7/L8/L9

Goal: find Phase 1 full-path limit while keeping source, commitment,
verification, and receipt accounting exact. Baseline L5 was 600 accepted
orders/s for five minutes on one hot lane. Historical hosted C5 venue-core
10k/s used 64 instruments, 16 partitions, and four materializers with
projections off; C2 full-projection 5k/s failed. Neither is directly
comparable to one-lane Calcify. Local host remained Docker 29.7.2 with 10 CPUs
and 16,745,824,256 bytes, reused volumes, fresh topics per JS setup.

L6a (10k/s offered, 250-pair Bun waves) and L6b (5k/s offered, 100-pair Bun
waves) both ended early on `FailedToOpenSocket`. L6b also exposed matching
partition retry at command offset 23,902: a 500-command `VenueEventBatch`
serialized to 1,049,080 bytes, exceeding Kafka producer's 1,048,576-byte
limit. Durable intake held 44,065 commands but source reached 23,902, leaving
20,163 commands. Changing only local matching batch size to 200 removed that
specific oversized-message failure in L6c; matching acknowledged all 44,074
accepted commands, but receipts were only 4,878 at the client failure and
last verified-to-receipt endpoint gap was 8,704 ms after drain. Receipt worker
had one PostgreSQL transaction and one Kafka offset commit per link.

Receipt worker now validates each poll's partition-prefix records, inserts
them in a JDBC batch with one transaction per partition, and checkpoints the
last valid offset after DB commit. Replays remain idempotent; a conflicting
policy version rolls back batch and falls back to per-record processing to
stop exactly at conflicting offset. Malformed wire records stop their own
partition without skipping earlier valid records. Focused PostgreSQL replay,
conflict rollback, and poison-prefix tests pass. L6d with this change
reconciled 300,000 accepted load orders and 150,001 source trades, commitment
links, verified links, and receipts, with gap p95/peak 300 trades and 1,217 ms
final drain. Bun needed 52,368 socket retries and took 100.770 seconds for a
30-second 10k/s target, so its measured intake rate was only 2,977.08/s.

Pooled Go crossing-pair load with same HTTP/idempotency path replaced Bun for
high-rate diagnostics. At 10k/s offered over 30 seconds on aged generation
18, 512 workers delivered 7,691.22 orders/s and 1024 workers 7,738.65/s;
neither met 10k. Both had zero request failures/retries and final receipts
reconciled. [All attempted runs and raw-report links](../evidence/calcify-phase1-high-rate-attempts-2026-09-29.json).

L7 set a 7,500 orders/s offered target for 300 seconds on fresh generation
19, one hot partition, 200-command matching batches, and batched receipt
worker. Predeclared local gate remained >=95% target intake, sampled
accepted-pair-to-receipt gap p95 <= two seconds and peak <= five seconds of
requested trade rate, exact final stage counts, and <=5-second final drain.
Go loader scheduled 1,125,000 pairs, dropped 35,121 when its bounded queue
filled, and accepted 2,179,758 orders in 300.299 seconds: 7,258.61/s,
96.78% of offered target. No request failure or retry occurred. Exact ingress
and source count was 2,179,760 including preflight; matching acknowledged
same count with zero NAKs/failures. Source trade, commitment link, verified
link, and PostgreSQL receipt counts were each 1,089,880. Sixty five-second
*lower-bound* gap samples had p95 1,025 and peak 1,670 trades, first six mean 824.5 and
last six mean 460.5; final drain 1,133 ms. Last-record source-to-commitment,
commitment-to-verified, and verified-to-receipt endpoint differences were
517, 335, and 19 ms. These are endpoint differences, not per-trade latency.
[Raw load report](../evidence/calcify-phase1-go-7k5-5m.json) and
[exact four-partition reconciliation](../evidence/calcify-phase1-go-7k5-5m-reconciliation.json).

L7 passed intake, exact accounting, and drain gates. Its gap gate was not
proven because the sampler read accepted count before querying receipts; the
query interval could add accepted pairs not reflected in that sample. The
historical raw report is retained. L8 corrected this by measuring accepted
pairs before and after every receipt query, then gating the conservative
upper-bound gap. On fresh generation 20 with 7,500 orders/s offered for 300
seconds, 1,930,226 orders were accepted in 300.624 seconds (6,420.72/s),
and the bounded client queue dropped 159,887 offered pairs. The 95% intake
and 5% drop gates failed; there were no request failures or retries. Exact
source trades, commitment links, verified links, and receipts each counted
965,114, with matching acking all 1,930,228 commands including preflight and
zero NAKs/failures. Conservative upper gap p95/peak was 866/1,272 trades;
final drain was 1,183 ms. Reused volumes had aged further, so the L7/L8
rate difference is not isolated to the sampler change. [L8 raw report](../evidence/calcify-phase1-go-7k5-upper-5m.json)
and [exact reconciliation](../evidence/calcify-phase1-go-7k5-upper-5m-reconciliation.json).

L9 lowered offered rate to 5,000 orders/s for 300 seconds on fresh
generation 21 with the same runtime code and corrected sampler. It accepted
1,499,902 load orders in 300.098 seconds (4,998.03/s); only 49 of 750,000
offered pairs dropped from the bounded queue, with no request failures or
retries. Intake/source commands each counted 1,499,904 including preflight;
matching acked all with zero NAKs/failures. Source trades, commitment links,
verified links, and receipts each counted 749,952. Sixty conservative
upper-gap samples had p95 628 and peak 846 trades; first/last six means were
436.17/477.67 trades. Final drain was 1,478 ms. The frozen local gate
passed at this rate. [L9 raw report](../evidence/calcify-phase1-go-5k-upper-5m.json)
and [exact reconciliation](../evidence/calcify-phase1-go-5k-upper-5m-reconciliation.json).

These runs do not establish 10k/s local capacity, hosted/multi-lane capacity,
fault/restart behavior under load,
identity-level independent reconciliation, or settlement readiness. Each
material post-match phase still needs a short higher-rate probe plus its own
five-minute sustained gate. `make dev-smoke-calcify-high-rate`,
`make dev-soak-calcify-high-rate-load`, and
`make dev-verify-calcify-high-rate` keep setup, paced load, and exact stage
reconciliation repeatable. Matching's 1 MiB batch ceiling still needs a
payload-aware limit before arbitrary large command payloads are allowed;
the 200-command local setting is this fixture's measured safe value.

## Run and limits

Start with `compose.base.yml`, `compose.local.yml`, and `compose.calcify.yml`, profiles `redpanda,calcify-phase1`; apply migrations before enabling sidecars. Stage environment has source and output topic names, source generation, and `CALCIFY_AUTO_OFFSET_RESET` (default `earliest`). Output topics are created with source partition count and one replica in this local Phase 1 path; deployment topology and retention need separate review. Source batches lacking `sha256-reef-canonical-v1` stop their partition rather than silently pass.

No archive exists. Before non-diagnostic no-archive run, enforce maximum run duration and post-close replay window; configure source and link topic retention to cover oldest source fact plus processing lag and margin. Define generation advancement on topic recreation, close/window semantics, and source-to-link reconciliation. This is focused follow-up, not claim that temporary receipt path is settlement-ready.
