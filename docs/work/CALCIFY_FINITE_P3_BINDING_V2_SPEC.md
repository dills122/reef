# Calcify Finite P3: Binding v2 Specification (S1-B1)

Status: **draft for independent docs review.** Specification only; no code, no schema change, no migration is made by this
document. It discharges the O3 packet section 5 requirement that the binding v2 specification be accepted before O3b starts.
Decisions D7 (option B) and the proposed policies P-3 (new schema version) and P-4 (budget classes) are inputs; see the
[O3 packet](CALCIFY_FINITE_P3_O3_PACKET.md) and the [program plan](CALCIFY_PROGRAM_STATUS_AND_SPRINT_PLAN_2026-10-11.md).

## 1. Scope and non-goals

In scope: the registered opening for runs that include financial admission: schema id, roles, identities, canonical bytes and
digest, registry constraint, parsers, control-writer placement, isolation proof pattern, restore cut and refusal rules, test
plan. Out of scope: the financial processor itself (O3b), SQL/read seam (O4), capacity claims.

Non-goals: changing any v1 byte, digest, fixture, parser behavior or migration. v1 runs (O1/O2) stay valid and readable.

## 2. Versioning (confirms P-3)

New schema `calcify-finite-binding-v2`. v1 is frozen. A parser dispatches on the `schema` field **before** any other field is
interpreted; an unknown schema is refused. There is no in-place upgrade: a v2 opening is a **new** registered opening with a
new digest, never a rewrite of a v1 row. A run is bound to exactly one binding digest for its lifetime.

## 3. Opening document (v2)

v2 is the v1 opening plus a finance block. Every v1 field keeps its name, type and validation. Differences:

| Field | v1 | v2 |
| --- | --- | --- |
| `schema` | `calcify-finite-binding-v1` | `calcify-finite-binding-v2` |
| `topics` | 5 entries, roles in fixed order `COMMAND, SOURCE, CAPTURE, OWNERSHIP, CAPTURE_CHANGELOG` | 8 entries, fixed order: the five above, then `CONTROL, FINANCE_DECISIONS, FINANCE_CHANGELOG` |
| `financeApplicationId` | absent | required, operational text, distinct from `captureApplicationId` |
| `financeStoreName` | absent | required, fixed `finite-finance` |
| `financeStateVersion` | absent | required, fixed `calcify-finite-finance-state-v1` |
| `financeConfigDigest` | absent | required, 64 lowercase hex: `FinanceConfig.digest()` of the pinned accounts and assets (O3a) |
| `financeBudget` | absent | required decimal-string object `{captures, controls, settleAttempts, records}` = the pinned per-class limits (128, 8, 160, 296) |
| `controlConsumerGroup`, `financeConsumerGroup` | absent | required, distinct from each other and from every v1 group |
| `initialControlNextOffset`, `initialFinanceCaptureGroupNextOffset` | absent | required, genesis value `"0"` |

Rules carried over unchanged: strict duplicate-key detection, exact field sets (missing and unknown fields refused), decimal
string spelling for numbers, UTF-8 strictness, party normalization, topic UUID spelling, all names and topic ids distinct,
`FINANCE_CHANGELOG` name = `financeApplicationId + "-finite-finance-changelog"` (mirrors the v1 changelog-name rule), genesis
offsets all zero, opening byte cap (v2 raises `openingBytes` cap to 24,576 and the budget hash covers it).

The financial parties and assets are **derived, not duplicated**: accounts come from `parties[].accountId`, assets are
`quoteCurrency` (cash) and `baseAssetId` (security), and the opening account id is a fixed reserved literal pinned in the
spec of `financeConfigDigest` (see the O3a `FinanceConfig`); the digest binds them so a mismatch refuses activation.

## 4. Canonical bytes and digest

`canonicalBytes()` for v2 = ASCII prefix `calcify-finite-binding-v2\0`, then **the v1 body algorithm unchanged** applied to the v2
document (profile hash, budget hash, the seven framed strings, parties sorted, **all eight** topics framed name+id, the three
ints, the nine v1 framed strings, the genesis byte, the four v1 initial offsets), then the finance suffix:

```
frame(financeApplicationId) frame(financeStoreName) frame(financeStateVersion) hex32(financeConfigDigest)
int32(captures) int32(controls) int32(settleAttempts) int32(records)
frame(controlConsumerGroup) frame(financeConsumerGroup) int64(initialControlNextOffset) int64(initialFinanceCaptureGroupNextOffset)
```

Digest = SHA-256 of those bytes. Because the prefix differs and the topic count is framed into the body, a v1 byte string can
never collide with a v2 byte string. The v1 digest of the same run is **not** reused; operators must treat the two as
different registrations.

Golden plan: a Python reference encoder (independent of Go and Kotlin) produces the expected hex for the fixture
`contracts/calcify/finite-binding-v2.json`; the Go parser and the Kotlin parser must each reproduce the digest and the full
canonical hex. Mutation corpus: every field changed one at a time changes the digest; each reordered role, duplicate topic,
missing finance field, extra field and v1/v2 schema confusion is refused.

## 5. Registry (`runtime.calcify_topic_identities`)

Additive migration `0077`: replace the role CHECK with the set `{verified, output, COMMAND, CAPTURE, OWNERSHIP,
CAPTURE_CHANGELOG, CONTROL, FINANCE_DECISIONS, FINANCE_CHANGELOG}` (same pattern as `0076`). `SOURCE` is deliberately absent
from the CHECK today: per `0076` it remains a row in the source-generation registry, not a topic-identity role, and v2 keeps
that. Existing rows unchanged. The
registrar role gets no new privileges beyond inserting the new role values (see the [DB roles](CALCIFY_FINITE_P3_DB_ROLES.md));
`scripts/dev/db/migrate.test.mjs` gains the new migration entry and a CHECK test that v1 role values still insert and an unknown
role is refused.

## 6. Application, store, and processor identity

- `financeApplicationId`: Kafka Streams application id, distinct from `captureApplicationId`; its internal topics are named
  from it and must not collide with the capture application's.
- `financeStoreName`: `finite-finance`; per-entity keyed state (positions, executions, obligations, attempts, actions) with
  separate keys, never a whole-state value (G9 lesson).
- Consumer groups: `financeConsumerGroup` reads CAPTURE; `controlConsumerGroup` reads CONTROL. Offsets for both are committed
  in the same Kafka transaction as the history append (section 8).

## 7. Control writer placement (resolves the O3 packet item P-3/P-4 question)

**Decision proposed: a separate small component, `FiniteControlWriter`, in platform-runtime, with its own principal and
transactional id, not an extension of the finite ingress adapter.** Reasons: the ingress adapter's authority is the COMMAND
stream and its isolation proof is built on that; sharing a principal would let a COMMAND-topic credential write controls, and
D4 says trading principals never fund. The writer: authenticates an OPERATOR principal; maps a request to `FinanceInputV1`
(`calcify_finance.proto`, PR 494) with `FinanceAuthorityV1.OPERATOR`; is idempotent by `ActionKey`; refuses beyond the control
budget; publishes in a Kafka transaction with a deterministic transactional id; keys are null and partition is 0. It never
writes COMMAND, SOURCE, CAPTURE or OWNERSHIP.

Alternative considered and rejected: extend the ingress adapter. It would put control traffic behind the COMMAND-stream
credential and reuse its admission state, weakening the exclusive-writer proof.

## 8. Determinism and restore cut (option B)

The financial processor consumes CAPTURE and CONTROL. When it accepts an input it appends it to its own transactional history
(a `FINANCE_DECISIONS` record carrying the O3a `Decision`) and commits both consumer offsets and the history append in **one**
Kafka transaction. Order is therefore defined by the history, not by arrival.

Restore cut = the tuple `(captureOffset, controlOffset, financeHistorySequence, financeChain)`:

1. The cut names all four; any missing component refuses activation.
2. `financeChain` must equal the head of the replayed `FINANCE_DECISIONS` history (O3a `restore(checkpoint, expectedChainHead)`
   semantics); a checkpoint that does not sit at the trusted head is refused.
3. Binding digest on the cut must equal the active v2 digest; a v1 digest on a v2 cut (or the reverse) refuses.
4. Replay from the cut over the recorded history reproduces byte-equal decisions; re-consumption is never used to rebuild order.
5. Offsets beyond the recorded history (consumed but not recorded) cannot exist by construction (single transaction); if
   observed, activation refuses as `FINANCE_OFFSET_AHEAD_OF_HISTORY`.

Spike S1-B2 tests items 3 and 5 against `TopologyTestDriver` before O3b begins.

## 9. Isolation and ACL proof pattern

Reuse the O2 pattern (`FiniteIngress`, role matrix, isolation proof):

| Topic role | Writer principal | Reader principals |
| --- | --- | --- |
| CONTROL | `calcify_finite_control` only | finance processor group |
| FINANCE_DECISIONS | finance processor transactional id only | verifier/read seam (O4), operator |
| FINANCE_CHANGELOG | finance Streams app (internal) only | finance Streams app |

Proof: a negative test per pair (every other principal's produce attempt is refused by the broker), plus detection that the
CONTROL topic cannot be written by the ingress principal. DB side: the registrar role registers the three new roles; no
financial role can write the capture identities.

## 10. Parser plan

- Go: `services/matching-engine/internal/streamdirect/calcify_finite_binding.go` gains `ParseFiniteBindingV2`, dispatch by
  schema; v1 path untouched; shared validators extracted without changing v1 behavior (v1 golden test must still pass byte for byte).
- Kotlin: `FiniteBindingContract.kt` gains a v2 opening type and `canonicalBytes()` v2; `roles` stays the v1 list and a new
  `rolesV2` is introduced; the startup witness for v2 adds the three ranges and the two new group cuts (`finite-startup-v2`).
- Cross-check: Go, Kotlin and the Python reference all agree on the fixture digest and canonical hex.

## 11. Refusal rules (activation)

Refuse (fail closed, no run state mutated) when: schema unknown or v1/v2 confused; any v2 field missing, unknown or
duplicated; role order differs; any topic name or id duplicates another; `financeConfigDigest` does not match the digest the
processor computes from the opening's parties and assets; finance budgets differ from the compiled O3a limits; CONTROL or
FINANCE topics exist with non-empty content at genesis; restore cut incomplete or binding digest mismatched; broker topic
UUID differs from the registry.

## 12. Test plan (O3b implementation)

1. Golden and mutation corpus (section 4) in Go and Kotlin.
2. Migration `0077` CHECK test and `migrate.test.mjs` entry.
3. v1 regression: all O1/O2 tests pass unchanged; v1 fixtures keep their digests.
4. Isolation matrix (section 9) on the real broker harness.
5. Restore-cut refusal table (section 8).
6. Determinism spike results (S1-B2) attached to the O3b design.

## 13. Open questions for the docs review

1. Is a separate `FiniteControlWriter` accepted, or should the control path ride the ingress adapter's process with a separate
   principal (same isolation, less code)?
2. Raise the `openingBytes` cap to 24,576 as proposed, or hold at 16,384 and trim fields?
3. `records = 296` (128 + 160 + 8) versus the contract's 320 history records: confirm 296 or restore 320 as the record bound.
4. Should `financeConfigDigest` include the opening account id literal, or is a fixed reserved id acceptable?
