# Calcify Finite P3: Binding v2 Specification (S1-B1)

Status: **revision 5, reviewed four times** (reviews 1-3 Not ready, each finding fixed; review 4, authorized by the owner beyond
the default three, returned **Ready with non-blocking follow-ups**, applied in this revision). Specification only; no code, no schema
change, no migration is made by this document. It discharges the [O3 packet](CALCIFY_FINITE_P3_O3_PACKET.md) section 5 requirement
that the binding v2 specification be accepted before O3b. Inputs: D7 (option B), P-3 (new schema version), P-4 (budget classes); see
the [program plan](CALCIFY_PROGRAM_STATUS_AND_SPRINT_PLAN_2026-10-11.md). Owner acceptance is still required.

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
| `controlTransactionalId` | absent | text; the control writer's transactional id; **no prefix relation in either direction** with `financeApplicationId`, `captureApplicationId`, or any group id or other transactional id the parser can see in the opening (the matcher's transactional id derives from `commandConsumerGroup`, so the group rule covers it; the ingress adapter's is covered by an ACL-provisioning test, not by the parser) |
| `financeConfigDigest` | absent | 64 lowercase hex; must equal the O3a `FinanceConfig.digest()` recomputed from the opening (section 4) |
| `financeBudget` | absent | decimal-string object `{obligations, captures, settleAttempts, controls}` = `{"256","128","160","8"}`; the record bound (296 = 128 + 160 + 8) is **derived**, not stored |
| `initialControlNextOffset`, `initialFinanceDecisionsNextOffset`, `initialFinanceCaptureNextOffset`, `initialFinanceControlNextOffset` | absent | decimal strings, genesis value `"0"` (topic end offsets for CONTROL and FINANCE_DECISIONS; the finance application group's next offsets on CAPTURE and CONTROL) |

There is **no separate finance or control consumer group**. The finance processor is one Kafka Streams application; its group
id is `financeApplicationId` and it consumes both CAPTURE and CONTROL under that single group, committing both partitions'
offsets, the history append and the decision publication in one transaction (section 8).

**v2 tightening.** Because the ledger's `AccountId`/`AssetId` are at most 128 UTF-8 bytes, `parties[].accountId`, `baseAssetId`,
`quoteCurrency` (already 3 uppercase letters) and `openingAccountId` must be 1..128 bytes of printable ASCII
`[A-Za-z0-9._:-]` in v2 (this also removes any ordering ambiguity between UTF-16, code-point and byte sorts when the config
digest sorts accounts), and `quoteCurrency != baseAssetId` (the ledger requires distinct assets). Exactly 2 parties (as v1).

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
frame(financeApplicationId) frame(financeStoreName) frame(financeStateVersion) frame(openingAccountId) frame(controlTransactionalId) hex32(financeConfigDigest)
int32(obligations) int32(captures) int32(settleAttempts) int32(controls)
int64(initialControlNextOffset) int64(initialFinanceDecisionsNextOffset) int64(initialFinanceCaptureNextOffset) int64(initialFinanceControlNextOffset)
```

Digest = SHA-256 of those bytes. `hex32(x)` means the 32 raw bytes decoded from the 64 lowercase hex characters. v1 and v2 byte
strings cannot collide because the prefixes differ (`...-v1\0` versus `...-v2\0`); v1 frames no topic count, so the prefix is the
only separator. The O3a genesis chain binds to the config digest, not the binding digest; distinct bindings with an identical
finance config share a genesis chain and are separated by their topic UUIDs and the binding digest in the restore cut. The v1 digest of the same run is not reused; operators treat them as different registrations.

Golden plan: a Python reference encoder (independent of Go and Kotlin) produces the expected canonical hex and digest for
`contracts/calcify/finite-binding-v2.json`; Go and Kotlin must each reproduce both. Mutation corpus: every field changed one at a
time changes the digest; each reordered role, duplicate topic, missing/extra field, `financeConfigDigest` mismatch and v1/v2
schema confusion is refused.

## 6. Registration and registry

1. **Boundary migration `0014`** (replaces `boundary.calcify_finite_register`, which today raises unless
   `o->>'schema' = 'calcify-finite-binding-v1'`): accept `calcify-finite-binding-v1` **or** `calcify-finite-binding-v2`, keep every
   other check, and leave the table CHECKs at 16,384 (no raise, section 3). `stages` requires the top-level `cuts` keys to be exactly the v1 set (every value except `brokerCuts` a string) and caps `cuts` at
   8,192 bytes; `brokerCuts` is an unconstrained JSON object, so v2 stores the eight ranges and the `financeGroup` cut **inside
   `brokerCuts`** and `stages` needs no change (the exact-key check must not be widened: that would break v1 rows). `begin_attempt`, `snapshot` and the remaining functions are unchanged and must be proven to behave identically for a v2 row. The DB-roles doc function list gains the
   changed signature note; `scripts/dev/db/migrate.test.mjs` gains the entry plus a test that a v1 registration still works and
   an unknown schema is refused.
2. **Runtime migration `0077`**: replace the `calcify_topic_identities` role CHECK with `{verified, output, COMMAND, CAPTURE,
   OWNERSHIP, CAPTURE_CHANGELOG, CONTROL, FINANCE_DECISIONS, FINANCE_CHANGELOG}` (pattern of `0076`). `SOURCE` stays a row in the
   source-generation registry, not a topic-identity role. Existing rows unchanged.
3. No new Postgres role: no new component needs DB state (section 7). The registrar's privileges are unchanged.

## 7. Control writer

**Decision: a separate small component, `FiniteControlWriter`, in platform-runtime, with its own Kafka principal
(`calcify_finite_control`) and transactional id (`controlTransactionalId`), no Postgres state, no extension of the ingress
adapter.** The ingress adapter's authority and proof are built on the COMMAND stream; sharing its credential would let a
COMMAND-topic principal write controls, and D4 says trading principals never fund or repair. (This resolves the control-writer
placement question in the O3 packet section 5, item 2; it is unrelated to P-3 and P-4.)

- **Authentication**: only an authenticated OPERATOR principal may call it; the writer stamps `FinanceAuthorityV1.OPERATOR`
  itself and never copies a caller-supplied authority. The concrete mechanism is O3b design item **P-5**, and it must be a
  platform authority, not a caller-controlled header.
- **Payload and pre-validation**: `FinanceInputV1` Fund or Settle only (never Capture). The writer applies the same structural
  validation the model constructors apply (bounded text, positive amount, attempt >= 1) and refuses before writing, so malformed
  input never reaches the log.
- **Budget enforced by the writer**: *writes*, not ledger outcomes. At most **8 Fund writes and 168 control writes in total**
  (8 funds + 160 settle attempts). Because the writer's counts are at least the ledger's counts, the ledger can never be asked to
  exceed its recorded-input budgets by the writer; the ledger's per-class refusal (junk does not consume a class) remains the
  authoritative accounting.
- **Funding typo cost**: writer slots are permanent per key, so a Fund write the ledger refuses still burns 1 of the 8 Fund writes; this is intended and the operator procedure must say so.
- **Idempotence**: an `ActionKey` already present in the CONTROL log is never written again. An identical payload returns the
  prior submission; a different payload under an existing key is refused `ACTION_CONFLICT` at the writer. Consequently a key the
  ledger *refused* (not recorded) is not reusable through the writer either; operators correct with a new key.
- **Durability without a database**: the CONTROL log's read-committed content is the writer's state (<= 168 writes by
  construction).
- **Recovery order** (mandatory): (1) `initTransactions()`, which fences any zombie producer and aborts its open transaction,
  *before* (2) the recovery read, so a read-committed read cannot stop behind an open transaction's last stable offset and miss a
  later-committing record; (3) read CONTROL from the beginning with `assign()` and `seek` (no consumer group, so no group ACL and
  no committed offsets); (4) rebuild the key set and counts; only then accept calls. After any send timeout or error the writer
  aborts or re-fences and repeats (3)-(4) before resending.
- **Outcome visibility**: the writer returns "submitted". The *outcome* (recorded or refused disposition) is read by action key
  from the finance history (section 8) through the O4 read seam; the writer has no reply channel.
- Never writes COMMAND, SOURCE, CAPTURE or OWNERSHIP; keys null, partition 0. No Postgres role; the Kafka principal is distinct
  from every Postgres role in the [DB roles](CALCIFY_FINITE_P3_DB_ROLES.md).

Alternative rejected: extending the ingress adapter (also rejected: same process with a separate principal, which shares fault and
memory domain with the COMMAND writer).

## 8. Determinism, history and restore cut (option B)

**Streams configuration (pinned)**: one Kafka Streams application, `processing.guarantee=exactly_once_v2`,
`num.stream.threads=1`, `num.standby.replicas=0`, single partition (partition 0, as v1), `auto.offset.reset=earliest`. The
application's single group `financeApplicationId` consumes CAPTURE and CONTROL. Streams commits `lastProcessedOffset + 1` per
input partition together with the state-store and output writes in one transaction per commit interval; one transaction may span
many records, and atomicity (all or none of: offsets, history entries, changelog writes, decisions) is Kafka's EOS property,
proven on the real broker (section 12.7), not by `TopologyTestDriver`.

**Input history.** The unit is the *ledger input*, not the Kafka record. A `FiniteLifecycleCaptureV1` envelope can contain several ready
members (each becomes its own `FinanceCaptureV1`), some replay members, or none. So the entry key is
`(sourceRole, sourceOffset, memberOrdinal)`, and:

- a CAPTURE envelope with `r >= 1` ready members yields `r` entries (`memberOrdinal` 0..r-1), one per ledger input;
- a CAPTURE envelope with no ready member (all replay, or empty) yields exactly one `NO_INPUT` entry (`memberOrdinal` 0), so every
  consumed record has at least one entry and the offset rule below is well-defined;
- a CONTROL record yields one entry (`memberOrdinal` 0).

Entries are assigned the next position of an explicit persisted counter (never `approximateNumEntries`) and stored as keyed
records in the finance store (changelog-backed by `FINANCE_CHANGELOG`; no extra topic role, no whole-state value). An entry holds
`{position, sourceRole, sourcePartition, sourceOffset, memberOrdinal, kind (INPUT | NO_INPUT), inputDigest, outcome (disposition,
including refused and prior-result dispositions), decisionChecksum (recorded outcomes only), prevEntryChain, entryChain}`.
**Canonical encoding** (length-framed like O3a `Canon`, never delimiter concatenation): `entryChain = SHA-256(frame("calcify-finance-history-entry-v1")
frame(prevEntryChain) int64(position) frame(sourceRole) int32(sourcePartition) int64(sourceOffset) int32(memberOrdinal)
frame(kind) frame(inputDigest) frame(outcome) frame(decisionChecksum or ""))`, with the genesis `prevEntryChain =
SHA-256(frame("calcify-finance-history-genesis-v1") frame(genesisChain))`. An independent-encoder golden for one entry is part
of the O3b test plan (section 12). Refused and prior-result entries are chained, hence tamper-evident, and operators/O4 read
dispositions by action key from history.

**History bound and overflow.** `INVALID_INPUT` and `PRIOR_RESULT` entries count. The 128 term follows from the v1 source budget (16 publications x 8 trades), not from the ledger's `maxCaptures`; `NO_INPUT` entries are bounded by one per source record, and replayed envelopes are new source records, so that term is bounded by the **source publication budget** (the v1 completed-record limit of 16), not by an engine re-emission assumption. If an entry would exceed 312, the processor **halts fail-closed** with `FINANCE_HISTORY_BUDGET_EXCEEDED` (no entry, no offset commit) rather than skipping a record. Entries <= 128 (ledger capture inputs, `maxCaptures`) + at most 16 `NO_INPUT` entries (one per envelope, bounded
by the v1 publication budget of 16) + 168 CONTROL writes (section 7) = **312 entries**, within the packet's 320 envelope. The
ledger's own record bound stays 296 (recorded decisions only). Redelivery cannot occur inside EOS (consume and record are atomic);
a producer-side duplicate is excluded by the writer's idempotence, and any that still arrives is an entry with disposition
`PRIOR_RESULT`.

**Pinned error handling.** The topology uses raw byte serdes and parses inside the processor; a record that fails to parse, or
whose body kind is wrong, becomes an `INVALID_INPUT` entry (never a stalled app or a skipped record), so every consumed record has at least one entry. `commit.interval.ms` and `max.task.idle.ms` are pinned in the topology configuration and named by the
crash-window test.

**Entry fields for non-ledger entries.** A `NO_INPUT` entry has `kind=NO_INPUT`, `inputDigest` = SHA-256 of the source record's raw bytes, `outcome=NO_INPUT`, no `decisionChecksum`; an `INVALID_INPUT` entry (unparseable or wrong body kind) has `kind=INPUT`, `inputDigest` = SHA-256 of the raw bytes, `outcome=INVALID_INPUT`. `memberOrdinal` is the index among the envelope's ready members (0-based). `position` is 1-based. In every framing above, `prevEntryChain`, `genesisChain`, `inputDigest` and `decisionChecksum` are framed as their **lowercase hex text** (as the O3a `genesisChain` does), not raw bytes.

**Decisions.** `FINANCE_DECISIONS` carries `FinanceDecisionV1` (with `stateDigest`) for **recorded** outcomes only (the ledger
returns no decision for refusals, no-ops and prior results). `headDecision.sequence` equals the ledger sequence; the history
`position` is >= that sequence; equality holds exactly when no entry is refused, `INVALID_INPUT`, `NO_INPUT` or `PRIOR_RESULT`. (Section 4's "history chain head at genesis" means the *decision* chain, `genesisChain`; the entry chain has its own genesis below.)

**Topic-kind check.** The processor refuses, at history level and without calling the ledger, a Fund/Settle body on CAPTURE or a
Capture body on CONTROL (disposition `INVALID_INPUT`, entry recorded).

**Restore cut** = `(historyPosition, entryChainHead, headDecision, captureNextOffset, controlNextOffset, bindingDigest)`:

1. `headDecision` is the last record of the read-committed `FINANCE_DECISIONS` log after `verifyChain` succeeds over the whole log
   from `genesisChain`; empty log means genesis. `entryChainHead` verifies over the history from genesis.
2. The restored ledger state must be the state committed by `headDecision.stateDigest`: O3a `restore(checkpoint, trustedHead)`
   refuses any other state, even an invariant-clean one with a recomputed checksum (O3a review 2, P1-1).
3. `bindingDigest` equals the active v2 digest; v1/v2 confusion refuses.
4. **Offsets versus history.** For each input partition let `lastRecorded` be the highest `sourceOffset` among its entries (all
   `memberOrdinal`s). `committedNext` must equal `lastRecorded + 1`, and `<= readCommittedEndOffset`. With **no** entries for the topic, `lastRecorded = -1` and
   an absent committed offset means `0`, while a present nonzero committed offset with no entries refuses as `FINANCE_OFFSET_AHEAD_OF_HISTORY`; an absent or reset committed offset *with* entries refuses as `FINANCE_GROUP_OFFSETS_EXPIRED`
   (group offsets can expire; the verifier also requires `offsets.retention.minutes` to exceed the intended run lifetime). Streams commits `lastProcessed + 1`, so trailing transaction markers are not in the committed offset
   and no marker arithmetic is needed; a read-committed client cannot see markers and the rule does not require it to.
   `committedNext` less than that value refuses as `FINANCE_HISTORY_AHEAD_OF_OFFSET` (cannot happen under one transaction, so
   it signals corruption, unless the group offsets expired, which has its own code above); greater refuses as `FINANCE_OFFSET_AHEAD_OF_HISTORY`.
5. **History cross-check.** The `decisionChecksum` values of the recorded (INPUT, recorded-outcome) entries must map one-to-one, in order, onto the decision log; the number of recorded entries must equal `headDecision.sequence`; and the last recorded entry's `decisionChecksum` must equal `headDecision.checksum`. Any mismatch refuses activation.
6. Replay from the cut reproduces byte-equal decisions; re-consumption never rebuilds order.

**Ledger state persistence.** The O3a `FinanceCheckpoint` is a logical object only. The finance store keeps ledger state per entity (`position/<account>/<asset>`, `execution/<key>`, `obligation/<key>`, `attempt/<key>/<n>`, `action/<key>`, and a small `meta` with sequence, chain and controls); a restore reads those keys, assembles the `FinanceState`, and passes it to `restore(checkpoint, history)`. The entity wire formats and measured record sizes are S2-A2 deliverables; the invariant is C1 (no whole-state value).

**Capture-to-input mapping.** How a `FiniteLifecycleCaptureV1` ready-execution record becomes a `FinanceCaptureV1` (action key,
execution key, buyer/seller account from the party map, quantity and nanos price conversion) is a deterministic pure function
that is **versioned by `financeStateVersion`**, specified and golden-tested in the O3b design (part of task S2-A2 in the program plan, as its first deliverable)
before the processor is written; it does not affect the opening's canonical bytes except through that version string.

## 9. Isolation and ACL matrix

Principals (nominal names; the O2 harness derives them as `User:<prefix>-ingress`, `-capture`, `-matcher`, `-observer`, and O3b maps the new ones the same way): `calcify_finite_ingress` (existing COMMAND writer), `calcify_finite_capture` (existing capture Streams app, reads
SOURCE, writes CAPTURE), `calcify_finite_control` (new, section 7), `calcify_finite_finance` (new, the finance Streams app),
operators (read only).

| Resource | Allowed | Everything else |
| --- | --- | --- |
| CONTROL topic | Write: `control`; Read: `control` (recovery read, no group), `finance` | refused (incl. `ingress`, `capture`) |
| FINANCE_DECISIONS | Write: `finance`; Read: `finance`, O4 read seam, operators | refused |
| FINANCE_CHANGELOG and finance internal/repartition topics (prefix `financeApplicationId-`) | Read/Write/Create: `finance` | refused |
| CAPTURE topic | Write: `capture` (unchanged); Read: `finance` | `finance` and `control` cannot write |
| TransactionalId `controlTransactionalId` (LITERAL) | Write/Describe: `control` | refused (**including `finance`**) |
| TransactionalId prefix `financeApplicationId` | `finance` | refused |
| Group `financeApplicationId` | Read: `finance`; Describe: `observer` | refused |
| Activation verifier / witness builder (`observer`) | Describe on all eight topics and on the group; Read on CONTROL, FINANCE_DECISIONS and FINANCE_CHANGELOG (`listConsumerGroupOffsets`, begin/end offsets, history and decision replay) | no write anywhere |
| `matcher` (existing) | unchanged; must be **denied** on CONTROL and FINANCE_* (tested) | refused |

The `controlTransactionalId` prefix-distinctness rule (section 3) guarantees the finance principal's prefixed transactional-id
ACL does not cover the control writer's id. Proof (extends the O2 pattern): a negative produce/consume test for every denied
principal-resource pair, tests that `ingress` and `capture` cannot write CONTROL/FINANCE_*, that `finance` cannot write
CAPTURE/COMMAND nor fence the control writer, and the transactional-id fencing test. DB side: nothing new.

## 10. Parser plan and startup witness

- Go `services/matching-engine/internal/streamdirect/calcify_finite_binding.go`: `ParseFiniteBindingV2` and dispatch by schema; v1
  path untouched. The Go startup-witness parser also hard-codes five ranges, five topics and the group-versus-range checks; a Go `ParseFiniteStartupV2` with the eight ranges and the `financeGroup` checks is part of S2-A1. Kotlin `FiniteBindingContract.kt`: v2 opening type and `canonicalBytes()`.
- **Explicit v2 validators** (each is hard-coded for v1 today): `topics.size == 8` and 8 distinct names and ids; the eight-role
  order of section 3; changelog-name checks at index 4 (capture) and index 7 (finance); the exact `openingFields` set including
  the new fields; per-field text validation; ASCII/length tightening and `quoteCurrency != baseAssetId` (section 3);
  prefix-distinctness: `financeApplicationId` is not a prefix of, and does not have as a prefix, `captureApplicationId`, any group id, `controlTransactionalId`, or any topic name other than `FINANCE_CHANGELOG` (the finance principal's prefix ACLs on `financeApplicationId` and `financeApplicationId-` must cover nothing but finance resources), and `controlTransactionalId` has no prefix relation with any application id, group id or transactional id; `openingAccountId` distinct from party accounts;
  `financeConfigDigest` recomputation; fixed `financeStoreName`/`financeStateVersion`; the v1 `limits[]` array unchanged.
- **Topic configuration verifier** (`FiniteKafka.verifyIdentities` hard-codes the five v1 roles): v2 adds CONTROL and FINANCE_DECISIONS as
  `retention.ms = -1`, `retention.bytes = -1`, `cleanup.policy = delete` (both are read from offset 0 for recovery and `verifyChain`, so
  any retention-based deletion breaks recovery), and FINANCE_CHANGELOG as `cleanup.policy = compact` like CAPTURE_CHANGELOG, each with
  `max.message.bytes` >= the largest encoded record (the decision record and finance entries are bounded by section 8 and the O3a size pins; the
  exact per-role byte values are **verifier constants** compiled into `FiniteKafka.verifyIdentities`, derived in the S2-A2 measurement; they are NOT opening fields and do not enter the canonical bytes or the digest, so the golden can be frozen now). The
  verifier and its tests are part of S2-A1.
- **Startup witness `calcify-finite-startup-v2`**: `opening` (v2), `run`, `journal` as v1; `cuts` as v1 (`commandGroup`,
  `captureSourceGroup`) with `ranges` extended to 8 entries in role order (same per-range invariants `begin <= committed <= end`)
  and a new `financeGroup = {groupId, captureNextOffset, controlNextOffset}` with `groupId == financeApplicationId`, where an
  absent committed offset is reported as `"0"` **only when the history has no entry for that topic** (otherwise section 8.4 refuses).
  Both of these (absent offset as 0, and a group cut for the finance group) differ deliberately from v1, whose `cuts()` refuses a
  group with no committed offset; the v1 regression tests must prove v1 stays strict. The ranges and `financeGroup` are stored inside `brokerCuts` (section 6); the 8,192-byte `cuts` cap and wire 32,768 and witness header 8,192 are unchanged and O3b measures v2 against them.

**Stop receipt.** `stages` and `release` take engine and capture receipts only. The finance processor deliberately produces no stop receipt in v2; its evidence is the verified decision log head and the history entry-chain head, read through the O4 seam. (Revisit if O5 needs a finance receipt.)

## 11. Refusal rules (activation)

Refuse, with no run state mutated, when: schema unknown or v1/v2 confused; any v2 field missing, unknown or duplicated; role order
differs; any topic name or id duplicates another; `financeConfigDigest` differs from the digest recomputed from the opening; budgets
differ from the compiled O3a limits (256/128/160/8); `openingAccountId` collides with a party account; any prefix-distinctness rule
fails; CONTROL or FINANCE topics non-empty at genesis, or a genesis offset is not zero; the source generation is already registered
for another binding; the restore cut is incomplete, mismatched or violates section 8 (including `FINANCE_OFFSET_AHEAD_OF_HISTORY`,
`FINANCE_HISTORY_AHEAD_OF_OFFSET`, `FINANCE_GROUP_OFFSETS_EXPIRED`, a failing `verifyChain`, a failing entry chain, or a state not committed by the head decision);
broker topic UUID differs from the registry.

## 12. Test plan (O3b)

1. Golden and mutation corpus (section 5) in Go and Kotlin against the Python reference.
2. Boundary `0014` (register; `stages` unchanged, with a test that v2 cuts inside `brokerCuts` are accepted) and runtime `0077` migration tests; v1 registration regression.
3. v1 regression: all O1/O2 tests unchanged; v1 fixtures keep their digests.
4. Size acceptance: v2 fixture <= 16,384 bytes; v2 witness header <= 8,192 and wire <= 32,768 with a full journal.
5. Isolation matrix (section 9) on the real broker harness, including producer fencing and the prefix-ACL separation.
6. Restore-cut refusal table (section 8), including a forged-but-consistent checkpoint, a tampered refused history entry, and an
   absent committed offset.
7. Real-broker crash test between poll and commit, and control-writer recovery (zombie with an open transaction) per section 7. Cases required: the last records on CAPTURE or CONTROL are aborted or marker-only and the app restarts; Streams commits only one of the two partitions; EOS v2 commit timing is demonstrated, not assumed.
8. History-entry golden (independent encoder) and envelope-to-entries cases (several ready members, none, replay-only).
9. Capture-to-input mapping golden (the S2-A2 mapping).

## 13. Decisions recorded and open items

Decided here (confirm in the final review): fresh source generation for v2; separate control writer with no database; one finance
group = `financeApplicationId`; `openingBytes` held at 16,384; input history with one entry per ledger input (plus `NO_INPUT`), chained, bounded
at 312, fail-closed on overflow; recorded-only `FINANCE_DECISIONS`; v2 identifiers restricted to printable ASCII.

Open (O3b design, not blocking the binding bytes): **P-5** operator authentication for the control writer; **the S2-A2 mapping** the
capture-to-input mapping and its version string; the exact Kafka ACL provisioning mechanism for the new principals.

## 14. Review dispositions

Review 1 (Not ready): P0-1 registration -> section 6; P0-2 size -> sections 3, 10, 12.4; P0-3 config digest -> section 4;
P1-1 -> section 5; P1-2 -> sections 3, 8; P1-3 -> sections 3, 4; P1-4 -> section 8.4 (superseded by review 2); P1-5 -> section 7.

Review 2 (Not ready): P1-A offset rule -> section 8.4 now `committedNext == lastRecorded + 1` with absent offsets as 0 and the
`lastProcessed + 1` commit semantics; the wording about commits per record is corrected (per commit interval). P1-B history ->
section 8 (every consumed record, chained entries, persisted counter, bound 296, recorded-only decisions, outcomes read from
history, store decided: finance store + `FINANCE_CHANGELOG`). P1-C ACL -> sections 3, 7, 9 (control Read, literal
`controlTransactionalId` with no prefix overlap, recovery without a group). P2-1 mapping -> section 8 (versioned, the S2-A2 mapping). P2-2 kind
check -> section 8. P2-3 -> section 3 tightening. P2-4 -> section 7 (writes vs ledger counts, conflict semantics). P2-5 recovery
order -> section 7. P2-6 witness placement -> sections 6, 10. P2-7 sort/hex32 -> sections 3, 5. P2-8 refusal names -> section 11.
P3: Streams config pinned (section 8); genesis binds config digest (section 5); CAPTURE read for `capture` removed (section 9);
collision wording simplified (section 5).

Review 3 (Not ready; review budget exhausted, so these fixes are NOT independently re-reviewed): P1-1 unit of history -> section 8 (entry key
`(sourceRole, sourceOffset, memberOrdinal)`, `NO_INPUT` entries, highest-offset rule, bound 312); P2-1 `stages` -> section 6 (cuts live
inside `brokerCuts`, no change to `stages`); P2-2 prefix distinctness -> sections 3, 10; P2-3 topic configuration -> section 10; P2-4 entry
encoding and golden -> sections 8, 12; P2-5 offset expiry -> section 8.4 (`FINANCE_GROUP_OFFSETS_EXPIRED`); P2-6 v1/v2 strictness
difference -> section 10; P3 error handling, commit interval, funding typo cost, ingress transactional id -> sections 3, 7, 8.

Review 4 (owner-authorized, Ready with non-blocking follow-ups; applied in revision 5): P2-1 verifier constants not opening fields -> section 10; P2-2 verifier/observer/matcher ACL rows and principal-name mapping -> section 9; P2-3 `NO_INPUT` bound source and overflow behavior -> section 8; P2-4 history/decision cross-check -> section 8.5; P2-5 per-entity ledger persistence -> section 8; P3 genesis wording, hex framing, non-ledger entry fields, `lastRecorded = -1`, Go witness parser, ingress transactional-id clause, principal names, stop receipt, test-plan cases, stale section 13 and status line -> sections 3, 8, 9, 10, 12, 13.
