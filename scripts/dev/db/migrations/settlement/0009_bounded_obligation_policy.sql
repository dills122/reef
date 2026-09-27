-- Independent obligation stage. A policy binding is immutable for one run and
-- venue session; the first trade fixes its effective profile for replay.
-- Existing intake receipts have no trade manifest. Rebuild shadow intake before
-- enabling obligations; NULL manifest fields fail closed until replayed.
ALTER TABLE settlement.canonical_intake_receipts
  ADD COLUMN trade_count INTEGER CHECK (trade_count >= 0),
  ADD COLUMN trade_digest TEXT;

CREATE TABLE settlement.canonical_obligation_frontiers (
  event_stream TEXT NOT NULL,
  partition_id INTEGER NOT NULL CHECK (partition_id >= 0),
  source_generation TEXT NOT NULL,
  last_stream_sequence BIGINT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (event_stream, partition_id)
);

CREATE TABLE settlement.canonical_obligation_coverage (
  event_stream TEXT NOT NULL,
  partition_id INTEGER NOT NULL,
  source_generation TEXT NOT NULL,
  from_exclusive_sequence BIGINT NOT NULL,
  through_inclusive_sequence BIGINT NOT NULL,
  trade_count INTEGER NOT NULL CHECK (trade_count >= 0),
  trade_digest TEXT NOT NULL,
  PRIMARY KEY (event_stream, partition_id, through_inclusive_sequence),
  CHECK (through_inclusive_sequence > from_exclusive_sequence)
);

CREATE TABLE settlement.canonical_policy_bindings (
  event_stream TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  run_id TEXT NOT NULL,
  venue_session_id TEXT NOT NULL,
  post_trade_profile_id TEXT NOT NULL,
  post_trade_policy_version INTEGER NOT NULL CHECK (post_trade_policy_version > 0),
  post_trade_mode TEXT NOT NULL,
  settlement_cycle TEXT NOT NULL,
  netting_mode TEXT NOT NULL,
  ledger_posting_mode TEXT NOT NULL,
  selection_source TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (event_stream, source_generation, run_id, venue_session_id)
);

CREATE TABLE settlement.canonical_settlement_obligations (
  event_stream TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  partition_id INTEGER NOT NULL,
  stream_sequence BIGINT NOT NULL,
  effect_ordinal INTEGER NOT NULL,
  trade_id TEXT NOT NULL,
  event_id TEXT NOT NULL,
  run_id TEXT NOT NULL,
  venue_session_id TEXT NOT NULL,
  post_trade_profile_id TEXT NOT NULL,
  post_trade_policy_version INTEGER NOT NULL CHECK (post_trade_policy_version > 0),
  post_trade_mode TEXT NOT NULL,
  settlement_cycle TEXT NOT NULL,
  netting_mode TEXT NOT NULL,
  ledger_posting_mode TEXT NOT NULL,
  selection_source TEXT NOT NULL,
  buyer_participant_id TEXT NOT NULL,
  seller_participant_id TEXT NOT NULL,
  buyer_account_id TEXT NOT NULL,
  seller_account_id TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  quantity_units NUMERIC NOT NULL CHECK (quantity_units > 0),
  cash_amount NUMERIC NOT NULL CHECK (cash_amount >= 0),
  currency TEXT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SETTLED', 'BREAK')),
  PRIMARY KEY (event_stream, source_generation, trade_id),
  UNIQUE (event_stream, partition_id, stream_sequence, effect_ordinal)
);

CREATE INDEX canonical_settlement_obligations_run_trade_idx
  ON settlement.canonical_settlement_obligations (run_id, trade_id);
