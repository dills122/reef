-- Capacity profile groundwork (docs/work/CALCIFY_CAPACITY_PROFILE.md): the projection's sequence bound becomes a property of the binding, fixed when its
-- checkpoint is created. Every existing and every finite binding keeps the 320 of migration 0014; a capacity binding is created with a larger bound by the
-- projector (`FinanceProjector.claim/open`). The bound cannot be changed afterwards and a decision row beyond it is refused, so the finite guard is unchanged.
ALTER TABLE boundary.calcify_finance_checkpoint
  ADD COLUMN sequence_bound BIGINT NOT NULL DEFAULT 320 CHECK (sequence_bound BETWEEN 320 AND 1000000000);

DO $$
DECLARE c text;
BEGIN
  SELECT conname INTO c FROM pg_constraint
   WHERE conrelid = 'boundary.calcify_finance_checkpoint'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%last_sequence%';
  IF c IS NULL THEN RAISE EXCEPTION 'migration 0014 checkpoint sequence CHECK not found'; END IF;
  EXECUTE format('ALTER TABLE boundary.calcify_finance_checkpoint DROP CONSTRAINT %I', c);
  SELECT conname INTO c FROM pg_constraint
   WHERE conrelid = 'boundary.calcify_finance_decision'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%sequence%' AND pg_get_constraintdef(oid) LIKE '%320%';
  IF c IS NULL THEN RAISE EXCEPTION 'migration 0014 decision sequence CHECK not found'; END IF;
  EXECUTE format('ALTER TABLE boundary.calcify_finance_decision DROP CONSTRAINT %I', c);
END $$;

ALTER TABLE boundary.calcify_finance_checkpoint
  ADD CONSTRAINT calcify_finance_checkpoint_sequence_within_bound CHECK (last_sequence BETWEEN 0 AND sequence_bound);
ALTER TABLE boundary.calcify_finance_decision
  ADD CONSTRAINT calcify_finance_decision_sequence_positive CHECK (sequence >= 1);

-- One check per INSERT statement, not per row (a per-row trigger added about 24 percent to bulk decision inserts): the transition table holds the statement's rows.
CREATE FUNCTION boundary.calcify_finance_decision_within_bound() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM new_rows n JOIN boundary.calcify_finance_checkpoint c ON c.binding_digest = n.binding_digest WHERE n.sequence > c.sequence_bound) THEN
    RAISE EXCEPTION 'a decision sequence exceeds the binding''s sequence bound' USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER calcify_finance_decision_within_bound AFTER INSERT ON boundary.calcify_finance_decision
  REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION boundary.calcify_finance_decision_within_bound();

CREATE FUNCTION boundary.calcify_finance_sequence_bound_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'the sequence bound of a binding cannot change' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER calcify_finance_sequence_bound_immutable BEFORE UPDATE ON boundary.calcify_finance_checkpoint
  FOR EACH ROW WHEN (OLD.sequence_bound IS DISTINCT FROM NEW.sequence_bound) EXECUTE FUNCTION boundary.calcify_finance_sequence_bound_immutable();
