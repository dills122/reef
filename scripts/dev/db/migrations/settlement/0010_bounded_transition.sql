-- Shadow transition state. None of these tables back public settlement reads.
-- Resource setup is outside the matching path; maintain keyed openings as
-- resource-position facts arrive so trade transitions never scan a run.
CREATE TABLE settlement.canonical_resource_openings (
  run_id TEXT NOT NULL,
  participant_id TEXT NOT NULL,
  account_id TEXT NOT NULL,
  asset_type TEXT NOT NULL CHECK (asset_type IN ('CASH', 'SECURITY')),
  asset_id TEXT NOT NULL,
  quantity NUMERIC NOT NULL,
  position_count BIGINT NOT NULL CHECK (position_count > 0),
  PRIMARY KEY (run_id, participant_id, account_id, asset_type, asset_id)
);

INSERT INTO settlement.canonical_resource_openings(
  run_id, participant_id, account_id, asset_type, asset_id, quantity, position_count
)
SELECT scenario_run_id, participant_id, account_id, asset_type, asset_id,
       SUM(quantity::numeric), COUNT(*)
FROM settlement.resource_positions
GROUP BY scenario_run_id, participant_id, account_id, asset_type, asset_id;

CREATE OR REPLACE FUNCTION settlement.maintain_canonical_resource_opening()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    DELETE FROM settlement.canonical_resource_openings
     WHERE run_id = OLD.scenario_run_id AND participant_id = OLD.participant_id
       AND account_id = OLD.account_id AND asset_type = OLD.asset_type
       AND asset_id = OLD.asset_id AND position_count = 1;
    IF NOT FOUND THEN
      UPDATE settlement.canonical_resource_openings
         SET quantity = quantity - OLD.quantity::numeric,
             position_count = position_count - 1
       WHERE run_id = OLD.scenario_run_id AND participant_id = OLD.participant_id
         AND account_id = OLD.account_id AND asset_type = OLD.asset_type
         AND asset_id = OLD.asset_id AND position_count > 1;
      IF NOT FOUND THEN
        RAISE EXCEPTION 'canonical resource opening is missing for %', OLD.resource_position_id;
      END IF;
    END IF;
  END IF;
  IF TG_OP IN ('INSERT', 'UPDATE') THEN
    INSERT INTO settlement.canonical_resource_openings(
      run_id, participant_id, account_id, asset_type, asset_id, quantity, position_count
    ) VALUES (
      NEW.scenario_run_id, NEW.participant_id, NEW.account_id, NEW.asset_type,
      NEW.asset_id, NEW.quantity::numeric, 1
    ) ON CONFLICT (run_id, participant_id, account_id, asset_type, asset_id)
    DO UPDATE SET quantity = settlement.canonical_resource_openings.quantity + EXCLUDED.quantity,
                  position_count = settlement.canonical_resource_openings.position_count + 1;
  END IF;
  RETURN COALESCE(NEW, OLD);
END $$;

CREATE TRIGGER maintain_canonical_resource_opening_after_change
AFTER INSERT OR UPDATE OR DELETE ON settlement.resource_positions
FOR EACH ROW EXECUTE FUNCTION settlement.maintain_canonical_resource_opening();

CREATE TABLE settlement.canonical_transition_frontiers (
  event_stream TEXT NOT NULL,
  partition_id INTEGER NOT NULL CHECK (partition_id >= 0),
  source_generation TEXT NOT NULL,
  last_stream_sequence BIGINT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (event_stream, partition_id)
);

CREATE TABLE settlement.canonical_transition_coverage (
  event_stream TEXT NOT NULL,
  partition_id INTEGER NOT NULL,
  source_generation TEXT NOT NULL,
  from_exclusive_sequence BIGINT NOT NULL,
  through_inclusive_sequence BIGINT NOT NULL,
  obligation_count INTEGER NOT NULL CHECK (obligation_count >= 0),
  obligation_digest TEXT NOT NULL,
  PRIMARY KEY (event_stream, partition_id, through_inclusive_sequence),
  CHECK (through_inclusive_sequence > from_exclusive_sequence)
);

CREATE TABLE settlement.canonical_account_state (
  event_stream TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  run_id TEXT NOT NULL,
  participant_id TEXT NOT NULL,
  account_id TEXT NOT NULL,
  asset_type TEXT NOT NULL CHECK (asset_type IN ('CASH', 'SECURITY')),
  asset_id TEXT NOT NULL,
  opening_quantity NUMERIC NOT NULL,
  opening_position_count BIGINT NOT NULL CHECK (opening_position_count >= 0),
  ledger_delta NUMERIC NOT NULL DEFAULT 0,
  account_version BIGINT NOT NULL DEFAULT 0 CHECK (account_version >= 0),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (event_stream, source_generation, run_id, participant_id, account_id, asset_type, asset_id)
);

CREATE TABLE settlement.canonical_account_checkpoints (
  event_stream TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  run_id TEXT NOT NULL,
  participant_id TEXT NOT NULL,
  account_id TEXT NOT NULL,
  asset_type TEXT NOT NULL,
  asset_id TEXT NOT NULL,
  account_version BIGINT NOT NULL CHECK (account_version > 0),
  partition_id INTEGER NOT NULL,
  from_exclusive_sequence BIGINT NOT NULL,
  through_inclusive_sequence BIGINT NOT NULL,
  before_delta NUMERIC NOT NULL,
  after_delta NUMERIC NOT NULL,
  posting_digest TEXT NOT NULL,
  PRIMARY KEY (event_stream, source_generation, run_id, participant_id, account_id, asset_type, asset_id, account_version),
  CHECK (through_inclusive_sequence > from_exclusive_sequence)
);

CREATE INDEX canonical_account_checkpoints_window_idx
  ON settlement.canonical_account_checkpoints
  (event_stream, source_generation, partition_id, through_inclusive_sequence);

CREATE UNIQUE INDEX canonical_account_checkpoints_window_account_idx
  ON settlement.canonical_account_checkpoints
  (event_stream, source_generation, partition_id, through_inclusive_sequence,
   run_id, participant_id, account_id, asset_type, asset_id);

CREATE TABLE settlement.canonical_transition_attempts (
  event_stream TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  trade_id TEXT NOT NULL,
  attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
  partition_id INTEGER NOT NULL,
  stream_sequence BIGINT NOT NULL,
  run_id TEXT NOT NULL,
  post_trade_profile_id TEXT NOT NULL,
  post_trade_policy_version INTEGER NOT NULL CHECK (post_trade_policy_version > 0),
  outcome TEXT NOT NULL CHECK (outcome IN ('SETTLED', 'BREAK')),
  break_reason TEXT,
  workflow_facts TEXT NOT NULL,
  workflow_digest TEXT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (event_stream, source_generation, trade_id, attempt_number),
  FOREIGN KEY (event_stream, source_generation, trade_id)
    REFERENCES settlement.canonical_settlement_obligations(event_stream, source_generation, trade_id),
  CHECK ((outcome = 'BREAK') = (break_reason IS NOT NULL))
);

CREATE TABLE settlement.canonical_transition_ledger_entries (
  event_stream TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  trade_id TEXT NOT NULL,
  attempt_number INTEGER NOT NULL,
  entry_kind TEXT NOT NULL CHECK (entry_kind IN (
    'BUYER_CASH_DEBIT', 'SELLER_CASH_CREDIT',
    'SELLER_SECURITY_DEBIT', 'BUYER_SECURITY_CREDIT'
  )),
  run_id TEXT NOT NULL,
  participant_id TEXT NOT NULL,
  account_id TEXT NOT NULL,
  asset_type TEXT NOT NULL CHECK (asset_type IN ('CASH', 'SECURITY')),
  asset_id TEXT NOT NULL,
  direction TEXT NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
  quantity NUMERIC NOT NULL CHECK (quantity >= 0),
  occurred_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (event_stream, source_generation, trade_id, attempt_number, entry_kind),
  FOREIGN KEY (event_stream, source_generation, trade_id, attempt_number)
    REFERENCES settlement.canonical_transition_attempts(event_stream, source_generation, trade_id, attempt_number)
);

CREATE INDEX canonical_transition_attempts_run_trade_idx
  ON settlement.canonical_transition_attempts (run_id, trade_id);
CREATE INDEX canonical_transition_ledger_entries_account_idx
  ON settlement.canonical_transition_ledger_entries
  (run_id, participant_id, account_id, asset_type, asset_id);
