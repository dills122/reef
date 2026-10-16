# Engine lane: what one lane costs, and what batch size does to it

Status: measurement note, 2026-10-15. Apple M1 Max, 10 cores, nothing else running, Redpanda v26.2.3 in a local container, Go 1.x toolchain of the repository. Not a capacity claim and not an end-to-end number: no front door, no downstream stages, one instrument, crossing pairs (a BUY then a SELL at the same price from different participants), 50 accounts.

## Method

- `BenchmarkLaneProcessOnce` (`lane_benchmark_test.go`): the processor with a pre-built in-memory source and a publisher that marshals the batch, no broker. One op is 10,000 pairs in 500-command batches.
- `TestKafkaLaneDrainRate` (`kafka_lane_drain_test.go`; skipped unless `REEF_KAFKA_INTEGRATION_BOOTSTRAP` and `KAFKA_DRAIN=1`): pre-fills a command topic and drains it through the real `KafkaCommandSource`, the real transactional publisher and the real processor, once per size in `KAFKA_DRAIN_BATCHES`. Fetch and publish are timed by wrappers; the rest of the wall time is build (decode, match, checksum) and framework. The producer limit and the event topic's `max.message.bytes` are raised to 32 MiB so batch bytes do not interfere.

## Results

CPU only (no broker), per crossing pair: about 27 us, 36.8k pairs/s on one core. Share of the runner's CPU: command JSON decode 14 percent, matching 9, batch checksum (typed) 15, batch JSON for the record 7. (A profile of the first fixture, which used one participant on both sides, showed the self-trade-prevention walk dominating because the taker was rejected; the fixture now uses distinct participants.)

Drain from a backlog through the real transactional lane, three interleaved sweeps, 100,000 pairs (200,000 commands), lz4 (the engine default). The three sweeps agree within 2 percent:

| Batch (commands) | Pairs/s | ms per batch | Publish (transaction) per batch | Share of time in publish |
| --- | --- | --- | --- | --- |
| 500 | 19.5k | 12.8 | 6.9 ms | 54 percent |
| 1,000 | 22.4k | 22.3 | 10.5 ms | 47 percent |
| 2,000 | 26.3k | 38.0 | 14.2 ms | 37 percent |
| 4,000 | 28.8k | 69.0 | 21.5 ms | 31 percent |

Fetch is about 4 percent at every size and build is a constant 10.8 us per command (2.16 s per 200,000 commands). Publish is about 5 ms of fixed cost per batch (transaction round trips) plus about 4 us per command (record size, lz4). Raising the batch from 500 to 2,000 commands adds about 35 percent to the lane's capacity and 4,000 adds about 48 percent. Compression off adds about 9 percent at batch 500 (one run of each).

Consumer settings (channel buffer 2,048, fetch 8 MiB) changed nothing and were not kept.

A larger batch is a larger Kafka record: about 2.2 KB per command in the event batch, so 2,000 commands is about 4.4 MB and 4,000 is about 8.8 MB, over the 4 MiB the high-rate profile sets. With the byte cap in PR 565 an oversize batch is halved instead of wedging the lane, but the producer limit and the event topic's `max.message.bytes` should be raised with the batch size, as in this test.

## What this does and does not say

On a quiet machine and a pre-filled backlog the lane drains 19.5k pairs/s at batch 500, against the 8k to 9k sustained edge measured through the whole stack (CAL-FIN-P21, shared laptop, front door and every downstream stage competing for the same ten cores). The gap is contention and the arrival pattern, not the lane's own cost. Whether a larger batch moves the end-to-end edge, and what it does to latency, the extractor and the resolver, is not measured here and needs the full stack on a quiet host. Batch size is a single environment variable (`MATCHING_ENGINE_DIRECT_STREAM_BATCH_SIZE`; the repository profiles use 500, the finite profile requires 1), so the test is cheap to run once a quiet host is available.

Pipelining the transaction with the next batch's build would overlap the 54 percent publish share at batch 500 with the build, in principle up to 1.8 times, but it changes the rollback story (the next batch would be built on state that is not yet durable). That is a design change to take to the owner, not a tuning step.

## On a dedicated host (CAL-FIN-P23, 2026-10-15)

DigitalOcean `c-32` (32 dedicated vCPU, 62 GB, **Intel Xeon Platinum 8168 at 2.7 GHz**), Redpanda in a container with a tmpfs data directory (4 cores, 8 GB), the same tests, run by `droplet-chain-run.sh lane`. Raw output: `docs/work/evidence/CAL-FIN-P23-c32-lane-matrix.txt`.

CPU only, no broker: **17.9k pairs/s** (laptop: 36.8k). Drain through the real transactional lane, three interleaved sweeps (200,000 pairs each, lz4), pairs/s:

| Batch (commands) | Sweep 1 | Sweep 2 | Sweep 3 | Laptop (this note, above) |
| --- | --- | --- | --- | --- |
| 500 | 13.4k | 13.7k | 13.6k | 19.5k |
| 1,000 | 14.3k | 14.5k | 14.3k | 22.4k |
| 2,000 | 14.5k | 14.5k | 14.7k | 26.3k |
| 4,000 | 13.5k | 13.4k | 13.4k | 28.8k |

Compression off: 12.5k at batch 500 and 13.3k at 2,000 (one run each; the laptop gained 9 percent from it, this host lost 6).

**This corrects the laptop reading above.** On this host the time per command is dominated by build (decode, match, checksum, batch JSON): 10.0 s of a 14.9 s drain at batch 500 (68 percent; about 25 us per command, 2.3 times the laptop's 10.8), against publish 4.3 s (29 percent) and fetch 0.5 s. Batch size therefore buys at most 8 percent here (2,000 against 500), not the 35 to 48 percent the laptop suggested, and 4,000 is slower than 2,000. The laptop's fast single thread had made build cheap and publish look large. Changing the default batch size is not justified by this evidence; the engine lane's capacity on this host, 13.4k to 14.7k pairs/s, is above 10k pairs/s at the default batch of 500.
