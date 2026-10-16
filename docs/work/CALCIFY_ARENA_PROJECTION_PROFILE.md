# Calcify arena projection profile: a slim Postgres write path for disposable runs

Date: 2026-10-16. Status: **design note, owner approved the direction in chat on 2026-10-16 ("yes" to the slim profile as a contract change); nothing is built; every number below is labelled measured or estimated.**

## Why this exists
The 2026-10-16 droplet runs put the full chain at 10,000 booked trades/s for 60 s (exact accounting) and for 5 minutes (99.5 percent delivered, load average 17 to 25 on 16 vCPUs, the load generator on the same box). In those runs the boundary Postgres, the read projection of the finance decision log, was the largest CPU consumer (about 2.5 cores on average, 3 at peak, of 16) and the largest data consumer (about 4.3 KB per trade including WAL, measured as a 12 GB tmpfs that filled at about 2.8 million projected trades).

Arena pods are disposable: provisioned for one game, exported, destroyed. Nothing in a pod needs the per-trade history of the projection while the game runs, and the full history is already in the durable decisions topic. This note defines the **arena-lite projection profile**: what the pod's Postgres keeps live, what moves to an end-of-run export, and what changes at the API.

## What the projection writes today (read from the code, not measured)
`FinanceProjector.apply` writes, per applied decision, with one set-based statement per table per batch:

| Table | Per trade (capture-settle-v1) | Keys and indexes |
| --- | --- | --- |
| `calcify_finance_decision` | one row: action key (namespace, domain, id), disposition, input digest, prior chain, checksum (three 64-character hex strings) | primary key (binding, sequence) and a UNIQUE index on (binding, namespace, domain, action id) |
| `calcify_finance_obligation` | one row, inserted already SETTLED | primary key over (binding, run, session, instrument, execution) |
| `calcify_finance_attempt` | one row (attempt 1, disposition SETTLED) | primary key over six text columns, plus a foreign key to the obligation |
| `calcify_finance_position` | upserts, aggregated per (account, asset) per batch | primary key (binding, account, asset) |
| `calcify_finance_checkpoint` | one update per batch | primary key (binding) |

Rows, four wide-text indexes and WAL for each is consistent with the measured 4.3 KB per trade, but **the split between rows, indexes and WAL is an estimate**; the first implementation step measures it with `pg_total_relation_size` per table.

## Who reads the projection
Read routes exist in `CalcifyFinanceReadGateway`: participant routes (`positions`, `obligations`, `attempts` for an account the principal is scoped to) and operator routes (`checkpoint`, `decisions`, and the same three). A search of the repository on 2026-10-16 found no caller outside tests and evidence scripts: not the bot SDK, not the hosted runner, not the arena orchestrator. Bots receive fills through the venue path, not through the finance projection. This is a fact about today's code, not a promise; the profile below keeps the reads a participant plausibly needs.

## The arena-lite profile

**Scope.** Defined for the `capture-settle-v1` decision mode only. In the default `two-step` mode every trade passes through PENDING, so the savings below do not exist; a lite binding in `two-step` mode is refused at open.

**Kept live in Postgres**

| Table | Arena-lite content |
| --- | --- |
| `checkpoint` | unchanged (last sequence, last chain, owner epoch, sequence bound) plus a `projection_profile` column |
| `position` | unchanged: balances per account and asset, bounded by accounts times assets |
| `obligation` | **only rows that did not settle on their first attempt** (PENDING after INSUFFICIENT_CASH, INSUFFICIENT_SHARES or BALANCE_OVERFLOW); a later successful settle updates the row exactly as today |
| `attempt` | **only non-SETTLED outcomes**, so the foreign key to the obligation keeps working |

**Not written live**

- `decision` rows, and with them the UNIQUE action-key index.
- Obligation rows for trades that settled on the first attempt (the happy path, the whole load in a healthy run).
- SETTLED attempt rows.

The happy path then writes to Postgres only the batch's position upserts and the checkpoint update. Rows per trade drop from three inserts to none; the estimated bytes per trade fall from about 4.3 KB to a small fraction that depends on how many distinct (account, asset) pairs a batch touches. **Unmeasured.** The measurement plan below replaces this sentence with numbers.

**Where the dropped data lives.** The decisions topic is the source of truth and already holds every decision with its chain link. The pod's export step (the run-plane "export/cleanup service" in `docs/BOT_ARENA_PLAN.md`, not built) reads the topic before teardown, writes a compressed file to object storage (Cloudflare R2, already chosen) and ingests aggregates into the backbone analytics DB. Decision history is therefore available after the run, not during it.

## What stays verifiable
The chain is the integrity mechanism, not the `decision` table. Three properties the full profile gets from stored rows, and how lite keeps them:

1. **Continuity and tamper-evidence.** Each applied record is still verified in memory (input digest, replay equality, hash link to the checkpoint chain) before any row is written, and the checkpoint advances atomically with the batch. Unchanged.
2. **Redelivery after a crash.** Today a record at or below the checkpoint sequence is compared with its stored checksum in `decision`. Without stored rows the replayed segment is verified by its own hash links: a replayed run of consecutive records must link record to record and end at exactly the checkpoint's last chain, which proves it is the same log. A replay that does not end at the checkpoint is refused as diverged, as today. This needs a test that redelivers from before, at and after the checkpoint (see the plan).
3. **Offline audit.** Anyone holding the exported log can re-run the chain verification from genesis and recompute positions. The export must therefore carry the full decision record, not a summary.

What is lost: a DB-level guarantee that an action id appears once (the UNIQUE index). The ledger enforces idempotence on action keys before a decision is made, so a duplicate cannot reach the log; the index was a second line of defence. The lite profile drops it only for lite bindings.

## Contract changes (the part that needs the owner)
The owner approved the direction on 2026-10-16. The specific changes are listed here so they are reviewed once, in writing:

| Surface | Full profile (today) | Arena-lite |
| --- | --- | --- |
| Migration | none | **0018**: `checkpoint.projection_profile TEXT NOT NULL DEFAULT 'full' CHECK IN ('full','arena-lite')`, fixed at creation like `sequence_bound`; a statement trigger refuses `decision` inserts for a lite binding, so a defect cannot mix the two layouts |
| `GET .../decisions` (operator) | decisions after a sequence | `404` with code `NOT_PROJECTED_IN_PROFILE`; history is in the export |
| `GET .../obligations`, `.../attempts` | every execution | only unsettled executions and failed attempts; the response carries `projectionProfile` so a client cannot mistake "empty" for "all settled history" |
| `GET .../positions`, `.../checkpoint` | unchanged | unchanged, plus `projectionProfile` on the progress object |
| Finite bindings and binding v1/v2 | unchanged | unchanged: the profile defaults to `full` and finite code paths stay byte-identical |

## Decisions still open
1. **Export format and destination.** Compressed NDJSON of the decision records, or Parquet; R2 only, or R2 plus a direct load into the backbone analytics DB. The backbone analytics DB is not built (`docs/BOT_ARENA_PLAN.md`, backbone inventory), so R2 plus a later load is the smallest first step. Recommendation: NDJSON gzip to R2 per run and lane, loader later.
2. **Whether anything outside tests wants the history routes live.** If a future operator UI needs them during a run, serve them from the export once it is loaded into analytics, not from the pod.
3. **Cheap Postgres settings as a separate lever.** `synchronous_commit` off and fewer or smaller indexes on the full profile are independent of this note; their effect is unmeasured and they are not needed if lite lands.

## Plan, in order
1. **Measure the full profile** (local, no cloud spend): run the full-chain harness at a modest rate on the laptop, then read `pg_total_relation_size` per table and index and the WAL volume, to replace the estimate above with a per-table split.
2. **Migration 0018** and the projector's lite write path, with tests: lite and full projections of the same log agree on positions, checkpoint, pending obligations and failed attempts (property test over mixed settle outcomes); redelivery from before, at and after the checkpoint; a lite binding refuses decision inserts and a `two-step` open.
3. **Read gateway and docs**: the route semantics table above, `projectionProfile` in responses, `docs/work/CALCIFY_CAPACITY_RUNTIME.md` and the arena plan updated.
4. **Harness knob** (`FC_PROJECTION=arena-lite`) and a local comparison of `cpu_pg_boundary` and database size per trade at the same rate.
5. **Droplet verification** with the two-box harness: the same 5-minute 10k/s case, with the lite profile. This is paid and needs the owner's explicit go with size and cost.
6. **Export step**, designed separately: reads the decisions topic before teardown and writes the run's file.

## What this does not claim
No lite number exists yet. The expected saving is large because the happy path stops inserting rows, but the CPU and bytes per trade are to be measured in steps 1 and 4 before any capacity claim. The lite profile does not change the finance lane, the decisions topic, the chain, or the commitment; it changes only what the read projection stores live.
