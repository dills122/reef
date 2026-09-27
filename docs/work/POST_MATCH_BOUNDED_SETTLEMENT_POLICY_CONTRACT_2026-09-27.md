# Bounded settlement policy binding — 2026-09-27

Status: implementation contract for the opt-in shadow obligation stage. This
does not approve public settlement cutover or claim post-trade capacity.

## Authority and timing

`settlement.canonical_trade_intake` is the verified trade input. The runtime
source owns scenario-run and venue-session profile assignments plus active
platform profiles. The shadow obligation stage resolves their effective
selection in one repeatable-read source snapshot and binds it immutably to
`(event_stream, source_generation, run_id, venue_session_id)` when it first
processes a trade for that key. Every later window re-resolves and compares the
selection to that binding. The binding includes profile ID, policy version,
mode, settlement cycle, netting mode, ledger posting mode, and selection source.
Any change to those values stops progress; it never rewrites prior obligations.

The current control-plane tables are mutable and have no historical version
log. First-consumer observation therefore cannot prove the profile that was
effective at execution if configuration changed before the consumer caught up.
Shadow parity and the disposable-droplet workload require profile assignments
and definitions to be frozen from run/session setup until source and obligation
frontiers catch up. Public cutover additionally requires a durable pre-trade
binding or versioned control-plane history, plus a recovery rule for existing
runs. This is an explicit cutover gate, not a silent assumption.

## Bounded application

The obligation stage advances independently over contiguous intake sequence
windows, including ranges with no trades. Each transaction locks its own
partition frontier, verifies the intake generation and coverage, inserts exact
trade obligations and policy bindings, records a window digest, then advances
progress atomically. It reads only the bounded source range and keyed bindings.
Intake receipts now commit each source position's trade count and digest with
the trade rows and intake frontier. The obligation stage checks every receipt
and the corresponding trade rows before advancing; absent or mismatched
membership fails closed. Receipts written before migration `settlement/0009`
have no manifest. Rebuild the isolated shadow intake from canonical source
before enabling this obligation worker on an existing settlement target.
The frontier means obligations exist for the covered trades; it does not mean
allocation, clearing, settlement, or ledger finality. Those transitions need
separate keyed account state and completion evidence before capacity testing.

Instant and realistic profiles use the same obligation shape. Realistic mode
stays pending according to policy. Instant mode also stays pending at this
stage; its complete workflow, DvP legs, and ledger posting arrive in the next
bounded transition and are a prerequisite to enabling the path.
