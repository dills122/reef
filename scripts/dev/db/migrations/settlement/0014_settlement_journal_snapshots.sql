-- Default-off proof artifact. A snapshot never grants a writer lease or financial finality.
-- One atomic row binds complete derived evaluator state to a committed journal head.
CREATE TABLE settlement.settlement_journal_snapshots (
  event_stream TEXT NOT NULL REFERENCES settlement.settlement_journal_heads(event_stream),
  batch_sequence BIGINT NOT NULL CHECK (batch_sequence > 0),
  batch_digest TEXT NOT NULL CHECK (length(batch_digest) = 64),
  owner_epoch BIGINT NOT NULL CHECK (owner_epoch > 0),
  incarnation_id TEXT NOT NULL CHECK (incarnation_id <> ''),
  control_sequence BIGINT NOT NULL CHECK (control_sequence >= 0),
  control_prefix_digest TEXT NOT NULL CHECK (length(control_prefix_digest) = 64),
  state_version INTEGER NOT NULL CHECK (state_version = 1),
  state_bytes BYTEA NOT NULL CHECK (octet_length(state_bytes) > 0),
  state_digest TEXT NOT NULL CHECK (length(state_digest) = 64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (event_stream, batch_sequence),
  FOREIGN KEY (event_stream, batch_sequence)
    REFERENCES settlement.settlement_journal_batches(event_stream, batch_sequence)
);
