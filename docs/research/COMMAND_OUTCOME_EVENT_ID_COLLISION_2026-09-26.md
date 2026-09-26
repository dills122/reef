# Command outcome event IDs under full-projection load

On September 26, a fresh c-32 full-projection control based on `ffc5b08a`
accepted and materialized 2,999,951 commands during a 10k/s, 300-second run,
but projected only 1,021 at the timed collection. Every projector repeatedly
failed in `runtime_persist_submit_outcome_timeline_stage` with PostgreSQL's
`ON CONFLICT DO UPDATE command cannot affect row a second time`. This is a
correctness/setup failure, not a throughput measurement. Original failed
reports and projector status are preserved under
`artifacts/projection-dirty-f02-20260926/failed-control/` on the F02 branch.

Matching engine generated constant rejection event IDs such as
`evt-reject-order-filled` and `evt-reject-order-not-found` for distinct durable
commands. It also reused accepted modification IDs derived only from order ID.
Migration `0068_event_replay_conflicts.sql` correctly rejects changed immutable
event IDs; multiple such outcomes in one projection batch hit PostgreSQL's
multi-row conflict rule before the intended replay check.

Command outcome events now use `evt-command-outcome-<commandId>` when a command
ID is present. The same command replays to the same ID; distinct accepted or
rejected commands have distinct IDs even when they concern the same order.
Legacy direct service calls without a command ID retain their prior IDs.
Focused service and direct-stream tests cover repeated rejection and repeated
accepted modification identities. A fresh hosted control is required before
any F02 or F04 capacity conclusion.
