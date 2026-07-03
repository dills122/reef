CREATE OR REPLACE FUNCTION runtime.runtime_append_canonical_submit_outcomes(
  p_outcomes JSONB
)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
  appended_count BIGINT := 0;
BEGIN
  IF p_outcomes IS NULL THEN
    RETURN 0;
  END IF;

  IF jsonb_typeof(p_outcomes) <> 'array' THEN
    RAISE EXCEPTION 'canonical submit outcomes payload must be a JSON array';
  END IF;

  WITH outcomes AS (
    SELECT outcome, ordinality::BIGINT AS outcome_ordinality
    FROM jsonb_array_elements(p_outcomes) WITH ORDINALITY AS outcome_rows(outcome, ordinality)
  ),
  insert_results AS (
    INSERT INTO runtime.canonical_command_results(
      command_id,
      run_id,
      venue_session_id,
      partition_id,
      partition_seq,
      stream_name,
      stream_seq,
      idempotency_key,
      payload_hash,
      instrument_id,
      command_type,
      result_status,
      reject_code,
      accepted_at,
      completed_at,
      engine_shard_id,
      result_payload
    )
    SELECT
      outcome->>'commandId',
      COALESCE(outcome->>'runId', ''),
      COALESCE(outcome->>'venueSessionId', ''),
      COALESCE((outcome->>'partitionId')::INTEGER, -1),
      COALESCE((outcome->>'partitionSequence')::BIGINT, 0),
      COALESCE(outcome->>'streamName', ''),
      COALESCE((outcome->>'streamSequence')::BIGINT, 0),
      COALESCE(outcome->>'idempotencyKey', ''),
      COALESCE(outcome->>'payloadHash', ''),
      COALESCE(outcome->>'instrumentId', ''),
      COALESCE(outcome->>'commandType', ''),
      COALESCE(outcome->>'resultStatus', ''),
      COALESCE(outcome->>'rejectCode', ''),
      COALESCE(outcome->>'acceptedAt', ''),
      COALESCE(outcome->>'completedAt', ''),
      COALESCE(outcome->>'engineShardId', ''),
      COALESCE(outcome->'resultPayload', '{}'::jsonb)
    FROM outcomes
    ON CONFLICT (command_id) DO NOTHING
    RETURNING 1
  ),
  parsed_events AS (
    SELECT
      outcome,
      event,
      event_ordinality::INTEGER AS deterministic_event_index
    FROM outcomes
    CROSS JOIN LATERAL jsonb_array_elements(
      CASE
        WHEN COALESCE(NULLIF(current_setting('reef.stream_ack_canonical_event_rows_enabled', TRUE), ''), 'true')::BOOLEAN
          AND jsonb_typeof(outcome->'events') = 'array' THEN outcome->'events'
        ELSE '[]'::jsonb
      END
    ) WITH ORDINALITY AS event_rows(event, event_ordinality)
  ),
  insert_events AS (
    INSERT INTO runtime.canonical_venue_events(
      event_id,
      run_id,
      venue_session_id,
      partition_id,
      partition_seq,
      event_seq,
      command_id,
      event_type,
      aggregate_type,
      aggregate_id,
      instrument_id,
      deterministic_event_index,
      payload,
      emitted_at
    )
    SELECT
      event->>'eventId',
      COALESCE(outcome->>'runId', ''),
      COALESCE(outcome->>'venueSessionId', ''),
      COALESCE((outcome->>'partitionId')::INTEGER, -1),
      COALESCE((outcome->>'partitionSequence')::BIGINT, 0),
      COALESCE((outcome->>'partitionSequence')::BIGINT, 0) * 1000 + deterministic_event_index,
      outcome->>'commandId',
      COALESCE(event->>'eventType', ''),
      CASE
        WHEN event->>'eventType' = 'ExecutionCreated' THEN 'execution'
        WHEN event->>'eventType' = 'TradeCreated' THEN 'trade'
        ELSE 'order'
      END,
      COALESCE(event->>'orderId', event->>'eventId', ''),
      COALESCE(outcome->>'instrumentId', ''),
      deterministic_event_index,
      event,
      COALESCE(event->>'occurredAt', outcome->>'completedAt', '')
    FROM parsed_events
    ON CONFLICT (event_id) DO NOTHING
    RETURNING 1
  )
  SELECT COUNT(*) INTO appended_count FROM outcomes;

  RETURN appended_count;
END;
$$;
