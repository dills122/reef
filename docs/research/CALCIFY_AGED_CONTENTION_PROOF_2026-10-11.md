# Calcify aged-state and single-server contention proof — 2026-10-11

Later [matched control](CALCIFY_MATCHED_CONTROL_2026-10-11.md) passes aged capture
without cold resolver restart. Original treatment results below remain unchanged;
comparison now prioritizes recovery-path proof on current host.

**Result: capture/context capacity gate failed; final integrity checks passed.**
Completed attempt02 verified at least10,172.16 contexts/s, but resolved only
8,106.99–8,145.11/s within300s. Upper bound also misses10k: this is a measured
failure, not merely insufficient sampling. Final3,063,383 contexts match source
facts exactly, with no duplicates. Independent synthetic financial contender
recovered from SQL and passed complete-history/balance audit for366,625 trades.

Current [compact receipt](../evidence/calcify-aged-contention/verification.json)
and [retention receipt](../records/2026-10-11-calcify-aged-contention-retention.json)
pin original attempts, source/configuration and corrections. Earlier
[architecture research](CALCIFY_SINGLE_SERVER_ARCHITECTURE_REVIEW_2026-10-11.md)
remains scoped to its offline experiments; this proof supplies its next empirical
checkpoint. No production financial authority or new capacity qualification.

## Scope and frozen workload

Source baseline `b9a8756e8d0efc5e8b665a5c62da1ab064d01539` plus isolated research
probe/harness changes. Capture uses current Phase1/2,64 standing makers and no
terminal eviction. Finite P0/O1 lifecycle profile remains off; its tiny whole-run
budget cannot qualify10k. Financial contender executes existing gross kernel
against synthetic independent domains and writes an atomic SQL journal, legs,
balances and checkpoint. It does not map captured trades into financial actions.

All service workloads share one local Docker VM:10 CPUs,16,745,824,256 bytes RAM;
HTTP load driver shares physical64GiB macOS host. RF1 Redpanda v26.2.3 is cached
and explicitly differs from checkout's v26.2.4 Compose default. Current compiled
runtime uses Kafka4.3.1/Kotlin2.4.20; images and actual topic settings are frozen.
No host-loss resilience or production financial-authority claim.

All five cohort topics have16 partitions, RF1 and inspected `write.caching=false`.
Runtime/test classes compiled from current checkout; matching binary rebuilt.
Fresh image removes cached runtime jars before copying current distribution.
`actual-profile.json` and attempt02 supplement freeze image IDs, allowlisted
runtime controls and source hashes. One host here means local physical machine;
Docker VM is not a production Droplet qualification. Capacity observers also run
on VM; host load driver uses physical host resources outside VM allowance.

Predeclared aged run:300s,10,500 offered trades/s,1,024 HTTP workers. Financial/SQL
starts at60s, process killed/recovered at150s, capture resolver cold-restored in
fresh local state directory at180s. Fault commands have actual timestamps; phase
rate bounds use independent received observations, not interpolated timestamps.

This ages state within one run: first minute accumulates roughly625k contexts;
cold recovery starts after roughly1.8m. It does not simulate days of history or
pre-age to maximum supported lifetime. No eviction avoids shortening exact
identity horizon to manufacture capacity. Financial domains close every10,000
trades; that contender does not prove million-trade financial-domain state.

## Results and measurement boundaries

| Measure | Attempt02 result | Interpretation |
| --- | --- | --- |
| Timed HTTP accepted | 3,063,383 | Zero failed requests/retries;64 seed makers excluded |
| Scheduled / scheduler dropped | 3,150,000 /86,617 | 2.75% drops, below existing5% loader cap |
| Verified by conservative300s deadline | 3,051,648–3,063,383 | 10,172.16–10,211.28/s |
| Full contexts by deadline | 2,432,098–2,443,533 | **8,106.99–8,145.11/s; gate fails** |
| Full-context gap at deadline | At most631,285 | Timed accepted count minus last pre-deadline observed count |
| Resolved drain upper bound | 29,118.95ms | Measured from conservative HTTP completion origin; exceeds frozen drain gate |
| Final full-fact oracle | 3,063,383/3,063,383 pass | Full source/context parity, ordered unique, no duplicates |
| SQL financial audit | 366,625 trades /37 domains pass | Independent synthetic workload, includes activity after300s |
| Financial recovery | Restored at nextTrade41,200 | 1,289ms history restore, excluding JVM startup; not full recovery time |

Rates divide deadline-covered cohort counts by300s. Observed timestamps bound
coverage; they are not individual acceptance-to-context latencies. Final counts
after deadline cannot repair failed rate or drain. Capture oracle independently
decodes source facts, but shares `CalcifySourceBatch.checked` checksum validator;
no claim of an independently implemented checksum algorithm.

Conservative phase bounds use first sample at/after start and last sample
at/before end, so prior population never inflates aged rate:

| Planned window | Verified lower bound/s | Resolved lower bound/s |
| --- | ---: | ---: |
| 0–60s: initial capture | 10,313.50 | 10,248.48 |
| 60–150s: financial/SQL starts | 10,258.83 | 9,836.78 |
| 150–180s: financial recovery | 10,085.50 | 7,967.20 |
| 180–300s: cold resolver recovery | 9,767.25 | 5,538.68 |
| 60–300s: combined aged window | 10,088.80 | 7,527.79 |

These are planned windows, not perfectly aligned isolated treatments. Actual
financial start/kill/restart occurred65.17/155.83/156.48s after load detection;
cold resolver replacement began183.62s, submitted187.73s. Detection is file
creation observation and used only for fault scheduling. Throughput uses frozen
capacity runner's monotonic load origin. Sampling slack means9,836.78 lower
bound alone does not establish a60–150s10k miss. Multi-fault run cannot attribute
exact cost to SQL, financial serialization, GC, broker I/O or cold restore alone.

SQL contender uses existing gross financial kernel and25-trade SQL transactions:
complete history, journal legs, balances and next-trade checkpoint commit
atomically. Restart replays current domain from committed history. Final audit
replays every domain, checks expected balances, history/leg counts and balanced
assets. Target pacing cap1,000 trades/s is not an achieved throughput claim.
366,625 includes work during post-load drain and source audit. This is real
kernel/JDBC contention, but captured trades are not its financial inputs and
production SQL/API materializers are not exercised.

## State and shared-server cost

146 resource samples cover named service and financial containers. Sampled
matching memory reached4.205GiB, resolver1.405GiB, broker2.077GiB and restarted
financial contender1.050GiB. These Docker statistics are rounded sampled values,
not continuous RSS or heap bounds; peaks need not coincide. Observers and host
load driver are outside this accounting, though their work competes on host.
No observed OOM; minimum sampled guest disk free34.705GB exceeded8GB stop reserve.

Final resolver state uses2,332,079,011 logical bytes; preserved pre-restart state
adds1,413,794,317 bytes on same volume. Cold recovery therefore needs space for
both while old state is retained. SQL database size1,363,713,047 bytes includes
smoke, failed attempt and control data; it is not attempt02-only storage cost.
Final resolved payload total6,097,277,845 bytes excludes broker replication,
indexes/changelog/WAL/compaction overhead. Do not extrapolate that payload alone
into a whole-server retention budget.

Earlier D7 passed10,440.92 resolved/s with3,142,846 exact contexts under its
fresh, narrower-retention profile. This trial changes source baseline/software,
retention0, actual broker version, contention and recovery; it does not invalidate
D7's original scope or isolate a regression. Matching's material4.2GiB footprint
and recovery slowdown establish missing operating envelopes, not proof that any
one data structure or service caused entire miss.

## Architecture tightened by this failure

1. **Recovery is part of10k qualification.** A healthy/RUNNING process is not a
   caught-up resolver. Track durable input, verified, resolved and recovery
   frontiers separately, plus oldest unresolved age and end gap. Declare permitted
   recovery deficit/drain before test. Until owner changes gate, this run fails;
   do not waive recovery interval or credit later catch-up.
2. **Bound exact identity lifetime before production load.** Budget matching
   order identities and resolver state by supported population and age, including
   old+new restore directories. Admission/run-size limits can enforce an initial
   envelope. Compact tombstones or certified pruning require separate replay and
   identity proofs; arbitrary eviction or ID reuse is not a capacity fix.
3. **Reserve resources for recovery and capture.** Define per-role CPU, heap/native
   memory, disk working space and backlog ceilings on one server. JVM
   `ActiveProcessorCount` is not a Docker CPU limit. Financial/SQL concurrency must
   fit remaining measured allowance; add lag-aware pacing and admission before
   unbounded backlog consumes capture's reserve. No production controller built here.
4. **Keep financial integration as its own gate.** Atomic synthetic SQL recovery
   supports journal/checkpoint design, but does not accept financial authority,
   managed financial adapter, lifecycle coverage, or captured-trade-to-API parity.
   Settlement may lag only within explicit backlog/freshness/recovery contract.
5. **Diagnose before choosing hardware or replacing infrastructure.** Next proof
   is matched aged control with same SQL/financial load and no resolver restart,
   followed by same cold restore with one resource/configuration variable changed.
   Keep initial age, workload, observers, versions and bounds fixed. Include all
   process/cgroup CPU and memory, disk latency and state restoration progress.
   Larger single server remains allowed after results justify size; none qualified.

## Attempts, corrections and closure

Initial smoke:15,000 contexts independently match full source facts;500 financial
trades replay exactly from SQL. Attempt01 ended early because cold-restart Compose
command omitted profiles;49,100 financial trades across5 domains still passed full
history/balance audit after process recovery. Attempt01 is nonqualifying, preserved
unchanged. Attempt02 corrects profiles and uses a fresh cohort/financial schema.

Initial setup also found exhausted Docker default subnet pools and image build
context excluding build output. Corrected with inspected unused10.252.200.0/24
subnet and isolated image context; failed logs retained. No unrelated network,
container, image or volume removed. All owned services stopped at closure;
PostgreSQL, broker and resolver volumes preserved. Archives retain logs/configs,
hashes and audits; raw multi-gigabyte topic/SQL/state volumes are not exported.
Portable independent replay from archived output bytes alone is therefore not
claimed.

Verification: runtime/test-class compilation succeeds;17 focused Node tests pass
(11 existing capacity +6 new conservative-window tests), script surface and
retention checks pass. New tests registered in CI. Proof runner returns exit1 for
expected measured capacity failure; that is preserved, not converted to pass.

102 exact proof files/2,221,997 bytes published at
[Records commit8fedbbe3](https://github.com/dills122/reef-records/tree/8fedbbe309a75a72a0bd58ba2640cd9c9aa9fb51/records/reef/docs/evidence/calcify-aged-contention/run-2026-10-11-b9a8756e),
[draft PR12](https://github.com/dills122/reef-records/pull/12). All105 changed archive blobs
(2,420,983 bytes including manifest/navigation/provenance) remotely byte-verified.
Local-only proof source `42bc04487d6fe284e83327c9b5aca66d16537596` records originals;
working branch/index untouched. Records CI passes full36,496-record inventory,
append-only check, hygiene and4 unit tests. Archive remains unmerged; no tracked
source removed. Reef hosted CI not run for local research changes.

## Reproduction entry points

Compile current runtime/test classes and build isolated images before load.
`scripts/dev/calcify-direct-bootstrap.mjs` now accepts explicit
`CALCIFY_DIRECT_PROJECT`, `CALCIFY_DIRECT_SUBNET`, `CALCIFY_DIRECT_BROKER_IMAGE`;
default setup unchanged. Project override assigns unique container names/labels.
Bootstrap still requires unused host ports and an inspected, unused subnet.

```sh
bun scripts/dev/calcify-financial/research/run-contention.mjs SETUP_DIRECTORY NEW_OUTPUT_DIRECTORY smoke
bun scripts/dev/calcify-financial/research/run-contention.mjs SETUP_DIRECTORY NEW_OUTPUT_DIRECTORY aged
bun scripts/dev/calcify-financial/research/summarize-contention.mjs OUTPUT_DIRECTORY
node --test scripts/dev/calcify-financial/research/summarize-contention.test.mjs
```

Runner is deliberately tied to named local research images/network and frozen
plan at `/private/tmp/calcify-contention-20261011/plan.json`; inspect configuration
before reproducing on another host. Original outputs, setup failures, image/source
hashes and correction receipts remain in that proof directory during execution.
Read result and retention receipt before making a capacity claim. Source checkpoint
retains exact proof scripts; baseline production behavior remains unchanged.
