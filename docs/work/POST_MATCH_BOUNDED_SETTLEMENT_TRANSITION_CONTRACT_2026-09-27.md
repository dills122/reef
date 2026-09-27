# Bounded settlement transition — 2026-09-27

Status: accepted arbitration design; shadow implementation under local
verification and independent review. This contract does not approve public
settlement reads or establish 10k/s capacity. See [D-059](../DECISIONS.md#d-059-durable-settlement-admission-order-for-scarce-accounts)
and the [research spike](../research/POST_MATCH_ACCOUNT_ARBITRATION_SPIKE_2026-09-27.md).

## Input and progress

Consume only obligations committed by the bounded obligation stage. Admission
reads a bounded contiguous source interval, verifies obligation coverage and
the underlying intake manifest, then assigns one durable global rank to the
window. Trades inside that window retain source sequence/effect order. A
transactional counter, immutable admission record, dependency and keyed
account-membership rows, and per-partition admission frontier commit together. Empty
windows also get a rank so source coverage remains explicit. A failed
transaction leaves no rank or frontier gap. Matching acceptance gains no
synchronous database write.

Execution has a separate `(event_stream, partition_id)` frontier. It may apply
an admitted window only after every earlier admitted window that touches one
of its account keys, or precedes it on the same source partition, has a
committed completion proof. It rechecks source and admission digests, then
advances transition coverage, workflow facts, account state, ledger facts,
completion proof, and execution frontier in one settlement transaction.
Disjoint windows on different partitions may execute concurrently. Replay
compares committed facts and balances without posting again. Gap, generation
change, dependency corruption, policy drift, or partial state stops progress.

Deterministic replay includes the retained admission log. A rebuilt target
must import/retain that log and rank order; fresh admission from matching
facts alone is a new arbitration history and can choose a different scarce
account winner. Admission facts and immutable account membership need backup
and audit retention. The latest prior rank for each account is found by an
indexed lookup over that membership, so no mutable account-tail pointer must
survive restore.

## Account ownership and concurrency

Cash is keyed by participant, account, and currency; security by participant,
account, and instrument. Include stream, source generation, and scenario run in
the shadow key so a rebuild cannot reuse another generation's balance. Opening
resources come from explicit resource-position facts and are summarized by key
on insert, update, and delete, outside the matching command path. The transition
seeds and locks only affected account keys, in a stable global key order across
partitions. Admission dependencies, rather than lock arrival, define which
window decides first for a scarce shared account. A separate
post-lock read checks the latest committed account
checkpoint against cached balance and source opening. Each changed balance gets
a versioned checkpoint with its before/after delta and digest of that window's
ledger postings. Replay checks the window's checkpoints against its exact
ledger legs, adjacent checkpoint balances, and the latest account state. It
applies ledger deltas and checkpoints in the same transaction as
the trade's workflow facts and progress. A run with no resource positions keeps the current
simulation convention of unbounded resources; once positions are configured,
cash and security sufficiency are checked for every instant attempt. Freeze
resource setup before a shadow run starts; mutable opening resources need a
durable versioned authority and recovery rule before public cutover.

## Workflow and financial finality

Each trade has one idempotent obligation identity. `instant-post-trade` emits
allocation, confirmation, affirmation, clearing acceptance, novation,
instruction, attempt, and both DvP leg outcomes. Successful cash and security
legs post four balanced ledger entries: buyer cash debit, seller cash credit,
seller security debit, buyer security credit. Mark an obligation `SETTLED` only
after all four entries and both successful leg outcomes commit. Insufficient
resources open a typed break and leave ledger balances unchanged. A later repair
and retry use the same keyed transition rules. `ops-realistic` remains pending
until an explicit policy-timed command advances it; creation alone never
implies settlement. Both modes keep the same fact shapes and bound profile
version from the obligation.

## Delivery and qualification

Keep these tables and worker shadow-only until exact parity with legacy
settlement reads, resource behavior, failure injection, repair, and ledger
proof is established. The source control-plane timing gap described in the
[policy contract](POST_MATCH_BOUNDED_SETTLEMENT_POLICY_CONTRACT_2026-09-27.md)
also blocks public cutover. Local tests must cover same-account trades on
different source partitions, replay, empty intervals, insufficient resources,
cash and security conservation, crash rollback, changed resource or policy
inputs, opposing shared-account contention, and disjoint parallel execution.
The later disposable-droplet campaign measures settlement trades/s,
ledger facts/s, account contention, frontier lag, and in-load visibility with
all mandatory stages enabled.
