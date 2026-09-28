-- Default-off primary acceptance candidate for future post-match settlement controls.
-- Historical mutable policy/resource rows are not backfilled or authenticated by this log.
CREATE TABLE postmatch.settlement_control_log_heads (
  event_stream TEXT PRIMARY KEY CHECK (event_stream <> ''),
  next_control_sequence BIGINT NOT NULL DEFAULT 1 CHECK (next_control_sequence > 0),
  last_control_digest TEXT NOT NULL CHECK (last_control_digest ~ '^[0-9a-f]{64}$'),
  owner_epoch BIGINT NOT NULL CHECK (owner_epoch > 0),
  owner_nonce TEXT NOT NULL CHECK (owner_nonce <> ''),
  incarnation_id TEXT NOT NULL CHECK (incarnation_id <> '')
);

CREATE TABLE postmatch.settlement_control_log_batches (
  event_stream TEXT NOT NULL REFERENCES postmatch.settlement_control_log_heads(event_stream),
  first_control_sequence BIGINT NOT NULL CHECK (first_control_sequence > 0),
  last_control_sequence BIGINT NOT NULL CHECK (last_control_sequence >= first_control_sequence),
  member_count INTEGER NOT NULL CHECK (member_count > 0),
  previous_digest TEXT NOT NULL CHECK (previous_digest ~ '^[0-9a-f]{64}$'),
  batch_digest TEXT NOT NULL CHECK (batch_digest ~ '^[0-9a-f]{64}$'),
  owner_epoch BIGINT NOT NULL CHECK (owner_epoch > 0),
  owner_nonce TEXT NOT NULL CHECK (owner_nonce <> ''),
  incarnation_id TEXT NOT NULL CHECK (incarnation_id <> ''),
  accepted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (event_stream, first_control_sequence),
  CHECK (last_control_sequence - first_control_sequence + 1 = member_count)
);

CREATE TABLE postmatch.settlement_control_log_members (
  event_stream TEXT NOT NULL,
  control_sequence BIGINT NOT NULL CHECK (control_sequence > 0),
  batch_first_control_sequence BIGINT NOT NULL,
  control_id TEXT NOT NULL CHECK (control_id <> ''),
  control_kind TEXT NOT NULL CHECK (control_kind IN ('POLICY', 'OPENING', 'FUNDING')),
  control_version INTEGER NOT NULL CHECK (control_version = 1),
  payload BYTEA NOT NULL CHECK (octet_length(payload) BETWEEN 1 AND 1048576),
  member_digest TEXT NOT NULL CHECK (member_digest ~ '^[0-9a-f]{64}$'),
  prefix_digest TEXT NOT NULL CHECK (prefix_digest ~ '^[0-9a-f]{64}$'),
  PRIMARY KEY (event_stream, control_sequence),
  UNIQUE (event_stream, control_id),
  FOREIGN KEY (event_stream, batch_first_control_sequence)
    REFERENCES postmatch.settlement_control_log_batches(event_stream, first_control_sequence)
);
