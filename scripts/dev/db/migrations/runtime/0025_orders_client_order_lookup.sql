-- Persist clientOrderId/runId/venueSessionId on runtime.orders so a slow, outside-the-hot-path
-- resolver (POST /api/v1/orders/cancel-by-client-order) can turn a client-supplied clientOrderId
-- back into the runId/venueSessionId/instrumentId/orderId a normal routed CancelOrder needs.
-- See docs/COMMAND_INTAKE_PROCESS.md "Cancel Policy".
ALTER TABLE runtime.orders
  ADD COLUMN IF NOT EXISTS client_order_id TEXT NOT NULL DEFAULT '',
  ADD COLUMN IF NOT EXISTS run_id TEXT NOT NULL DEFAULT '',
  ADD COLUMN IF NOT EXISTS venue_session_id TEXT NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS runtime_orders_participant_client_order_id
  ON runtime.orders(participant_id, client_order_id)
  WHERE client_order_id <> '';

CREATE OR REPLACE FUNCTION runtime.runtime_persist_submit_outcomes(
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
  upsert_results AS (
    INSERT INTO runtime.submit_results(command_id, result_type, event_id, order_id, engine_order_id, code, reason, occurred_at)
    SELECT
      outcome->>'commandId',
      outcome->>'resultType',
      outcome->>'eventId',
      outcome->>'orderId',
      outcome->>'engineOrderId',
      outcome->>'code',
      outcome->>'reason',
      outcome->>'occurredAt'
    FROM outcomes
    ON CONFLICT (command_id) DO UPDATE SET
      result_type = EXCLUDED.result_type,
      event_id = EXCLUDED.event_id,
      order_id = EXCLUDED.order_id,
      engine_order_id = EXCLUDED.engine_order_id,
      code = EXCLUDED.code,
      reason = EXCLUDED.reason,
      occurred_at = EXCLUDED.occurred_at
    RETURNING 1
  ),
  accepted_orders AS (
    SELECT NULLIF(outcome->'acceptedOrder', 'null'::jsonb) AS accepted_order
    FROM outcomes
  ),
  upsert_orders AS (
    INSERT INTO runtime.orders(order_id, engine_order_id, instrument_id, participant_id, account_id, side, order_type, quantity_units, limit_price, currency, time_in_force, accepted_at, client_order_id, run_id, venue_session_id)
    SELECT
      accepted_order->>'orderId',
      accepted_order->>'engineOrderId',
      accepted_order->>'instrumentId',
      accepted_order->>'participantId',
      accepted_order->>'accountId',
      accepted_order->>'side',
      accepted_order->>'orderType',
      accepted_order->>'quantityUnits',
      accepted_order->>'limitPrice',
      accepted_order->>'currency',
      accepted_order->>'timeInForce',
      accepted_order->>'acceptedAt',
      COALESCE(accepted_order->>'clientOrderId', ''),
      COALESCE(accepted_order->>'runId', ''),
      COALESCE(accepted_order->>'venueSessionId', '')
    FROM accepted_orders
    WHERE accepted_order IS NOT NULL
      AND jsonb_typeof(accepted_order) = 'object'
    ON CONFLICT (order_id) DO UPDATE SET
      engine_order_id = EXCLUDED.engine_order_id,
      instrument_id = EXCLUDED.instrument_id,
      participant_id = EXCLUDED.participant_id,
      account_id = EXCLUDED.account_id,
      side = EXCLUDED.side,
      order_type = EXCLUDED.order_type,
      quantity_units = EXCLUDED.quantity_units,
      limit_price = EXCLUDED.limit_price,
      currency = EXCLUDED.currency,
      time_in_force = EXCLUDED.time_in_force,
      accepted_at = EXCLUDED.accepted_at,
      client_order_id = EXCLUDED.client_order_id,
      run_id = EXCLUDED.run_id,
      venue_session_id = EXCLUDED.venue_session_id
    RETURNING 1
  ),
  insert_executions AS (
    INSERT INTO runtime.executions(event_id, execution_id, order_id, instrument_id, quantity_units, execution_price, currency, occurred_at)
    SELECT
      execution->>'eventId',
      execution->>'executionId',
      execution->>'orderId',
      execution->>'instrumentId',
      execution->>'quantityUnits',
      execution->>'executionPrice',
      execution->>'currency',
      execution->>'occurredAt'
    FROM outcomes
    CROSS JOIN LATERAL jsonb_array_elements(
      CASE
        WHEN jsonb_typeof(outcome->'executions') = 'array' THEN outcome->'executions'
        ELSE '[]'::jsonb
      END
    ) AS execution
    ON CONFLICT (event_id) DO NOTHING
    RETURNING 1
  ),
  insert_trades AS (
    INSERT INTO runtime.trades(event_id, trade_id, execution_id, buy_order_id, sell_order_id, instrument_id, quantity_units, price, currency, occurred_at)
    SELECT
      trade->>'eventId',
      trade->>'tradeId',
      trade->>'executionId',
      trade->>'buyOrderId',
      trade->>'sellOrderId',
      trade->>'instrumentId',
      trade->>'quantityUnits',
      trade->>'price',
      trade->>'currency',
      trade->>'occurredAt'
    FROM outcomes
    CROSS JOIN LATERAL jsonb_array_elements(
      CASE
        WHEN jsonb_typeof(outcome->'trades') = 'array' THEN outcome->'trades'
        ELSE '[]'::jsonb
      END
    ) AS trade
    ON CONFLICT (event_id) DO NOTHING
    RETURNING 1
  ),
  dirty_ids AS (
    SELECT DISTINCT order_id FROM (
      SELECT outcome->>'orderId' AS order_id FROM outcomes
      UNION ALL
      SELECT trade->>'buyOrderId'
      FROM outcomes
      CROSS JOIN LATERAL jsonb_array_elements(
        CASE WHEN jsonb_typeof(outcome->'trades') = 'array' THEN outcome->'trades' ELSE '[]'::jsonb END
      ) AS trade
      UNION ALL
      SELECT trade->>'sellOrderId'
      FROM outcomes
      CROSS JOIN LATERAL jsonb_array_elements(
        CASE WHEN jsonb_typeof(outcome->'trades') = 'array' THEN outcome->'trades' ELSE '[]'::jsonb END
      ) AS trade
    ) ids
    WHERE COALESCE(order_id, '') <> ''
  ),
  mark_dirty AS (
    INSERT INTO runtime.order_lifecycle_dirty(order_id)
    SELECT order_id FROM dirty_ids
    ON CONFLICT (order_id) DO UPDATE SET dirtied_at = now()
    RETURNING 1
  ),
  parsed_events AS (
    SELECT
      event,
      outcomes.outcome_ordinality,
      event_ordinality::BIGINT AS event_ordinality
    FROM outcomes
    CROSS JOIN LATERAL jsonb_array_elements(
      CASE
        WHEN jsonb_typeof(outcome->'events') = 'array' THEN outcome->'events'
        ELSE '[]'::jsonb
      END
    ) WITH ORDINALITY AS event_rows(event, event_ordinality)
  ),
  trace_counts AS (
    SELECT event->>'traceId' AS trace_id, COUNT(*)::BIGINT AS event_count
    FROM parsed_events
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
      parsed.event,
      parsed.outcome_ordinality,
      parsed.event_ordinality,
      row_number() OVER (
        PARTITION BY parsed.event->>'traceId'
        ORDER BY parsed.outcome_ordinality, parsed.event_ordinality
      ) - 1 AS trace_offset
    FROM parsed_events parsed
  ),
  insert_events AS (
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
    ORDER BY ordered_events.outcome_ordinality, ordered_events.event_ordinality
    ON CONFLICT (event_id) DO NOTHING
    RETURNING 1
  )
  SELECT COUNT(*) INTO persisted_count FROM outcomes;

  RETURN persisted_count;
END;
$$;

CREATE OR REPLACE FUNCTION runtime.runtime_project_canonical_command_outcomes(
  p_projection_name TEXT,
  p_batch_size INTEGER,
  p_partitions INTEGER[] DEFAULT NULL,
  p_include_fills BOOLEAN DEFAULT TRUE
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

  WITH selected_partitions AS (
    SELECT DISTINCT partition_id
    FROM (
      SELECT unnest(p_partitions) AS partition_id
      WHERE p_partitions IS NOT NULL AND cardinality(p_partitions) > 0
      UNION ALL
      SELECT DISTINCT partition_id
      FROM runtime.canonical_command_outcomes
      WHERE p_partitions IS NULL OR cardinality(p_partitions) = 0
    ) partitions
  ),
  partition_budget AS (
    SELECT GREATEST(
      1,
      CEIL(effective_batch_size::NUMERIC / GREATEST((SELECT COUNT(*) FROM selected_partitions), 1))::INTEGER
    ) AS per_partition_limit
  ),
  ranked AS (
    SELECT
      canonical.partition_id,
      canonical.stream_sequence,
      canonical.command_id,
      canonical.command_type,
      canonical.order_id,
      canonical.result_status,
      canonical.reject_code,
      canonical.result_payload,
      COALESCE(canonical.result_payload->'acceptedOrder', payloads.payload_json) AS order_payload,
      row_number() OVER (
        PARTITION BY canonical.partition_id
        ORDER BY canonical.stream_sequence
      ) AS partition_row
    FROM runtime.canonical_command_outcomes canonical
    JOIN selected_partitions selected
      ON selected.partition_id = canonical.partition_id
    LEFT JOIN runtime.projection_watermarks watermark
      ON watermark.projection_name = p_projection_name
     AND watermark.partition_id = canonical.partition_id
    LEFT JOIN command_log.command_payloads payloads
      ON payloads.command_id = canonical.command_id
    WHERE canonical.command_type IN ('SubmitOrder', 'ModifyOrder', 'CancelOrder')
      AND canonical.stream_sequence > COALESCE(watermark.last_partition_seq, 0)
  ),
  eligible AS (
    SELECT *
    FROM ranked
    CROSS JOIN partition_budget
    WHERE partition_row <= partition_budget.per_partition_limit
    ORDER BY partition_row, partition_id, stream_sequence
    LIMIT effective_batch_size
  ),
  shaped AS (
    SELECT
      partition_id,
      stream_sequence,
      jsonb_build_object(
        'commandId', command_id,
        'resultType', result_status,
        'eventId', COALESCE(NULLIF(result_payload #>> '{accepted,eventId}', ''), NULLIF(result_payload #>> '{rejected,eventId}', ''), 'evt-' || command_id),
        'orderId', order_id,
        'engineOrderId', COALESCE(result_payload #>> '{accepted,engineOrderId}', ''),
        'code', COALESCE(NULLIF(reject_code, ''), result_payload #>> '{rejected,code}', ''),
        'reason', COALESCE(result_payload #>> '{rejected,reason}', ''),
        'occurredAt', COALESCE(NULLIF(result_payload #>> '{accepted,occurredAt}', ''), NULLIF(result_payload #>> '{rejected,occurredAt}', ''), ''),
        'acceptedOrder', CASE
          WHEN command_type = 'SubmitOrder'
           AND order_payload IS NOT NULL
           AND COALESCE(order_payload->>'instrumentId', '') <> ''
           AND COALESCE(order_payload->>'participantId', '') <> ''
           AND COALESCE(order_payload->>'accountId', '') <> ''
           AND (
             result_status <> 'rejected'
             OR COALESCE(NULLIF(reject_code, ''), result_payload #>> '{rejected,code}', '') NOT IN ('AUTHORIZATION_ERROR', 'REFERENCE_DATA_ERROR')
           )
          THEN jsonb_build_object(
            'orderId', order_id,
            'engineOrderId', CASE WHEN result_status = 'rejected' THEN '' ELSE COALESCE(result_payload #>> '{accepted,engineOrderId}', order_payload->>'engineOrderId', '') END,
            'instrumentId', COALESCE(order_payload->>'instrumentId', ''),
            'participantId', COALESCE(order_payload->>'participantId', ''),
            'accountId', COALESCE(order_payload->>'accountId', ''),
            'side', COALESCE(order_payload->>'side', ''),
            'orderType', COALESCE(order_payload->>'orderType', ''),
            'quantityUnits', COALESCE(order_payload->>'quantityUnits', ''),
            'limitPrice', COALESCE(order_payload->>'limitPrice', ''),
            'currency', COALESCE(order_payload->>'currency', ''),
            'timeInForce', COALESCE(order_payload->>'timeInForce', ''),
            'clientOrderId', COALESCE(order_payload->>'clientOrderId', ''),
            'runId', COALESCE(order_payload->>'runId', ''),
            'venueSessionId', COALESCE(order_payload->>'venueSessionId', ''),
            'acceptedAt', COALESCE(
              NULLIF(result_payload #>> '{accepted,occurredAt}', ''),
              NULLIF(result_payload #>> '{rejected,occurredAt}', ''),
              NULLIF(order_payload->>'acceptedAt', ''),
              ''
            )
          )
          ELSE NULL
        END,
        'executions', CASE WHEN p_include_fills THEN COALESCE(result_payload->'executions', '[]'::jsonb) ELSE '[]'::jsonb END,
        'trades', CASE WHEN p_include_fills THEN COALESCE(result_payload->'trades', '[]'::jsonb) ELSE '[]'::jsonb END,
        'events', jsonb_build_array(
          jsonb_build_object(
            'eventId', COALESCE(NULLIF(result_payload #>> '{accepted,eventId}', ''), NULLIF(result_payload #>> '{rejected,eventId}', ''), 'evt-' || command_id),
            'eventType', CASE
              WHEN result_status = 'rejected' THEN 'OrderRejected'
              WHEN command_type = 'CancelOrder' THEN 'OrderCancelled'
              WHEN command_type = 'ModifyOrder' THEN 'OrderModified'
              ELSE 'OrderAccepted'
            END,
            'orderId', order_id,
            'traceId', command_id,
            'causationId', command_id,
            'correlationId', command_id,
            'actorId', '',
            'producer', 'venue-event-batch-projector',
            'schemaVersion', 'v1',
            'occurredAt', COALESCE(NULLIF(result_payload #>> '{accepted,occurredAt}', ''), NULLIF(result_payload #>> '{rejected,occurredAt}', ''), ''),
            'payloadJson', result_payload
          )
        )
      ) AS result_payload
    FROM eligible
  ),
  projected AS (
    SELECT runtime.runtime_persist_submit_outcomes(
      COALESCE(
        jsonb_agg(result_payload ORDER BY stream_sequence, partition_id),
        '[]'::jsonb
      )
    ) AS count
    FROM shaped
  ),
  partition_max AS (
    SELECT partition_id, MAX(stream_sequence) AS last_partition_seq
    FROM shaped
    GROUP BY partition_id
  ),
  upsert_watermarks AS (
    INSERT INTO runtime.projection_watermarks(
      projection_name,
      partition_id,
      last_partition_seq,
      last_projected_at,
      updated_at,
      last_error
    )
    SELECT
      p_projection_name,
      partition_id,
      last_partition_seq,
      now(),
      now(),
      ''
    FROM partition_max
    ON CONFLICT (projection_name, partition_id) DO UPDATE SET
      last_partition_seq = GREATEST(
        runtime.projection_watermarks.last_partition_seq,
        EXCLUDED.last_partition_seq
      ),
      last_projected_at = EXCLUDED.last_projected_at,
      updated_at = EXCLUDED.updated_at,
      last_error = ''
    RETURNING 1
  )
  SELECT COALESCE(MAX(count), 0) INTO projected_count FROM projected;

  RETURN projected_count;
EXCEPTION WHEN OTHERS THEN
  INSERT INTO runtime.projection_watermarks(
    projection_name,
    partition_id,
    last_partition_seq,
    last_projected_at,
    updated_at,
    last_error
  )
  VALUES (p_projection_name, -1, 0, NULL, now(), SQLERRM)
  ON CONFLICT (projection_name, partition_id) DO UPDATE SET
    updated_at = EXCLUDED.updated_at,
    last_error = EXCLUDED.last_error;
  RAISE;
END;
$$;
