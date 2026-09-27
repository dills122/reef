# Bounded settlement transition — 2026-09-27

Status: draft shadow implementation. Independent review found that scarce
accounts shared across source partitions can settle in different orders on
rebuild. Do not enable this worker for a shadow cohort until deterministic
contention authority is designed and verified. This contract does not approve
public settlement reads or establish 10k/s capacity.

## Input and progress

Consume only obligations committed by the bounded obligation stage. Give the
transition its own `(event_stream, partition_id)` frontier and source generation.
Read a bounded contiguous source interval, verify every overlapping obligation
coverage digest against its bounded intake interval and the underlying intake
manifest, and advance transition coverage, workflow
facts, account state, ledger facts, and frontier in one settlement transaction.
Empty source intervals advance progress. A replay compares all committed facts
and balances without posting another leg. A gap, generation change, policy
drift, or ambiguous partial state stops progress.

## Account ownership and concurrency

Cash is keyed by participant, account, and currency; security by participant,
account, and instrument. Include stream, source generation, and scenario run in
the shadow key so a rebuild cannot reuse another generation's balance. Opening
resources come from explicit resource-position facts and are summarized by key
on insert, update, and delete, outside the matching command path. The transition
seeds and locks only affected account keys, in a stable global key order across
partitions. Stable lock-key order prevents some deadlocks but does not define
which partition's trade decides first for a scarce shared account. A separate
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
inputs, and opposing shared-account contention. The later disposable-droplet campaign measures settlement trades/s,
ledger facts/s, account contention, frontier lag, and in-load visibility with
all mandatory stages enabled.
