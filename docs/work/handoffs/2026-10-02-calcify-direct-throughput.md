# Handoff: Calcify direct-path sustained10k

## Objective And Boundary

Fresh authorized window October2 05:54:53–07:54:53UTC. Goal achieved for explicit standing-liquidity workload:300s10,425.19verified matched trades/s and10,440.92resolved contexts/s, all3,142,846exact. Primary path unit is completed matched trades/s through Calcify; separate durable intake counter10,475.59order commands/s. No SQL receipts/final financial settlement claim. Critical matching-acceptance timing represented by conservative batch-entry->verified visibility p99 upper433ms, not exact per-command latency.

## Canonical Sources

[Result, scope, comparisons, limitations](../../research/CALCIFY_DIRECT_THROUGHPUT_2026-10-02.md), [D7 raw qualification](../../evidence/calcify-direct-throughput-2026-10-02/d7-300s-standing-liquidity/results.json), [actual settings](../../evidence/calcify-direct-throughput-2026-10-02/d7-300s-standing-liquidity/actual-profile.json), [class/source manifest](../../evidence/calcify-direct-throughput-2026-10-02/d7-300s-standing-liquidity/source-class-manifest.json), [baseline ledger](../../THROUGHPUT_BASELINES.md), [local configuration](../../LOCAL_CONFIGURATION.md). Read AGENTS/AI_CONTEXT first; handoff does not replace canonical truth.

## Current Repository State

Worktree `/Users/dsteele/.codex/worktrees/calcify-phase2-experiments/reef`; local branch `codex/calcify-direct-throughput`, based080f0eda0ee1798d744669f88125d98056890f32; retained candidate/evidence commitddc7e8b9494b8bda2063241495d210d65b51604d. Delivered by fast-forward append to existing draft PR462 branch `codex/calcify-10k-timebox`; no force push. D7 source manifest records080f0eda plus explicit dirty overlay hashes; final delivery commit contains candidate. Primary `/Users/dsteele/repos/reef` unrelated AGENTS.md/CLAUDE.md/.mcp.json/JSON research/planning edits untouched. Local uncommitted generated loader/image staging and .planning/calcify-direct-throughput/progress.md deliberately excluded from delivery; do not accidentally stage binaries/jars.

## Completed Work And Evidence

21owned Reef containers/107owned test volumes removed before fresh campaign; raw full-env Docker inventory not committed. D1 incorrect broker topic-key diagnostic explicitly nonqualifying. D3/D4 durable paired controls identify extractor commits/source lag as bottleneck. D5 bounded-poll transaction lifts comparable paired throughput~2.1k->5.2k verified trades/s. D6 explicitly changes workload to64seed makers +1SELL/1trade and fresh3,906terminal/book cap;60s diagnostic only. D7 same candidate controls, fresh300s namespace: zero HTTP failures/retries,7,154scheduler jobs dropped before dispatch, final source3,142,910accepted orders including64seeds; verified/resolved3,142,846 exact/full-fact/ordered/unique. Final drain<=1.21s cohort upper bound. No builds/oracle decode overlapped timed D7.

Extractor plan keeps valid partition prefixes and source offsets inside one Kafka transaction; poison leaves its own suffix blocked, healthy partitions continue, zero-trade checkpoints preserved. Optional matcher producer4MiB cap accommodates rich canonical batches; default1MiB unchanged. Loader aggressor mode and strict fresh-maker observer/runner tests included. No new JSON dependency.

## Decisions And Rationale

C5 fast ingress controls recovered from actual prior stress harness, rather than SQL smoke. In-memory intake/idempotency with broker-durable202, Netty512MiB/2CPU,16lanes,64balanced instruments,1,024workers, matching batch500. Three Calcify workers only; no SQL receipt worker or legacy materializer. PostgreSQL controls startup/rebalance generation/topic identity, not per-order hot path. All five D7 topics actual write.caching=false, local RF1. Resolver managedRocksDB,2threads, no standbys. CreatedAt bounds matching entry but lacks integrity checksum; workFinishedAt timing checksum checked.

Only benchmark retention3,906per-book changed, fresh source/state required. Existing terminal lookup/reuse horizon remains explicit. D7 actual runtime image sha256:212c08152521449235b365368902f8bfacf4d8310056f033701d0ea97dd9f2f3; matcher sha256:720663db604d132a41453aaa81dedd28edbbda050b709b3ce036265619bf03f6.

## Blockers And Limitations

No blocker to scoped result. First pushed head failed Node script-surface gate because new runner test lacked CI wiring; follow-up adds test to Node coverage job. Local red/green gate evidence retained; qualification production/classes unchanged. One local successful300s record, stub verifier and standing-maker state locality only. RF3/hosted/fault-at10k/aged/skew/paired sustained qualification remains. Expanded output fanout bounds deserve separate failure/heap checks; poll100 is source-record bound. Resource monitor missed final~30s load, while live output observation covered entire300s. Max wall-step~4.46ms limits exact wall latency precision. Independent full-fact decoder shares canonical checksum-envelope validator, disclosed.

Owned seven-container Compose project `reef-calcify-direct-20261002` STOPPED, volumes preserved. D7 source generation8, source UUID2y1C_MtOThuxfpaEBPr7xQ; topics REEF_D7_COMMANDS/EVENTS/COMMITMENTS/VERIFIED/RESOLVED. Application reef-d7-g8; managed state retained. Other previously paused reef-matching-443-445-postgres absent at closure, nothing to resume. [Closure manifest](../../evidence/calcify-direct-throughput-2026-10-02/d7-300s-standing-liquidity/closure.json). Existing stopped containers use fixed names; inspect ownership before starting another stack. Do not globally prune preserved best source/state.

## Immediate Next Actions

1. First run git status/log in stated worktree and read canonical report. Verify delivery changes/new commits; preserve unrelated edits and excluded generated staging. Inspect Docker labels/guest disk before restart; never infer source UUID or actual durability from topic name/requested settings.
2. If reproducing record, repeat unchanged candidate with fresh ID/generation rather than reusing D7. Build images/test classes/loader before load; freeze image/source/class hashes and actual broker settings. Use preflight64orders/0trades and ordered full-fact oracle. Stop on failed gate.
3. Next separate scope: paired/hot/skew/aged300s, then abort/rebalance/poison and state-loss tests under load, then hosted/RF3. Do not reopen JSON swap or SQL ingress based solely on old diagnostics.

## Verification Commands

From repository root:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home services/platform-runtime/gradlew -p services/platform-runtime check installDist
go -C services/matching-engine test -race ./internal/streamdirect -count=1
go -C services/simulator test -race ./cmd/calcify-load -count=1
node --test scripts/dev/calcify-direct-capacity.test.mjs
node --check scripts/dev/calcify-direct-bootstrap.mjs
git diff --check
```

Reproduction example after preparing fresh images, test classes and loader (intentionally distinct namespace/generation; inspect registry before choosing9):

```sh
export CALCIFY_DIRECT_SMOKE_ID=d8
export CALCIFY_DIRECT_GENERATION=9
export CALCIFY_DIRECT_SECONDS=300
export CALCIFY_DIRECT_PAIRS_PER_SECOND=10500
export CALCIFY_DIRECT_WORKERS=1024
export CALCIFY_DIRECT_WORKLOAD_MODE=aggressor
export CALCIFY_DIRECT_TERMINAL_PER_BOOK=3906
export CALCIFY_DIRECT_IMAGE=reef-platform-runtime:calcify-direct-pollbatch
export CALCIFY_DIRECT_MATCHING_IMAGE=reef-matching-engine:calcify-direct-4m
node scripts/dev/calcify-direct-bootstrap.mjs
```

Before runner, inspect Docker ownership/resources and exact allowlisted environment settings. Confirm commands/events/commitments/verified/resolved each16partitions/RF1/write.caching=false; source max.message.bytes4194304; broker write_caching_defaultfalse. Do not start load if identity/durability/role checks fail. Capture settings/hashes to fresh run evidence. Existing images reproduce local snapshot only if their IDs match manifest; otherwise rebuild and label new candidate.

```sh
export CALCIFY_DIRECT_PREFLIGHT_ORDERS=64
export CALCIFY_DIRECT_PREFLIGHT_CONTEXTS=0
export CALCIFY_DIRECT_NETWORK=reef-calcify-direct-20261002_default
export CALCIFY_DIRECT_LOAD_BINARY=.planning/calcify-10k-timebox/calcify-load
export CALCIFY_DIRECT_OUT_DIR=/private/tmp/calcify-direct-d8-run
export CALCIFY_DIRECT_SCOPE='repeat D7 controls; local RF1; Phase1 stub; standing makers; terminal3906/book'
export CALCIFY_DIRECT_INSTRUMENT_IDS="$(node -e 'const fs=require("node:fs");console.log(JSON.parse(fs.readFileSync("/private/tmp/calcify-direct-d8/setup.json")).instruments.join(","))')"
node scripts/dev/calcify-direct-capacity.mjs
```

## Delivery Metadata

Date2026-10-02. Existing [draft PR462](https://github.com/dills122/reef/pull/462) updated around final fix and sustained record. Candidate source/classes unchanged during D7; qualification raw hash files stay historical. New documentation/closure do not rewrite old freezes. Verification logs under campaign evidence/tests. Keychain GitHub operations outside sandbox with GH_TOKEN/GITHUB_TOKEN unset; never extract credentials. Timebox goal marked complete only after report/evidence/PR packaging and resource closure.
