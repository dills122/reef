-- Isolated live post-match store. Canonical venue batches remain in runtime DB.
CREATE SCHEMA IF NOT EXISTS postmatch;

CREATE TABLE postmatch.consumer_frontiers (
  consumer_name TEXT NOT NULL,
  event_stream TEXT NOT NULL,
  partition_id INTEGER NOT NULL CHECK (partition_id >= 0),
  source_generation TEXT NOT NULL,
  last_stream_sequence BIGINT NOT NULL DEFAULT 0 CHECK (last_stream_sequence >= 0),
  last_batch_id TEXT,
  last_coverage_digest TEXT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (consumer_name, event_stream, partition_id),
  CHECK (consumer_name <> '' AND event_stream <> '' AND source_generation <> ''),
  CHECK (last_coverage_digest IS NULL OR last_coverage_digest ~ '^[0-9a-f]{64}$')
);

-- A claim coordinates workers; the frontier row lock remains the transaction
-- authority. A lease alone never permits a gap or a frontier advance.
CREATE TABLE postmatch.consumer_stage_claims (
  consumer_name TEXT NOT NULL,
  event_stream TEXT NOT NULL,
  partition_id INTEGER NOT NULL CHECK (partition_id >= 0),
  source_generation TEXT NOT NULL,
  claim_owner TEXT NOT NULL,
  claim_expires_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (consumer_name, event_stream, partition_id),
  CHECK (consumer_name <> '' AND event_stream <> '' AND source_generation <> '' AND claim_owner <> '')
);

-- Source coverage is verified outside this database against canonical source
-- membership. The digest binds the exact covered positions and payloads. A
-- consumer must write this and the frontier in its effect transaction.
CREATE TABLE postmatch.consumer_source_coverage (
  consumer_name TEXT NOT NULL,
  event_stream TEXT NOT NULL,
  partition_id INTEGER NOT NULL CHECK (partition_id >= 0),
  source_generation TEXT NOT NULL,
  from_exclusive_sequence BIGINT NOT NULL CHECK (from_exclusive_sequence >= 0),
  through_inclusive_sequence BIGINT NOT NULL,
  source_member_count INTEGER NOT NULL CHECK (source_member_count >= 0),
  source_digest TEXT NOT NULL CHECK (source_digest ~ '^[0-9a-f]{64}$'),
  covered_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (consumer_name, event_stream, partition_id, through_inclusive_sequence),
  CHECK (through_inclusive_sequence > from_exclusive_sequence),
  CHECK (consumer_name <> '' AND event_stream <> '' AND source_generation <> '')
);

-- One receipt per source command, not one write per decoded effect. The digest
-- covers the entire versioned result; effect ordinals are derived from it.
CREATE TABLE postmatch.consumer_outcome_receipts (
  consumer_name TEXT NOT NULL,
  event_stream TEXT NOT NULL,
  partition_id INTEGER NOT NULL CHECK (partition_id >= 0),
  source_generation TEXT NOT NULL,
  stream_sequence BIGINT NOT NULL CHECK (stream_sequence > 0),
  batch_id TEXT NOT NULL,
  command_id TEXT NOT NULL,
  command_payload_hash TEXT NOT NULL,
  result_digest TEXT NOT NULL CHECK (result_digest ~ '^[0-9a-f]{64}$'),
  effect_count INTEGER NOT NULL CHECK (effect_count > 0),
  applied_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (consumer_name, event_stream, partition_id, source_generation, stream_sequence),
  CHECK (consumer_name <> '' AND event_stream <> '' AND source_generation <> '' AND batch_id <> '' AND command_payload_hash <> '')
);

-- Rebuildable keyed ownership cache, populated from accepted-order effects.
CREATE TABLE postmatch.canonical_order_directory (
  event_stream TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  order_id TEXT NOT NULL,
  engine_order_id TEXT NOT NULL,
  client_order_id TEXT NOT NULL,
  run_id TEXT NOT NULL,
  venue_session_id TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  participant_id TEXT NOT NULL,
  account_id TEXT NOT NULL,
  side TEXT NOT NULL CHECK (side IN ('BUY', 'SELL')),
  order_type TEXT NOT NULL,
  quantity_units TEXT NOT NULL,
  limit_price TEXT NOT NULL,
  currency TEXT NOT NULL,
  time_in_force TEXT NOT NULL,
  accepted_at TEXT NOT NULL,
  source_partition_id INTEGER NOT NULL CHECK (source_partition_id >= 0),
  source_stream_sequence BIGINT NOT NULL CHECK (source_stream_sequence > 0),
  source_effect_ordinal INTEGER NOT NULL CHECK (source_effect_ordinal >= 0),
  PRIMARY KEY (event_stream, source_generation, order_id),
  UNIQUE (event_stream, source_generation, source_partition_id, source_stream_sequence, source_effect_ordinal),
  CHECK (event_stream <> '' AND source_generation <> '' AND order_id <> '' AND venue_session_id <> '' AND instrument_id <> ''
    AND participant_id <> '' AND account_id <> '')
);

CREATE INDEX idx_canonical_order_directory_account
  ON postmatch.canonical_order_directory (account_id, event_stream, source_generation, order_id);
