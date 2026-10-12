-- Finite P3 binding v2: registration accepts calcify-finite-binding-v1 or -v2 openings. Every other check is unchanged and the
-- table CHECKs (16384-byte opening/canonical caps) are deliberately not relaxed: the v2 opening fits them. A missing or unknown
-- schema is refused (the previous comparison let a NULL schema through; this one does not).
CREATE OR REPLACE FUNCTION boundary.calcify_finite_register(d TEXT, o JSONB, c BYTEA, instance TEXT) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, boundary AS $$
BEGIN
  IF o->>'schema' IS NULL OR o->>'schema' NOT IN ('calcify-finite-binding-v1', 'calcify-finite-binding-v2')
     OR o->>'openingMode' IS DISTINCT FROM 'GENESIS'
     OR (o->>'partition')::INT <> 0 OR (o->>'partitionCount')::INT <> 1 THEN
    RAISE EXCEPTION 'invalid finite opening';
  END IF;
  INSERT INTO boundary.calcify_finite_bindings VALUES (d,o,c);
  INSERT INTO boundary.calcify_finite_runs(digest,owner_instance,owner_epoch) VALUES (d,instance,1);
  RETURN TRUE;
END $$;
