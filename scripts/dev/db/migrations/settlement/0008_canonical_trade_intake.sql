-- Isolated, replayable canonical trade intake. This is the source boundary for
-- bounded settlement transitions; legacy scenario-wide materialization remains
-- the public reference until the downstream workflow and ledger are cut over.

CREATE TABLE settlement.canonical_intake_frontiers (
  event_stream TEXT NOT NULL,
  partition_id INTEGER NOT NULL CHECK (partition_id >= 0),
  source_generation TEXT NOT NULL,
  last_stream_sequence BIGINT NOT NULL,
  last_coverage_digest TEXT NOT NULL DEFAULT '',
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (event_stream, partition_id)
);

CREATE TABLE settlement.canonical_intake_coverage (
  event_stream TEXT NOT NULL,
  partition_id INTEGER NOT NULL,
  source_generation TEXT NOT NULL,
  from_exclusive_sequence BIGINT NOT NULL,
  through_inclusive_sequence BIGINT NOT NULL,
  source_member_count INTEGER NOT NULL CHECK (source_member_count > 0),
  source_digest TEXT NOT NULL,
  PRIMARY KEY (event_stream, partition_id, through_inclusive_sequence),
  CHECK (through_inclusive_sequence > from_exclusive_sequence),
  CHECK (through_inclusive_sequence - from_exclusive_sequence = source_member_count)
);

CREATE TABLE settlement.canonical_intake_receipts (
  event_stream TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  partition_id INTEGER NOT NULL,
  stream_sequence BIGINT NOT NULL,
  batch_id TEXT NOT NULL,
  command_id TEXT NOT NULL,
  command_payload_hash TEXT NOT NULL,
  result_digest TEXT NOT NULL,
  effect_count INTEGER NOT NULL,
  PRIMARY KEY (event_stream, partition_id, stream_sequence)
);

CREATE TABLE settlement.canonical_order_directory (
  event_stream TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  order_id TEXT NOT NULL,
  run_id TEXT NOT NULL,
  venue_session_id TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  participant_id TEXT NOT NULL,
  account_id TEXT NOT NULL,
  side TEXT NOT NULL CHECK (side IN ('BUY', 'SELL')),
  currency TEXT NOT NULL,
  source_partition_id INTEGER NOT NULL,
  source_stream_sequence BIGINT NOT NULL,
  source_effect_ordinal INTEGER NOT NULL,
  PRIMARY KEY (event_stream, source_generation, order_id)
);

CREATE TABLE settlement.canonical_trade_intake (
  event_stream TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  partition_id INTEGER NOT NULL,
  stream_sequence BIGINT NOT NULL,
  effect_ordinal INTEGER NOT NULL,
  event_id TEXT NOT NULL,
  trade_id TEXT NOT NULL,
  execution_id TEXT NOT NULL,
  run_id TEXT NOT NULL,
  venue_session_id TEXT NOT NULL,
  buy_order_id TEXT NOT NULL,
  sell_order_id TEXT NOT NULL,
  buyer_participant_id TEXT NOT NULL,
  seller_participant_id TEXT NOT NULL,
  buyer_account_id TEXT NOT NULL,
  seller_account_id TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  quantity_units TEXT NOT NULL,
  price TEXT NOT NULL,
  currency TEXT NOT NULL,
  occurred_at_text TEXT NOT NULL,
  PRIMARY KEY (event_stream, partition_id, stream_sequence, effect_ordinal),
  UNIQUE (event_stream, source_generation, trade_id),
  UNIQUE (event_stream, source_generation, event_id)
);

CREATE INDEX canonical_trade_intake_run_trade_idx
  ON settlement.canonical_trade_intake (run_id, trade_id);
