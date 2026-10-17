# Calcify capacity runtime: how to run the capacity finance service

Status: working description of what is built (2026-10-14). The capacity profile is separate from the finite P3 profile and does not use its binding; binding v3 will replace the plan file below with a registered, signed opening. No capacity claim: see CAL-FIN-P16 in the throughput ledger for what has been measured.

## What it is
One process (`CALCIFY_CAPACITY_PLAN_FILE` set; never together with `CALCIFY_STAGE`, `CALCIFY_FINITE_BINDING_DIGEST` or `CALCIFY_FINANCE_BINDING_DIGEST`) running:
- a Kafka Streams app, exactly-once v2, **one stream thread per lane** (task N is lane N): reads partition N of the capture and control topics, keeps lane N's ledger in a RocksDB store with a changelog, writes partition N of the decisions topic;
- one **projection** per lane (own binding digest, own owner epoch, own tailing thread) into `boundary.calcify_finance_*`.

A lane is a set of instruments. With the production source the lanes are the **venue source partitions**: the Phase 2 resolver publishes each resolved match context to the partition of the venue event it came from, and the platform chooses that partition with `StreamCommandEnvelopeBuilder.partition(run, session, instrument, partitions)` (a SHA-256 hash of the three; not the legacy `PartitionLaneHash`, which an earlier version of this service assumed and a real-stack run disproved). Set `laneRouting` to `stream-command-partition-v1` and the service refuses a plan whose instrument sets differ from that. Because the hash includes the run and session, the instrument sets of a plan are specific to its run and session.

Every executed trade becomes two decisions in its lane's log in the default `two-step` decision mode: the capture and its first settlement attempt (`<executionId>/1`); in the opt-in `capture-settle-v1` mode it becomes **one** decision (capture and first settlement together; see CALCIFY_CAPACITY_PROFILE.md decision 7). A failed attempt (insufficient cash or shares) stays pending and is repaired by an operator control with attempt 2 or later. Cash is held **per lane**: fund each participant's cash in each lane with `CapacityControlWriter.fundCash`, and shares of an instrument in the lane that trades it (`fundSecurity`). There is no automatic cash rebalance between lanes yet.

`topics.capture` can be the resolver's own output topic (`CALCIFY_RESOLVED_TOPIC`, `REEF_MATCH_CONTEXT_RESOLVED_V1` in the shared profile): the service reads the resolved contexts where they are written, with no copy stage, provided the topic has exactly `lanes` partitions. `CapacityResolvedFixtureTest` runs the decoder and ledger over the 130 resolved contexts the repository's resolver produces from its recorded venue-event fixture (all decode, all book and settle, cash conserved); the full engine-to-Postgres run through the compose stack (the generic full-path smoke with `DEV_CALCIFY_PHASE2=1`, archived to Reef Records on 2026-10-16; the supported way to run the whole path is `make calcify-fullchain-soak`, below) was done by running this service in a container on the stack's network (CAL-FIN-P18: 1, 3,001 and 20,001 real trades, restarts and a SIGKILL mid-load, all exact).

## The plan file (`calcify-capacity-plan-v1`)
Strict JSON, every field required, numbers as decimal text:

```json
{"schema":"calcify-capacity-plan-v1","planId":"arena-1","run":"run-1","session":"session-1","cashAsset":"USD","openingAccount":"opening",
 "laneRouting":"stream-command-partition-v1","laneInstruments":[["INS0","INS4"],["INS1","INS2"],["INS3"]],
 "maxObligations":"4000000","maxCaptures":"4000000","maxSettleAttempts":"8000000","maxControls":"100000","sequenceBound":"8000000","autoSettle":true,
 "applicationId":"capacity-arena-1","topics":{"capture":"CAPACITY_CAPTURE","control":"CAPACITY_CONTROL","decisions":"CAPACITY_DECISIONS"},
 "captureSource":{"kind":"resolved-context-v1","policyVersion":"1"}}
```

`autoSettle` (required boolean): the instant profile. `true` makes the decoder follow every captured execution with its first settlement attempt (operator authority, attempt 1) in the same consumed record; `false` emits the capture only and every settlement is an operator control (netting or deferred settlement later). How many ledger inputs and decisions the `true` case is depends on `decisionMode`.

`decisionMode` is the one **optional** field: `"two-step"` (the default when absent; spelling it out does not change any digest) or `"capture-settle-v1"` (one `CaptureSettle` input and one decision per trade; requires `autoSettle` true; the sequence counts trades, so the same `sequenceBound` covers twice the slice). The mode is bound into the lane's configuration digest and so into its binding digest, genesis chain and store: a store, log or projection made under one mode is refused by the other. Unknown values are refused.

`captureSource` may instead be `{"kind":"finite-lifecycle-capture-v1","bindingDigest":"<64 hex>","verifyContentDigest":true}` (the finite capture envelope; any laneRouting). The lane binding digest commits to the plan id, run, session, lane count and index, sequence bound and the lane's configuration, so a store, log or projection made for another lane or plan is refused. The budgets are per lane and per slice (a slice is about five minutes of the contract rate; rotation to the next slice is not built).

## Environment
| Variable | Meaning |
| --- | --- |
| `CALCIFY_CAPACITY_PLAN_FILE` | the plan file; a private regular file (owner read/write only) |
| `CALCIFY_CAPACITY_KAFKA_BOOTSTRAP_SERVERS`, `_SECURITY_PROTOCOL` (SASL_PLAINTEXT), `_SASL_MECHANISM` (PLAIN) | broker connection |
| `CALCIFY_CAPACITY_KAFKA_USERNAME` + `CALCIFY_CAPACITY_KAFKA_PASSWORD_FILE` | the `finance` Streams principal |
| `CALCIFY_CAPACITY_OBSERVER_KAFKA_USERNAME` + `CALCIFY_CAPACITY_OBSERVER_KAFKA_PASSWORD_FILE` | the read-only `observer` principal (decision-log heads, projection tails); must differ from the finance principal |
| `CALCIFY_CAPACITY_STATE_DIRECTORY` | owner-only directory for the RocksDB state |
| `CALCIFY_CAPACITY_POSTGRES_JDBC_URL` (no password in it), `_USER`, `_PASSWORD_FILE` | the projector role (SELECT, INSERT, UPDATE on `boundary.calcify_finance_*`) |
| optional `CALCIFY_CAPACITY_STREAM_THREADS` (lanes), `_PROJECTION_INTERVAL_MS` (100), `_PROJECTION_CHUNK` (2000 decisions per transaction), `_SESSION_TIMEOUT_MS` (6000 to 45000) | tuning |
| optional `CALCIFY_CAPACITY_EVICT_SETTLED` (unset or `true`: on; `false`: off; anything else is refused) | each lane keeps only open work in memory and reads settled trades and old actions back from its RocksDB store through an in-heap filter (docs/work/CALCIFY_BOUNDED_MEMORY_DESIGN.md); decisions, chains, commitments and stored bytes are identical either way, so switching it needs no migration |

Inline secrets (a variable without `_FILE`) are refused.

For a local development stack whose broker has no authentication (the compose Redpanda), set `CALCIFY_CAPACITY_KAFKA_SECURITY_PROTOCOL=PLAINTEXT` together with `CALCIFY_CAPACITY_DEV_PLAINTEXT=true`; no Kafka principals or passwords are then read. The flag exists so PLAINTEXT cannot be chosen by accident; do not use it anywhere else.

## Provision before the first start
- Topics `capture`, `control`, `decisions`: **at least `lanes` partitions each, the same count**, retention unlimited (`retention.ms=-1`, `retention.bytes=-1`) for the run, replication as the cluster requires. The Streams changelog `<applicationId>-capacity-finance-changelog` needs `lanes` partitions and compaction; provision it up front as the finite profile does.
- Migrations 0014 and 0016 (`boundary.calcify_finance_*`, the per-binding `sequence_bound`).
- Broker grants as in `docs/work/CALCIFY_FINITE_P3_BINDING_V2_SPEC.md` section 9 for the two principals, over these topic names.

## Start, restart and failure
- **Start:** reads the last read-committed record of each lane's decision log, claims each lane's projection (raising its owner epoch, which fences an older instance), opens each checkpoint with the plan's sequence bound, starts the Streams app. Each lane restores its store (the entities must hash to the head decision's committed state) and must be **exactly the head of its published log**; otherwise the lane's task fails and the process exits non-zero with nothing wrong silently accepted.
- **Restart after a clean stop or a crash, or with the local state wiped:** the store is rebuilt from the changelog; redelivered records are absorbed by the ledger's action keys. Verified by `CapacityEndToEndTest` for both capture sources.
- **An unusable capture record or member** (wrong lane, wrong run, inconsistent acceptances, malformed amounts, unparseable): the lane **stops** and the process exits non-zero; nothing is skipped, because a skipped record is an executed trade missing from the books. It stops at the same record until the cause is resolved. An unusable control record is counted and ignored.
- **A captured input the ledger refuses** (an account that was never funded, conflicting economics for an execution, an exhausted budget) also **stops the lane**: it is an executed trade missing from the books. The only refusals that do not are the ones that mean the ledger already holds the input (a redelivered capture or settlement). To recover from an unfunded account, fund it with the control writer and restart: **buffered controls are always processed before buffered captures** (CONTROL records are stamped 0 and CAPTURE records at least 1, because the resolver writes its records with timestamp 0), so after the restart the funding goes first and the trade then books.
- **Projection failures:** transient database or broker errors are retried with backoff; a stale owner epoch, a projection that is not a prefix of the log, or ten consecutive failures stop the process.
- **Health:** the process supervises itself and exits 1 when the Streams app or any projection dies, so the supervisor restarts it.

## Rollout of `capture-settle-v1`
Additive wire change (`FinanceInputV1.capture_settle = 5`); a consumer that does not know it fails loudly on decode (unknown field), so **upgrade every consumer first**: the projector, replay and evidence tools, and any reader of the decisions topic, in every environment that will read a combined log. Only then deploy the producer (the capacity service with `"decisionMode":"capture-settle-v1"`). Use a **new plan id (new binding label)**, new topics (or a fresh run) and a fresh state directory: a live two-step lane is never converted, and an existing two-step binding, its store, log and projection are unaffected and remain valid (their digests do not change). Rolling back means going back to a two-step plan with its own binding; a combined log cannot be continued by a two-step service. The finite profile refuses the new input, and a `CaptureSettle` body on the CONTROL topic is an unusable control record.

## Full-chain paced run
`make calcify-fullchain-soak` (`scripts/dev/calcify-fullchain-soak.mjs`) runs the whole path on one host on the validated 10k ingress profile (October 2, D7): Netty HTTP, in-memory bounded intake with a durable Redpanda acknowledgement, **16 command partitions and 16 direct Go matching lanes**, the Phase 1 sidecars, the Phase 2 resolver, the capacity service in a container (16 finance lanes) and its projection into Postgres, driven by `calcify-load` at a paced rate. The stack and its reference data come from `scripts/dev/calcify-direct-bootstrap.mjs`; the soak builds the images from the checkout, picks instruments so every lane has one (the platform's routing hash is recomputed in the script and compared with the Kotlin `CapacityPlanTool` output), funds the load accounts **before** the service starts (a lane that meets an unfunded account stops by design), samples every stage and the containers' CPU every 10 s into `samples.csv`, drains, and requires the projection to hold exactly the expected decisions (`(trades + 1 preflight) x decisions per trade + funding records`). It asserts exactness only: no throughput or latency threshold, so a passing run is not a capacity claim.

The script reads these knobs from the environment (`make` passes command-line variables through as environment). Defaults are in the script header.

| Knob | Meaning |
| --- | --- |
| `FC_ID` | run id (default `fc<time>`); names the compose project, topics and plan. **Use a fresh id for every run**: the source-generation row and the topic names are fixed per id, so a reused id contaminates or fails the run |
| `FC_RATE`, `FC_SECONDS` | booked trades/s offered (one timed SELL each) and duration |
| `FC_MODE` | `capture-settle-v1` (default, one decision per trade) or `two-step` (two decisions per trade; changes the expected decision count) |
| `FC_EVICT` | `CALCIFY_CAPACITY_EVICT_SETTLED`, default `true` |
| `FC_QUANTITY` | default 1 (at the load price int64 cash lasts about 600k trades at quantity 100) |
| `FC_WORKERS` | load-generator workers, default 1024 |
| `FC_ENGINE_BATCH` | matching direct-stream batch, default the bootstrap's 500; if raised, raise the message limits too |
| `FC_TERMINAL_PER_BOOK` | matching terminal-order retention per book, default **3906** in the soak (the bootstrap's own default is 250000). The soak profile is deliberately narrower; see [Local Run Profiles](../LOCAL_RUN_PROFILES.md#terminal-order-retention-two-regimes) |
| `FC_TMPFS`, `FC_TMPFS_BROKER_GB` / `_BOUNDARY_GB` / `_OTHER_GB` | `true` puts the broker and database data volumes on tmpfs (limits 24 / 40 / 6 GB; they count against host RAM). Not durable: no recovery or durability claim follows |
| `FC_CAPACITY_JAVA_OPTS` | capacity service JVM options, default `-Xmx8g -XX:+UseParallelGC` |
| `FC_SAMPLE_SECONDS`, `FC_DRAIN_SECONDS`, `FC_OUT_DIR`, `FC_TEARDOWN`, `FC_SKIP_BUILD` | sampling interval, drain bound, output directory, teardown, reuse of already built `latest` images (stale-code risk after code changes) |

There is no `FC_INSTRUMENTS` knob: the bootstrap fixes 64 instruments over the 16 lanes. The tmpfs volumes are created by the script itself (`docker volume create` with tmpfs options, named `<project>_reef-redpanda-data`, `_reef-postgres-data`, `_reef-boundary-postgres-data`); `compose.fullchain-tmpfs.yml` at the repository root declares the same volumes but no script uses it.

The droplet matrices `fullchain` and `fullchainsmoke` of `scripts/dev/calcify-financial/droplet-chain-run.sh` run the soak as a rate ladder; dedicated-host rules (owner approval and cost first, host class) are in [How to benchmark correctly](../PERFORMANCE_LEARNINGS.md#how-to-benchmark-correctly). The 15 minute component soak acceptance (10k trades/s, 9M trades, integrity ok, booking lag cap 1.6 s) is [D-059](../DECISIONS.md#d-059-bounded-capacity-lane-memory); the soak harness fail rules are in [CALCIFY_SOAK_HARNESS.md](CALCIFY_SOAK_HARNESS.md).

Limits of the workload: two accounts (one buyer, one seller) trade every instrument; paired mode sends two orders per trade, so 10k trades/s is 20k orders/s through the intake (the soak's aggressor mode sends one, so the two are not comparable; report orders/s and trades/s separately).

**Withdrawn claim (2026-10-17 correction).** An earlier version of this section described four stream-direct partitions and four lanes and said that 3,000 trades/s offered saturated "the Postgres-backed intake at about 2.9k trades/s". That ran the per-order PostgreSQL intake on the JDK HTTP server with four partitions, which is not the validated profile; the "2.9k trades/s intake wall" is **withdrawn**. See "CAL-J10K configuration correction" and the October 16 archive note in [THROUGHPUT_BASELINES.md](../THROUGHPUT_BASELINES.md#cal-j10k-configuration-correction--october2). The first local runs (laptop) on that profile did show 1,000 trades/s for 60 s exact end to end; measured full-chain results belong in the ledger, not in this runbook.

## Not built yet
Binding v3 and signed plan identity; rotation to the next slice; cash rebalance between lanes; the hot-instrument options beyond the measured skew budget (see CALCIFY_HOT_INSTRUMENT_DESIGN.md); arena credit; the projection tail resuming from its stored position (a restart currently re-reads each lane's decision log from the start, and applies only what the checkpoint lacks); reads, visibility and settlement-latency measurements; a dedicated-host run; metrics export (the launcher keeps read and apply time counters and the processor counts refusals and unusable controls, none exported yet).
