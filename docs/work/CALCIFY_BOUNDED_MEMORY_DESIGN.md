# Calcify finance lane: bounded memory, and what restart costs (design note)

Status: stage 1 (eviction of settled objects) is implemented and ON by default in the launcher (2026-10-15); an evicting lane restarts from a lane snapshot after a clean close and by the full scan after a crash (2026-10-16); stage 2 (commitment v2) is design only and not approved. Owner direction: bounded memory is a requirement; the delivery goal is 10,000 booked trades/s sustained for 15 to 30 minutes (9M to 18M trades), and 15 to 20k/s (27M to 36M trades) is later work, so stage 1 is sized to leave memory growing only by the commitment trie (about 350 bytes per combined trade) and the filter.

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

## Stage 1 status: eviction implemented, on by default (`CapacityProcessorConfig.evictSettled` is the switch; the launcher turns it on; `CALCIFY_CAPACITY_EVICT_SETTLED=false` turns it off)

What it does: a lane keeps in memory only positions, pending trades (with their attempts) and the commitment trie. Settled executions, obligations, attempts and all actions leave the maps once `CapacityProcessor` has applied the record's writes to the store (`CapacityLedger.persisted()`). Lookups of an old action or execution go through `EntityFilter` (a growing blocked Bloom filter of X and K keys, 1.47% false positives, 2.94 bytes per key measured at 3M keys) and then one store read; a filter miss, the common case for a new trade, reads nothing. Restore streams the entities class by class (O, X, A, K, P) and keeps only open work.

Equivalence is tested, not argued: `CapacityEvictionTest` runs a plain ledger and an evicting one on the same random schedules (both decision modes, budgets, replays, conflicts, failed and retried settlements, store writes applied after random groups of one to three decisions) and requires equal dispositions, decisions, writes and commitments; the same through the Streams processor with multi-input records and a restart (published bytes and stored entries equal); and an evicting restore that goes on deciding like a plain one. The store tamper suite runs for both restore modes. Finance suite: 286 tests, 0 failures.

Measured on the laptop with the real RocksDB store (combined mode, `CapacityRestoreCostTest`, same final commitment in both modes at every size):

| Trades | Running lane heap, plain | Running lane heap, evicting | Restore, plain streamed | Restore, evicting |
|---|---|---|---|---|
| 300k | 1,558 B/trade (0.47 GB) | 648 B/trade (0.19 GB) | 2.9 s, peak 0.83-0.89 GB | 2.8-2.9 s, peak 0.52 GB |
| 1M | 1,299 B/trade (1.30 GB) | 371 B/trade (0.37 GB) | 11.5-12.0 s, peak 2.5-3.8 GB | 10.3-11.2 s, peak 0.74-0.77 GB |
| 2M | 1,282 B/trade (2.56 GB) | 359 B/trade (0.72 GB) | 24.4-26.2 s, peak 4.8-6.4 GB | 21.8-22.0 s, peak 1.2-2.4 GB |

The evicting slope is about 350 B per trade, which is the trie (3.1 entities x 112 B); everything else is fixed. Throughput is unchanged: through the real store 2M trades took 106.3 s plain and 106.9 s evicting, and the no-broker lane harness (in-memory store, 600k trades, run twice each, alternating) gave 25.4k and 26.0k trades/s plain against 24.9k and 26.5k evicting, which is inside the run-to-run noise. Extrapolated, not measured: 36M combined trades is about 12.6 GB of heap against about 46 GB plain. Restore without a snapshot still rebuilds the trie by hashing every entity, so it is still linear in history (about 11 s per million trades); a clean restart now reads the lane snapshot instead (owner chose snapshot-on-clean-shutdown, full scan after a crash; see "Lane snapshot" below).

### Soaks (combined mode, one lane, 10,000 trades/s for 15 minutes = 9M trades, real Redpanda and Postgres)

| | Laptop, plain | Laptop, evicting | EPYC 9555P droplet, evicting |
|---|---|---|---|
| End-to-end rate | 9,983/s | 9,985/s | 9,996/s |
| Heap still reachable after drain | 10.2 GB (1,191 B/trade) | 4.6 GB (541 B/trade) | not printed (heap at the end 4.1-4.4 GB) |
| Peak heap sampled | 15.2 GB | 6.3 GB | 4.4 GB |
| Total GC time | 8.3 s (G1) | 2.6 s (G1) | 8.1 s (ParallelGC), worst 10 s interval 242 ms |
| Max sent-to-booked lag / booked-to-projected | 0.40 s / 1.70 s | 1.37 s / 2.95 s | 0.11 s / 0.43 s |
| Lane thread CPU (mean / max of one core) | 73% | 73% | 73% / 90% |
| Integrity | ok | ok | ok |

The laptop evicting run had one 1.4 s booking-lag spike near 8.8M trades with no GC to explain it (unexplained, not seen on the droplet). Owner set the booking-lag cap for these runs to 1.6 s (2026-10-15); all three runs are inside it. The droplet run (`g5-8vcpu-64gb-50gb`, atl1, 20.8 minutes of rental, about $0.43; a first attempt failed in the test compile with a Kotlin daemon OutOfMemoryError, fixed by giving the host's daemons 4 GB) shows the lane using about 73% of one core on average at 10k/s, so one lane's headroom on this hardware is roughly 1.3x; 15-20k/s needs more than one lane or a cheaper lane.

Independent review, two instances (neither found a P0 or P1; both verdicts Ready with non-blocking follow-ups). Fixed: the evicting restore refuses unknown or stray `e/` keys (including keys that sort past the old scan bound on a byte-ordered store, now covered by `[prefix, successor)` scans) and unpaired resident pending obligations, and rejects a source that returns the wrong class; attempt keys are no longer added to the filter; `FlatStateTrie` snapshots end in a SHA-256 of their content that `readFrom` verifies; the launcher default and its environment switch are tested; a budget-refusal check and a multi-input processor test were added. Since closed (see "Lane snapshot"): the snapshot is bound to the store's head, sequence and commitment, and its tree shape is validated in full; a kill-mid-transaction broker test runs with eviction on. Still open: the filter allocates a new layer in one piece (a one-off pause, about 300 MB at 70M keys); the 1.4 s booking-lag spike in the laptop soak is unexplained. Ids are bounded at 128 UTF-8 bytes by `boundedText`, so the 4096-byte limit of `DecodedFields` cannot be reached on a read-back, and store keys are hex, so characters in ids cannot collide with the key layout.

### Lane snapshot: clean restarts read one file (2026-10-16)

An evicting lane writes `calcify-<store>.lane-snapshot` into its Streams task directory when `CapacityProcessor.close()` runs (temporary file, forced to disk, atomic rename): magic, version, binding digest, configuration digest, sequence, controls, chain, the five counts, the open work (pending obligations with their executions and attempts, all positions) in the store's entity encodings, the `EntityFilter` layers and the `FlatStateTrie`, then a SHA-256 of all of it (`CapacityStore.writeSnapshot`, `CapacityLedger.writeSnapshot`). Streams also closes processors on a dirty shutdown, so `CapacityStore.restore` never trusts the file on its own. It is used only if the checksum, version, binding and configuration match, the open work are leaves of the snapshot's trie with exactly those values, the counts add up to the trie's leaves and the filter's keys, the trie has a valid Patricia shape (every node reached once, increasing bit indices, every key on its path), the sequence, chain and controls are the store's `meta` and the head decision (whose checksum recomputes), and the trie's commitment is the head decision's `stateDigest`. Anything else, or any exception while reading, falls back to the scan, which stays the verified path. The file is deleted by every start that finds it, so it cannot outlive a later crash; a close after a record that failed half way writes nothing. Lanes that do not evict never use or keep one. A state wipe and changelog restore deletes the task directory, snapshot included.

Equivalence and refusal are tested, not argued: `CapacitySnapshotTest` restores the same random schedules (both modes, budgets, replays, conflicts, failed and retried settlements) by scan and by snapshot, requires equal sequence, chain, controls, commitment, entity count and resident objects, then 320 further inputs per round (a third of them verbatim replays of earlier inputs, plus new executions) with equal dispositions, decisions and store writes, and a second restart from a snapshot of the snapshot-restored ledger. Flipped bytes, truncation, a trailing byte, another binding, configuration or history at the same sequence, a snapshot older or newer than the store, and resealed files (trailers rewritten) with a changed trie root, a changed resident position, a changed count or a changed sequence all fall back with the scan's result. Through the processor (`MockProcessorContext`), a lane restarted by clean close six times publishes and stores the same bytes as one restarted without close and one never stopped. `CapacityEndToEndTest` (broker) now takes the snapshot path on its clean restarts and still equals the oracle.

Measured (`CapacitySnapshotRestoreCostTest`, combined mode, real RocksDB store, laptop, each restore twice, alternating; every snapshot restore was used and gave the scan's sequence and commitment):

| Trades | Store entries | Snapshot file | Write on close | Scan restore | Snapshot restore | Heap left after |
|---|---|---|---|---|---|---|
| 1M | 3.1M | 0.35 GB (347 B/trade) | 0.43 s | 10.6-11.2 s, peak 1.0-1.2 GB | 0.83-0.89 s, peak 0.70-0.74 GB | 0.57 GB |
| 2M | 6.1M | 0.69 GB (345 B/trade) | 1.04 s | 22.5-23.5 s, peak 2.1-2.4 GB | 1.75-1.79 s, peak 1.35-1.39 GB | 1.11 GB |

About 13x faster, linear at about 0.9 s per million trades (file read, shape check, filter), with a third less peak heap. Extrapolated, not measured: 18M trades about 8 s and a 6 GB file; 36M about 16 s. **What the snapshot does and does not protect against.** The checksum guards against corruption and truncation; the checks against the store (sequence, chain, controls, head checksum, trie commitment equal to the head's `stateDigest`, every open-work entity a leaf of the trie with that exact value, counts adding up) guard against a stale or foreign snapshot. They are not authentication: the trie's interior digests are not re-hashed, the resident set is not proven complete (a pending obligation with its execution can be left out of the open work, and then a repair `Settle` for it is answered `ALREADY_SETTLED`, a benign refusal, while a scan restore would settle it), and the filter's bits are not checked (clearing them gives false negatives, so a replayed capture could be booked twice). Whoever can rewrite the lane's private state directory can therefore forge a consistent snapshot and reseal both checksums. Re-hashing the trie would close none of the completeness or filter cases. The scan restore resists all of it, because it rebuilds every entity from the store and the store is bound to the decision log. If that writer is inside the threat model the options are a MAC whose key lives outside the state directory, or a scan of the O class only (pending-obligation completeness) on every start; neither is built. The state directory is owner-only (0700) and is the same directory that holds the RocksDB store itself.

**Close time.** The snapshot is written on the stream thread during `close()`: about 0.5 s per million trades per lane (about 9 s at 18M trades, extrapolated), serial across the lanes of a thread, against the launcher's 30 s close timeout. A close cut off by the timeout leaves a partial `.tmp` file (removed at the next start, never read) and the lane scans; commit and checkpoint happen before the write, so nothing else is at risk.

Kill test (`CapacityLauncherCrashTest`, broker, real Redpanda and Postgres, two evicting lanes in combined mode, 2,400 trades with one in seven left pending until operator repair): the launcher runs in a child JVM and is stopped four times while trades flow: SIGKILL; SIGKILL then the local state wiped (changelog restore); SIGTERM (clean close: one snapshot per lane written; the next start restored every lane from it); SIGKILL of that snapshot-restored child. Run twice: 3 of 3 SIGKILLs landed while trades flowed, and the run printed 2 and 3 of them with a transaction open on the decision log (observed output; the test does not assert it, the kill point being timed). Each lane's decision log is contiguous and equal decision by decision to an independent replay, and the projection checkpoint, positions and obligations (all settled exactly once) equal it. What it does not prove: a kill during the snapshot write itself (covered by the atomic rename and the tests above, not by a kill), or a kill landing inside a state-store flush; the kill point is timed, not chosen.

## Open decisions for the owner

1. Is bounded memory a requirement? (Owner: yes, 2026-10-15, because of 15 to 20k trades/s sustained.)
2. Are inclusion proofs by sequence acceptable instead of by key (needed for stage 2)? **Owner: yes, absence proofs are not required (2026-10-15).** Policy for stage 2: a proof of presence is by sequence (the leaf carries the execution key, so a key-to-sequence index outside the commitment is untrusted but checkable); a claim that an execution was never booked is answered relative to a signed head by replaying the decision chain, not by an O(log n) proof; duplicates remain rejected deterministically by the ledger.
3. Stage 1 first, with commitment v2 designed alongside, or stage 1 only for now? **Owner: stage 1 first; stage 2 stays a design only, decided after stage 1 numbers (2026-10-15).**

## Next measurements, in order

1. ~~Restore cost with a real RocksDB store and a streamed scan, and the cost of the trie snapshot~~ done above at 2.0M and 6.0M entities (9M not run).
2. ~~Stage 1 prototype against the real RocksDB store~~ done: running heap, restore and throughput at 300k, 1M and 2M trades, differential tests for decisions, writes and commitments.
3. ~~A 15 minute paced soak~~ done on the laptop (plain and evicting) and on the EPYC droplet (evicting), see Soaks.
4. ~~Wire the trie snapshot for clean restarts; a kill-mid-transaction broker test with eviction on~~ done, see "Lane snapshot" (1M and 2M trades; 9M not run).
5. Open: whether a crash may keep costing the full scan (a touch journal would bound it, not built); whether a writer of the lane's state directory is inside the threat model (if so a MAC with an outside key or an O-class completeness scan is needed; re-hashing the trie would not be enough, see Lane snapshot); stage 2 only if a longer session than 30 minutes, or the 15 to 20k/s goal, makes the trie itself the limit.
