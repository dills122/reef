-- The decision table carried three regular-expression CHECKs (`~ '^[0-9a-f]{64}$'`) on input_digest, prior_chain and checksum. At the capacity rate they were about a
-- quarter of the projector's database time (CAL-FIN-P17: one lane 11.8k decisions/s with them, 14.7k without). The projector only writes values it has just verified in
-- memory (the checksum is recomputed from the decision, the prior_chain must equal the stored chain, the input digest must equal the digest of the input it carries), so
-- the format guard stays as a cheap length check; hex spelling is established by that verification, not by the database.
DO $$
DECLARE r record; dropped integer := 0;
BEGIN
  FOR r IN SELECT conname FROM pg_constraint
            WHERE conrelid = 'boundary.calcify_finance_decision'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%~%' LOOP
    EXECUTE format('ALTER TABLE boundary.calcify_finance_decision DROP CONSTRAINT %I', r.conname);
    dropped := dropped + 1;
  END LOOP;
  IF dropped <> 3 THEN RAISE EXCEPTION 'migration 0014 decision hex CHECKs: expected 3, found %', dropped; END IF;
END $$;
ALTER TABLE boundary.calcify_finance_decision
  ADD CONSTRAINT calcify_finance_decision_digest_lengths CHECK (octet_length(input_digest) = 64 AND octet_length(prior_chain) = 64 AND octet_length(checksum) = 64);
