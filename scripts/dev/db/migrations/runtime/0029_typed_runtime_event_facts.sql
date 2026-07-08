-- Add typed companion facts for runtime event identity and time.
--
-- The existing text columns remain the compatibility/read surface. The typed
-- columns give Postgres native timestamp/UUID ordering and index support for
-- replay and recent-event queries without rewriting every event insert routine.

ALTER TABLE runtime.runtime_events
  ADD COLUMN IF NOT EXISTS event_id_uuid UUID,
  ADD COLUMN IF NOT EXISTS occurred_at_ts TIMESTAMPTZ;

UPDATE runtime.runtime_events
SET
  event_id_uuid = CASE
    WHEN event_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' THEN event_id::UUID
    ELSE NULL
  END,
  occurred_at_ts = CASE
    WHEN occurred_at ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$' THEN occurred_at::TIMESTAMPTZ
    ELSE NULL
  END;

CREATE OR REPLACE FUNCTION runtime.runtime_events_set_typed_facts()
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

DROP TRIGGER IF EXISTS runtime_events_set_typed_facts ON runtime.runtime_events;

CREATE TRIGGER runtime_events_set_typed_facts
BEFORE INSERT OR UPDATE OF event_id, occurred_at ON runtime.runtime_events
FOR EACH ROW
EXECUTE FUNCTION runtime.runtime_events_set_typed_facts();

CREATE INDEX IF NOT EXISTS idx_runtime_events_occurred_typed
  ON runtime.runtime_events(occurred_at_ts DESC, event_id_uuid DESC)
  WHERE occurred_at_ts IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_runtime_events_order_occurred_typed
  ON runtime.runtime_events(order_id, occurred_at_ts DESC, event_id_uuid DESC)
  WHERE occurred_at_ts IS NOT NULL;
