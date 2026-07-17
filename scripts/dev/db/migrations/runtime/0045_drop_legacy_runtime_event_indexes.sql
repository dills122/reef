-- Drop legacy all-event indexes that are superseded by the live/typed runtime
-- event indexes. Keep the order-scoped occurred_at index for now because the
-- lifecycle projector still uses text occurred_at ordering for modification
-- selection; remove that only with a dedicated lifecycle query/index change.

DROP INDEX IF EXISTS runtime.runtime_events_occurred_at_idx;
DROP INDEX IF EXISTS runtime.runtime_events_trace_seq_idx;
DROP INDEX IF EXISTS runtime.idx_runtime_events_occurred_event;
