-- Add typed companion facts for command submit results.
--
-- Submit result rows are keyed by text command ids for compatibility, but the
-- linked event identity and result time are stable audit facts. Typed
-- companions keep existing API reads intact while enabling native time/UUID
-- checks for replay, audit, and operator queries.

ALTER TABLE runtime.submit_results
  ADD COLUMN IF NOT EXISTS event_id_uuid UUID,
  ADD COLUMN IF NOT EXISTS occurred_at_ts TIMESTAMPTZ;

UPDATE runtime.submit_results
SET
  event_id_uuid = CASE
    WHEN event_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' THEN event_id::UUID
    ELSE NULL
  END,
  occurred_at_ts = CASE
    WHEN occurred_at ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$' THEN occurred_at::TIMESTAMPTZ
    ELSE NULL
  END;

CREATE OR REPLACE FUNCTION runtime.submit_results_set_typed_facts()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  NEW.event_id_uuid := CASE
    WHEN NEW.event_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' THEN NEW.event_id::UUID
    ELSE NULL
  END;

  NEW.occurred_at_ts := CASE
    WHEN NEW.occurred_at ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$' THEN NEW.occurred_at::TIMESTAMPTZ
    ELSE NULL
  END;

  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS submit_results_set_typed_facts ON runtime.submit_results;

CREATE TRIGGER submit_results_set_typed_facts
BEFORE INSERT OR UPDATE OF event_id, occurred_at ON runtime.submit_results
FOR EACH ROW
EXECUTE FUNCTION runtime.submit_results_set_typed_facts();

CREATE INDEX IF NOT EXISTS idx_submit_results_occurred_typed
  ON runtime.submit_results(occurred_at_ts DESC, command_id)
  WHERE occurred_at_ts IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_submit_results_event_uuid
  ON runtime.submit_results(event_id_uuid)
  WHERE event_id_uuid IS NOT NULL;
