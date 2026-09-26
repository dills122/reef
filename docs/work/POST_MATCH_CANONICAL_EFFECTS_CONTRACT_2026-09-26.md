# Canonical post-match effects v1

Status: Wave 1 implementation contract. No live route or consumer cutover yet.
Decision: [D-058](../DECISIONS.md#d-058-versioned-canonical-effects-for-independent-post-match-consumers).

## Source and identity

The committed `runtime.canonical_venue_event_batches` payload and its
`runtime.canonical_command_outcomes` rows remain authority. The matching engine
adds `effectVersion: 1` and `orderStates` to each supported command result.
Each changed-order state is the final state **after that command**, including
resting maker fills and self-trade-prevention cancellations. The engine emits
these facts before publishing its durable venue batch; the command acceptance
path gains no synchronous database write.
Batch rollback defers terminal-order retention eviction until after these
snapshots are captured and the durable publish succeeds.

An effect is identified by `(eventStream, partitionId, streamSequence,
effectOrdinal)`. Ordinals start at zero in each command outcome. The envelope
also carries batch ID, command ID, command type, payload hash, and underlying
event ID when one exists. Retries decode to the same identity and payload.
Poison/failed commands may have no recoverable command ID; stream position and
payload hash still identify them, and they never create business effects.
Distinct payloads at one identity are semantic conflicts. Consumers validate
canonical source membership, not merely an event ID string.

Decoder order for each supported v1 outcome:

1. Accepted, rejected, or failed command outcome.
2. For each trade in source order: buy execution, sell execution, trade.
3. Final changed-order states in source order, incoming order first, then
   affected makers. A state appears at most once in one command outcome.

The decoder verifies every trade has exactly its buy and sell execution with
matching order IDs, quantity, price, and currency. Each execution and trade
has a unique event ID. Every execution and trade order ID has a corresponding
changed-order state in that command. Rejected commands have no business
effects. Unknown versions, malformed fields, and conflicts fail closed.

## Ownership and causality

Accepted submits supply immutable order ID, venue session, instrument,
participant, account, side, quantity, price, currency, and time in force.
Client order ID and run ID retain the producer's optional empty-string values;
the routed venue session is required. The decoder rejects a missing session.
Modify, cancel, maker execution, and trade facts refer to those orders by ID.
Consumers resolve missing ownership by keyed canonical order directory; they
never scan all orders in a run. An unresolved maker/order or cross-partition
dependency is visible lag, not a silent default. The directory is rebuilt from
canonical accepted-order facts and protected by the same replay checks.

Per-consumer progress is `(consumer, eventStream, partitionId,
lastContiguousStreamSequence)` plus source-generation/coverage evidence.
The empty frontier for Kafka partition `p` starts at `p << 48`, matching the
matching engine's encoded `offset + 1` sequence. Partition zero starts at zero.
An initial outcome above that origin is an unproven source gap.
The runtime database holds one durable `runtime.postmatch_source_generation`
UUID. Isolated consumers read it before source windows and reject a target
frontier from a different generation. A new runtime database receives a new
generation during migration; an ordinary database restart retains it.
Effects, dedupe identities, and frontier advance commit in one target-store
transaction. A batch can advance only through verified contiguous source
membership. Any valid Kafka offset gap needs explicit source coverage proof;
absence of a row does not itself prove a gap is safe. Consumers may lag each
other; combined responses carry the required as-of coverage. Direct
admin/protective events keep their own durable audit source and frontier.

The first operational apply path reads bounded source ranges from retained
canonical outcome and batch rows. It checks exact v1 membership at every
position and records one receipt per outcome. The source window, keyed accepted
order identities, consumer effects, coverage digest, and frontier are committed
in one transaction in the isolated target store. Exact replay verifies stored
coverage and receipts before skipping effects; changed payloads and overlapping
windows fail closed. It only initializes a new frontier at the partition's
encoded origin. Kafka offset holes and nonzero starting offsets still require an authoritative
source proof and bootstrap contract before those windows can advance. The
strict reader currently stops there rather than treating missing rows as proof.
Opt-in shadow workers read indexed partition high-water marks, verify bounded
windows against retained batch membership, and commit live state and market
state under separate frontiers. They do not switch public reads.

## Compatibility and promotion

Unversioned historical batches stay on the legacy projector until an exact
backfill strategy is verified. New effects use isolated stores and tables;
legacy normalized tables are comparison and rollback evidence. No public read
switch happens in Wave 1. Later promotion requires closed-cohort live, market,
audit, and settlement parity, crash/replay/gap proof, then one integrated
capacity campaign on disposable droplets. Local Docker covers quick functional
checks. Backbone is not a benchmark or migration prerequisite for this work.
