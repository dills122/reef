# Calcify Finite P3: Binding v2 Specification (S1-B1)

Status: **revision 2, for independent docs re-review** (review 1 of 3 returned Not ready; every P0/P1 finding is addressed
below and listed in section 14). Specification only; no code, no schema change, no migration is made by this document. It
discharges the [O3 packet](CALCIFY_FINITE_P3_O3_PACKET.md) section 5 requirement that the binding v2 specification be accepted
before O3b starts. Inputs: D7 (option B), P-3 (new schema version), P-4 (budget classes); see the
[program plan](CALCIFY_PROGRAM_STATUS_AND_SPRINT_PLAN_2026-10-11.md).

## 1. Scope and non-goals

In scope: schema id, roles, identities, canonical bytes and digest, registration and registry constraints, parsers, control
writer, isolation matrix, startup witness, restore cut and refusal rules, test plan. Out of scope: the financial processor
(O3b), SQL/read seam (O4), capacity claims. Non-goals: changing any v1 byte, digest, fixture, parser behavior or migration.

## 2. Versioning (confirms P-3)

New schema `calcify-finite-binding-v2`; v1 is frozen. Parsers dispatch on `schema` **before** interpreting any other field; an
unknown schema is refused. There is no in-place upgrade: a v2 opening is a new registration with a new digest. A run is bound
to exactly one binding digest for life. **A v2 run requires a fresh `sourceGeneration`** (the registry primary key is
`(source_generation, role)`, so reusing a v1 generation would collide on the COMMAND/CAPTURE/OWNERSHIP/CAPTURE_CHANGELOG rows
unless the topics were identical; v2 does not allow reuse).

## 3. Opening document (v2)

v2 is the v1 opening plus a finance block; every v1 field keeps its name, type and validation except as listed.

| Field | v1 | v2 |
| --- | --- | --- |
| `schema` | `calcify-finite-binding-v1` | `calcify-finite-binding-v2` |
| `topics` | exactly 5, roles in order `COMMAND, SOURCE, CAPTURE, OWNERSHIP, CAPTURE_CHANGELOG` | exactly 8: those five, then `CONTROL, FINANCE_DECISIONS, FINANCE_CHANGELOG` |
| `financeApplicationId` | absent | operational text, distinct from `captureApplicationId` and from every group id, topic name and `matcherShardId` |
| `financeStoreName` | absent | fixed `finite-finance` |
| `financeStateVersion` | absent | fixed `calcify-finite-finance-state-v1` |
| `openingAccountId` | absent | text, distinct from every `parties[].accountId` (the ledger's source of funds; O3a `FinanceConfig.openingAccount`) |
| `financeConfigDigest` | absent | 64 lowercase hex; must equal the O3a `FinanceConfig.digest()` recomputed from the opening (section 4) |
| `financeBudget` | absent | decimal-string object `{obligations, captures, settleAttempts, controls}` = `{"256","128","160","8"}`; the record bound (296 = 128 + 160 + 8) is **derived**, not stored |
| `initialControlNextOffset`, `initialFinanceDecisionsNextOffset`, `initialFinanceCaptureNextOffset`, `initialFinanceControlNextOffset` | absent | decimal strings, genesis value `"0"` (topic end offsets for CONTROL and FINANCE_DECISIONS; the finance application group's next offsets on CAPTURE and CONTROL) |

There is **no separate finance or control consumer group**. The finance processor is one Kafka Streams application; its group
id is `financeApplicationId` and it consumes both CAPTURE and CONTROL under that single group, committing both partitions'
offsets, the history append and the decision publication in one transaction (section 8).

Carried over unchanged: strict duplicate-key detection, exact field sets (missing and unknown refused), decimal-string numbers,
strict UTF-8, party normalization, topic UUID spelling, all names and ids distinct, genesis offsets zero.
`FINANCE_CHANGELOG` name must equal `financeApplicationId + "-finite-finance-changelog"`.

**Opening size.** The v1 hard caps stay: opening JSON and canonical bytes <= **16,384** (Go `finiteStrictJSON` cap, Kotlin
`parseOpening`, boundary CHECKs, `openingBytes` budget), startup witness header <= 8,192 and wire <= 32,768. The v2 opening adds
roughly 1.2 KB to a ~2.5 KB v1 fixture, well within 16,384; O3b must assert the measured v2 fixture and the v2 witness header
fit these caps (acceptance item, section 12).

## 4. Derived finance configuration and digest

The processor derives its `FinanceConfig` from the opening and refuses on any mismatch with `financeConfigDigest`:

- accounts = `parties[].accountId` (2 accounts, sorted by value inside the digest);
- cash asset id = `quoteCurrency`; security asset id = `baseAssetId`;
- opening account = `openingAccountId`;
- limits = `financeBudget` (`maxObligations`, `maxCaptures`, `maxSettleAttempts`, `maxControls`).

`FinanceConfig.digest()` is exactly: SHA-256 over `frame("calcify-finance-config-v1")`, `int32(accountCount)`, each sorted
account `frame`, `frame(cash)`, `frame(security)`, `frame(opening)`, then `int32` of obligations, captures, settleAttempts,
controls, where `frame(x)` = `int32(len(utf8))` big-endian followed by the bytes. This is pinned by an independent-encoder golden
in `FinanceLedgerTest.digestsGenesisDecisionAndCheckpointMatchIndependentGoldens` (PR 494), so a Python reference can
reproduce `financeConfigDigest` for the fixture. The ledger's genesis chain is derived from the config digest (O3a
`genesisChain`), so **no genesis chain or sequence field is stored in the opening**: genesis history sequence is 0 and its chain
head is `genesisChain`.

## 5. Canonical bytes and digest

`canonicalBytes()` for v2 = ASCII `calcify-finite-binding-v2\0`, then the **v1 body algorithm unchanged** over the v2 document
(profile hash, budget hash, the seven framed strings, party count and sorted parties, **all eight** topic name+id frames, the
three int32, the nine framed strings, the genesis byte `1`, the four v1 initial offsets), then the finance suffix:

```
frame(financeApplicationId) frame(financeStoreName) frame(financeStateVersion) frame(openingAccountId) hex32(financeConfigDigest)
int32(obligations) int32(captures) int32(settleAttempts) int32(controls)
int64(initialControlNextOffset) int64(initialFinanceDecisionsNextOffset) int64(initialFinanceCaptureNextOffset) int64(initialFinanceControlNextOffset)
```

Digest = SHA-256 of those bytes. v1 and v2 byte strings can never collide **because the prefixes differ** (v1 body bytes always
follow the v1 prefix); v1 does not frame a topic count, so the prefix is the only separator and the v2 body is not a
prefix-extension of any v1 string. The v1 digest of the same run is not reused; operators treat them as different registrations.

Golden plan: a Python reference encoder (independent of Go and Kotlin) produces the expected canonical hex and digest for
`contracts/calcify/finite-binding-v2.json`; Go and Kotlin must each reproduce both. Mutation corpus: every field changed one at a
time changes the digest; each reordered role, duplicate topic, missing/extra field, `financeConfigDigest` mismatch and v1/v2
schema confusion is refused.

## 6. Registration and registry

1. **Boundary migration `0014`** (replaces `boundary.calcify_finite_register`, which today raises unless
   `o->>'schema' = 'calcify-finite-binding-v1'`): accept `calcify-finite-binding-v1` **or** `calcify-finite-binding-v2`, keep every
   other check, and leave the table CHECKs at 16,384 (no raise, section 3). `begin_attempt`, `snapshot` and the remaining ten
   functions are unchanged and must be proven to behave identically for a v2 row. The DB-roles doc function list gains the
   changed signature note; `scripts/dev/db/migrate.test.mjs` gains the entry plus a test that a v1 registration still works and
   an unknown schema is refused.
2. **Runtime migration `0077`**: replace the `calcify_topic_identities` role CHECK with `{verified, output, COMMAND, CAPTURE,
   OWNERSHIP, CAPTURE_CHANGELOG, CONTROL, FINANCE_DECISIONS, FINANCE_CHANGELOG}` (pattern of `0076`). `SOURCE` stays a row in the
   source-generation registry, not a topic-identity role. Existing rows unchanged.
3. No new Postgres role: no new component needs DB state (section 7). The registrar's privileges are unchanged.

## 7. Control writer

**Decision: a separate small component, `FiniteControlWriter`, in platform-runtime, with its own Kafka principal and
transactional id, no Postgres state, no extension of the ingress adapter.** Reason: the ingress adapter's authority and proof are
built on the COMMAND stream; sharing its credential would let a COMMAND-topic principal write controls, and D4 says trading
principals never fund or repair. (This resolves the control-writer placement question in the O3 packet section 5, item 2; it is
unrelated to P-3 and P-4.)

Design:

- **Authentication**: only an authenticated OPERATOR principal may call it; the writer stamps `FinanceAuthorityV1.OPERATOR`
  itself and never copies a caller-supplied authority. The concrete operator-auth mechanism is an O3b design item (**P-5**,
  recorded in section 13) and must be a platform authority, not a request header the caller controls.
- **Payload**: `FinanceInputV1` (`calcify_finance.proto`) for Fund and Settle (repair). The writer rejects a Capture (captures
  come only from the CAPTURE topic).
- **Durability without a database**: the CONTROL topic's read-committed log **is** the writer's state. On start and before each
  publish it reads the CONTROL log (at most 8 funding records plus settle repairs, bounded by the 168-record control+repair
  envelope), derives the set of `ActionKey`s already written and the count of controls, and refuses beyond the budget.
- **Unknown outcome**: after a failed or timed-out transactional send, the writer re-reads the read-committed log; if the action
  key is present the call returns the prior result, else it resends. The deterministic transactional id
  (`<financeApplicationId>-control-writer`) fences zombie producers.
- **No Postgres role**: the Kafka principal `calcify_finite_control` is distinct from every Postgres role in the
  [DB roles](CALCIFY_FINITE_P3_DB_ROLES.md).
- Never writes COMMAND, SOURCE, CAPTURE or OWNERSHIP; keys null, partition 0.

Alternative rejected: extending the ingress adapter (shared credential and admission state weaken the exclusive-writer proof).
The prior open question 1 (same process, separate principal) is closed as: separate component, because the same-process variant
shares fault and memory domain with the COMMAND writer.

## 8. Determinism and restore cut (option B)

The processor consumes CAPTURE and CONTROL under one group. For each consumed record, in one Kafka transaction, it: assigns the
next position in its own **history** (an entry `{position, input, sourceRole, sourcePartition, sourceOffset}` stored per entity in
the keyed finance store), decides with the O3a ledger, publishes the `FinanceDecisionV1` (including `stateDigest`) to
`FINANCE_DECISIONS`, and commits both input partitions' offsets. Order is defined by the history, not arrival. The spike
([result](CALCIFY_FINITE_P3_B2_SPIKE_RESULT.md)) shows two interleavings both valid and each reproducible from its history.

**Restore cut** = `(historyPosition, headDecision, captureNextOffset, controlNextOffset, bindingDigest)`:

1. `headDecision` is the last record of the read-committed `FINANCE_DECISIONS` log after `verifyChain` succeeds over the whole
   log from `genesisChain`; an empty log means genesis.
2. The restored state must be the state committed by `headDecision.stateDigest`: O3a `restore(checkpoint, trustedHead)` refuses
   any checkpoint whose recomputed state digest differs, so a forged but invariant-consistent state cannot be adopted (O3a review
   2, P1-1).
3. `bindingDigest` on the cut must equal the active v2 digest; a v1 digest on a v2 cut, or the reverse, refuses.
4. **Offsets versus history.** Each history entry records its source role/partition/offset. Let `lastCapture` / `lastControl` be the
   highest recorded source offsets. Activation refuses `FINANCE_OFFSET_AHEAD_OF_HISTORY` when a committed group next offset exceeds
   `lastRecorded + 1` by more than the transaction-marker offsets in that gap, evaluated against `readCommittedEndOffset` (control
   markers occupy offsets in read-committed topics, which is why the v1 witness already carries both end offsets). The inverse
   (history ahead of committed offsets) is impossible by the single transaction and refuses as `FINANCE_HISTORY_AHEAD_OF_OFFSET`.
5. Replay from the cut reproduces byte-equal decisions; re-consumption never rebuilds order.

## 9. Isolation and ACL matrix

Principals: `calcify_finite_ingress` (existing COMMAND writer), `calcify_finite_capture` (existing capture Streams app),
`calcify_finite_control` (new, section 7), `calcify_finite_finance` (new, the finance Streams app), operators (read only).

| Resource | Allowed | Everything else |
| --- | --- | --- |
| CONTROL topic | Write: `control`; Read: `finance` | refused (incl. `ingress`, `capture`) |
| FINANCE_DECISIONS | Write: `finance`; Read: `finance`, verifier/read seam (O4), operators | refused |
| FINANCE_CHANGELOG and finance internal/repartition topics (prefix `financeApplicationId-`) | Read/Write/Create: `finance` | refused |
| CAPTURE topic | Write: `capture` (unchanged); Read: `finance`, `capture` | `finance` and `control` cannot write |
| TransactionalId `<financeApplicationId>-control-writer` | `control` | refused |
| TransactionalId prefix `financeApplicationId` | `finance` | refused |
| Group `financeApplicationId` | Read: `finance` | refused |

Proof (extends the O2 pattern): a negative produce/consume test for every denied principal-resource pair in the table, plus
tests that the `ingress` and `capture` principals cannot write CONTROL/FINANCE_*, that `finance` cannot write CAPTURE or
COMMAND, and the transactional-id fencing test. DB side: nothing new.

## 10. Parser plan and startup witness

- Go `services/matching-engine/internal/streamdirect/calcify_finite_binding.go`: `ParseFiniteBindingV2` and dispatch by schema;
  v1 path untouched. Kotlin `FiniteBindingContract.kt`: v2 opening type and `canonicalBytes()`.
- **Explicit v2 validators** (the v1 code hard-codes these, so each needs a v2 variant): `topics.size == 8` and 8 distinct names and
  ids; `roles` list of eight in the section 3 order; changelog-name checks at **index 4 (capture) and index 7 (finance)**;
  `openingFields` exact set including the new fields; per-field text validation for all new fields; `financeApplicationId`,
  `openingAccountId` distinctness rules; `financeConfigDigest` recomputation; fixed `financeStoreName` / `financeStateVersion`; the
  `limits[]` budget array unchanged.
- **Startup witness `calcify-finite-startup-v2`**: `opening` (v2), `run`, `cuts`, `journal` as v1, with `cuts.ranges` extended to 8
  entries in role order (`role, partition, beginningOffset, endOffset, readCommittedEndOffset`, same invariants
  `begin <= committed <= end`), and `cuts.financeGroup = {groupId, captureNextOffset, controlNextOffset}` with `groupId ==
  financeApplicationId` (v1's `commandGroup` and `captureSourceGroup` retained). Wire cap 32,768 and header cap 8,192 unchanged;
  O3b measures the v2 witness against them.

## 11. Refusal rules (activation)

Refuse, with no run state mutated, when: schema unknown or v1/v2 confused; any v2 field missing, unknown or duplicated; role order
differs; any topic name or id duplicates another; `financeConfigDigest` does not equal the digest recomputed from the opening;
budgets differ from the compiled O3a limits (256/128/160/8); `openingAccountId` collides with a party account; CONTROL or
FINANCE topics non-empty at genesis, or a genesis offset is not zero; the source generation is already registered for another
binding; the restore cut is incomplete, mismatched or violates section 8; broker topic UUID differs from the registry.

## 12. Test plan (O3b)

1. Golden and mutation corpus (section 5) in Go and Kotlin against the Python reference.
2. Boundary `0014` and runtime `0077` migration tests; v1 registration regression.
3. v1 regression: all O1/O2 tests unchanged; v1 fixtures keep their digests.
4. Size acceptance: v2 fixture <= 16,384 bytes; v2 witness header <= 8,192 and wire <= 32,768 with a full journal.
5. Isolation matrix (section 9) on the real broker harness, including producer fencing.
6. Restore-cut refusal table (section 8), including a forged-but-consistent checkpoint and the offset/history gap with markers.
7. Real-broker crash test between poll and commit (the atomicity limit recorded in the spike result).

## 13. Open items and decisions for the re-review

Decided here (confirm in review): fresh source generation for v2; separate control writer with no database; single finance group
`financeApplicationId`; openingBytes held at 16,384; 296 (derived) as the ledger record bound under the packet's 320 envelope
(captures are in practice also limited by the v1 budget of 8 trades).

Open: **P-5** operator authentication mechanism for the control writer (platform admin authority; O3b design); whether history
entries live in the changelog-backed store or a dedicated role (decided in the O3b design review, constraint: per-entity keys, no
whole-state value).

## 14. Review 1 disposition

| Finding | Disposition |
| --- | --- |
| P0-1 registration | Fixed: boundary `0014` (section 6) |
| P0-2 opening size | Fixed: hold 16,384; witness caps retained (section 3, 10, 12.4) |
| P0-3 config digest | Fixed: explicit `openingAccountId`, asset mapping, `maxObligations`, exact recomputation (section 4) |
| P1-1 false topic-count claim | Fixed (section 5) |
| P1-2 consumer groups | Fixed: single group = application id (section 3, 8) |
| P1-3 genesis anchoring | Fixed: derived genesis chain, explicit topic and group genesis offsets (sections 3, 4) |
| P1-4 offset-ahead rule | Fixed: history records source offsets; marker-aware rule (section 8.4) |
| P1-5 control writer state | Fixed: topic-as-state, recovery algorithm, fencing, no PG role (section 7); P-5 open |
| P2-1 generation scope | Fixed (section 2) |
| P2-2 validators | Fixed (section 10) |
| P2-3 budgets | Fixed: derived 296 under 320 envelope (sections 3, 13) |
| P2-4 ACL | Fixed (section 9) |
| P2-5 witness v2 | Fixed (section 10) |
