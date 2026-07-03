CREATE OR REPLACE FUNCTION runtime.runtime_persist_submit_outcomes(
  p_outcomes JSONB
)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
  outcome JSONB;
  persisted_count BIGINT := 0;
BEGIN
  IF p_outcomes IS NULL THEN
    RETURN 0;
  END IF;

  IF jsonb_typeof(p_outcomes) <> 'array' THEN
    RAISE EXCEPTION 'runtime submit outcomes payload must be a JSON array';
  END IF;

  FOR outcome IN SELECT value FROM jsonb_array_elements(p_outcomes)
  LOOP
    PERFORM runtime.runtime_persist_submit_outcome(
      outcome->>'commandId',
      outcome->>'resultType',
      outcome->>'eventId',
      outcome->>'orderId',
      outcome->>'engineOrderId',
      outcome->>'code',
      outcome->>'reason',
      outcome->>'occurredAt',
      NULLIF(outcome->'acceptedOrder', 'null'::jsonb),
      COALESCE(outcome->'executions', '[]'::jsonb),
      COALESCE(outcome->'trades', '[]'::jsonb),
      COALESCE(outcome->'events', '[]'::jsonb)
    );
    persisted_count := persisted_count + 1;
  END LOOP;

  RETURN persisted_count;
END;
$$;
