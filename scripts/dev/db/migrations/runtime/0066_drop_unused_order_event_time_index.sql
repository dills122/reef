-- Order event reads use the order/trace index; latest OrderModified uses
-- idx_runtime_events_order_modified_lifecycle. The broad typed time index
-- adds a write to every timestamped runtime event without serving those reads.
DROP INDEX IF EXISTS runtime.idx_runtime_events_order_occurred_typed;
