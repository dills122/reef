# Handoff: Calcify joined10k timebox

## Objective And Boundary
Authorized two-hour campaign03:38:05–05:38:05UTC, October2. Target10,000 durable HTTP order commands/s through Phase1 stub verifier and Phase2 full-fact resolver. Target NOT achieved. Best full300s7,708.36/s; do not report stage-only10k contexts/s as this unit.

## Canonical Sources
[Campaign report](../../research/CALCIFY_10K_TIMEBOX_2026-10-02.md), [throughput ledger](../../THROUGHPUT_BASELINES.md), [frozen policy/corrections/raw records](../../evidence/calcify-10k-timebox-2026-10-01/campaign-policy.json), [current work](../../WORK_PLAN.md). Read AGENTS/AI_CONTEXT before continuation; handoff is continuation context, not product truth.

## Current Repository State
Worktree `/Users/dsteele/.codex/worktrees/calcify-phase2-experiments/reef`, branch `codex/calcify-10k-timebox`. Benchmark base037971d63ebee882799e0bd78b7dd0035af1c3a6; candidate77fe7b67. Later origin/master16e15340 merged as eb000b4e; compatibility482846d0 preserves new run-scoped/hidden-limit contracts and Phase1 retention/durability gates. Timed results do NOT transfer to integrated newer code. Primary checkout `/Users/dsteele/repos/reef` unrelated changes preserved. Campaign .planning image staging/binaries ignored from delivery; only markdown planning state retained.

## Completed Work And Evidence
Five60s diagnostics retained; two300s runs completed. q1 strict6250.80/s,940045 exact contexts,receipt789ms/resolved upper2065ms. q2 strict7708.36/s,1158676 exact contexts/full-stage counts,receipt1191ms/resolved upper1652ms,zero failures/retries. q3 stops200.974s with1805failures on PostgreSQL ENOSPC;800374 full trade contexts exact,277partial-pair commands break command accounting. Never call q3 five-minute record.
Bounded verifier/receipt polls default100/range1–1000 with poison-prefix tests. Correct per-ACK deadline/worker-completion drain measurements. Optional complete-pair instrument routing; actual SHA256(run|session|instrument) partition mapping. Correct four Go backpressure durable groups;50k threshold unchanged. Go checksum preserves reference bytes; captured350 benchmark ~23% time/~70% allocation reduction, no JSON dependency swap or platform-gain inference. Test-only FIFO1/16 source producer and independent full-fact oracle included.
Latest integrated platform check/coverage and full matcher race pass. Final latest-master fresh-image full-path smoke PASS, retained in `smoke-master461.log`; correctness only, not throughput evidence. Old cohort hashes/images/corrections preserved.

## Decisions And Rationale
PostgreSQL fsync,synchronous_commit,full_page_writes stay on; five timed-cohort topics write.caching=false. Local RF1,10CPU,stub verifier only. Netty/pool/group delay changed coherently, not isolated causal proof. Distinct instruments can share partition; q1 usedthree actual partitions, q2/q3four. Full-fact oracle decodes independently; checksum envelope validator shared/disclosed. Latest master oracle uses length-framed run/order keys, authoritative trade run, hidden-limit mapping and compatibility tests.

## Blockers And Limitations
Docker guest150GB reached35MB free even with host space. Exact ten oldest fully-audited test topics removed with cleanup proof; best q2 data and all PG volumes preserved,~1.7GB guest free remains inadequate for another sustained run. No global prune/user-data removal. Historical early cached topics and sampled deadline artifacts remain unchanged. q3 storage failure confounds concurrency comparison. No latency p99 measured; sampled gaps/drain are conservative bounds. No RF3/hosted/real-verifier/settlement or latest-master capacity qualification.

## Immediate Next Actions
1. Read guest disk usage/ownership and size required headroom before starting any load; preserve best q2 and all unrelated project data. Do not infer guest space from host df.
2. Restore equal dataset/physical state; repeat bounded controls on integrated latest code. Compare Netty,pool and measured group delay separately. Investigate batched durable PG reservations/index/WAL cost while every202 awaits actual durable intake plus broker ACK; preserve idempotency/order.
3. Re-run hot/balanced/skew/aged300s plus unchanged-candidate fault/recovery/retention/rebalance gates. New state version2 requires fresh namespace, not restoring old version1 state.

## Verification Commands
`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home services/platform-runtime/gradlew -p services/platform-runtime check installDist`
`go -C services/matching-engine test -race ./...`
`go -C services/simulator test -race ./cmd/calcify-load -count=1`
`node --check scripts/dev/calcify-joined-capacity.mjs`
Use LOCAL_CONFIGURATION for fresh smoke/instrument lists. Joined harness requires prebuilt loader/classes and explicit scope; freeze actual settings/hashes before load. No simultaneous build/full oracle during timed observation.

## Delivery Metadata
Date2026-10-02. PR link supplied by final delivery record. Runtime image `reef-platform-runtime:calcify-10k-master461`; matcher `reef-matching-engine:calcify-10k-master461`; these integrate latest contracts. Benchmark poll1000/canonical images represent older frozen baseline only. Owned Compose project `reef-calcify-10k-20261001`; stop owned services at timebox closure while preserving volumes. No task indexers launched. Evidence/report/planning/handoff committed on feature branch; staging binaries omitted. GitHub operations use Keychain-backed credentials with GH_TOKEN/GITHUB_TOKEN unset outside sandbox; never extract tokens.
