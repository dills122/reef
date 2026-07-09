-- Partitioned archive targets for runtime event and trade history.
--
-- runtime_events and trades stay hot read tables keyed for current API/query
-- paths. Archive movement should copy only rows with typed occurred_at_ts so
-- exported/dropped partitions align to event time instead of archive time.

CREATE TABLE IF NOT EXISTS runtime.trades_archive (
  event_id TEXT NOT NULL,
  trade_id TEXT NOT NULL,
  execution_id TEXT NOT NULL,
  buy_order_id TEXT NOT NULL,
  sell_order_id TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  quantity_units TEXT NOT NULL,
  price TEXT NOT NULL,
  currency TEXT NOT NULL,
  occurred_at TEXT NOT NULL,
  event_id_uuid UUID,
  quantity_units_num NUMERIC,
  price_num NUMERIC,
  occurred_at_ts TIMESTAMPTZ NOT NULL,
  sequence BIGINT,
  archived_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (occurred_at_ts, event_id)
) PARTITION BY RANGE (occurred_at_ts);

CREATE TABLE IF NOT EXISTS runtime.trades_archive_default
  PARTITION OF runtime.trades_archive DEFAULT;

CREATE INDEX IF NOT EXISTS idx_trades_archive_instrument_occurred
  ON runtime.trades_archive(instrument_id, occurred_at_ts, event_id);

CREATE INDEX IF NOT EXISTS idx_trades_archive_sequence
  ON runtime.trades_archive(instrument_id, sequence DESC)
  WHERE sequence IS NOT NULL;

CREATE TABLE IF NOT EXISTS runtime.runtime_events_archive (
  event_id TEXT NOT NULL,
  event_type TEXT NOT NULL,
  order_id TEXT NOT NULL,
  trace_id TEXT NOT NULL,
  causation_id TEXT NOT NULL,
  correlation_id TEXT NOT NULL,
  actor_id TEXT NOT NULL DEFAULT '',
  producer TEXT NOT NULL,
  schema_version TEXT NOT NULL,
  sequence_number BIGINT NOT NULL,
  payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  occurred_at TEXT NOT NULL,
  event_id_uuid UUID,
  occurred_at_ts TIMESTAMPTZ NOT NULL,
  archived_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (occurred_at_ts, event_id)
) PARTITION BY RANGE (occurred_at_ts);

CREATE TABLE IF NOT EXISTS runtime.runtime_events_archive_default
  PARTITION OF runtime.runtime_events_archive DEFAULT;

CREATE INDEX IF NOT EXISTS idx_runtime_events_archive_trace_seq
  ON runtime.runtime_events_archive(trace_id, sequence_number);

CREATE INDEX IF NOT EXISTS idx_runtime_events_archive_order_occurred
  ON runtime.runtime_events_archive(order_id, occurred_at_ts, event_id);
