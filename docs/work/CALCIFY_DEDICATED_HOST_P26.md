# Finance lane on dedicated hosts: the merged lane code and the combined decision (CAL-FIN-P26)

Status: measurement note, 2026-10-15. Two DigitalOcean droplets, the same code and cases on each, owner-approved, both destroyed on exit and verified through the API (only the owner's two long-lived droplets remain, no volumes). Cost about $0.65 in total (EPYC about 13 minutes at $0.36/hr, Xeon about 27 minutes at $1.00/hr, plus bootstrap). Raw output: `docs/work/evidence/CAL-FIN-P26-epyc-capmode.txt` and `CAL-FIN-P26-xeon-capmode.txt`.

## What was run

`droplet-chain-run.sh capmode`: `CapacityLaneThroughputTest`, resolved-context source, 64 instruments, 200 accounts, broker and Postgres data on tmpfs, `-XX:+UseParallelGC`, 600,000 trades per hot-lane case (twice per mode), and for the Xeon also 1,200,000 trades over four uniform lanes (once per mode). The code is the flat state trie and the removed re-encoding (PR 570) plus the opt-in `capture-settle-v1` decision mode (one decision per booked trade, branch `claude/combined-decision`); `two-step` is today's behaviour. Every run's chain digest was verified. Short runs (36 to 150 seconds each), finance lane and projector only: the engine, the front door and the resolver are not in the loop.

| Host | CPU | SHA-256 of 64 bytes | One thread (microbenchmark) | Root disk sync write |
| --- | --- | --- | --- | --- |
| EPYC: `g5-8vcpu-32gb-50gb`, atl1, $0.36/hr | AMD EPYC 9555P | 81 ns | 8.85 M ops/s | 3.8 ms |
| Xeon: `c-32`, sfo2, $1.00/hr | Intel Xeon Platinum 8168 at 2.7 GHz | 454 ns | 3.36 M ops/s | 1.9 ms (CAL-FIN-P23) |

## Results (booked trades per second; ranges are the two runs)

| Case | EPYC processed | EPYC projected | Xeon processed | Xeon projected |
| --- | --- | --- | --- | --- |
| One hot lane, `two-step` | 13.4k to 13.5k | **9.9k to 10.0k** | 4.65k to 4.67k | 4.08k to 4.13k |
| One hot lane, `capture-settle-v1` | 19.1k to 19.3k | **16.5k** | 6.78k to 6.97k | 6.74k to 6.94k |
| Four lanes, `two-step` | not run | not run | 15.4k | 12.6k |
| Four lanes, `capture-settle-v1` | not run | not run | 22.6k | **20.1k** |

Against the earlier Xeon run of the code before these changes (CAL-FIN-P23): one hot lane 7.1k decisions/s, that is about 3.5k trades/s, processed and projected; four lanes 22.0k decisions/s projected, that is 11.0k trades/s. On the same host the changes give, for the hot lane, 3.5k to 4.1k trades/s projected from the lane work alone (+16 percent projected, +31 percent processed) and 6.7k to 6.9k with the combined decision (about 1.9 times); for four lanes, 11.0k to 12.6k, then 20.1k (about 1.8 times).

## What it shows

1. **The host was the hot-lane problem.** The EPYC lane is about 2.4 times the Xeon's on every projected row and 2.8 to 2.9 times on processed, in line with its single-thread score (2.6 times) and with hardware SHA (81 ns against 454 ns for the 67-byte hash that the state trie does about 80 times per decision). One hot instrument at 10k trades/s is reached on the EPYC with the lane changes alone (projected 9.9k to 10.0k) and exceeded by 65 percent with the combined decision (16.5k); on the Xeon a single lane stops at 6.9k even with both.
2. **The combined decision pays most where the projector is the limit.** In `two-step` the projector trails the lane on both hosts (EPYC processed 13.4k, projected 10.0k; Xeon 4.65k, 4.1k) because it writes two decision rows per trade; with one decision per trade the projected rate rises by more (EPYC 10.0k to 16.5k, 1.65 times) than the processed rate does (13.4k to 19.2k, 1.43 times).
3. **The lane budget law moves.** With a lane ceiling `C` (trades/s one lane sustains) the busiest lane may carry at most `C / total` of the trades. At 10k trades/s: Xeon `two-step` C = 4.1k so 41 percent; Xeon `capture-settle-v1` C = 6.9k so 69 percent; EPYC `two-step` C = 10.0k so about 100 percent; EPYC `capture-settle-v1` C = 16.5k so more than the whole flow. On the Xeon, uniform flow over four lanes (the busiest took 30 percent of the trades) reached 12.6k and 20.1k trades/s.
4. **Where the earlier laptop numbers stand.** The laptop (M1 Max) lane processed 18.2k trades/s in `two-step` and about 30k in the no-broker `capture-settle-v1` harness; the EPYC processed 13.4k and 19.2k with a real broker and Postgres. Laptop figures were valid as ratios on one machine; the EPYC is the first target-class hardware whose absolute numbers can be quoted.

## Limits

Short runs on tmpfs, so nothing here says a lane holds a rate for 15 or 30 minutes (that is what the paced soak harness is for), or how Postgres behaves on a real disk. Finance lane and projector only; the engine lane drained 13.4k to 14.7k pairs/s on the Xeon (CAL-FIN-P23) and has not been measured on the EPYC. One run of each case per host (two for the hot lane), so the ranges are narrow but the sample is small. The `capture-settle-v1` mode is opt-in and its PR was not yet merged when these runs were made; sequence numbers count trades in that mode, so compare modes in trades per second only.
