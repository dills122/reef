-- Durable cross-partition settlement arbitration. Admission ranks are
-- canonical post-trade facts; execution frontiers are rebuildable indexes
-- over admitted windows and committed completions.
CREATE TABLE settlement.canonical_transition_admission_counter (
  event_stream TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  next_rank BIGINT NOT NULL DEFAULT 1 CHECK (next_rank > 0),
  PRIMARY KEY (event_stream, source_generation)
);

CREATE TABLE settlement.canonical_transition_admission_frontiers (
  event_stream TEXT NOT NULL,
  partition_id INTEGER NOT NULL CHECK (partition_id >= 0),
  source_generation TEXT NOT NULL,
  last_stream_sequence BIGINT NOT NULL,
  last_rank BIGINT,
  PRIMARY KEY (event_stream, partition_id)
);

CREATE TABLE settlement.canonical_transition_admissions (
  event_stream TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  admission_rank BIGINT NOT NULL CHECK (admission_rank > 0),
  partition_id INTEGER NOT NULL CHECK (partition_id >= 0),
  from_exclusive_sequence BIGINT NOT NULL,
  through_inclusive_sequence BIGINT NOT NULL,
  obligation_count INTEGER NOT NULL CHECK (obligation_count >= 0),
  obligation_digest TEXT NOT NULL,
  account_set_digest TEXT NOT NULL,
  opening_digest TEXT NOT NULL,
  dependency_digest TEXT NOT NULL,
  admitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (event_stream, source_generation, admission_rank),
  UNIQUE (event_stream, source_generation, partition_id, through_inclusive_sequence),
  CHECK (through_inclusive_sequence > from_exclusive_sequence)
);

CREATE INDEX canonical_transition_admissions_partition_idx
  ON settlement.canonical_transition_admissions
  (event_stream, source_generation, partition_id, from_exclusive_sequence);

CREATE TABLE settlement.canonical_transition_admission_accounts (
  event_stream TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  admission_rank BIGINT NOT NULL,
  run_id TEXT NOT NULL,
  participant_id TEXT NOT NULL,
  account_id TEXT NOT NULL,
  asset_type TEXT NOT NULL CHECK (asset_type IN ('CASH', 'SECURITY')),
  asset_id TEXT NOT NULL,
  predecessor_rank BIGINT,
  PRIMARY KEY (event_stream, source_generation, admission_rank,
               run_id, participant_id, account_id, asset_type, asset_id),
  FOREIGN KEY (event_stream, source_generation, admission_rank)
    REFERENCES settlement.canonical_transition_admissions(event_stream, source_generation, admission_rank),
  FOREIGN KEY (event_stream, source_generation, predecessor_rank,
               run_id, participant_id, account_id, asset_type, asset_id)
    REFERENCES settlement.canonical_transition_admission_accounts(
      event_stream, source_generation, admission_rank,
      run_id, participant_id, account_id, asset_type, asset_id),
  CHECK (predecessor_rank < admission_rank)
);

CREATE INDEX canonical_transition_admission_accounts_latest_idx
  ON settlement.canonical_transition_admission_accounts
  (event_stream, source_generation, run_id, participant_id, account_id,
   asset_type, asset_id, admission_rank DESC);

CREATE TABLE settlement.canonical_transition_dependencies (
  event_stream TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  admission_rank BIGINT NOT NULL,
  predecessor_rank BIGINT NOT NULL,
  PRIMARY KEY (event_stream, source_generation, admission_rank, predecessor_rank),
  FOREIGN KEY (event_stream, source_generation, admission_rank)
    REFERENCES settlement.canonical_transition_admissions(event_stream, source_generation, admission_rank),
  FOREIGN KEY (event_stream, source_generation, predecessor_rank)
    REFERENCES settlement.canonical_transition_admissions(event_stream, source_generation, admission_rank),
  CHECK (predecessor_rank < admission_rank)
);

CREATE TABLE settlement.canonical_transition_admission_completions (
  event_stream TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  admission_rank BIGINT NOT NULL,
  obligation_digest TEXT NOT NULL,
  completed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (event_stream, source_generation, admission_rank),
  FOREIGN KEY (event_stream, source_generation, admission_rank)
    REFERENCES settlement.canonical_transition_admissions(event_stream, source_generation, admission_rank)
);
