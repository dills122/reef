# Handoff: shrink the finance lane's memory and storage footprint

Prepared 2026-10-15 by the Claude session that did the lane work. Audience: a Codex session (or a person) picking this up cold. Everything you need to start is on this branch (`claude/footprint-handoff`); read this file top to bottom first, then `AGENTS.md`.

## Pickup correction (2026-10-15)

Codex pickup baseline is `f09f20c7e9699dcb4156fe67eaeaa19b009452ae` on
`codex/footprint-investigations`. Owner authorized takeover of both investigations.
Local spike branches exist; heap worktree contains an unfinished measurement harness,
bytes worktree has no findings. No spike findings document is committed on either branch.
PR571 merged at 22:19 UTC, but this handoff branch does **not** contain its lz4 default.
Measurements therefore pin compression explicitly; do not call this branch's baseline lz4.
PR573 remains open at pickup; safe-win PRs must wait for their prerequisite base to merge.
The 1.2 KB/trade estimate below is mode/workload/collector dependent, not a universal
upper bound: new forced-GC two-step measurements exceed it. See new footprint notes.

## 1. The task, in one paragraph

The Calcify finance lane keeps **too much state in the JVM heap (about 1.2 KB per booked trade, an upper bound)** and writes **too many bytes (about 3.7 KB per booked trade: ~2.2 KB on the broker, ~1.5 KB in Postgres, plus ~1 GB of WAL per 450k trades)**. The arena sessions the owner cares about are **15 minutes (runs whose records are kept) and 30 minutes (top end)** at a target of **10,000 booked trades/s**, i.e. 9M and 18M trades. At today's footprint that is roughly 11 GB or more of heap and 33 GB of storage for 15 minutes, 22 GB or more of heap and 67 GB of storage for 30 minutes, which does not fit a modest host. Your job: **find out exactly why the footprint is that big, and slim it down**, measured, without breaking the verification contract. Two investigations, which can be done independently: (A) heap per trade, (B) bytes per trade.

## 2. Who owns what; the rules you work under

- The repository owner is Dylan Steele (`dills122/reef`). He decides contract changes (see section 6); everything else you may decide.
- Read `AGENTS.md` and `docs/steering/` first; this file does not replace them. `.codex/skills` and most of `.codex/steering` are machine-local links and not useful here.
- Do not commit these (they are the owner's untracked files): `docs/research/KOTLIN_JSON_LIBRARY_RESEARCH_2026-09-30.md`, `.mcp.json`, anything under `.planning/`. Stage exact paths only, never `git add -A` or `git add docs`.
- No cloud spend, no droplets, without the owner's explicit go with the size and cost stated in chat. The droplet tooling is on this branch for later; do not run it. Never print or copy secrets (`.env` holds a DigitalOcean token; leave it alone).
- Leave no Docker containers or volumes behind (`docker ps -a`, `docker volume ls`; clean with `docker rm -f -v`).
- Code PRs get the `ocr-pilot` label (an automatic reviewer runs and costs money); docs-only PRs do not. Squash merge. Do not stack PRs: open the next one only when its base has merged (keep developing locally meanwhile).
- Do not run polling loops on CI; build the next piece while checks run.
- Style: write code that reads like its surroundings (comment density, naming, idiom). Commit messages: conventional commits (`perf(calcify): ...`, `test(calcify): ...`, `docs(calcify): ...`).
- Independent review: for anything that touches hashing, encoding, state or decision bytes, get a fresh-context review (a new session with only the base/head commits and a neutral bootstrap, not your explanation) and mutation-test the tests. The previous changes were reviewed this way and it found real problems.

## 3. Background: what this system is (enough to be useful)

Reef is a market-simulation platform (bot arena). **Calcify** is its finance path: executed trades arrive from the matching engine, and a ledger books them as cash/share movements with a verifiable audit trail.

The **capacity profile** (`docs/work/CALCIFY_CAPACITY_PROFILE.md`, `CALCIFY_CAPACITY_RUNTIME.md`) is the high-throughput form:

- **A lane** = one Kafka partition = one Kafka Streams task = one thread. Instruments are routed to lanes by `StreamCommandEnvelopeBuilder.partition(run, session, instrument, count)` (the same hash the matching engine uses), so one instrument is always one lane. Cash is held per lane.
- Per lane: one `CapacityLedger` (in memory), a RocksDB-backed Streams state store with a changelog topic, a decisions topic (`FinanceDecisionV1` protobuf records, key `<lane>/<sequence>`), and a **projector** that tails the decisions topic into Postgres tables `boundary.calcify_finance_*` (decision, position, obligation, attempt, checkpoint; migrations under `scripts/dev/db/migrations/boundary/`, especially 0014, 0016, 0017).
- **Decision modes:** `two-step` (today's default: a trade is a `Capture` decision plus a first `Settle` decision) and `capture-settle-v1` (opt-in, PR 573: one `CaptureSettle` input, one decision per trade).
- **Verification contract:** every decision carries a checksum chained to the previous one and a **state commitment** (`stateDigest`): the root of a path-compressed binary Merkle trie over every ledger entity, recomputed per decision (`FlatStateTrie`, `StateCommitment`). Restore must reproduce the head decision's commitment exactly. Entity kinds: P (position), X (execution), O (obligation), A (settle attempt), K (action key).
- Inputs on the CAPTURE topic are `MatchContextResolvedV1` (resolved-context source) or finite-lifecycle envelopes; CONTROL carries operator `Fund`/`Settle`.

Key code, all under `services/platform-runtime/src/main/kotlin/com/reef/platform/calcify/finance/`: `CapacityLedger.kt` (the in-memory state and `decide`), `FinanceModel.kt` (`ExecutionKey`, `ActionKey`, `Canon` encoder), `FlatStateTrie.kt`, `StateCommitment.kt`, `CapacityStore.kt` (store keys/values, HEAD/META, restore), `CapacityProcessor.kt` (the Streams processor), `FinanceWire.kt` + `contracts/proto/calcify_finance.proto`, `CapacityLauncher.kt` (Streams config), `FinanceProjector.kt`/`FinanceProjectionRunner.kt` (Postgres rows), the two decoders (`CapacityCaptureMapping.kt`, `CapacityResolvedContextDecoder.kt`), `CapacityPlan.kt`.

## 4. What has been done and measured (so you do not redo it)

All numbers are booked trades per second unless stated. Ledger entries: `docs/THROUGHPUT_BASELINES.md` CAL-FIN-P5 to P26.

- Lane CPU work (byte-identical, merged as PR 570): flat trie + removed re-encoding, +41% on a quiet laptop. Notes: `CALCIFY_LANE_COST.md`, `CALCIFY_STATE_COMMITMENT_INVESTIGATION.md`, `CALCIFY_REENCODING_INVESTIGATION.md`.
- Combined decision (PR 573, opt-in): `CALCIFY_COMBINED_DECISION_INVESTIGATION.md`.
- Dedicated hosts (CAL-FIN-P26; note `CALCIFY_DEDICATED_HOST_P26.md` is on branch `claude/calcify-p26-docs`, not yet merged): on an **AMD EPYC 9555P** (`g5-8vcpu-32gb-50gb`, $0.36/hr, hardware SHA, fast core) one hot lane **projected 9.9-10.0k (two-step) and 16.5k (capture-settle-v1)** trades/s; on a **Xeon 8168** (`c-32`, no fast SHA) 4.1k and 6.7-6.9k; four Xeon lanes 12.6k and 20.1k. So throughput is reachable on the right hardware; **footprint and duration are now the open problem**.
- Store-writes investigation (not merged; the doc is on this branch, the code on branch `claude/store-writes-investigation`): `CALCIFY_STORE_WRITES_INVESTIGATION.md`. Findings: 11 store puts / 1,799 bytes per trade today; checkpointing head+meta every K records and a merged per-execution record (store layout v2) got to ~7 puts / 705 bytes; `producer.compression.type=lz4` cut broker bytes per decision 871 to 395 (now the capacity app's default, PR 571). The layout v2/checkpoint change is a **restore-contract change** the owner has not approved yet.
- The paced soak harness (this branch; `CALCIFY_SOAK_HARNESS.md`) produced the footprint numbers that motivate this task, from two 90 s laptop runs: JVM heap used grew ~1.2 KB/trade (G1 figure, includes garbage); broker ~2.2 KB/trade (the Streams changelog is the biggest part); Postgres ~1.5 KB/trade; ~1 GB WAL.

**Other Claude sessions may already be working on the same two investigations** (started just before this handoff): look for branches `claude/heap-spike` and `claude/bytes-spike` (`git branch --list 'claude/*spike*'`) and their docs `docs/work/CALCIFY_HEAP_FOOTPRINT_SPIKE.md`, `docs/work/CALCIFY_BYTES_FOOTPRINT_SPIKE.md`. If they exist, read them before doing anything and build on them; ask the owner whether to take over or review. If they do not exist, they were stopped; start fresh.

## 5. Your investigations

### A. Heap per trade

Why: the ledger keeps the whole state in memory: positions, executions, obligations, attempts, actions (HashMaps keyed by `ExecutionKey` = four Strings, `ActionKey` = three Strings, `Pair<ExecutionKey,Int>`, boxed values, `ActionRecord` holding a 64-char hex digest String), plus `FlatStateTrie` (flat chunked arrays, ~112 bytes per entity by layout, 3 to 5 entities per trade). At 18M trades that is 22 GB or more.

Do:
1. Measure live heap per trade properly: no-broker harness to N = 250k, 500k, 1M, 2M trades in both modes, forced full GC, `jcmd <pid> GC.class_histogram`; split trie from maps; report class breakdown.
2. Explain the number against the information content (what is overhead: object headers, String coder/hash, HashMap nodes, boxed Longs, hex digests as Strings, cached frames).
3. Prototype slimming and measure live heap per trade AND processing-thread CPU per trade (A/B interleaved): contract-neutral compaction inside the ledger (compact keys, dictionary-encoded ids, packed values, raw 32-byte digests, one execution table instead of four maps); what must stay in memory to **decide** versus only for commitment/restore (settled executions could be evicted from the maps and re-read from the store on a redelivery or operator retry; the trie keeps their leaf digests): prove from the code what each decision path reads; bounded-state options (slice rotation, spill to the RocksDB store with a hot cache) and their contract implications.
4. Give the heap budget for 9M and 18M trades per option, and the GC behaviour (G1 vs ParallelGC) at those sizes.

### B. Bytes per trade

Why: ~3.7 KB per trade across the broker and Postgres is 60% more than was estimated and does not fit a modest host for 30 minutes (~67 GB).

Do:
1. Measure the byte inventory exactly per trade in both modes with the real broker and Postgres in Docker (e.g. 1 lane, 300k trades): per topic uncompressed (key+value) and on disk (after compression, plus segment/index overhead); per Postgres table heap/index/TOAST/WAL; record counts per trade per topic.
2. Explain each against its information content (hex digests as 64-char text vs 32 raw bytes, the whole input echoed inside each decision, repeated key strings, protobuf tags, Kafka framing, RocksDB changelog values/keys as long hex strings, Postgres tuple headers and each text index repeating its key, the unique index on action key, FKs, WAL full-page writes).
3. Evaluate and measure: contract-neutral configuration first (topic compression lz4 vs zstd, changelog compaction/segment/retention so on-disk size follows live keys, retention for the CAPTURE/DECISIONS topics for a 15-30 minute session, Postgres `wal_compression`, fillfactor, indexes nobody reads (grep readers of `boundary.calcify_finance_*` before proposing to drop), UNLOGGED projection tables for soaks since the projection is rebuildable); then record-shape changes (raw 32-byte digests and `bytea` columns, not echoing the input in the decision record, dropping stored `prior_chain`/`input_digest` if recomputable, binary entity keys in the store, store layout v2/checkpointed head+meta from the store-writes investigation).
4. Give the bytes budget for 9M and 18M trades per option and for the combined neutral set (measure the combination; do not multiply gains blindly).

### Definition of done

For each of A and B: a note in `docs/work/` (measurements with ranges, the explanation table, prior art with sources, options with before/after numbers, a ranking, the 9M/18M budget), committed prototype code and tests, and a short list of **contract-change items for the owner**. Land the safe, byte-identical or configuration-only slimming as normal PRs (reviewed as in section 2); leave contract changes as recommendations with exact estimates.

## 6. What counts as a contract change (stop and ask the owner)

Anything that changes: decision checksum inputs or layout; `stateDigest`/leaf/key bytes or the entity set; `CapacityStore` restore semantics or store layout (existing stores and changelogs become unreadable); the protobuf wire format beyond additive fields; decision results/dispositions; plan/binding digests. Byte-identical optimisations and pure configuration are not contract changes, but must be proven (differential tests against the old code on random inputs; the golden vectors in `FinanceLedgerTest`, `StateCommitmentTest`, `CapacityStoreTest`, `FinanceWireTest` and the independent Python encoder must pass unchanged). The binding digests of the default mode are pinned as literals in `CaptureSettleModeTest`.

## 7. Setup

Prerequisites: JDK 21 toolchain (Gradle downloads/uses it), Docker running (Redpanda `redpandadata/redpanda:v26.2.3` and Postgres 16 are started by the tests), nothing else needed for these investigations (Go is only for the engine, which is not involved). The machine used so far was a shared 10-core M1 Max laptop with 64 GB: **timings vary +-15-20% run to run**, so prefer contention-immune metrics (live heap after full GC, bytes, puts per trade, `ThreadMXBean` thread CPU and allocated bytes per trade) and always A/B interleaved with >=3 repeats and report ranges.

Branch: `git fetch origin && git checkout claude/footprint-handoff`. It contains master plus: the opt-in `capture-settle-v1` mode (PR 573 not yet merged), the droplet tooling (PR 572, do not use without approval), the paced soak harness, the store-writes investigation doc.

Run from `services/platform-runtime`:

```bash
# default suite (fast, no Docker): must stay green
./gradlew test --tests '*finance*' --console=plain

# no-broker lane profile + CPU/hash counters (research tag). Heap: add -PtestHeap=24g for big N
CAP_TRADES=2000000 CAP_DECISION_MODE=capture-settle-v1 \
  ./gradlew test -PresearchTests -PtestHeap=24g --tests '*CapacityLaneCostTest*' -i --console=plain

# real broker + Postgres, throughput (Docker). CAP_DECISION_MODE=two-step|capture-settle-v1
CAP_LANES=1 CAP_TRADES=300000 CAP_INSTRUMENTS=64 CAP_ACCOUNTS=200 CAP_SOURCE=resolved \
  ./gradlew test -PbrokerTests -PresearchTests --tests '*CapacityLaneThroughputTest*' -i --console=plain
# docker run options for the stores: S4_RP_DOCKER / S4_PG_DOCKER (e.g. --tmpfs=/var/lib/redpanda/data:rw,size=16g,uid=101,gid=101,mode=0777)
# CAP_PG_SYNC_OFF=true makes Postgres commit asynchronously (safe: the projection is rebuildable)

# paced soak (Docker): CAPACITY-SOAK-* lines and a 38-column CSV
CAP_SOAK_SECONDS=90 CAP_SOAK_RATE=3000 CAP_LANES=1 CAP_INSTRUMENTS=16 CAP_ACCOUNTS=100 \
  ./gradlew cleanTest test -PbrokerTests -PresearchTests --tests '*CapacitySoakTest*' -i --no-daemon

# broker-backed correctness (restart, wiped-state restart, equality with an independent replay)
./gradlew test -PbrokerTests --tests '*CapacityEndToEndTest*' --tests '*CaptureSettleProjectorPostgresTest*'
```

Test output lines to look for: `LANE-COST` (no-broker rate, branch hashes per decision), `CAPACITY-CPU` / `CAPACITY-RATE` (processed and projected rates, `verify_s`/`sql_s`), `CAPACITY-SOAK-*`. Test results XML is under `build/test-results/test/`; stdout is only shown with `-i`.

Useful tools: `jcmd <pid> GC.class_histogram`, `-XX:+PrintClassHistogramAfterFullGC`, JFR; in Docker `docker exec <redpanda> du -sk /var/lib/redpanda/data`, `rpk topic describe -p`, `psql ... pg_total_relation_size`; `scripts/dev/calcify-financial/droplet-sizes.sh` is a ready-made data-directory size sampler.

Gotchas learned the hard way:
- Do not edit sources while a Docker image or gradle build is mid-run.
- `sed -i` on macOS needs `sed -i ''`.
- Kafka's topic `max.message.bytes` applies to the compressed batch; the producer limit applies to uncompressed bytes.
- The grep-filtered `-i` output hides failures; read the XML or drop the filter when a case fails.
- If a shell variable holding a command with spaces misbehaves, use a wrapper script.

## 8. Reporting back

Write findings into the notes above, commit on a branch from this one (name it `codex/footprint-*`), open PRs for the safe wins, and give the owner a short summary: bytes/live-heap per trade now and per option, the ranked recommendation, the contract-change items needing a decision, and the 15- and 30-minute budgets. If something in this handoff is wrong or out of date, fix this file in your first commit.

## 9. Where things stand for the people who will review your work

Open PRs at the time of writing: 571 (lz4 for the Streams producer), 572 (droplet tooling), 573 (capture-settle-v1). Merged: 561, 562, 564, 565, 566, 567, 568, 569, 570. Ledger numbering used so far: P20-P26 (P25 is the combined decision, P26 the dedicated-host measurement).
