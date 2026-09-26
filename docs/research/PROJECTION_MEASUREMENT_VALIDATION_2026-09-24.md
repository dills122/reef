# Projection measurement validation — September 24, 2026

Status: in progress; no sustained-throughput promotion.

## Scope

Base `0dd72ba9`, branch `codex/projection-sustained-10k`, isolated worktree
`/private/tmp/reef-projection-sustained-10k`. Reconciles unmerged `23d3ca58`
measurement implementation and independent-review fixes. Original checkout
and its tooling changes are excluded.

Current promoted evidence remains venue core 10k/s and full projections 2.5k/s
for 5 minutes. Frozen sequence remains 1A → 1B → 1C → isolated capacity → Checkpoint A
→ one-lever tuning → sustained 5k/7.5k/10k, each with at least 20% drain margin.

## Correctness and authority changes

- Separate source timing integrity from retry-stable canonical semantic identity.
- Exact source-member journal reconciliation; complete command-weighted
  residence bounds instead of latest-batch/tail-only subtraction.
- Optional caller counters, coverage and journals all follow instrumentation
  toggle. Dedicated stage work counters remain separate; caller counters do
  not mix order rows with market instruments.
- Committed watermark read precedes one MVCC dirty-queue snapshot. Pending
  deletions remain visible until commit. Existing source lag and active callers
  do not prevent conservative prefix coverage.
- Database generation binds both reads and measured cohort. A restart fails
  authority. This does not rebuild unlogged queues after pre-existing crashes.
- Effective image/environment/resource/mount/database settings fingerprints
  must match. Mount array order normalized; real mount changes still fail.
- Required health counters absent/null/malformed are failures, never zero.

## Verification

- Matching engine `go test ./...`: passed, including lost-publication-response
  retry preserving semantic identity and changing only valid timing proof.
- Kotlin `test installDist`: 598 tests, 0 failures/errors in default environment.
- Separate live PostgreSQL focused tests: passed. Prefix proof covers uncommitted
  lifecycle delete/market enqueue, uncommitted market delete, newer prefix,
  missing/error frontier, and actual concurrent re-dirty blocked on deleted-row
  lock before surviving commit. Both worker toggle suites passed.
- Node source/downstream/residence/A-B/sampler/checker suite: 53 passed.
  Mount normalization plus A/B health suite: 17 passed.
- Two fresh independent reviews completed; findings accepted. Final review
  instance remains reserved for materially changed behavior and final evidence.

## Local evidence and limits

Docker Desktop ARM64, 10 CPU, approximately 12 GB allocated RAM. Separate Compose
project `reef-10k-measurement`, names/volumes/ports.
Performance here is directional, not remote promotion evidence.

Initial 15-second smoke accepted/direct-acked/materialized/projected 37,500 commands,
no failures/final lag. Source and downstream cohort gates passed then-current
checks; p95 lifecycle observation bound 15.2 seconds versus final tail drain 1 second.
It predates database-generation checks and is not current promotion evidence.

First A/B attempt stopped after review showed caller counters enabled in both
arms. Corrected attempt v2 accepted 149,981 commands in 60 seconds, all exact cohort
and generation checks passed, but mount ordering changed runtime hash. Exact
permutation comparison confirmed both hashes represented same mount set. This
sample remains excluded, not retroactively repaired.

A/B v3: fixed 2.5k/s, 256 workers, 60 seconds, fresh volumes per sample; order
instrumented 1/control 1/control 2/instrumented 2/instrumented 3/control 3. Same images,
settings and host except optional instrumentation toggle. Frozen limit 1%; at
least 3 samples per arm. Pass requires complete cohorts, explicit healthy counters,
and stable matching effective-runtime evidence. Completed comparison failed:
control p95 ranged 23.34–137.57 ms; instrumented median p95 157.18 ms versus
control median 24.52 ms. One control differed in projection database resource/mount
fingerprints. Instrumented sample 3 completed only 135,867 of 150,000 demanded
commands (90.578%), despite no request failures. These results cannot isolate
observer cost or support promotion. All three instrumented source/downstream
cohorts reconciled their actual accepted commands; this does not prove demanded
throughput. Subsequent checker fixes reject demand deficit and non-frozen sampler
cadence explicitly.

Hosted validation used isolated DigitalOcean fixed-profile measurement. User
authorized billable disposable tests. Existing module, OpenTofu 1.12.5/provider 2.100.0,
local state owned by this worktree; reviewed plan created only uniquely tagged
`reef-projection-10k-20260924` Droplet and firewall. Dedicated `c-16`, 16 vCPU,
32 GB, `sfo3`, API-listed $0.50/hour. SSH limited to caller address. No existing
account resource changes. Fetch evidence before planned resource cleanup.
Capacity tuning remains behind Checkpoint A.

Local validation runtime image `sha256:b96f12e233143eda23f44735bcbd5a90398dde01b47026c1712b18c5a0d3897a`.
Local validation engine image `sha256:9bc5f2266cf305b46f2a12358911c6ef7f3e42720463853e597171ce6ed907b2`.

## Hosted measurement result and source-binding limit

Hosted A/B v3 comparison **passed optional-instrumentation measurement on its
recorded profile**: three control and three instrumented 2.5k/s, 60-second samples,
frozen 1% limit, all twelve comparison checks true. Effective runtime and workload
matched; source/downstream cohorts and explicit health/demand checks passed.
Control medians: accepted/projected 2499.422/s, HTTP p95 48.330404 ms,
p99 144.357788 ms. Instrumented medians: accepted/projected 2499.763/s,
p95 45.974727 ms, p99 141.906190 ms. Maximum measured degradation is 0% under
the comparison's one-sided definition; this does not establish that
instrumentation improves latency.

Raw evidence fetched to
`/private/tmp/reef-10k-do/remote-evidence/reef-10k-ab-v3/`; `comparison.json`
contains the result, with six reports, source manifest, image identity and runner
configuration retained. This hosted v3 is distinct from failed **local** v3 above.
Hosted setup v1 remains excluded because Node18 was incompatible with repository
JavaScript. Hosted v2 remains excluded after strict runtime comparison rejected
`HostConfig.Binds` ordering between otherwise identical PostgreSQL configurations.
The collector now sorts only bind-array order, retaining every path, option,
value and all other resource fields. No historical sample was repaired/promoted.

Subsequent profile audit found canonical projector/covering markers named
`runtime-normalized-submit`, but market status named
`runtime-normalized-venue-outcomes`. Hosted v3 instrumented sample 1 confirms
that mismatch. The matched A/B result remains evidence about observer overhead
on that recorded profile; it does **not** establish correct public-market
snapshot source/frontier/lag metadata or sustained freshness. Corrected hosted
profile validation remains pending, with fresh public-read evidence required.

Standalone stress defaults now preserve explicit canonical namespace (otherwise
existing `runtime-normalized-submit`) and bind an unset market source to it.
Full-projection preflight rejects explicit mismatch; sustained freshness checker
requires actual canonical, market status and both covering-marker source names
to agree. Twelve targeted source-binding/checker tests passed. Standard
`do-benchmark-host.sh` already aligns the names; this correction addresses
standalone/custom profiles without changing core Compose/Kotlin defaults.

## Task 2 diagnostic status

Isolated capacity diagnostics remain **unaccepted**; **Checkpoint A has not
passed**. Three restored-fixture lifecycle SQL repetitions each drained 123,683
dirty order IDs, with acknowledged-autocommit wall times/rates:

| Repetition | Wall seconds | Dirty orders/s |
| --- | ---: | ---: |
| 1 | 10.018863346 | 12,345 |
| 2 | 11.287567801 | 10,957 |
| 3 | 17.188356941 | 7,196 |

Keep all repetitions; first result alone is not representative capacity evidence.
Fixture contains 150,000 source commands; commands can coalesce into one dirty
row, so these rates are not commands/s or whole-pipeline throughput. Reports'
`pass: true` covers SQL-drain guards only; each explicitly records
`staticBusinessDigestVerifiedByRunner: false`. Separate full source-fact and
watermark hashes match across restored runs and after each drain.

**Rebuild equivalence failed**: incremental and reference each contain 123,683
rows, but 23,339 rows differ (23,339 missing from each comparison direction).
Examples include `CANCELLED` rows with textual `remaining_quantity_units = "0"`
and stale nonzero `remaining_quantity_units_num`. Raw
`lifecycle-reference/result.ndjson` retains failed proof; provenance excludes
only `updated_at`, not business columns. This pre-fix failure is retained; corrected validation follows below. There was
no capacity acceptance, Checkpoint A, tuning, or sustained-rate promotion until
corrected equivalence and remaining validation pass.

Artifacts: `/private/tmp/reef-10k-do/remote-evidence/reef-task2/`, including
`lifecycle-sql-{1,2,3}/report.json`, source hashes and raw reference output.
Initial `pg_restore --clean` encountered an inherited partition constraint;
fixture reset reran by dropping/recreating only isolated database from preserved
dump. Original artifacts remain retained; failed restore is not accepted evidence.

## Corrected Task 2 SQL and repair evidence

Migration `0051_lifecycle_terminal_numeric_parity.sql` corrects terminal numeric
remaining quantity and aligns zero-quantity FILLED guard with full rebuild.
Local PostgreSQL regression failed before (five terminal mismatches), then passed
across 12 lifecycle shapes, explicit stale-row repair, and no-op replay. Proof:
`/private/tmp/reef-lifecycle-parity-proof/{before.xml,after.xml,function.diff}`.
Function replacement alone does not repair persisted rows; recovery remains an
explicit operation. No scheduling or capacity tuning accompanies this correction.

Copied hosted evidence root:
`/private/tmp/reef-10k-do/remote-evidence/reef-task2-corrected/`.
All three corrected lifecycle SQL repetitions restore prepared fixture, apply
0051, and explicitly ANALYZE outside timing. Each drains 123,683 dirty orders in
248 productive calls of at most 500 plus zero confirmation. One persistent psql
session measures each SQL statement through acknowledged autocommit; client wall
includes transport/framing, excludes process setup and pre/post guards.

| Repetition | Client wall seconds | Dirty orders/client-wall second | Dirty orders/productive-SQL second |
| --- | ---: | ---: | ---: |
| 1 | 9.938461 | 12,444.885 | 12,471.788 |
| 2 | 10.835110 | 11,415.020 | 11,438.952 |
| 3 | 9.537236 | 12,968.432 | 12,999.186 |

Each `lifecycle-reference-{1,2,3}/verification.json` passes: 123,683 rows on each
side, zero missing/extra rows, excluding only `updated_at`, rollback confirmed.
Lifecycle queue ends zero; 64 market dirties remain as expected with market
caller disabled. These are dirty-order service rates, not commands/s. Fixture's
150,000 source commands can coalesce; no whole-pipeline headroom follows.

Existing-row repair also passed: `stale-before.txt` records 23,339 bad rows;
explicit re-dirty followed by corrected projection drains 23,339 IDs in
3.237581 client-wall seconds (7,208.777 dirty orders/s; 7,229.855 per productive
SQL second). `recovery-reference/verification.json` compares all 123,683 lifecycle
rows with zero differences. This is repair evidence, not comparable fresh-backlog
capacity or crash recovery. Repair leaves 64 market dirties for downstream work.

Replacement market series `market-restored-{1,2,3}` restores identical prepared
market snapshot before every repetition, with ANALYZE outside timing, three
warmup cycles, then 30 timed cycles. Each cycle separately commits re-dirty of
exactly 64 instruments and calls existing market function. Each repetition has
1,920 timed dirty-instrument operations; both final queues are zero.

| Repetition | Market SQL ms | Re-dirty SQL ms | Timed wall ms | Dirty instruments/market-SQL second | Complete cycles/wall second |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 3,027.723 | 59.719 | 3,093.775 | 634.140 | 9.697 |
| 2 | 2,977.632 | 59.243 | 3,042.374 | 644.808 | 9.861 |
| 3 | 3,029.285 | 62.515 | 3,097.787 | 633.813 | 9.684 |

All three `market-restored-reference-*/verification.json` files pass complete
business-row comparison: 123,683 lifecycle rows and 64 market rows, zero
missing/extra, only `updated_at` excluded. Source binding is explicit
`runtime-normalized-submit`, source max `4222124650669353`. Source-fact hashes
for orders, executions, trades, runtime_events and projection_watermarks in
`source-facts-original.sha256` and `source-facts-final.sha256` match exactly.
These are repeated instrument-refresh units, not unique instruments/s or source
commands/s. Runner's own `staticBusinessDigestVerifiedByRunner: false` remains
accurate; separate reference artifacts supply equivalence proof.

Initial market series remains incomplete and diagnostic. Samples 1/2 produced
186.346 / 175.871 dirty instruments per market-SQL second, and 2.892 / 2.730
complete cycles per wall second. Sample 2 reference failed NOWAIT lock acquisition
on `runtime.order_lifecycle_state`; holder was not captured. Maintenance is a
hypothesis, not proven cause. `market-original-series-disposition.txt` and
`market-reference-2/stderr.log` retain failure. Rollback preserves logical state
but can leave physical dead tuples and other work; replacement series restores
identical snapshot before each repetition so reference execution does not carry
that physical state into next sample. Rate differences do not prove a causal
speedup or production tuning result.

Corrected worker baseline now passes all three repetitions on same 123,683-order
backlog, lifecycle batch 500/poll interval 250 ms, one caller, market disabled:

| Repetition | Startup-through-empty seconds | Dirty orders/startup-inclusive wall second |
| --- | ---: | ---: |
| 1 | 75.327634 | 1,641.934 |
| 2 | 76.400570 | 1,618.875 |
| 3 | 76.470144 | 1,617.402 |

These include container/JVM startup, worker polling, and one-second completion
observation; they are not pure SQL service rates or source commands/s. Final
worker metrics report 123,683 processed rows, zero failures, one caller,
max concurrency one, lifecycle queue zero and market queue64. Each
`lifecycle-worker-*/reference/verification.json` passes all 123,683 business rows
with zero missing/extra and only `updated_at` excluded.

Public-read validation also passes after timing completed. API changed from
`noop` persistence to PostgreSQL plus participant-scoped static-token auth for
this separate validation. `public-read/api-profile.json` records actual
`postgres` persistence, canonical host `postgres`, projection host
`projection-postgres`, and matching intended effective environment. This does
not establish that measured API profile served database-backed reads. Successful
script restores original API configuration and stops it; `restore-api.log`
records recreate/start/stop sequence.

`public-read/summary.json` passes 64 snapshots, three depth reads, three tapes,
representative own-current/history for one participant/instrument, and three
auth negatives (missing/wrong token401, cross-participant403). Scope is public
responses against previously verified database, **not whole-own-order API
coverage**. `db.json` and `after/db.json` are byte-identical. Separate
`public-read/db-reference/result.ndjson` and verification confirm full lifecycle
123,683 and market64 rows equal rebuild, zero differences, rollback complete.

Actual copied reference count is **17 passing stage comparisons across12
nonempty verification files**, not18: lifecycleSQL3 + worker3 + initialmarket
sample1 two stages + restoredmarket6 + publicDB2 + repair1. Initialmarket sample2
lock refusal produces no successful reference result and is not counted. These
comparisons do not represent independent fresh fixtures; count is an audit
inventory, not a confidence multiplier.

Corrected-source-binding A/B v4 has started six samples in same frozen order
I1/C1/C2/I2/I3/C3; no comparison pass claimed. Crash recovery, full
isolated-capacity decision, corrected-profile measurement/freshness, and sustained
validation remain pending. **Checkpoint A has not passed; no throughput promotion.**

## Frozen sustained freshness acceptance

Before capacity tuning, freeze complete command-weighted source-work-finished
→ canonical commit observation, lifecycle covering observation and market covering
observation at p95 ≤5 seconds, p99 ≤10 seconds and maximum ≤30 seconds each.
These are conservative observation bounds for asynchronous control-room views,
not exact per-command commit times or exchange market-feed latency promises.
One-second diagnostic cadence and transient batching allow short bursts; a
five-minute run must not accumulate minutes of unobserved downstream work.
Do not relax thresholds to fit candidate results.

Each 5k/7.5k/10k five-minute gate also requires ≥99% scheduled-demand completion,
exact accepted/direct-acked/materialized/projected reconciliation, zero failures,
retries and deadlocks, empty final dirty queues, idle maintainers, stable generation
and complete sampler evidence. Keep HTTP latency separate. Same-shape fixed-backlog
end-to-end drain must demonstrate at least 20% headroom; warm/aged public reads and
explicit crash/rebuild equivalence remain required.

## Artifact locations

- `/private/tmp/reef-10k-measurement-smoke/`: initial diagnostic report.
- `/private/tmp/reef-10k-ab/`: interrupted first control.
- `/private/tmp/reef-10k-ab-v2/`: rejected mount-order sample.
- `/private/tmp/reef-10k-ab-v3/`: completed failed comparison;
  `source-manifest.json` records 63 changed/new source hashes for uncommitted
  candidate. Base Git HEAD alone does not identify tested code.
- `/private/tmp/reef-10k-do/remote-evidence/reef-10k-ab-v3/`: passed hosted
  observer-overhead comparison on recorded profile; later source-binding
  mismatch leaves public-market metadata freshness unvalidated.
- `/private/tmp/reef-10k-local-env.sh`: isolated profile/ports/settings.
- `/private/tmp/reef-10k-ab-v3.sh`: deterministic sample/reset orchestration.
- `/private/tmp/reef-projection-prefix-full-build.log`: full Kotlin verification.
- `/private/tmp/reef-matching-all-tests.log`: complete Go verification.
- `.planning/sustained-10k/`: execution/review ledger.

Generation checks establish continuity during a measured cohort only. Fresh
reset state is an explicit precondition. Aged-state and crash/rebuild proof,
corrected-profile hosted validation against frozen freshness thresholds, complete
isolated-capacity evidence and sustained promotion remain outstanding despite
scoped hosted observer-overhead pass. Checkpoint A remains pending.

## Corrected source-binding A/B v4 result

Complete three-control/three-instrumented comparison, fixed I1/C1/C2/I2/I3/C3 order, same images/resources and corrected `runtime-normalized-submit` source binding. All eleven configuration, health and source-authority checks pass. Frozen1% overhead gate **fails**: control/instrumented median p95 45.748048/48.068383ms (+5.071987%), p99 142.403820/153.052747ms (+7.477978%). Accepted/projected median2499.571962/2499.607538commands/s. Raw retained locally at `/private/tmp/reef-10k-do/remote-evidence/reef-10k-ab-v4/`; `comparison.json` is authoritative.

CheckpointA remains blocked. v3 pass cannot replace this corrected-profile failure. Six samples establish the gate result, not causal attribution to a specific observer component. Measurement-only cost diagnosis precedes a new matched series; no lifecycle scheduler, business SQL capacity, batch, pool or topology tuning yet. Corrected Task2 rebuild/public-read proofs remain valid.

## Journal allocation candidate and resumed validation

Typed private journal records now retain owned primitive sequence arrays and Instant values. Exact checkpoint maps, decimal membership strings and timestamp formatting are deferred to dedicated before/after snapshots. Recording remains synchronous after canonical commit; validation, clock checks, bounded capacity, deduplication and invalid/drop counters remain unchanged. Returned snapshots are detached from retained state and input list mutation. No asynchronous evidence queue or dropped correctness guard.

Bounded allocation diagnostic (10,000 prebuilt batches of18 members; five warmups and five measured rounds, Java21): median record allocations59,980,128→32,613,128bytes (-45.63%); checkpoint allocations11,960,968→29,320,968bytes. Work shifts to checkpoints; CPU timing was noisy, not evidence of speedup. Raw diagnostic retained under durable checkpoint `reef-journal-allocation/`. Two new schema/mutation regressions passed both representations, as expected for a representation refactor.

On resumed execution, full Kotlin suite601tests passed with no failures/errors, and installDist succeeded.31 focused Node measurement/headroom/reference tests passed. Database integration tests requiring explicit environment are not newly claimed as executed by this generic suite. Native images rebuilt on fresh same-size host; matched A/Bv5 fixed three-per-arm protocol is running. No overhead, CheckpointA or sustained-throughput pass inferred until complete comparison.

## Corrected A/B v5 completion and Task3

Matched v5 series completed in frozen I1/C1/C2/I2/I3/C3 order on fresh same-size host. All12 comparison checks pass, including exact cohort authority, corrected market source binding, runtime configuration and frozen1% perturbation limit. Control/instrumented medians: accepted and projected2499.561531/2499.744762commands/s, intake p9523.995035/23.740805ms and p99110.295417/110.855716ms. Largest positive perturbation0.507998%. This supersedes earlier pending CheckpointA status; v4 failure remains valid historical evidence. Cross-host v4/v5 differences do not establish causal speedup.

CheckpointA passes with corrected Task2 full-state/source/public-read proofs. Instrumented v5 lifecycle and market p95 covering bounds remain roughly62–82seconds, above frozen5seconds. No sustained5k/7.5k/10k or freshness promotion follows from overhead pass. Full v5 artifacts: `/private/tmp/reef-10k-resume/remote-evidence/reef-10k-ab-v5/`.

Task3 changes lifecycle scheduling only: positive processed rows continue immediately; empty/error calls retain configured backoff. Per-run cancellation prevents overlap on stop/restart, and interrupted persistence preserves thread interruption. Three regressions reproduced before fix;11 focused worker tests and full609-test Kotlin suite pass with no failures/errors; installDist passes.79 focused report/cohort/headroom/reference tests also pass. Generic Kotlin suite does not claim fresh execution of environment-gated PostgreSQL integration tests. Remote productive-worker comparison and crash/rebuild proof remain pending.

## Live-state recovery preflight failure

Current-host synthetic C3 fixture failed full-state reference before crash injection: lifecycle123675rows/382mismatches each direction; market64rows/39mismatches. Aggregate field drift: filled323, remaining355,status337,last_event_at60; marketbid21/ask25. Exact149976-command cohort and empty dirty queues did not imply business equality. Rollback-only diagnostic reproduced failure. Earlier CheckpointA measurement/isolated results remain historical evidence; online correctness now blocks all capacity promotion. Lost-invalidation race investigation and deterministic PostgreSQL tests underway; no recovery pass inferred.

## Deterministic dirty-invalidation regression evidence

Four live PostgreSQL tests cover lifecycle and market invalidations in both the
claim-snapshot gap and recompute/delete window. Pre-0052 functions fail all four
assertions. Producer conflict updates alone pass the two delete-window tests but
fail both stale-snapshot comparisons. Complete 0052 passes all four. Tests use
actual stored functions and advisory-lock/backend-lock barriers, compare full
business rows excluding only updated_at, and preserve failing proof. Logs/XML:
`/private/tmp/reef-dirty-projection-proof/`. Migration metadata suite: 25 passed.

Recovery-v3 source harness passes 11 local checks and verifies migration ledger
and actual function definitions before/after restore and recovery. Cloud upload
of newly added SQL was rejected by automatic review; expanded source-deployment
approval requested. No migration, repair, new pin, or crash has run remotely yet.

- Final full Kotlin suite with live PostgreSQL enabled: 613 tests, zero failures/errors, installDist passed. Database initialized with repository schema-init SQL and 96 checksum-ledger migrations. Earlier missing-schema run retained as setup failure. One pre-existing command-log JSON whitespace assertion corrected to semantic JsonCodec comparison; no production command-log changes. Log /private/tmp/reef-10k-resume/dirty-fix-kotlin-migrated-verified.log.
