# Calcify finance lane: bounded memory, and what restart costs (design note)

Status: design discussion with measurements, 2026-10-15. Nothing here is implemented or approved. Owner direction: the target is 15 to 20k booked trades/s sustained for 15 to 30 minutes (27M to 36M trades), so the lane's memory must stop growing with history.

## What was measured (this branch, `CapacityLaneCostTest` with `CAP_RESTORE=true`, `RocksDbLookupCostTest`)

**Restore is the first wall, not steady state.** `CapacityStore.restore` does `kv.scan("e/")`, which materialises every entity as a list of (key String, value bytes) pairs, maps that into a second list of decoded entities, and only then builds the ledger and the trie. Combined mode, in-memory store (RocksDB iteration would add time), measured with the original ledger still resident:

| Trades in store | Entities | Restore time | Peak heap during restore | Live ledger before |
| --- | --- | --- | --- | --- |
| 500,000 | 1.6M | 5.6 s | 5.7 GB | 1.1 GB |
| 1,000,000 | 3.1M | 11.6 s | 11.5 GB | 2.2 GB |
| 2,000,000 | 6.1M | 24.0 s | 16.2 GB | 4.2 GB |

About 12 s and 6 to 8 GB of heap per million trades. Linear extrapolation (not measured): 9M trades about 2 minutes and 50 to 70 GB; 18M about 4 minutes and over 100 GB; 36M (20k/s for 30 minutes) is out of reach. A restart of a long session would not fit a 64 GB host even though steady state does. The no-broker rate also falls as state grows: 29.0k, 27.1k, 25.5k trades/s at 0.5M, 1M, 2M trades.

**Read-through to RocksDB is affordable with a bloom filter.** Direct RocksDB, 10M entries (68 byte keys, 96 byte values), 512 MB block cache, laptop NVMe, keys precomputed:

| | No bloom | Bloom (10 bits/key) |
| --- | --- | --- |
| Negative get (key never written) | 8.7 us | 3.2 us |
| Positive get, random old key | 5.4 us | 4.5 us |
| Per trade: 2 negative gets + 3 puts | 34.5 us | 15.2 us |

The lane spends about 31 us per trade today (combined, no broker), so two filtered negative lookups per new trade would add roughly 6 us (about 20 percent); unfiltered, about 17 us (55 percent). An in-heap bloom or cuckoo filter of execution ids and action keys would make the common case (a new trade) a sub-microsecond in-memory miss and send only the rare "maybe" to RocksDB: 10 bits per key is about 1.25 bytes, so 36M executions and 36M action keys need about 90 MB.

## Where memory goes (from the heap spike measurements, CAL-FIN-P24 and the footprint investigation)

Live heap at scale: combined about 950 to 990 bytes per trade, two-step about 1,500; the commitment trie payload alone is about 336 bytes per combined trade (560 two-step), so **6.0 GB at 18M and 12.1 GB at 36M trades** with nothing else resident; the rest is the four hash maps, key Strings, wrappers and cached encodings. At 36M combined trades today's design needs about 35 GB of heap before restore; two-step about 54 GB.

## The design question

Two separate things grow with history: (1) the ledger's object maps (executions, obligations, attempts, actions: needed only to decide idempotency and operator retries, and to rebuild the commitment) and (2) the commitment trie (needed to compute each decision's state root). The RocksDB store already holds every entity, so the heap maps are a **second copy**.

The trie is the hard part. It is keyed by hashes, so a new execution lands at a random place about 20 levels deep: no locality, so a disk-backed trie would do tens of random reads per trade (my estimate: 2 to 8k trades/s per lane, too slow). It therefore has to stay in RAM unless the commitment changes. Executions, attempts and action keys are only ever **created**, though, so an append-only structure (a creation hash chain or a Merkle mountain range, the H2 and H2m options in `CALCIFY_STATE_COMMITMENT_INVESTIGATION.md`) keeps only O(log n) peaks live and never revisits old nodes; only positions (a bounded set of accounts times assets) would need a real trie, and obligations are derivable from executions and attempts (checked on 3,000 obligations including refused attempts in that investigation).

### Proposed layering (not approved)

| Responsibility | Placement |
| --- | --- |
| Positions and their trie; pending (unsettled) obligations; lane metadata (sequence, chain head, root, binding) | RAM, bounded by accounts times assets and by the pending count |
| In-heap filter of execution ids and action keys | RAM, about 1.25 bytes per key |
| Settled executions, obligations, attempts, actions | RocksDB only (read-through on a filter hit or an operator retry), never evicted without consulting the store |
| Commitment of the append-only entities | a mountain range (or creation chain): O(log n) live nodes, nodes persisted once |
| Decisions, inputs | the topics (durable log) |
| Query and report tables | Postgres projection, rebuildable |
| Recovery | a periodic checkpoint (positions trie, pending obligations, mountain-range peaks, filter, sequence, chain head, input offsets, binding) plus replay of the suffix; megabytes, so restart is seconds whatever the session length |

### Two stages

1. **Contract-neutral:** evict settled objects from the heap maps with store read-through behind the filter; snapshot the (flat-array) trie in the checkpoint so restore stops rebuilding millions of objects; stream the restore scan instead of materialising it. Heap at 36M combined trades is then about the trie's 12 GB (against about 35 GB), restore bounded by reading one snapshot, throughput cost about the lookup cost above. Memory still grows with history, just 3 times slower.
2. **Commitment v2 (contract change, owner decision):** a mountain range or creation chain for the append-only entities and a trie for positions only. Memory stops growing; inclusion proofs survive by sequence rather than by key; the independent Python encoder and goldens change.

The first stage is needed by the second, so it is the first increment either way.

## Rules any implementation must keep

Cache eviction is not forgetting: a miss on an old action or execution consults the durable index before the input is treated as new; duplicate and conflict, retry, settlement and budget semantics are unchanged; state changes, output progress and consumed-input progress share one recovery boundary; caches are disposable without losing correctness; byte-identical output and the golden vectors for stage 1.

## Restore and trie snapshot against a real RocksDB store (`CapacityRestoreCostTest`, combined mode, laptop)

The ledger that built the state is dropped first, so these are a fresh JVM's numbers. Each restore ran twice; the pairs agree within 3%.

| Trades | Entities | Materialized scan | Streamed scan | Heap left after restore | Trie snapshot | Snapshot write / read |
|---|---|---|---|---|---|---|
| 650k | 2.0M | 6.6 s, peak 2.5-2.7 GB | 6.7 s, peak 1.5-1.7 GB | 0.95 GB | 0.22 GB | 0.04 s / 0.04-0.07 s |
| 2M | 6.0M | 22.0-22.5 s, peak 6.6-7.1 GB | 22.5-22.8 s, peak 5.0-5.1 GB | 2.91 GB | 0.68 GB | 0.12-0.14 s / 0.12-0.16 s |

- Restore costs about 3.7 us per entity (about 11 s per million trades). Streaming the scan does **not** make it faster; it only lowers the peak by about 25-30%.
- The snapshot is 112 bytes per entity (32 B key, 16 B node, 64 B child digests) and reads about 150x faster than rebuilding the trie from entities. Every snapshot read was checked: its root gives the same commitment as the restored ledger.
- Heap left after restore is about 480 bytes per entity, of which the trie is 112. The other 370 are the object maps that stage 1 evicts.
- Extrapolated, not measured: 36M combined trades is about 110M entities, so a 12 GB snapshot (a few seconds if the page cache holds it, disk speed if not) against about 6 minutes of scan restore.

**The snapshot alone is not enough after a crash.** The snapshot is taken at sequence S; a crashed lane's store is at head H > S, and the store keeps only the latest value of each entity, so it cannot say which entities changed after S. Options, in order of preference:
1. Clean shutdown or checkpoint only: take the snapshot when the store is checkpointed at S, and fall back to the full streamed scan if the store head is not S. Simple; a crash costs the full scan.
2. A touch journal: each decision also writes `w/<sequence>` listing its entity keys, deleted after the next snapshot. Restore reads the snapshot, then re-applies the entities for sequences S+1..H from the store. Bounded by H-S, costs one small extra write per decision (not yet measured).

The open question is whether a crash may cost the full scan. At 30 minutes and 20k trades/s that is about 6 minutes of restore with stage 1's eviction applied afterwards; a touch journal makes it seconds.

## Open decisions for the owner

1. Is bounded memory a requirement? (Owner: yes, 2026-10-15, because of 15 to 20k trades/s sustained.)
2. Are inclusion proofs by sequence acceptable instead of by key (needed for stage 2)? **Owner: yes, absence proofs are not required (2026-10-15).** Policy for stage 2: a proof of presence is by sequence (the leaf carries the execution key, so a key-to-sequence index outside the commitment is untrusted but checkable); a claim that an execution was never booked is answered relative to a signed head by replaying the decision chain, not by an O(log n) proof; duplicates remain rejected deterministically by the ledger.
3. Stage 1 first, with commitment v2 designed alongside, or stage 1 only for now? **Owner: stage 1 first; stage 2 stays a design only, decided after stage 1 numbers (2026-10-15).**

## Next measurements, in order

1. ~~Restore cost with a real RocksDB store and a streamed scan, and the cost of the trie snapshot~~ done above at 2.0M and 6.0M entities (9M not run).
2. Stage 1 prototype on the no-broker harness against the real RocksDB store (not the in-memory map): trades/s, live heap per trade, restore time, with differential tests that decisions, snapshot and commitment equal today's.
3. A 15 minute paced soak on the EPYC droplet with the prototype (needs the owner's go and cost).
