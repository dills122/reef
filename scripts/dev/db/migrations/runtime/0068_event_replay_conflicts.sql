-- Fail closed on changed event IDs while keeping full payloads in the cold table.
-- Existing hot rows are checked against their side payload lazily on replay;
-- adding the nullable digest avoids a table-wide backfill.
ALTER TABLE runtime.runtime_events
  ADD COLUMN IF NOT EXISTS payload_sha256 BYTEA;

CREATE OR REPLACE FUNCTION runtime.runtime_reject_event_replay_conflict(p_event_id TEXT)
RETURNS TEXT
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'runtime event replay conflict for existing event_id %', p_event_id
    USING ERRCODE = '23505';
END;
$$;

CREATE OR REPLACE FUNCTION runtime.runtime_persist_submit_outcome_timeline_stage(
  p_outcomes JSONB
)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
  persisted_count BIGINT := 0;
  v_trace TEXT;
BEGIN
  IF p_outcomes IS NULL THEN
    RETURN 0;
  END IF;

  IF jsonb_typeof(p_outcomes) <> 'array' THEN
    RAISE EXCEPTION 'runtime submit outcomes payload must be a JSON array';
  END IF;

  -- Legacy sequences are allocated only for IDs absent after same-trace writers
  -- finish. Acquire locks in one order to avoid deadlocks between batches.
  FOR v_trace IN
    SELECT DISTINCT event->>'traceId'
    FROM jsonb_array_elements(p_outcomes) AS outcome_rows(outcome)
    CROSS JOIN LATERAL jsonb_array_elements(
      CASE WHEN jsonb_typeof(outcome->'events') = 'array' THEN outcome->'events' ELSE '[]'::jsonb END
    ) AS event_rows(event)
    WHERE CASE
      WHEN COALESCE(outcome->>'streamSequence', '') ~ '^[0-9]+$'
       AND (outcome->>'streamSequence')::NUMERIC BETWEEN 1 AND 92233720368547758
      THEN false ELSE true
    END
    ORDER BY 1
  LOOP
    PERFORM pg_advisory_xact_lock(198765432, hashtext(COALESCE(v_trace, '')));
  END LOOP;

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
  existing_legacy_events AS (
    SELECT legacy.event, legacy.outcome_ordinality, legacy.event_ordinality, stored.sequence_number
    FROM legacy_events legacy
    JOIN runtime.runtime_events stored ON stored.event_id = legacy.event->>'eventId'
  ),
  new_legacy_events AS (
    SELECT legacy.*
    FROM legacy_events legacy
    WHERE NOT EXISTS (
      SELECT 1 FROM runtime.runtime_events stored WHERE stored.event_id = legacy.event->>'eventId'
    )
  ),
  trace_counts AS (
    SELECT event->>'traceId' AS trace_id, COUNT(*)::BIGINT AS event_count
    FROM new_legacy_events
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
  ordered_legacy_events AS (
    SELECT
      legacy.event,
      legacy.outcome_ordinality,
      legacy.event_ordinality,
      trace_starts.start_sequence + row_number() OVER (
        PARTITION BY legacy.event->>'traceId'
        ORDER BY legacy.outcome_ordinality, legacy.event_ordinality
      ) - 1 AS sequence_number
    FROM new_legacy_events legacy
    JOIN trace_starts ON trace_starts.trace_id = legacy.event->>'traceId'
  ),
  all_events AS (
    SELECT
      event,
      outcome_ordinality,
      event_ordinality,
      stream_sequence * 100 + event_ordinality AS sequence_number
    FROM deterministic_events
    UNION ALL
    SELECT
      event,
      outcome_ordinality,
      event_ordinality,
      sequence_number
    FROM ordered_legacy_events
    UNION ALL
    SELECT event, outcome_ordinality, event_ordinality, sequence_number
    FROM existing_legacy_events
  ),
  insert_events AS (
    INSERT INTO runtime.runtime_events AS stored(
      event_id,
      event_type,
      order_id,
      trace_id,
      causation_id,
      correlation_id,
      actor_id,
      producer,
      schema_version,
      sequence_number,
      payload_json,
      occurred_at,
      modify_quantity_units,
      modify_limit_price,
      payload_sha256
    )
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
      all_events.sequence_number,
      '{}'::jsonb,
      event->>'occurredAt',
      CASE
        WHEN event->>'eventType' = 'OrderModified' THEN COALESCE(NULLIF(event->'payloadJson'->>'quantityUnits', ''), '')
        ELSE ''
      END,
      CASE
        WHEN event->>'eventType' = 'OrderModified' THEN COALESCE(NULLIF(event->'payloadJson'->>'limitPrice', ''), '')
        ELSE ''
      END,
      sha256(COALESCE(event->'payloadJson', '{}'::jsonb)::text::bytea)
    FROM all_events
    ORDER BY all_events.event->>'eventId'
    ON CONFLICT (event_id) DO UPDATE
      SET event_id = runtime.runtime_reject_event_replay_conflict(EXCLUDED.event_id)
      WHERE ROW(
        stored.event_type, stored.order_id, stored.trace_id, stored.causation_id,
        stored.correlation_id, stored.actor_id, stored.producer, stored.schema_version,
        stored.sequence_number, stored.occurred_at, stored.modify_quantity_units,
        stored.modify_limit_price,
        COALESCE(stored.payload_sha256, sha256(COALESCE(
          (SELECT payload_json FROM runtime.runtime_event_payloads WHERE event_id = stored.event_id),
          stored.payload_json
        )::text::bytea))
      ) IS DISTINCT FROM ROW(
        EXCLUDED.event_type, EXCLUDED.order_id, EXCLUDED.trace_id, EXCLUDED.causation_id,
        EXCLUDED.correlation_id, EXCLUDED.actor_id, EXCLUDED.producer, EXCLUDED.schema_version,
        EXCLUDED.sequence_number, EXCLUDED.occurred_at, EXCLUDED.modify_quantity_units,
        EXCLUDED.modify_limit_price, EXCLUDED.payload_sha256
      )
    RETURNING event_id
  ),
  insert_payloads AS (
    INSERT INTO runtime.runtime_event_payloads AS stored(event_id, payload_json)
    SELECT
      event->>'eventId',
      COALESCE(event->'payloadJson', '{}'::jsonb)
    FROM all_events
    WHERE COALESCE(event->'payloadJson', '{}'::jsonb) <> '{}'::jsonb
    ORDER BY all_events.event->>'eventId'
    ON CONFLICT (event_id) DO UPDATE
      SET event_id = runtime.runtime_reject_event_replay_conflict(EXCLUDED.event_id)
      WHERE stored.payload_json IS DISTINCT FROM EXCLUDED.payload_json
    RETURNING 1
  )
  SELECT COUNT(*) INTO persisted_count FROM outcomes;

  RETURN persisted_count;
END;
$$;
