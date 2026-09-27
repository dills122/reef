-- Shadow audit/history owner. Legacy runtime_events remains the mixed-route
-- authority for direct admin and protective events until route parity cutover.
CREATE TABLE runtime.canonical_audit_frontiers (
  event_stream TEXT NOT NULL,
  partition_id INTEGER NOT NULL CHECK (partition_id >= 0),
  source_generation TEXT NOT NULL,
  last_stream_sequence BIGINT NOT NULL CHECK (last_stream_sequence >= 0),
  last_coverage_digest TEXT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (event_stream, partition_id),
  CHECK (event_stream <> '' AND source_generation <> ''),
  CHECK (last_coverage_digest IS NULL OR last_coverage_digest ~ '^[0-9a-f]{64}$')
);

-- A locked frontier row claims one partition for the duration of its write.
-- Coverage and every effect commit in the same transaction as advancement.
CREATE TABLE runtime.canonical_audit_coverage (
  event_stream TEXT NOT NULL,
  partition_id INTEGER NOT NULL CHECK (partition_id >= 0),
  source_generation TEXT NOT NULL,
  from_exclusive_sequence BIGINT NOT NULL CHECK (from_exclusive_sequence >= 0),
  through_inclusive_sequence BIGINT NOT NULL,
  source_member_count INTEGER NOT NULL CHECK (source_member_count > 0),
  source_digest TEXT NOT NULL CHECK (source_digest ~ '^[0-9a-f]{64}$'),
  covered_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (event_stream, partition_id, through_inclusive_sequence),
  CHECK (through_inclusive_sequence > from_exclusive_sequence),
  CHECK (event_stream <> '' AND source_generation <> '')
);

-- Full canonical result retained once per source position for lossless replay.
CREATE TABLE runtime.canonical_audit_outcomes (
  event_stream TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  partition_id INTEGER NOT NULL CHECK (partition_id >= 0),
  stream_sequence BIGINT NOT NULL CHECK (stream_sequence > 0),
  batch_id TEXT NOT NULL,
  command_id TEXT NOT NULL,
  command_type TEXT NOT NULL,
  command_payload_hash TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  order_id TEXT NOT NULL,
  result_status TEXT NOT NULL,
  result_payload JSONB NOT NULL,
  result_digest TEXT NOT NULL CHECK (result_digest ~ '^[0-9a-f]{64}$'),
  effect_count INTEGER NOT NULL CHECK (effect_count > 0),
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (event_stream, source_generation, partition_id, stream_sequence),
  CHECK (event_stream <> '' AND source_generation <> '' AND batch_id <> '' AND command_payload_hash <> '')
);

-- One row per decoded effect, including state changes and failed outcomes.
-- Nullable event_id reflects effects that have no canonical event ID.
CREATE TABLE runtime.canonical_audit_effects (
  event_stream TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  partition_id INTEGER NOT NULL CHECK (partition_id >= 0),
  stream_sequence BIGINT NOT NULL CHECK (stream_sequence > 0),
  effect_ordinal INTEGER NOT NULL CHECK (effect_ordinal >= 0),
  event_id TEXT,
  effect_type TEXT NOT NULL,
  order_id TEXT NOT NULL,
  related_order_id TEXT NOT NULL DEFAULT '',
  occurred_at TEXT NOT NULL,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (event_stream, source_generation, partition_id, stream_sequence, effect_ordinal),
  FOREIGN KEY (event_stream, source_generation, partition_id, stream_sequence)
    REFERENCES runtime.canonical_audit_outcomes(event_stream, source_generation, partition_id, stream_sequence),
  CHECK (event_stream <> '' AND source_generation <> '' AND effect_type <> '')
);

CREATE UNIQUE INDEX idx_canonical_audit_effect_event_id
  ON runtime.canonical_audit_effects(event_stream, source_generation, event_id)
  WHERE event_id IS NOT NULL;
CREATE INDEX idx_canonical_audit_effect_order
  ON runtime.canonical_audit_effects(order_id, event_stream, source_generation, partition_id, stream_sequence, effect_ordinal);
CREATE INDEX idx_canonical_audit_effect_related_order
  ON runtime.canonical_audit_effects(related_order_id, event_stream, source_generation, partition_id, stream_sequence, effect_ordinal)
  WHERE related_order_id <> '';
