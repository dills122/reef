# Calcify capacity profile: what the finite bounds are and what replaces them

Date: 2026-10-14. Status: **working spec; decisions below were taken by the implementation task under the owner's delegation of 2026-10-14 (design choices that are not a major refactor or pivot), recorded here so they can be overturned.** Nothing here is accepted authority and none of it changes the finite P3 path.

## Why this exists
The finite P3 profile is one closed run: one instrument, one session, at most 16 parties, at most 128 captures. The arena goalpost (10,000 booked trades/s, 5 to 60 minute sessions, thousands of participants, 64 instruments) cannot be expressed in it. The commitment design ([state commitment design](CALCIFY_FINANCE_STATE_COMMITMENT_DESIGN.md)) says how the ledger scales; this note lists **every pinned bound** that a capacity profile has to replace, where it lives, and which stage owns it, so the work can be cut into small reviewable pieces.

## Bound inventory

| Bound (finite value) | Where it is pinned | Capacity profile | Owner stage |
| --- | --- | --- | --- |
| Projection sequence 320 | migration 0014 CHECKs on checkpoint and decision | **per-binding `sequence_bound`**, 320 for every finite binding, up to 10^9; fixed at creation, immutable, enforced by a statement trigger on decision inserts | **done: migration 0016, `FinanceProjector.open/claim`** |
| Ledger budgets 256 obligations / 128 captures / 160 attempts / 8 controls | `FinanceConfig.init` pinned limits; bound into the config digest | per-slice budgets in a new config label; the O(touched) engine does not hold the state in one immutable object | engine (design S3) |
| Accounts 2..16, listed up front in the config digest and the opening | `FinanceConfig.init`, binding v2 opening `parties` | accounts are **admitted by operator control** (a Fund creates the account entity), not listed in the config; the config carries the asset pair and the opening account only | engine + binding v3 |
| History entries 312, all materialized at restore | `FinanceStore.MAX_HISTORY_ENTRIES`, `FinanceProcessor` halt, restore reads every entry | history is not materialized: the decision log is the history, the store keeps the head, meta and (lagged profile) the undo ring | store (design S4) |
| Binding v2 opening: fixed `financeBudget` strings, one run/session/instrument, sizes 8 KiB header / 32 KiB wire | `FiniteBindingContract`, binding v2 spec | **binding v3** with a profile id, slice number and a reference to the previous slice's seal; many instruments; budgets as parameters; sizes unchanged (no party list) | binding v3 spec (needs independent review, as v2 did) |
| Capture-side finite budget (16 publications x 8 trades = 128 executions) | `FiniteLifecycleBudget` | per-entity incremental capture reducer (merged: `IncrementalLifecycleReducer`) | lane B (core done) |
| One partition, one thread per topic | `FinanceTopology` pinned properties | unchanged for one lane; sharding is a separate decision (processor-path and sharding options note) | later |
| Projection read cost, whole-log runner passes | `FinanceProjectionRunner` | **done: tailing runner (PR 549) and set-based writes (PR 548)** | done |

## Decisions taken (defaults; overturn by editing this table)
1. **A capacity profile is a new profile id in a new binding schema (v3)**; binding v1 and v2, their goldens and the finite code paths stay byte-identical. The projection schema is shared (one set of tables; the bound is per binding).
2. **The rotation unit is a slice of about 5 minutes** (6 million decisions, about 15 million entities, about 5.5 GiB in one ledger at the contract rate), sealed by `(final sequence, C(S), chain head)` that the next slice's genesis references. Default `sequence_bound` for a slice: 8,000,000. A 60-minute session is twelve slices.
3. **Accounts are state, not configuration.**
4. **The projection bound is enforced in the database, not only by the application**, so a defect in the projector cannot overrun a binding (this PR).
5. **Foreign keys on the projection stay** (measured: not measurably slower with set-based writes, CAL-FIN-P13).

## What this PR (migration 0016) changes
`boundary.calcify_finance_checkpoint.sequence_bound` (default 320, 320..10^9, immutable); the checkpoint CHECK becomes `last_sequence BETWEEN 0 AND sequence_bound`; the decision row CHECK `BETWEEN 1 AND 320` becomes `>= 1` plus a statement-level trigger that refuses any insert beyond the binding's bound (no measurable cost: 300,000 rows in 9.3 s with it, 10.3 s without, same machine, run order dominates the difference). `FinanceProjector.open/claim` take the bound (default 320) and refuse an existing binding opened with another. Finite behaviour is unchanged: all 142 finance tests pass, including the broker and end-to-end suites, and a new test shows a finite binding still refuses row 321 and a capacity binding refuses its own bound plus one.

## Next pieces, each its own small PR
1. Binding v3 specification (profile id, slice, seal reference, multi-instrument), for independent review.
2. `StateCommitment.reference` and the independent Python encoder with goldens (design S1), then the incremental trie with differential tests (S2). Neutral to every open decision.
3. The capacity engine in new files (design S3), then store, processor and restore (S4), the lagged option (S5) only if the owner still wants it after the measurements the design requires.
