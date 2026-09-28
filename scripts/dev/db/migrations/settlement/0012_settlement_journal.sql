-- Default-off settlement journal. No existing worker or public read uses these tables.
-- One financial head per event stream, deliberately independent of source generation.
CREATE TABLE settlement.settlement_journal_heads (
  event_stream TEXT PRIMARY KEY,
  next_batch_sequence BIGINT NOT NULL DEFAULT 1 CHECK (next_batch_sequence > 0),
  last_batch_digest TEXT NOT NULL CHECK (length(last_batch_digest) = 64),
  owner_epoch BIGINT NOT NULL CHECK (owner_epoch > 0),
  incarnation_id TEXT NOT NULL CHECK (incarnation_id <> ''),
  last_control_sequence BIGINT NOT NULL DEFAULT 0 CHECK (last_control_sequence >= 0),
  last_control_digest TEXT NOT NULL CHECK (length(last_control_digest) = 64)
);

CREATE TABLE settlement.settlement_journal_batches (
  event_stream TEXT NOT NULL REFERENCES settlement.settlement_journal_heads(event_stream),
  batch_sequence BIGINT NOT NULL CHECK (batch_sequence > 0),
  owner_epoch BIGINT NOT NULL CHECK (owner_epoch > 0),
  incarnation_id TEXT NOT NULL CHECK (incarnation_id <> ''),
  previous_digest TEXT NOT NULL CHECK (length(previous_digest) = 64),
  proposal_digest TEXT NOT NULL CHECK (length(proposal_digest) = 64),
  batch_digest TEXT NOT NULL CHECK (length(batch_digest) = 64),
  source_window_count INTEGER NOT NULL CHECK (source_window_count >= 0),
  control_count INTEGER NOT NULL CHECK (control_count >= 0),
  result_count INTEGER NOT NULL CHECK (result_count >= 0),
  committed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (event_stream, batch_sequence)
);

-- Every covered range, including a range with zero outcome members, has a row.
CREATE TABLE settlement.settlement_journal_source_windows (
  event_stream TEXT NOT NULL,
  batch_sequence BIGINT NOT NULL,
  window_index INTEGER NOT NULL CHECK (window_index >= 0),
  step_index INTEGER NOT NULL CHECK (step_index >= 0),
  source_generation TEXT NOT NULL CHECK (source_generation <> ''),
  partition_id INTEGER NOT NULL CHECK (partition_id BETWEEN 0 AND 32767),
  from_exclusive_sequence BIGINT NOT NULL,
  through_inclusive_sequence BIGINT NOT NULL,
  coverage_proof_id TEXT NOT NULL CHECK (coverage_proof_id <> ''),
  coverage_digest TEXT NOT NULL CHECK (length(coverage_digest) = 64),
  member_count INTEGER NOT NULL CHECK (member_count >= 0),
  member_manifest BYTEA NOT NULL,
  window_digest TEXT NOT NULL CHECK (length(window_digest) = 64),
  PRIMARY KEY (event_stream, batch_sequence, window_index),
  UNIQUE (event_stream, batch_sequence, step_index),
  FOREIGN KEY (event_stream, batch_sequence)
    REFERENCES settlement.settlement_journal_batches(event_stream, batch_sequence),
  CHECK (through_inclusive_sequence > from_exclusive_sequence)
);
CREATE INDEX settlement_journal_source_window_frontier_idx
  ON settlement.settlement_journal_source_windows
  (event_stream, partition_id, batch_sequence DESC, window_index DESC);
CREATE INDEX settlement_journal_source_window_member_lookup_idx
  ON settlement.settlement_journal_source_windows
  (event_stream, partition_id, source_generation, from_exclusive_sequence,
   through_inclusive_sequence);

-- Ordered immutable control facts. Payload is the exact retained control encoding.
CREATE TABLE settlement.settlement_journal_controls (
  event_stream TEXT NOT NULL,
  control_sequence BIGINT NOT NULL CHECK (control_sequence > 0),
  batch_sequence BIGINT NOT NULL,
  control_index INTEGER NOT NULL CHECK (control_index >= 0),
  step_index INTEGER NOT NULL CHECK (step_index >= 0),
  control_id TEXT NOT NULL CHECK (control_id <> ''),
  control_kind TEXT NOT NULL CHECK (control_kind IN ('POLICY', 'OPENING', 'FUNDING', 'REPAIR')),
  control_version INTEGER NOT NULL CHECK (control_version > 0),
  payload BYTEA NOT NULL,
  control_digest TEXT NOT NULL CHECK (length(control_digest) = 64),
  prefix_digest TEXT NOT NULL CHECK (length(prefix_digest) = 64),
  PRIMARY KEY (event_stream, control_sequence),
  UNIQUE (event_stream, control_id),
  UNIQUE (event_stream, batch_sequence, control_index),
  UNIQUE (event_stream, batch_sequence, step_index),
  FOREIGN KEY (event_stream, batch_sequence)
    REFERENCES settlement.settlement_journal_batches(event_stream, batch_sequence)
);

-- One typed attempted transition. Four DvP effects reconstruct from account
-- identities, currency/instrument, cash/quantity and SETTLED; BREAK moves none.
CREATE TABLE settlement.settlement_journal_results (
  event_stream TEXT NOT NULL,
  batch_sequence BIGINT NOT NULL,
  result_index INTEGER NOT NULL CHECK (result_index >= 0),
  decision_step_index INTEGER NOT NULL CHECK (decision_step_index >= 0),
  trade_id TEXT NOT NULL CHECK (trade_id <> ''),
  attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
  source_generation TEXT NOT NULL CHECK (source_generation <> ''),
  partition_id INTEGER NOT NULL CHECK (partition_id BETWEEN 0 AND 32767),
  stream_sequence BIGINT NOT NULL,
  effect_ordinal INTEGER NOT NULL CHECK (effect_ordinal >= 0),
  source_member_digest TEXT NOT NULL CHECK (length(source_member_digest) = 64),
  event_id TEXT NOT NULL CHECK (event_id <> ''),
  run_id TEXT NOT NULL CHECK (run_id <> ''),
  venue_session_id TEXT NOT NULL CHECK (venue_session_id <> ''),
  buyer_participant_id TEXT NOT NULL CHECK (buyer_participant_id <> ''),
  buyer_account_id TEXT NOT NULL CHECK (buyer_account_id <> ''),
  seller_participant_id TEXT NOT NULL CHECK (seller_participant_id <> ''),
  seller_account_id TEXT NOT NULL CHECK (seller_account_id <> ''),
  currency TEXT NOT NULL CHECK (currency <> ''),
  instrument_id TEXT NOT NULL CHECK (instrument_id <> ''),
  cash_amount NUMERIC NOT NULL CHECK (cash_amount > 0),
  quantity_units NUMERIC NOT NULL CHECK (quantity_units > 0),
  occurred_at TIMESTAMPTZ NOT NULL,
  occurred_at_text TEXT NOT NULL,
  policy_control_id TEXT NOT NULL CHECK (policy_control_id <> ''),
  opening_control_ids TEXT[] NOT NULL,
  funding_control_ids TEXT[] NOT NULL,
  bound_control_digest TEXT NOT NULL CHECK (length(bound_control_digest) = 64),
  outcome TEXT NOT NULL CHECK (outcome IN ('SETTLED', 'BREAK')),
  break_reason TEXT CHECK (break_reason IN ('CASH_LEG_FAILED', 'SECURITY_LEG_FAILED')),
  workflow_facts TEXT NOT NULL,
  result_digest TEXT NOT NULL CHECK (length(result_digest) = 64),
  PRIMARY KEY (event_stream, batch_sequence, result_index),
  UNIQUE (event_stream, trade_id, attempt_number),
  FOREIGN KEY (event_stream, batch_sequence)
    REFERENCES settlement.settlement_journal_batches(event_stream, batch_sequence),
  CHECK ((outcome = 'BREAK') = (break_reason IS NOT NULL))
);
CREATE UNIQUE INDEX settlement_journal_first_source_idx
  ON settlement.settlement_journal_results
  (event_stream, partition_id, stream_sequence, effect_ordinal)
  WHERE attempt_number = 1;
CREATE INDEX settlement_journal_results_run_trade_idx
  ON settlement.settlement_journal_results (run_id, trade_id);
