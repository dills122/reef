-- Default-off, rebuildable financial read projection. Journal tables remain authority.
-- A new projector generation starts at origin without mutating a prior generation.
CREATE TABLE settlement.settlement_journal_projection_checkpoints (
  event_stream TEXT NOT NULL REFERENCES settlement.settlement_journal_heads(event_stream),
  projector_generation TEXT NOT NULL CHECK (projector_generation <> ''),
  journal_incarnation_id TEXT NOT NULL CHECK (journal_incarnation_id <> ''),
  last_batch_sequence BIGINT NOT NULL DEFAULT 0 CHECK (last_batch_sequence >= 0),
  last_batch_digest TEXT NOT NULL CHECK (length(last_batch_digest) = 64),
  PRIMARY KEY (event_stream, projector_generation)
);

CREATE TABLE settlement.settlement_journal_projection_balances (
  event_stream TEXT NOT NULL,
  projector_generation TEXT NOT NULL,
  run_id TEXT NOT NULL,
  participant_id TEXT NOT NULL,
  account_id TEXT NOT NULL,
  asset_type TEXT NOT NULL,
  asset_id TEXT NOT NULL,
  amount NUMERIC NOT NULL CHECK (amount >= 0),
  PRIMARY KEY (event_stream, projector_generation, run_id, participant_id,
               account_id, asset_type, asset_id),
  FOREIGN KEY (event_stream, projector_generation)
    REFERENCES settlement.settlement_journal_projection_checkpoints
      (event_stream, projector_generation)
);

CREATE TABLE settlement.settlement_journal_projection_trades (
  event_stream TEXT NOT NULL,
  projector_generation TEXT NOT NULL,
  run_id TEXT NOT NULL,
  trade_id TEXT NOT NULL,
  attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
  outcome TEXT NOT NULL CHECK (outcome IN ('SETTLED', 'BREAK')),
  break_reason TEXT CHECK (break_reason IN ('CASH_LEG_FAILED', 'SECURITY_LEG_FAILED')),
  result_batch_sequence BIGINT NOT NULL CHECK (result_batch_sequence > 0),
  result_index INTEGER NOT NULL CHECK (result_index >= 0),
  PRIMARY KEY (event_stream, projector_generation, run_id, trade_id),
  FOREIGN KEY (event_stream, projector_generation)
    REFERENCES settlement.settlement_journal_projection_checkpoints
      (event_stream, projector_generation),
  CHECK ((outcome = 'BREAK') = (break_reason IS NOT NULL))
);
