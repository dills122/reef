# Calcify paced soak harness

Status: tool note, 2026-10-15. This is the harness, not a result. The figures quoted here are from two 90 second runs on a shared laptop and are there to show what the output looks like and to size a droplet; they are not a capacity claim.

## Why

Every throughput number so far (`CapacityLaneThroughputTest`, CAL-FIN-P5 to P25) pre-generates N trades and pushes them as fast as the producer can for 30 to 40 seconds. The arena's top end is a 30 minute session (15 minutes for runs whose records are kept) at a target of 10,000 booked trades/s. Nothing measured shows a lane **holding** a rate for minutes: backlog and lag growth, GC, JVM heap growth, store, broker and Postgres data growth, disk throughput, stability. `CapacitySoakTest` measures exactly that.

It runs the same chain as the throughput test (real decoders, the capacity Streams app with a thread per lane, per-lane decision logs and Postgres projections, Redpanda and Postgres 16 in containers) but the load is a lazy, seeded generator feeding one **paced** sender, and a sampler writes a time series every 10 seconds.

## Run it locally

Docker must be running. From `services/platform-runtime`:

```
CAP_SOAK_SECONDS=90 CAP_SOAK_RATE=3000 CAP_LANES=1 CAP_INSTRUMENTS=16 CAP_ACCOUNTS=100 \
  ./gradlew cleanTest test -PbrokerTests -PresearchTests --tests '*CapacitySoakTest*' -i --no-daemon
```

Add `CAP_DECISION_MODE=capture-settle-v1` for the one-decision-per-trade mode. Output lines start with `CAPACITY-SOAK-`; `CAPACITY-SOAK-CSV` names the CSV (default `build/capacity-soak/soak-<label>-<timestamp>.csv`, or `CAP_SOAK_OUT_DIR`). The test removes its containers, volumes and Streams state directory on every exit path. The research test heap is 12 GB; raise it with `-PtestHeap=24g` (see "Memory" below).

The pure parts (pacing schedule, sustained-lag rule, verdict, accounting, funding size, generator determinism, CSV shape) are covered by `CapacitySoakSupportTest`, which needs no Docker and runs in the default test run.

## Run it on a droplet

`scripts/dev/calcify-financial/droplet-chain-run.sh` has two matrices (same rules as the other matrices: tracked files only, droplet destroyed on every exit path, `DIGITALOCEAN_TOKEN` and the harness SSH key needed; **renting a droplet needs the owner's approval**).

```
# proves the harness, the CSV fetch and the cleanup (about 10 minutes after bootstrap)
REEF_DO_SIZE=c-16 scripts/dev/calcify-financial/droplet-chain-run.sh soaksmoke

# 15 minute cases only (tmpfs/tmpfs, then Postgres on the root disk), 10,000 trades/s
REEF_SOAK_CASES="tmpfs15 disk15" REEF_DO_SIZE=<64 GB class> scripts/dev/calcify-financial/droplet-chain-run.sh soak

# 30 minute cases only
REEF_SOAK_CASES="tmpfs30 disk30" REEF_DO_SIZE=<128 GB class> scripts/dev/calcify-financial/droplet-chain-run.sh soak

# everything: tmpfs15 disk15 tmpfs30 disk30 (the 30 minute cases are skipped, with the numbers, on a host too small to hold them)
REEF_DO_SIZE=<size> scripts/dev/calcify-financial/droplet-chain-run.sh soak
```

Cases:

| Case | Label | Broker | Postgres | `CAP_PG_SYNC_OFF` |
|---|---|---|---|---|
| `tmpfs15` | `soak-tmpfs-15m` | tmpfs | tmpfs | false |
| `disk15` | `soak-pgdisk-15m` | tmpfs | root disk (`REEF_SOAK_PG_STORE=volume`: the attached volume) | true |
| `tmpfs30` | `soak-tmpfs-30m` | tmpfs | tmpfs | false |
| `disk30` | `soak-pgdisk-30m` | tmpfs | root disk | true |

All use capture-settle-v1, the resolved-context source, one lane, 64 instruments, 200 accounts, ParallelGC. The CSV and the full gradle log of each case are fetched into `<out dir>/soak/<label>/` right after the case; the `CAPACITY-SOAK-*` lines also go to `<out dir>/matrix.txt`. The root disk case answers the owner's question whether Postgres can live on a normal disk with `synchronous_commit=off` while the broker is on tmpfs: read `lag_booked_projected_s`, `inst_projected_per_s`, `pg_growth_kib_s` and `host_disk_write_mib_s` of that case against the tmpfs one. A volume attached with `REEF_DO_VOLUME_GB` is the network-disk variant.

Script knobs: `REEF_SOAK_RATE` (10000), `REEF_SOAK_SHORT_SECONDS` (900), `REEF_SOAK_LONG_SECONDS` (1800), `REEF_SOAK_SMOKE_SECONDS` (60), `REEF_SOAK_SMOKE_RATE` (3000), `REEF_SOAK_CASES`, `REEF_SOAK_RP_TMPFS_GB`, `REEF_SOAK_PG_TMPFS_GB`, `REEF_SOAK_HEAP_GB`, `REEF_SOAK_LANES` (1), `REEF_SOAK_HOT_SHARE` (0), `REEF_SOAK_ABORT_ON_LAG` (true), `REEF_SOAK_PG_STORE` (root|volume), `REEF_SOAK_EXTRA_ENV` (extra `NAME=value` words for the test), `REEF_SOAK_FORCE=1` (run a case although the memory check says it does not fit), `REEF_DROPLET_DEADLINE_SECONDS` (14400 for `soak`).

### Memory (what the defaults assume)

tmpfs sizes and the heap are limits, not allocations, but a tmpfs counts against RAM as it fills. From the two laptop runs below: broker about 2.2 KB per trade, Postgres about 1.5 KB per trade plus about 1 GB of WAL, JVM heap used about 1.2 KB per trade (the capacity ledger keeps every execution, obligation, attempt and action of the run in memory; G1 on the laptop, used heap includes garbage, so this is an upper estimate of the live set). 10,000/s for 15 minutes is 9 million trades; for 30 minutes 18 million.

| Case | Broker tmpfs | Postgres tmpfs | Heap | Plus | Host RAM needed |
|---|---|---|---|---|---|
| 15 min, both tmpfs | 20 GB | 16 GB | 22 GB | 4 GB | 62 GB |
| 15 min, Postgres on disk | 20 GB | - | 22 GB | 4 GB | 46 GB |
| 30 min, both tmpfs | 40 GB | 30 GB | 40 GB | 4 GB | 114 GB |
| 30 min, Postgres on disk | 40 GB | - | 40 GB | 4 GB | 84 GB |

These are planning numbers from a 450,000 trade run, not measurements of a 9 million trade run. If a store fills, the broker or Postgres fails and the test fails; if the heap fills the run dies with an OutOfMemoryError (the matrix output greps for it). The size and the heap of a first real run are an input to the next one.

## Environment

| Variable | Default | Meaning |
|---|---|---|
| `CAP_SOAK_SECONDS` | 120 | Producing time. Planned trades = seconds x rate (the plan budgets, sequence bound and funding are sized from it). |
| `CAP_SOAK_RATE` | 10000 | Target trades per second, held on a fixed schedule. |
| `CAP_LANES` | 1 | Lanes (one Streams thread, one partition, one projection each). |
| `CAP_INSTRUMENTS` | 64 | Instruments `INS0..`; assigned to lanes by the platform's stream-command routing (resolved source) or round-robin (lifecycle). |
| `CAP_ACCOUNTS` | 200 | Accounts `u0..`, funded once before the clock starts. |
| `CAP_HOT_SHARE` | 0 | Fraction of trades on `INS0`; the rest uniform. |
| `CAP_SOURCE` | `resolved` | `resolved` (MatchContextResolvedV1 per trade) or `lifecycle` (an edited genuine capture envelope per trade; costs more generator CPU). |
| `CAP_DECISION_MODE` | `two-step` | `two-step` or `capture-settle-v1`. Rates, lag and the integrity check are in **trades**, mode-aware. |
| `CAP_MAX_APPLY` | 2000 | Decisions per projector transaction. |
| `CAP_PG_SYNC_OFF` | false | `ALTER DATABASE reef SET synchronous_commit = off` (the effective value is printed: `CAPACITY-SOAK-POSTGRES`). |
| `CAP_SOAK_SAMPLE_SECONDS` | 10 | Sampler period. |
| `CAP_SOAK_MAX_LAG_SECONDS` | 30 | Lag threshold, in seconds of the target rate (lag trades / rate). |
| `CAP_SOAK_LAG_SUSTAIN_SECONDS` | 60 | How long a lag must stay over the threshold to fail the run. |
| `CAP_SOAK_ABORT_ON_LAG` | true | true: stop at the first breach (saves droplet time); false: run the soak to the end, then fail with the verdict (keeps the whole curve). |
| `CAP_SOAK_DRAIN_SECONDS` | 600 | Bound on the wait for the lanes to book and project everything after the producer stops. |
| `CAP_SOAK_STALL_SECONDS` | 120 | Drain fails if neither booking nor projection advances for this long. |
| `CAP_SOAK_COUNT_ROWS` | true | End-of-run `count(*)` of the decision table against the logs (a few seconds on tens of millions of rows). |
| `CAP_SOAK_OUT_DIR`, `CAP_SOAK_LABEL` | `build/capacity-soak`, `soak` | CSV directory and its file label. |
| `S4_RP_DOCKER`, `S4_PG_DOCKER` | empty | Extra `docker run` options for the broker and Postgres containers, e.g. `--tmpfs=/var/lib/redpanda/data:rw,size=20g,uid=101,gid=101,mode=0777` and `--tmpfs=/var/lib/postgresql/data:rw,size=16g,mode=1777`. One token per option. |
| `S4_SMP`, `S4_BROKER_MEM` | 1, 2G | Broker cores and memory. |

## How the load is made

* **Streaming generation.** `SoakTradeGenerator` produces one trade at a time from a seeded `kotlin.random.Random` (the same seed gives the same byte stream): execution id `exec-<i>`, instrument `INS0` with probability `CAP_HOT_SHARE` else uniform, distinct buyer and seller uniform over the accounts, quantity 1 to 4, price 1,000 to 1,049 nanos. A generator thread keeps a 131,072 entry queue full.
* **Funding.** Cash and shares are funded once, before the clock starts, through the CONTROL path, sized from the planned total so that no account can run out during the soak (`SoakFunding`: cash for every trade bought at the top quantity and price; shares six times the busiest seller's expected sells). The funding decisions are counted per lane (`accounts x (1 + instruments in lane)`) and subtracted when converting decision sequences to trades.
* **Pacing.** One sender thread against `System.nanoTime`: trade k is due at `start + k / rate`. There is no accumulated bucket; the sender sends what is overdue (at most 4,096 per pass) and otherwise parks for at most 1 ms, so batches are at most about 1 ms of the schedule. Producer: idempotent, `acks=all`, 5 ms linger, 128 KiB batches, as in the throughput test. Up to two seconds of trades are generated before the clock starts so start-up is not counted as lateness.
* **Who is behind.** `producer_behind` is trades scheduled but not yet handed to the producer (the sender's own lateness). `lag_sent_booked` is trades handed to the producer but not yet in a decision log; `lag_booked_projected` is trades decided but not yet in Postgres. If the sender cannot hold the schedule the run reports **PRODUCER BEHIND** and says the lane was never offered the target load.

## The CSV (`soak-<label>-<timestamp>.csv`)

One row per sample (every `CAP_SOAK_SAMPLE_SECONDS`, `phase=run` while the producer runs, `phase=drain` afterwards, plus a final row). Counts are cumulative trades since the first scheduled trade; rates per second.

| Column | Meaning |
|---|---|
| `phase`, `seconds` | `run` or `drain`; seconds since the first scheduled trade (the time the counters were read) |
| `sent`, `acked` | trades handed to the producer; trades the broker has acknowledged |
| `booked` | trades in the decision logs (read-committed head of each lane, minus funding, divided by decisions per trade) |
| `projected` | trades in Postgres (`calcify_finance_checkpoint.last_sequence` per lane, converted the same way) |
| `producer_behind` | trades overdue on the sender's schedule (0 in the drain phase) |
| `lag_sent_booked`, `lag_booked_projected` | `sent - booked`, `booked - projected` (never negative; the lane state is read before the counters so `projected <= booked <= sent` in every row) |
| `producer_behind_s`, `lag_sent_booked_s`, `lag_booked_projected_s` | the three lags divided by the target rate: the figures the fail rules use |
| `inst_*_per_s`, `cum_*_per_s` | rate of sent, booked, projected over the last interval / since the start |
| `heap_used_mib` | JVM heap in use (includes garbage between collections) |
| `gc_count_delta`, `gc_ms_delta` | collections and collection time in the interval, all collectors summed |
| `process_cpu_pct` | JVM process CPU, percent of one core |
| `streams_cpu_pct` | Kafka Streams stream threads (the lanes' decision threads) |
| `projection_cpu_pct` | the `capacity-projection-*` threads |
| `kafka_clients_cpu_pct` | Kafka producer and coordinator threads |
| `harness_cpu_pct` | `soak-generator`, `soak-sender`, `soak-sampler` |
| `rp_total_kib`, `rp_capture_kib`, `rp_decisions_kib`, `rp_changelog_kib` | `docker exec du -sk` of the broker data directory and of the capture, decision and Streams changelog topics (allocated blocks: Redpanda preallocates segments, so small values step in 32 MiB units) |
| `pg_dir_kib`, `pg_wal_kib`, `pg_database_bytes` | `du -sk` of the Postgres data directory and of `pg_wal`; `pg_database_size('reef')` |
| `state_dir_kib` | the Streams state directory on the host (RocksDB) |
| `rp_growth_kib_s`, `pg_growth_kib_s` | growth of the two data directories over the interval (disk throughput into each store) |
| `host_disk_write_mib_s` | bytes written to whole block devices (`/proc/diskstats`), Linux only; blank on macOS. On a droplet this is the root disk write rate, including the Docker layer Postgres uses when it is not on tmpfs. |
| `healthy` | 1 while the Streams app and the projection threads run and no send error occurred |

A blank field means that probe failed in that sample. The console prints one `CAPACITY-SOAK-SAMPLE` line per row, the run ends with `CAPACITY-SOAK-RESULT`, and the sizes after funding are printed as `CAPACITY-SOAK-BASELINE` (an idle broker is already about 2 GB of preallocated internal topics; the per-trade sizes in the result are net of this).

## How to read it

* Healthy: `producer_behind_s` and the two lag columns stay at fractions of a second (commit interval and sampling latency, about 0.1 s) and flat; `cum_booked_per_s` and `cum_projected_per_s` converge to the target rate.
* A lane that is too slow shows `lag_sent_booked_s` growing linearly; the slope is (rate - booked/s) / rate. A projection that is too slow shows `lag_booked_projected_s` growing while booking keeps pace; check `projection_cpu_pct`, `pg_growth_kib_s`, `host_disk_write_mib_s`, `pg_wal_kib`.
* GC: `gc_ms_delta` is the time collectors ran in the interval; a rising trend with a rising `heap_used_mib` is the ledger's in-memory state growing toward the heap limit. Watch for the step between "fast" and "stop-the-world" intervals just before an OutOfMemoryError.
* Stores: `rp_decisions_kib` and `pg_database_bytes` divided by `booked` give bytes per trade; `rp_changelog_kib` is the Streams state changelog (compacted, so it can shrink).
* Producer limit: `harness_cpu_pct` near 100 with `producer_behind_s` growing means the load generator is the limit; move it or lower the rate.

## Pass and fail rules

The test fails (exception `CAPACITY SOAK FAILED: ...`, plus `CAPACITY-SOAK-FAIL` and the last eight CSV rows as `CAPACITY-SOAK-EVIDENCE` lines and the CSV path) when:

1. **Producer behind.** `producer_behind_s` stays above `CAP_SOAK_MAX_LAG_SECONDS` for at least `CAP_SOAK_LAG_SUSTAIN_SECONDS` of consecutive samples. The message says the harness, not the lane, is the limit and gives sent versus due, sent minus acked (broker acknowledgements slowing the producer), generator-starved sends (generator slower than the target) and the harness CPU. A producer breach wins over a lane breach, because a lane that was never offered the load says nothing about its capacity.
2. **Lane behind, booking.** `lag_sent_booked_s` over the threshold for the sustain period.
3. **Lane behind, projection.** `lag_booked_projected_s` over the threshold for the sustain period.
4. A lane stops (Streams not RUNNING, projection thread failed), a send error, or a harness fault.
5. **Drain.** After the producer stops, the lanes must book and project every trade within `CAP_SOAK_DRAIN_SECONDS` and make progress at least every `CAP_SOAK_STALL_SECONDS`. The drain time is reported (`drain_s`, `booked_after_producer_s`, `projected_after_producer_s`).
6. **Integrity** after the drain: trades sent = planned; acked = sent; per lane, the consumed capture offsets (committed with the Streams transaction) = trades sent to that lane; the decision-log head sequence (read with the production `FinanceEvidenceReader.lastValue`) = funding decisions + decisions per trade x trades sent; the projection checkpoint sequence and chain = the head's sequence and checksum; total trades booked = projected = sent; the decision table holds exactly as many rows as the logs hold decisions; no lane stopped.

With `CAP_SOAK_ABORT_ON_LAG=true` a breach stops the run immediately; with false the soak runs to the end and the breach is raised after the integrity check. The rule is "over the threshold continuously, first over-threshold sample to the latest at least the sustain period apart": one sample under the threshold resets it.

## First local runs (shared laptop, 10 cores, G1, containers on the Docker VM disk)

Not capacity claims. 1 lane, 16 instruments, 100 accounts, resolved source, 90 s, integrity ok in both:

| Mode | Target | Producer | Booked / projected after the producer | Max lag | Drain | Heap used at end | Broker growth | Postgres |
|---|---|---|---|---|---|---|---|---|
| two-step | 3,000/s | 3,000/s | 270,000 / 270,000 | 0.10 s | 1.0 s | 686 MiB | 9.9 KB/trade (gross of the idle 2 GB) | 567 MiB database, 1.36 GB directory |
| capture-settle-v1 | 5,000/s | 5,000/s | 450,000 / 450,000 | 0.11 s | 1.4 s | 738 MiB | 2.25 KB/trade net | 662 MiB database (1.5 KB/trade), 1.6 GB directory including 0.9 GB WAL |

Sample rows (capture-settle-v1, first twelve columns: `phase,seconds,sent,acked,booked,projected,producer_behind,lag_sent_booked,lag_booked_projected,producer_behind_s,lag_sent_booked_s,lag_booked_projected_s`): `run,90.0,450000,450000,449800,449283,0,200,517,0.0,0.0,0.1` and, after the producer stopped, `drain,91.8,450000,450000,450000,450000,0,0,0,0.0,0.0,0.0`. The same runs, with an artificially impossible target, fail loudly: 200,000 trades/s for 120 s (this laptop's sender held it; the lane booked 16-17k/s) stopped at t=40 s with `LANE BEHIND, BOOKING: ... sent-booked=7335002 trades = 36.7 s of rate ... the Streams lane books 15797/s against 200002/s sent`, and 1,500,000 trades/s stopped at t=20 s with `PRODUCER BEHIND (the load generator, not the lane): ... 12.8 s of rate ... generator-starved sends=1405281 ... this run does NOT measure the lane's capacity`.

## Known limits

* **One process, one producer thread.** The generator, the sender, the sampler, the broker client, the Streams app and the projection threads share one JVM; on a small host the harness competes with the lane for cores (`harness_cpu_pct` shows how much). The broker and Postgres containers share the machine too. A single paced sender holds about 500,000 trades/s on the laptop; do not use it to claim more than the lane can show.
* **The matching engine and the front door are not in the loop.** The input is the lane's own capture topic, filled directly. The Go engine's lane cost, the order API and the `202 Accepted` path are measured elsewhere (CAL-FIN-P22/P23).
* **Resolved-context records are synthetic.** Hand-built `MatchContextResolvedV1` messages with accounts taken from the acceptance facts; trades are independent (no order book), uniform over accounts and instruments unless `CAP_HOT_SHARE` says otherwise. A real session's account and instrument skew, cancels, rejected orders and settlement failures are not represented. The lifecycle source is a single edited fixture envelope.
* **State is in memory.** The ledger holds the run's entities in heap maps for the whole run (see Memory). The soak is expected to show this as heap and GC growth; a run that ends in an OutOfMemoryError is a result, not a harness fault.
* **No restarts, no failure injection, no retention.** Topics have infinite retention; nothing is compacted away except the Streams changelog. Crash recovery of a long run (the restore cost of a multi-million entity store) is not exercised.
* **du granularity.** Sizes are allocated blocks from `du` inside the containers, so Redpanda segments step in 32 MiB units; per-trade sizes are net of the after-funding baseline and are only meaningful over millions of trades.
* **Time base.** Row times are when the counters were read; a sample that takes longer than the interval skips ticks rather than bursting. Booking and projection lag include about 0.1 s of commit-interval and read latency.
* **Prefill.** Up to two seconds of trades are generated before the clock starts.
* **One decision-log head read per lane per sample** uses a long-lived consumer (same algorithm as `FinanceEvidenceReader.lastValue`); the integrity check at the end uses the production method itself.
