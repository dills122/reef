-- Avoid the shared trace-sequence allocator for canonical venue-event timeline
-- projection payloads that carry the durable stream sequence. Legacy payloads
-- without streamSequence keep the existing allocator path.

CREATE OR REPLACE FUNCTION runtime.runtime_persist_submit_outcome_timeline_stage(
  p_outcomes JSONB
)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
  persisted_count BIGINT := 0;
BEGIN
  IF p_outcomes IS NULL THEN
    RETURN 0;
  END IF;

  IF jsonb_typeof(p_outcomes) <> 'array' THEN
    RAISE EXCEPTION 'runtime submit outcomes payload must be a JSON array';
  END IF;

  WITH outcomes AS (
    SELECT outcome, ordinality::BIGINT AS outcome_ordinality
    FROM jsonb_array_elements(p_outcomes) WITH ORDINALITY AS outcome_rows(outcome, ordinality)
  ),
  parsed_events AS (
    SELECT
      event,
      outcomes.outcome_ordinality,
      event_ordinality::BIGINT AS event_ordinality,
      CASE
        WHEN COALESCE(outcome->>'streamSequence', '') ~ '^[0-9]+$'
         AND (outcome->>'streamSequence')::NUMERIC BETWEEN 1 AND 92233720368547758
        THEN (outcome->>'streamSequence')::BIGINT
        ELSE NULL
      END AS stream_sequence
    FROM outcomes
    CROSS JOIN LATERAL jsonb_array_elements(
      CASE
        WHEN jsonb_typeof(outcome->'events') = 'array' THEN outcome->'events'
        ELSE '[]'::jsonb
      END
    ) WITH ORDINALITY AS event_rows(event, event_ordinality)
  ),
  deterministic_events AS (
    SELECT *
    FROM parsed_events
    WHERE stream_sequence IS NOT NULL
  ),
  legacy_events AS (
    SELECT *
    FROM parsed_events
    WHERE stream_sequence IS NULL
  ),
  trace_counts AS (
    SELECT event->>'traceId' AS trace_id, COUNT(*)::BIGINT AS event_count
    FROM legacy_events
    GROUP BY event->>'traceId'
  ),
  trace_allocations AS (
    INSERT INTO runtime.runtime_trace_sequences AS trace_sequence(trace_id, next_sequence)
    SELECT trace_id, event_count FROM trace_counts
    ON CONFLICT (trace_id) DO UPDATE SET next_sequence = trace_sequence.next_sequence + EXCLUDED.next_sequence
    RETURNING trace_id, next_sequence
  ),
  trace_starts AS (
    SELECT
      counts.trace_id,
      allocations.next_sequence - counts.event_count + 1 AS start_sequence
    FROM trace_counts counts
    JOIN trace_allocations allocations ON allocations.trace_id = counts.trace_id
  ),
  ordered_events AS (
    SELECT
      legacy.event,
      legacy.outcome_ordinality,
      legacy.event_ordinality,
      row_number() OVER (
        PARTITION BY legacy.event->>'traceId'
        ORDER BY legacy.outcome_ordinality, legacy.event_ordinality
      ) - 1 AS trace_offset
    FROM legacy_events legacy
  ),
  insert_deterministic_events AS (
    INSERT INTO runtime.runtime_events(event_id, event_type, order_id, trace_id, causation_id, correlation_id, actor_id, producer, schema_version, sequence_number, payload_json, occurred_at)
    SELECT
      event->>'eventId',
      event->>'eventType',
      event->>'orderId',
      event->>'traceId',
      event->>'causationId',
      event->>'correlationId',
      COALESCE(event->>'actorId', ''),
      event->>'producer',
      event->>'schemaVersion',
      deterministic_events.stream_sequence * 100 + deterministic_events.event_ordinality,
      COALESCE(event->'payloadJson', '{}'::jsonb),
      event->>'occurredAt'
    FROM deterministic_events
    ORDER BY deterministic_events.event->>'eventId'
    ON CONFLICT (event_id) DO NOTHING
    RETURNING 1
  ),
  insert_legacy_events AS (
    INSERT INTO runtime.runtime_events(event_id, event_type, order_id, trace_id, causation_id, correlation_id, actor_id, producer, schema_version, sequence_number, payload_json, occurred_at)
    SELECT
      event->>'eventId',
      event->>'eventType',
      event->>'orderId',
      event->>'traceId',
      event->>'causationId',
      event->>'correlationId',
      COALESCE(event->>'actorId', ''),
      event->>'producer',
      event->>'schemaVersion',
      trace_starts.start_sequence + ordered_events.trace_offset,
      COALESCE(event->'payloadJson', '{}'::jsonb),
      event->>'occurredAt'
    FROM ordered_events
    JOIN trace_starts ON trace_starts.trace_id = ordered_events.event->>'traceId'
    ORDER BY ordered_events.event->>'eventId'
    ON CONFLICT (event_id) DO NOTHING
    RETURNING 1
  )
  SELECT COUNT(*) INTO persisted_count FROM outcomes;

  RETURN persisted_count;
END;
$$;

CREATE OR REPLACE FUNCTION runtime.runtime_persist_submit_outcomes(
  p_outcomes JSONB,
  p_projection_stage TEXT
)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
  normalized_stage TEXT := LOWER(COALESCE(NULLIF(p_projection_stage, ''), 'full'));
  persisted_count BIGINT := 0;
BEGIN
  IF normalized_stage IN ('full', 'all') THEN
    persisted_count := runtime.runtime_persist_submit_outcome_status_stage(p_outcomes);
    PERFORM runtime.runtime_persist_submit_outcome_timeline_stage(p_outcomes);
    RETURN persisted_count;
  END IF;

  IF normalized_stage IN ('command-status', 'status', 'lifecycle', 'core') THEN
    RETURN runtime.runtime_persist_submit_outcome_status_stage(p_outcomes);
  END IF;

  IF normalized_stage IN ('timeline', 'event-timeline', 'events') THEN
    RETURN runtime.runtime_persist_submit_outcome_timeline_stage(p_outcomes);
  END IF;

  RAISE EXCEPTION 'unsupported submit outcome projection stage: %', p_projection_stage;
END;
$$;
