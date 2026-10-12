-- Finite P3 O4: read projection of the financial decision log. Written only by the projector role (operator-provisioned,
-- never this migration); applies FINANCE_DECISIONS records exactly once per sequence under an owner-epoch fence.
-- Bounds mirror the O3a pins (128-byte identifiers, <=256 obligations); nothing here is financial authority.
CREATE TABLE boundary.calcify_finance_checkpoint (
  binding_digest TEXT PRIMARY KEY CHECK (binding_digest ~ '^[0-9a-f]{64}$'),
  owner_epoch BIGINT NOT NULL CHECK (owner_epoch > 0),
  last_sequence BIGINT NOT NULL DEFAULT 0 CHECK (last_sequence BETWEEN 0 AND 320),
  last_chain TEXT NOT NULL CHECK (last_chain ~ '^[0-9a-f]{64}$')
);
CREATE TABLE boundary.calcify_finance_decision (
  binding_digest TEXT NOT NULL REFERENCES boundary.calcify_finance_checkpoint(binding_digest),
  sequence BIGINT NOT NULL CHECK (sequence BETWEEN 1 AND 320),
  action_namespace TEXT NOT NULL CHECK (octet_length(action_namespace) BETWEEN 1 AND 128),
  action_domain TEXT NOT NULL CHECK (octet_length(action_domain) BETWEEN 1 AND 128),
  action_id TEXT NOT NULL CHECK (octet_length(action_id) BETWEEN 1 AND 128),
  disposition TEXT NOT NULL CHECK (octet_length(disposition) BETWEEN 1 AND 64),
  input_digest TEXT NOT NULL CHECK (input_digest ~ '^[0-9a-f]{64}$'),
  prior_chain TEXT NOT NULL CHECK (prior_chain ~ '^[0-9a-f]{64}$'),
  checksum TEXT NOT NULL CHECK (checksum ~ '^[0-9a-f]{64}$'),
  PRIMARY KEY (binding_digest, sequence),
  UNIQUE (binding_digest, action_namespace, action_domain, action_id)
);
CREATE TABLE boundary.calcify_finance_position (
  binding_digest TEXT NOT NULL REFERENCES boundary.calcify_finance_checkpoint(binding_digest),
  account_id TEXT NOT NULL CHECK (octet_length(account_id) BETWEEN 1 AND 128),
  asset_id TEXT NOT NULL CHECK (octet_length(asset_id) BETWEEN 1 AND 128),
  amount BIGINT NOT NULL,
  PRIMARY KEY (binding_digest, account_id, asset_id)
);
CREATE TABLE boundary.calcify_finance_obligation (
  binding_digest TEXT NOT NULL REFERENCES boundary.calcify_finance_checkpoint(binding_digest),
  run_id TEXT NOT NULL CHECK (octet_length(run_id) BETWEEN 1 AND 128),
  venue_session_id TEXT NOT NULL CHECK (octet_length(venue_session_id) BETWEEN 1 AND 128),
  instrument_id TEXT NOT NULL CHECK (octet_length(instrument_id) BETWEEN 1 AND 128),
  execution_id TEXT NOT NULL CHECK (octet_length(execution_id) BETWEEN 1 AND 128),
  buyer_account_id TEXT NOT NULL CHECK (octet_length(buyer_account_id) BETWEEN 1 AND 128),
  seller_account_id TEXT NOT NULL CHECK (octet_length(seller_account_id) BETWEEN 1 AND 128),
  quantity BIGINT NOT NULL CHECK (quantity > 0),
  price_nanos BIGINT NOT NULL CHECK (price_nanos >= 0),
  status TEXT NOT NULL CHECK (status IN ('PENDING','SETTLED')),
  cash_residual BIGINT NOT NULL CHECK (cash_residual >= 0),
  share_residual BIGINT NOT NULL CHECK (share_residual >= 0),
  PRIMARY KEY (binding_digest, run_id, venue_session_id, instrument_id, execution_id),
  CHECK ((status = 'SETTLED' AND cash_residual = 0 AND share_residual = 0) OR status = 'PENDING')
);
CREATE TABLE boundary.calcify_finance_attempt (
  binding_digest TEXT NOT NULL REFERENCES boundary.calcify_finance_checkpoint(binding_digest),
  run_id TEXT NOT NULL, venue_session_id TEXT NOT NULL, instrument_id TEXT NOT NULL, execution_id TEXT NOT NULL,
  attempt INTEGER NOT NULL CHECK (attempt >= 1),
  disposition TEXT NOT NULL CHECK (disposition IN ('SETTLED','INSUFFICIENT_CASH','INSUFFICIENT_SHARES','BALANCE_OVERFLOW')),
  PRIMARY KEY (binding_digest, run_id, venue_session_id, instrument_id, execution_id, attempt),
  FOREIGN KEY (binding_digest, run_id, venue_session_id, instrument_id, execution_id)
    REFERENCES boundary.calcify_finance_obligation(binding_digest, run_id, venue_session_id, instrument_id, execution_id)
);
