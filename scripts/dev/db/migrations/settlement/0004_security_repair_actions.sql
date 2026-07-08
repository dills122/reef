DO $$
DECLARE
  constraint_name text;
BEGIN
  SELECT conname INTO constraint_name
  FROM pg_constraint
  WHERE conrelid = 'settlement.repairs'::regclass
    AND contype = 'c'
    AND pg_get_constraintdef(oid) LIKE '%POST_CASH_LEG_REPAIR%';

  IF constraint_name IS NOT NULL THEN
    EXECUTE format('ALTER TABLE settlement.repairs DROP CONSTRAINT %I', constraint_name);
  END IF;
END $$;

ALTER TABLE settlement.repairs
  ADD CONSTRAINT repairs_repair_action_check
  CHECK (repair_action IN ('POST_CASH_LEG_REPAIR', 'POST_SECURITY_LEG_REPAIR')) NOT VALID;

ALTER TABLE settlement.repairs
  VALIDATE CONSTRAINT repairs_repair_action_check;
