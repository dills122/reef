-- Repeated invalidations need the conflict row lock, not a new tuple version.
-- PostgreSQL locks the conflicting row before evaluating DO UPDATE WHERE.
-- Keep producer/consumer serialization while avoiding redundant dirty-queue
-- heap and index writes; retain the oldest dirtied_at for queue ordering.
DO $$
DECLARE
  function_definition TEXT;
  old_arm TEXT;
BEGIN
  function_definition := pg_get_functiondef('runtime.runtime_persist_submit_outcome_status_stage(jsonb)'::regprocedure);
  old_arm := 'ON CONFLICT (order_id) DO UPDATE SET dirtied_at = dirty.dirtied_at';
  IF length(function_definition) - length(replace(function_definition, old_arm, '')) <> length(old_arm) THEN
    RAISE EXCEPTION 'expected one lifecycle dirty conflict arm';
  END IF;
  EXECUTE replace(function_definition, old_arm, old_arm || ' WHERE FALSE');

  function_definition := pg_get_functiondef('runtime.runtime_project_order_lifecycle_state(integer)'::regprocedure);
  old_arm := 'ON CONFLICT (instrument_id) DO UPDATE SET dirtied_at = dirty.dirtied_at';
  IF length(function_definition) - length(replace(function_definition, old_arm, '')) <> length(old_arm) THEN
    RAISE EXCEPTION 'expected one market dirty conflict arm';
  END IF;
  EXECUTE replace(function_definition, old_arm, old_arm || ' WHERE FALSE');
END;
$$;
