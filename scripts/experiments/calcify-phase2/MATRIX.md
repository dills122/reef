# Calcify Phase 2 bounded architecture experiment matrix

Base: 79dab22bda4d89c5a75eb677094f6e999dae303d. Date: 2026-09-30 Toronto.
Scope: isolated prototypes, fixtures, research. Production implementation requires user sync.

| ID | Decision | Experiment | Pass / stop criterion |
| --- | --- | --- | --- |
| E1 | Source-local join prerequisite/key | Real Go processor fixtures, baseline cross-run match, scratch run-scoped patch, resting/partial/modify/rejected/high-fanout/replay | Baseline limitation reproduced; aligned facts lane-local; exact parity; identify run derivation and acceptance ordering |
| E2 | Managed verified-led vs two-input | Same full-fact resolver in Kafka Streams one-input with external sequential reader and two-input Processor; opposite schedules, verifier pause, repeated links | Identical output; no per-trade backward seek; measured pending state; choose managed shape only if fault boundaries fit |
| E3 | Recovery protocol | Dedicated RF1 broker; abrupt stops after state, forward, committed offset; fresh local disk restore; standby/promote/rebalance; stale producer fence | read_committed exact count/bytes, output-silent standby, unaffected lane continuity; no RF3 claim |
| E4 | Sizing/runtime headroom | Full actual Go fact bytes; RocksDB inserts/two gets/output serialization with aged state, hot/spread lanes; paced 10k trades/s 300s only if knee permits | Measure rows/V1/logical replication, lag and recovery; microbenchmarks never full-system qualification |
| E5 | Availability/integrity | Generation mismatch, deleted required prefix, rejected-shaped facts, conflict vs local state loss, active run state | Fail closed affected lane, retain active orders; recovery from changelog rather than assumed cheap history |

Baseline: CAL-P1-L9 local 5k commands/s, ~2.5k trades/s for 300s. L6/L8 higher offered intake failed; C5 hosted ~10k commands/s venue-core only. E4 excludes intake, matching and Phase1; requires ~20k new successful submissions/s to supply 10k one-trade pairs/s. Relevant original success/failure JSON and throughput/projection plans inspected. All executed attempts including setup failures retained in evidence.

No TTL, deletion policy, ledger, clearing, allocation, balances, settlement or legacy replacement. Stop when runtime choice and implementation gates have concrete evidence; record any unproved operational boundary.
