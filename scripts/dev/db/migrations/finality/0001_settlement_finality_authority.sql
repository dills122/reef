-- Apply only to a durable PostgreSQL instance outside source/control and journal restore domains.
CREATE SCHEMA IF NOT EXISTS finality;

CREATE TABLE finality.settlement_finality_anchors (
  event_stream TEXT PRIMARY KEY CHECK (event_stream <> ''),
  journal_incarnation_id TEXT NOT NULL CHECK (journal_incarnation_id <> ''),
  source_binding_digest TEXT NOT NULL CHECK (source_binding_digest ~ '^[0-9a-f]{64}$'),
  acknowledged_batch_sequence BIGINT NOT NULL DEFAULT 0 CHECK (acknowledged_batch_sequence >= 0),
  acknowledged_batch_digest TEXT NOT NULL CHECK (acknowledged_batch_digest ~ '^[0-9a-f]{64}$'),
  control_sequence BIGINT NOT NULL DEFAULT 0 CHECK (control_sequence >= 0),
  control_digest TEXT NOT NULL CHECK (control_digest ~ '^[0-9a-f]{64}$'),
  lease_epoch BIGINT NOT NULL DEFAULT 0 CHECK (lease_epoch >= 0),
  lease_nonce TEXT,
  lease_expires_at TIMESTAMPTZ,
  snapshot_batch_sequence BIGINT CHECK (snapshot_batch_sequence > 0),
  snapshot_batch_digest TEXT CHECK (snapshot_batch_digest ~ '^[0-9a-f]{64}$'),
  snapshot_state_version INTEGER CHECK (snapshot_state_version > 0),
  snapshot_state_digest TEXT CHECK (snapshot_state_digest ~ '^[0-9a-f]{64}$'),
  CHECK ((lease_nonce IS NULL) = (lease_expires_at IS NULL)),
  CHECK (lease_nonce IS NULL OR lease_nonce <> ''),
  CHECK ((snapshot_batch_sequence IS NULL) = (snapshot_batch_digest IS NULL)
     AND (snapshot_batch_sequence IS NULL) = (snapshot_state_version IS NULL)
     AND (snapshot_batch_sequence IS NULL) = (snapshot_state_digest IS NULL)),
  CHECK (snapshot_batch_sequence IS NULL OR snapshot_batch_sequence <= acknowledged_batch_sequence),
  CHECK (snapshot_batch_sequence IS NULL OR snapshot_batch_sequence <> acknowledged_batch_sequence
     OR snapshot_batch_digest = acknowledged_batch_digest)
);

-- Even an accidental direct UPDATE must not rewind acknowledged finality or reuse a lease.
CREATE FUNCTION finality.reject_settlement_finality_rewind() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.event_stream <> OLD.event_stream
     OR NEW.journal_incarnation_id <> OLD.journal_incarnation_id
     OR NEW.source_binding_digest <> OLD.source_binding_digest
     OR NEW.acknowledged_batch_sequence < OLD.acknowledged_batch_sequence
     OR NEW.acknowledged_batch_sequence > OLD.acknowledged_batch_sequence + 1
     OR NEW.control_sequence < OLD.control_sequence
     OR (NEW.acknowledged_batch_sequence = OLD.acknowledged_batch_sequence
         AND NEW.acknowledged_batch_digest <> OLD.acknowledged_batch_digest)
     OR (NEW.control_sequence = OLD.control_sequence
         AND NEW.control_digest <> OLD.control_digest)
     OR NEW.lease_epoch < OLD.lease_epoch
     OR (NEW.lease_epoch = OLD.lease_epoch
         AND NEW.lease_nonce IS DISTINCT FROM OLD.lease_nonce)
     OR (OLD.snapshot_batch_sequence IS NOT NULL AND
         (NEW.snapshot_batch_sequence IS NULL
          OR NEW.snapshot_batch_sequence < OLD.snapshot_batch_sequence
          OR (NEW.snapshot_batch_sequence = OLD.snapshot_batch_sequence AND
              (NEW.snapshot_batch_digest IS DISTINCT FROM OLD.snapshot_batch_digest
               OR NEW.snapshot_state_version IS DISTINCT FROM OLD.snapshot_state_version
               OR NEW.snapshot_state_digest IS DISTINCT FROM OLD.snapshot_state_digest))))
  THEN
    RAISE EXCEPTION 'settlement finality or lease rewind rejected';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER settlement_finality_no_rewind
BEFORE UPDATE ON finality.settlement_finality_anchors
FOR EACH ROW EXECUTE FUNCTION finality.reject_settlement_finality_rewind();

CREATE FUNCTION finality.reject_settlement_finality_removal() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'settlement finality anchor removal rejected';
END;
$$;

CREATE TRIGGER settlement_finality_no_delete
BEFORE DELETE ON finality.settlement_finality_anchors
FOR EACH ROW EXECUTE FUNCTION finality.reject_settlement_finality_removal();

CREATE TRIGGER settlement_finality_no_truncate
BEFORE TRUNCATE ON finality.settlement_finality_anchors
FOR EACH STATEMENT EXECUTE FUNCTION finality.reject_settlement_finality_removal();
