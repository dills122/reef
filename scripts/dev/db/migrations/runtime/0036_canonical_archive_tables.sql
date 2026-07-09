-- Partitioned archive targets for canonical venue-event materialization history.
--
-- Keep canonical_venue_event_batches and canonical_command_outcomes as hot
-- lookup/projection tables. Archive older materialized rows by materialized_at
-- so retention/drop/export can operate on partitions without rewriting primary
-- command-id and event-stream lookup constraints in place.

CREATE TABLE IF NOT EXISTS runtime.canonical_venue_event_batches_archive (
  batch_id TEXT NOT NULL,
  shard_id TEXT NOT NULL,
  partition_id INTEGER NOT NULL,
  command_stream TEXT NOT NULL,
  event_stream TEXT NOT NULL,
  first_sequence BIGINT NOT NULL,
  last_sequence BIGINT NOT NULL,
  command_count INTEGER NOT NULL,
  payload_checksum TEXT NOT NULL,
  payload_format TEXT NOT NULL DEFAULT 'venue-event-batch-json',
  payload_version TEXT NOT NULL DEFAULT 'v1',
  payload_json JSONB NOT NULL,
  created_at TEXT NOT NULL,
  materialized_at TIMESTAMPTZ NOT NULL,
  created_at_ts TIMESTAMPTZ,
  archived_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (materialized_at, event_stream, batch_id)
) PARTITION BY RANGE (materialized_at);

CREATE TABLE IF NOT EXISTS runtime.canonical_venue_event_batches_archive_default
  PARTITION OF runtime.canonical_venue_event_batches_archive DEFAULT;

CREATE INDEX IF NOT EXISTS idx_canonical_venue_event_batches_archive_batch
  ON runtime.canonical_venue_event_batches_archive(event_stream, batch_id, materialized_at);

CREATE INDEX IF NOT EXISTS idx_canonical_venue_event_batches_archive_partition_seq
  ON runtime.canonical_venue_event_batches_archive(partition_id, first_sequence, last_sequence);

CREATE TABLE IF NOT EXISTS runtime.canonical_command_outcomes_archive (
  command_id TEXT NOT NULL,
  batch_id TEXT NOT NULL,
  shard_id TEXT NOT NULL,
  partition_id INTEGER NOT NULL,
  command_stream TEXT NOT NULL,
  event_stream TEXT NOT NULL,
  stream_sequence BIGINT NOT NULL,
  delivered_count BIGINT NOT NULL,
  command_type TEXT NOT NULL,
  payload_hash TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  order_id TEXT NOT NULL,
  result_status TEXT NOT NULL,
  reject_code TEXT NOT NULL,
  result_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  materialized_at TIMESTAMPTZ NOT NULL,
  occurred_at_ts TIMESTAMPTZ,
  archived_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (materialized_at, command_id)
) PARTITION BY RANGE (materialized_at);

CREATE TABLE IF NOT EXISTS runtime.canonical_command_outcomes_archive_default
  PARTITION OF runtime.canonical_command_outcomes_archive DEFAULT;

CREATE INDEX IF NOT EXISTS idx_canonical_command_outcomes_archive_command
  ON runtime.canonical_command_outcomes_archive(command_id, materialized_at);

CREATE INDEX IF NOT EXISTS idx_canonical_command_outcomes_archive_partition_seq
  ON runtime.canonical_command_outcomes_archive(partition_id, stream_sequence, materialized_at);
