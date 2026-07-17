-- Move runtime event payload JSON off the hot runtime_events row for new
-- projection inserts. The hot row keeps typed lifecycle facts needed by
-- rebuildable projections; full payloads remain queryable via the side table.

CREATE TABLE IF NOT EXISTS runtime.runtime_event_payloads (
  event_id TEXT PRIMARY KEY,
  payload_json JSONB NOT NULL DEFAULT '{}'::jsonb
);

ALTER TABLE runtime.runtime_events
  ADD COLUMN IF NOT EXISTS modify_quantity_units TEXT NOT NULL DEFAULT '',
  ADD COLUMN IF NOT EXISTS modify_limit_price TEXT NOT NULL DEFAULT '';

INSERT INTO runtime.runtime_event_payloads(event_id, payload_json)
SELECT event_id, payload_json
FROM runtime.runtime_events
WHERE payload_json <> '{}'::jsonb
ON CONFLICT (event_id) DO NOTHING;

UPDATE runtime.runtime_events
SET
  modify_quantity_units = COALESCE(NULLIF(payload_json->>'quantityUnits', ''), ''),
  modify_limit_price = COALESCE(NULLIF(payload_json->>'limitPrice', ''), '')
WHERE event_type = 'OrderModified'
  AND payload_json <> '{}'::jsonb;

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

  IF NEW.event_type = 'OrderModified' THEN
    NEW.modify_quantity_units := COALESCE(NULLIF(NEW.payload_json->>'quantityUnits', ''), NEW.modify_quantity_units, '');
    NEW.modify_limit_price := COALESCE(NULLIF(NEW.payload_json->>'limitPrice', ''), NEW.modify_limit_price, '');
  ELSE
    NEW.modify_quantity_units := '';
    NEW.modify_limit_price := '';
  END IF;

  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS runtime_events_set_typed_facts ON runtime.runtime_events;

CREATE TRIGGER runtime_events_set_typed_facts
BEFORE INSERT OR UPDATE OF event_id, occurred_at, event_type, payload_json, modify_quantity_units, modify_limit_price ON runtime.runtime_events
FOR EACH ROW
EXECUTE FUNCTION runtime.runtime_events_set_typed_facts();

CREATE OR REPLACE FUNCTION runtime.runtime_project_order_lifecycle_state(
  p_batch_size INTEGER
)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
  projected_count BIGINT := 0;
  effective_batch_size INTEGER := 0;
BEGIN
  IF p_batch_size IS NULL OR p_batch_size <= 0 THEN
    RETURN 0;
  END IF;

  effective_batch_size := LEAST(p_batch_size, 5000);

  WITH selected_dirty AS (
    SELECT order_id
    FROM runtime.order_lifecycle_dirty
    ORDER BY dirtied_at, order_id
    LIMIT effective_batch_size
    FOR UPDATE SKIP LOCKED
  ),
  execution_totals AS (
    SELECT
      order_id,
      SUM(quantity_units::NUMERIC) AS filled_quantity_units
    FROM runtime.executions
    WHERE order_id IN (SELECT order_id FROM selected_dirty)
      AND quantity_units ~ '^[0-9]+(\.[0-9]+)?$'
    GROUP BY order_id
  ),
  latest_modify AS (
    SELECT DISTINCT ON (order_id)
      order_id,
      COALESCE(NULLIF(modify_quantity_units, ''), '') AS modified_quantity_units,
      COALESCE(NULLIF(modify_limit_price, ''), '') AS modified_limit_price,
      occurred_at
    FROM runtime.runtime_events
    WHERE event_type = 'OrderModified'
      AND order_id IN (SELECT order_id FROM selected_dirty)
    ORDER BY order_id, occurred_at DESC, sequence_number DESC, event_id DESC
  ),
  order_event_state AS (
    SELECT
      order_id,
      BOOL_OR(event_type = 'OrderCancelled') AS cancelled,
      BOOL_OR(event_type = 'OrderRejected') AS rejected,
      COALESCE(MAX(NULLIF(occurred_at, '')), '') AS last_event_at
    FROM runtime.runtime_events
    WHERE order_id IN (SELECT order_id FROM selected_dirty)
    GROUP BY order_id
  ),
  shaped AS (
    SELECT
      orders.order_id,
      orders.engine_order_id,
      orders.instrument_id,
      orders.participant_id,
      orders.account_id,
      orders.side,
      orders.order_type,
      orders.quantity_units AS original_quantity_units,
      COALESCE(NULLIF(latest_modify.modified_quantity_units, ''), orders.quantity_units) AS current_quantity_units,
      COALESCE(NULLIF(latest_modify.modified_limit_price, ''), orders.limit_price) AS current_limit_price,
      orders.currency,
      orders.time_in_force,
      orders.accepted_at,
      COALESCE(execution_totals.filled_quantity_units, 0) AS filled_quantity_units,
      COALESCE(order_event_state.cancelled, FALSE) AS cancelled,
      COALESCE(order_event_state.rejected, FALSE) AS rejected,
      COALESCE(NULLIF(order_event_state.last_event_at, ''), orders.accepted_at) AS last_event_at
    FROM runtime.orders orders
    JOIN selected_dirty ON selected_dirty.order_id = orders.order_id
    LEFT JOIN execution_totals ON execution_totals.order_id = orders.order_id
    LEFT JOIN latest_modify ON latest_modify.order_id = orders.order_id
    LEFT JOIN order_event_state ON order_event_state.order_id = orders.order_id
    WHERE COALESCE(NULLIF(latest_modify.modified_quantity_units, ''), orders.quantity_units) ~ '^[0-9]+(\.[0-9]+)?$'
  ),
  calculated AS (
    SELECT
      *,
      GREATEST(current_quantity_units::NUMERIC - filled_quantity_units, 0) AS remaining_quantity_units,
      CASE
        WHEN current_limit_price ~ '^-?[0-9]+(\.[0-9]+)?$' THEN current_limit_price::NUMERIC
        ELSE NULL
      END AS current_limit_price_num
    FROM shaped
  ),
  upserted AS (
    INSERT INTO runtime.order_lifecycle_state(
      order_id,
      engine_order_id,
      instrument_id,
      participant_id,
      account_id,
      side,
      order_type,
      original_quantity_units,
      remaining_quantity_units,
      filled_quantity_units,
      limit_price,
      currency,
      time_in_force,
      status,
      accepted_at,
      last_event_at,
      updated_at,
      original_quantity_units_num,
      remaining_quantity_units_num,
      filled_quantity_units_num,
      limit_price_num
    )
    SELECT
      order_id,
      engine_order_id,
      instrument_id,
      participant_id,
      account_id,
      side,
      order_type,
      original_quantity_units,
      CASE WHEN cancelled OR rejected THEN '0' ELSE remaining_quantity_units::TEXT END,
      filled_quantity_units::TEXT,
      current_limit_price,
      currency,
      time_in_force,
      CASE
        WHEN rejected THEN 'REJECTED'
        WHEN cancelled THEN 'CANCELLED'
        WHEN remaining_quantity_units = 0 THEN 'FILLED'
        WHEN filled_quantity_units > 0 THEN 'PARTIALLY_FILLED'
        ELSE 'OPEN'
      END,
      accepted_at,
      last_event_at,
      NOW(),
      original_quantity_units::NUMERIC,
      remaining_quantity_units,
      filled_quantity_units,
      current_limit_price_num
    FROM calculated
    ON CONFLICT (order_id) DO UPDATE SET
      engine_order_id = EXCLUDED.engine_order_id,
      instrument_id = EXCLUDED.instrument_id,
      participant_id = EXCLUDED.participant_id,
      account_id = EXCLUDED.account_id,
      side = EXCLUDED.side,
      order_type = EXCLUDED.order_type,
      original_quantity_units = EXCLUDED.original_quantity_units,
      remaining_quantity_units = EXCLUDED.remaining_quantity_units,
      filled_quantity_units = EXCLUDED.filled_quantity_units,
      limit_price = EXCLUDED.limit_price,
      currency = EXCLUDED.currency,
      time_in_force = EXCLUDED.time_in_force,
      status = EXCLUDED.status,
      accepted_at = EXCLUDED.accepted_at,
      last_event_at = EXCLUDED.last_event_at,
      updated_at = EXCLUDED.updated_at,
      original_quantity_units_num = EXCLUDED.original_quantity_units_num,
      remaining_quantity_units_num = EXCLUDED.remaining_quantity_units_num,
      filled_quantity_units_num = EXCLUDED.filled_quantity_units_num,
      limit_price_num = EXCLUDED.limit_price_num
    RETURNING order_id, instrument_id
  ),
  touched_instruments AS (
    SELECT DISTINCT instrument_id
    FROM upserted
    WHERE instrument_id <> ''
  ),
  mark_market_dirty AS (
    INSERT INTO runtime.market_data_snapshot_dirty(instrument_id, dirtied_at)
    SELECT instrument_id, NOW()
    FROM touched_instruments
    ORDER BY instrument_id
    ON CONFLICT (instrument_id) DO NOTHING
  ),
  cleared AS (
    DELETE FROM runtime.order_lifecycle_dirty dirty
    USING selected_dirty
    WHERE dirty.order_id = selected_dirty.order_id
    RETURNING dirty.order_id
  )
  SELECT COUNT(*) INTO projected_count FROM upserted;

  RETURN projected_count;
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
  ordered_legacy_events AS (
    SELECT
      legacy.event,
      legacy.outcome_ordinality,
      legacy.event_ordinality,
      trace_starts.start_sequence + row_number() OVER (
        PARTITION BY legacy.event->>'traceId'
        ORDER BY legacy.outcome_ordinality, legacy.event_ordinality
      ) - 1 AS sequence_number
    FROM legacy_events legacy
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
  ),
  insert_events AS (
    INSERT INTO runtime.runtime_events(
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
      modify_limit_price
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
      END
    FROM all_events
    ORDER BY all_events.event->>'eventId'
    ON CONFLICT (event_id) DO NOTHING
    RETURNING event_id
  ),
  insert_payloads AS (
    INSERT INTO runtime.runtime_event_payloads(event_id, payload_json)
    SELECT
      event->>'eventId',
      COALESCE(event->'payloadJson', '{}'::jsonb)
    FROM all_events
    WHERE COALESCE(event->'payloadJson', '{}'::jsonb) <> '{}'::jsonb
    ORDER BY all_events.event->>'eventId'
    ON CONFLICT (event_id) DO NOTHING
    RETURNING 1
  )
  SELECT COUNT(*) INTO persisted_count FROM outcomes;

  RETURN persisted_count;
END;
$$;
