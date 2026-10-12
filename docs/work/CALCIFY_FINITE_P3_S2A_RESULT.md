# Finite P3 S2-A result: finance processor, restore cut and first real-broker evidence

Status: evidence record for sprint tasks S2-A1 (parsers), S2-A2 (processor) and S2-A4 (real broker). Not an O3b approval, not a
capacity claim. Written 2026-10-12; the spec is `CALCIFY_FINITE_P3_BINDING_V2_SPEC.md` (revision 5).

## What now exists

| Piece | Where | What it does |
| --- | --- | --- |
| Capture-to-input mapping `calcify-finite-finance-state-v1` | `finance/FinanceCaptureMapping.kt` | Pure, versioned. Only `READY_EXECUTION` members book; `REPLAY`, `APPLIED`, `REJECTED` never do. Action key `("calcify-finance","capture",executionId)`; accounts come from the two order dependencies and must be the bound BUY and SELL parties. A bad ready member is an `INVALID_INPUT` history entry, never a stall or a skip. |
| Per-entity store and history | `finance/FinanceStore.kt` | One key per entity (`position/`, `execution/`, `obligation/`, `attempt/`, `action/`), `meta`, `head` and one history entry per ledger input (`h/<position>`), all versioned bytes. No whole-state value. Entry chain follows spec section 8 and is pinned against an independent encoder in the test. |
| Processor and topology | `finance/FinanceProcessor.kt` | CAPTURE and CONTROL in, `FINANCE_DECISIONS` out. All writes for a record are computed first and applied together; the cached state moves only after the writes; the 312-entry bound halts fail-closed with no partial write. Restore is a single verified pass in `init`. |
| Restore cut | `finance/FinanceRestoreCut.kt` | Pure function over observed evidence with stable refusal codes for the section 8 rules: decision chain, store versus log, one-to-one history cross-check, `FINANCE_OFFSET_AHEAD_OF_HISTORY`, `FINANCE_HISTORY_AHEAD_OF_OFFSET`, `FINANCE_GROUP_OFFSETS_EXPIRED`, lost store. |
| v2 startup witness and topic rules | Go `ParseFiniteStartupV2`, Kotlin `parseStartupV2`, `FiniteTopicRulesV2` | PR 505. |
| Real-broker tests | `FinanceBrokerTest.kt` (tag `broker`) | Disposable Redpanda container, Streams EOS v2, RocksDB state. |

## Real-broker results (Redpanda v26.2.3, single node, Streams 4.3.1, `exactly_once_v2`, 1 thread)

Run with `./gradlew test -PbrokerTests --tests '*FinanceBrokerTest'` (Docker required; excluded from the default run).

1. **Clean stop, wiped local state, restart.** Six executions funded, captured and repaired; stop; delete the local state directory;
   restart. The state-restore listener reported restored records, so the store was rebuilt from the changelog and `init` saw it
   (restore happens before processor init: a restart that had started empty would have re-used history position 1 and failed the
   test). One more capture and repair were then processed. The decision log equals the in-process ledger oracle for the same input
   order; the restore cut verifies on the real decision log, the real changelog contents and the real committed offsets. Passed.
2. **Three SIGKILLs in the middle of a stream.** Sixty executions, captures and repairs interleaved on two topics by a paced
   producer. A child JVM runs the app and is killed after a random amount of progress plus a random sub-commit-interval jitter; the
   second kill is followed by a wiped local store. One of the three kills landed with an open transaction on `FINANCE_DECISIONS`
   (read-uncommitted end greater than read-committed end). After the final drain: the restore cut verifies; every CAPTURE and CONTROL
   offset is positioned in history exactly once (no skip, no duplicate); replaying the inputs carried by the decisions reproduces them
   byte for byte; every execution is settled exactly once with the expected final positions. Passed.

What this proves: for this topology, atomicity of offsets plus history plus changelog plus decisions under SIGKILL, restore from the
changelog before init, and the section 8 restore-cut rules on real broker data.

What it does not prove (still open, S2-A4 follow-ups):

- **ACL and principal isolation** (section 9): the broker runs without SASL. Write and transactional-id fencing negative tests need a
  secured harness.
- **Poll-to-commit windows beyond what random kills hit.** Kills are random, not exhaustive; one of three hit an open transaction. A
  deterministic fault injection (kill between produce and commit) would need broker or client hooks.
- **Marker-only tails on the input topics.** The test producers are not transactional, so the "last records on CAPTURE or CONTROL are
  aborted" case is not covered. It belongs with the control writer (S2-A3), which is the only transactional writer.
- **Real activation verifier.** The evidence reader in the test is test code; the production witness builder is S2-A1 follow-up work.
- **CI.** The broker tests are tagged and excluded from the default run; wiring them into a hosted job (image pull, runtime) is an
  owner decision.

## Measurement (S2-A2 deliverable) and a design finding

Mock context, in-memory store, one thread, JIT warmed (tens of thousands of records), 17 records per run (12 capture envelopes of
which 10 carry no trade, 2 funds, 3 repairs; 8 decisions):

- **29,000 records/s and 13,700 decisions/s** through the processor path.
- Store footprint for that run: 43 keys, about 12 KB; entity values 8 to 40 bytes plus key text; history entries about 350 bytes.
- Attribution (separate micro-measurements): envelope parse plus content digest about 11.7 microseconds per envelope; the ledger
  itself about 9 microseconds per decision (about 110,000 decisions/s, consistent with the earlier single-thread figure); the
  remainder is serde, store puts, entry hashing and the forward.

Reading against the sprint-plan gate ("below 50,000 decisions/s: re-plan the representation before O4"): **the gate as written is
not met by this measurement**, but the figure is dominated by envelope handling and mock overhead on a tiny, budget-capped run, and
it is not a RocksDB plus EOS number. Treat it as a per-record cost estimate. The finite budgets (296 decisions) make a sustained
rate unmeasurable on this fixture.

**Design finding that matters for the 10k/s goalpost.** The O3a `decide` recomputes `stateDigest` over the entire state for every
decision. That is 16 microseconds at the finite fixture's final size and grows linearly with state; the restore design (C1, per-entity
keys) removed the whole-state *value* but not the whole-state *digest*. The finite P3 budgets cap state at roughly 700 entities, so
this is acceptable for P3, but it cannot carry an unbounded ledger. Before any capacity claim the state commitment must become
incremental (for example a per-entity hash folded into an order-independent or Merkle accumulator updated by only the touched
entities), with a differential test against the current digest. Recommend recording this as a named item in the program plan.

## Decisions taken without the owner (please confirm or overrule)

1. `max.message.bytes` minimums for CONTROL (8,192), FINANCE_DECISIONS (16,384) and FINANCE_CHANGELOG (capture bytes plus 1,024) are
   **provisional** verifier constants (`FiniteTopicRulesV2`), not opening fields. The measured record sizes (history entry about 350
   bytes, decision record under 1 KB) leave wide margin; tighten when S2-A3 measures the control record.
2. Capture-to-input mapping rules 1 to 6 (see `FinanceCaptureMapping` KDoc) are the first version of `calcify-finite-finance-state-v1`.
   Any change needs a new version string.
3. A capture envelope that fails schema, binding-digest or content-digest checks is an `INVALID_INPUT` entry (counted, chained),
   consistent with section 8's pinned error handling.
4. `FinanceProcessorConfig` takes the capture binding explicitly; how the v2 digest relates to the capture lane's
   `finite_binding_digest` in envelopes is **not decided here** and needs a line in the O3b design.
