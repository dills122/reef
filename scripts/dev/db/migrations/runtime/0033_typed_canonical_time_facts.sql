-- Add typed timestamp companions for canonical command and event facts.
--
-- Projection determinism remains sequence-based. These timestamp companions
-- support audit/range queries, retention planning, and future partitioning
-- without changing canonical JSON payloads or public wire shapes.

ALTER TABLE runtime.canonical_command_results
  ADD COLUMN IF NOT EXISTS accepted_at_ts TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS completed_at_ts TIMESTAMPTZ;

ALTER TABLE runtime.canonical_venue_events
  ADD COLUMN IF NOT EXISTS emitted_at_ts TIMESTAMPTZ;

ALTER TABLE runtime.canonical_venue_event_batches
  ADD COLUMN IF NOT EXISTS created_at_ts TIMESTAMPTZ;

ALTER TABLE runtime.canonical_command_outcomes
  ADD COLUMN IF NOT EXISTS occurred_at_ts TIMESTAMPTZ;

UPDATE runtime.canonical_command_results
SET
  accepted_at_ts = CASE
    WHEN accepted_at ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$' THEN accepted_at::TIMESTAMPTZ
    ELSE NULL
  END,
  completed_at_ts = CASE
    WHEN completed_at ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$' THEN completed_at::TIMESTAMPTZ
    ELSE NULL
  END;

UPDATE runtime.canonical_venue_events
SET emitted_at_ts = CASE
  WHEN emitted_at ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$' THEN emitted_at::TIMESTAMPTZ
  ELSE NULL
END;

UPDATE runtime.canonical_venue_event_batches
SET created_at_ts = CASE
  WHEN created_at ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$' THEN created_at::TIMESTAMPTZ
  ELSE NULL
END;

UPDATE runtime.canonical_command_outcomes
SET occurred_at_ts = CASE
  WHEN COALESCE(NULLIF(result_payload #>> '{accepted,occurredAt}', ''), NULLIF(result_payload #>> '{rejected,occurredAt}', '')) ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$'
    THEN COALESCE(NULLIF(result_payload #>> '{accepted,occurredAt}', ''), NULLIF(result_payload #>> '{rejected,occurredAt}', ''))::TIMESTAMPTZ
  ELSE NULL
END;

CREATE OR REPLACE FUNCTION runtime.canonical_command_results_set_typed_facts()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  NEW.accepted_at_ts := CASE
    WHEN NEW.accepted_at ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$' THEN NEW.accepted_at::TIMESTAMPTZ
    ELSE NULL
  END;

  NEW.completed_at_ts := CASE
    WHEN NEW.completed_at ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$' THEN NEW.completed_at::TIMESTAMPTZ
    ELSE NULL
  END;

  RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION runtime.canonical_venue_events_set_typed_facts()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  NEW.emitted_at_ts := CASE
    WHEN NEW.emitted_at ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$' THEN NEW.emitted_at::TIMESTAMPTZ
    ELSE NULL
  END;

  RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION runtime.canonical_venue_event_batches_set_typed_facts()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  NEW.created_at_ts := CASE
    WHEN NEW.created_at ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$' THEN NEW.created_at::TIMESTAMPTZ
    ELSE NULL
  END;

  RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION runtime.canonical_command_outcomes_set_typed_facts()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  v_occurred_at TEXT;
BEGIN
  v_occurred_at := COALESCE(
    NULLIF(NEW.result_payload #>> '{accepted,occurredAt}', ''),
    NULLIF(NEW.result_payload #>> '{rejected,occurredAt}', '')
  );

  NEW.occurred_at_ts := CASE
    WHEN v_occurred_at ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$' THEN v_occurred_at::TIMESTAMPTZ
    ELSE NULL
  END;

  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS canonical_command_results_set_typed_facts ON runtime.canonical_command_results;
DROP TRIGGER IF EXISTS canonical_venue_events_set_typed_facts ON runtime.canonical_venue_events;
DROP TRIGGER IF EXISTS canonical_venue_event_batches_set_typed_facts ON runtime.canonical_venue_event_batches;
DROP TRIGGER IF EXISTS canonical_command_outcomes_set_typed_facts ON runtime.canonical_command_outcomes;

CREATE TRIGGER canonical_command_results_set_typed_facts
BEFORE INSERT OR UPDATE OF accepted_at, completed_at ON runtime.canonical_command_results
FOR EACH ROW
EXECUTE FUNCTION runtime.canonical_command_results_set_typed_facts();

CREATE TRIGGER canonical_venue_events_set_typed_facts
BEFORE INSERT OR UPDATE OF emitted_at ON runtime.canonical_venue_events
FOR EACH ROW
EXECUTE FUNCTION runtime.canonical_venue_events_set_typed_facts();

CREATE TRIGGER canonical_venue_event_batches_set_typed_facts
BEFORE INSERT OR UPDATE OF created_at ON runtime.canonical_venue_event_batches
FOR EACH ROW
EXECUTE FUNCTION runtime.canonical_venue_event_batches_set_typed_facts();

CREATE TRIGGER canonical_command_outcomes_set_typed_facts
BEFORE INSERT OR UPDATE OF result_payload ON runtime.canonical_command_outcomes
FOR EACH ROW
EXECUTE FUNCTION runtime.canonical_command_outcomes_set_typed_facts();

CREATE INDEX IF NOT EXISTS idx_canonical_command_results_completed_typed
  ON runtime.canonical_command_results(completed_at_ts, partition_id, partition_seq)
  WHERE completed_at_ts IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_canonical_venue_events_emitted_typed
  ON runtime.canonical_venue_events(emitted_at_ts, partition_id, event_seq)
  WHERE emitted_at_ts IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_canonical_venue_event_batches_created_typed
  ON runtime.canonical_venue_event_batches(created_at_ts, event_stream, batch_id)
  WHERE created_at_ts IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_canonical_command_outcomes_occurred_typed
  ON runtime.canonical_command_outcomes(occurred_at_ts, partition_id, stream_sequence)
  WHERE occurred_at_ts IS NOT NULL;
