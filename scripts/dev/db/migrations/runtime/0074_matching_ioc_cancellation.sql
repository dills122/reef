-- Preserve exact IOC cancellation in durable results and terminal stream lifecycle.
ALTER TABLE runtime.submit_results ADD COLUMN IF NOT EXISTS cancelled JSONB,
  ADD COLUMN IF NOT EXISTS matching_facts JSONB;

-- Legacy NULL means unknown original facts, not an empty matching result.
-- Restore only from immutable command facts, never later order projections.
WITH original_results AS (
  SELECT command_id, result_status, result_payload, 1 AS source_priority
  FROM runtime.canonical_command_outcomes
  UNION ALL
  SELECT command_id, result_status, result_payload, 2 AS source_priority
  FROM runtime.canonical_command_outcomes_archive
  UNION ALL
  SELECT command_id, result_status, result_payload, 3 AS source_priority
  FROM runtime.canonical_command_results
), recoverable AS (
  SELECT DISTINCT ON (stored.command_id) stored.command_id, original.result_payload
  FROM runtime.submit_results stored
  JOIN original_results original ON original.command_id = stored.command_id
  WHERE stored.matching_facts IS NULL
    AND stored.result_type = original.result_status
    AND stored.event_id = COALESCE(original.result_payload #>> '{accepted,eventId}', original.result_payload #>> '{rejected,eventId}')
    AND stored.order_id = COALESCE(original.result_payload #>> '{accepted,orderId}', original.result_payload #>> '{rejected,orderId}')
  ORDER BY stored.command_id, original.source_priority
)
UPDATE runtime.submit_results stored
SET matching_facts = jsonb_build_object(
      'executions', COALESCE(NULLIF(original.result_payload->'executions', 'null'::jsonb), '[]'::jsonb),
      'trades', COALESCE(NULLIF(original.result_payload->'trades', 'null'::jsonb), '[]'::jsonb)),
    cancelled = COALESCE(stored.cancelled, NULLIF(original.result_payload->'cancelled', 'null'::jsonb))
FROM recoverable original
WHERE stored.command_id = original.command_id;

CREATE OR REPLACE FUNCTION runtime.runtime_persist_submit_outcome_status_stage(
  p_outcomes JSONB
)
RETURNS BIGINT
LANGUAGE plpgsql
VOLATILE
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
  parsed_trades AS MATERIALIZED (
    SELECT trade, COALESCE(outcome->>'runId', outcome#>>'{acceptedOrder,runId}', '') AS run_id
    FROM outcomes
    CROSS JOIN LATERAL jsonb_array_elements(
      CASE
        WHEN jsonb_typeof(outcome->'trades') = 'array' THEN outcome->'trades'
        ELSE '[]'::jsonb
      END
    ) AS trade
  ),
  upsert_results AS (
    INSERT INTO runtime.submit_results(command_id, result_type, event_id, order_id, engine_order_id, code, reason, occurred_at, run_id, cancelled, matching_facts)
    SELECT
      outcome->>'commandId',
      outcome->>'resultType',
      outcome->>'eventId',
      outcome->>'orderId',
      outcome->>'engineOrderId',
      outcome->>'code',
      outcome->>'reason',
      outcome->>'occurredAt',
      COALESCE(outcome->>'runId', outcome#>>'{acceptedOrder,runId}', ''),
      NULLIF(outcome->'cancelled', 'null'::jsonb),
      COALESCE(outcome->'matchingFacts', jsonb_build_object('executions', COALESCE(outcome->'executions', '[]'::jsonb), 'trades', COALESCE(outcome->'trades', '[]'::jsonb)))
    FROM outcomes
    ON CONFLICT (command_id) DO UPDATE SET
      command_id = runtime.runtime_reject_submit_result_replay_conflict(EXCLUDED.command_id)
    WHERE ROW(
      runtime.submit_results.run_id,
      runtime.submit_results.result_type,
      runtime.submit_results.event_id,
      runtime.submit_results.order_id,
      runtime.submit_results.engine_order_id,
      runtime.submit_results.code,
      runtime.submit_results.reason,
      runtime.submit_results.occurred_at,
      runtime.submit_results.cancelled,
      runtime.submit_results.matching_facts
    ) IS DISTINCT FROM ROW(
      EXCLUDED.run_id,
      EXCLUDED.result_type,
      EXCLUDED.event_id,
      EXCLUDED.order_id,
      EXCLUDED.engine_order_id,
      EXCLUDED.code,
      EXCLUDED.reason,
      EXCLUDED.occurred_at,
      EXCLUDED.cancelled,
      EXCLUDED.matching_facts
    )
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
    ON CONFLICT (run_id, order_id) DO UPDATE SET
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
    INSERT INTO runtime.executions(event_id, execution_id, order_id, instrument_id, quantity_units, execution_price, currency, occurred_at, liquidity_role, run_id)
    SELECT
      execution->>'eventId',
      execution->>'executionId',
      execution->>'orderId',
      execution->>'instrumentId',
      execution->>'quantityUnits',
      execution->>'executionPrice',
      execution->>'currency',
      execution->>'occurredAt',
      COALESCE(NULLIF(execution->>'liquidityRole', ''), 'UNSPECIFIED'),
      COALESCE(NULLIF(execution->>'runId', ''), outcome->>'runId', outcome#>>'{acceptedOrder,runId}', '')
    FROM outcomes
    CROSS JOIN LATERAL jsonb_array_elements(
      CASE
        WHEN jsonb_typeof(outcome->'executions') = 'array' THEN outcome->'executions'
        ELSE '[]'::jsonb
      END
    ) AS execution
    ON CONFLICT (event_id) DO UPDATE SET
      event_id = runtime.runtime_reject_execution_replay_conflict(EXCLUDED.event_id)
    WHERE ROW(
      runtime.executions.execution_id,
      runtime.executions.order_id,
      runtime.executions.instrument_id,
      runtime.executions.quantity_units,
      runtime.executions.execution_price,
      runtime.executions.currency,
      runtime.executions.run_id,
      runtime.executions.occurred_at,
      runtime.executions.liquidity_role
    ) IS DISTINCT FROM ROW(
      EXCLUDED.execution_id,
      EXCLUDED.order_id,
      EXCLUDED.instrument_id,
      EXCLUDED.quantity_units,
      EXCLUDED.execution_price,
      EXCLUDED.currency,
      EXCLUDED.run_id,
      EXCLUDED.occurred_at,
      EXCLUDED.liquidity_role
    )
    RETURNING 1
  ),
  insert_trades AS (
    INSERT INTO runtime.trades(event_id, trade_id, execution_id, buy_order_id, sell_order_id, instrument_id, quantity_units, price, currency, occurred_at, run_id)
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
      trade->>'occurredAt',
      COALESCE(NULLIF(trade->>'runId', ''), run_id)
    FROM parsed_trades
    ON CONFLICT (event_id) DO UPDATE SET
      event_id = runtime.runtime_reject_trade_replay_conflict(EXCLUDED.event_id)
    WHERE ROW(
      runtime.trades.trade_id,
      runtime.trades.execution_id,
      runtime.trades.buy_order_id,
      runtime.trades.sell_order_id,
      runtime.trades.instrument_id,
      runtime.trades.quantity_units,
      runtime.trades.price,
      runtime.trades.currency,
      runtime.trades.run_id,
      runtime.trades.occurred_at
    ) IS DISTINCT FROM ROW(
      EXCLUDED.trade_id,
      EXCLUDED.execution_id,
      EXCLUDED.buy_order_id,
      EXCLUDED.sell_order_id,
      EXCLUDED.instrument_id,
      EXCLUDED.quantity_units,
      EXCLUDED.price,
      EXCLUDED.currency,
      EXCLUDED.run_id,
      EXCLUDED.occurred_at
    )
    RETURNING 1
  ),
  dirty_ids AS (
    SELECT DISTINCT run_id, order_id FROM (
      SELECT COALESCE(outcome->>'runId', outcome#>>'{acceptedOrder,runId}', '') AS run_id, outcome->>'orderId' AS order_id FROM outcomes
      UNION ALL
      SELECT COALESCE(NULLIF(trade->>'runId', ''), run_id), trade_order.order_id
      FROM parsed_trades
      CROSS JOIN LATERAL (VALUES (trade->>'buyOrderId'), (trade->>'sellOrderId')) AS trade_order(order_id)
    ) ids
    WHERE COALESCE(order_id, '') <> ''
  ),
  mark_dirty AS (
    INSERT INTO runtime.order_lifecycle_dirty AS dirty(run_id, order_id)
    SELECT run_id, order_id FROM dirty_ids
    ORDER BY run_id, order_id
    ON CONFLICT (run_id, order_id) DO UPDATE SET dirtied_at = dirty.dirtied_at WHERE FALSE
    RETURNING 1
  )
  SELECT COUNT(*) INTO persisted_count FROM outcomes;

  RETURN persisted_count;
END;
$$;

CREATE OR REPLACE FUNCTION runtime.runtime_project_canonical_command_outcome_members(
  p_projection_name TEXT,
  p_selected_members JSONB,
  p_include_fills BOOLEAN DEFAULT TRUE
)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
  selected_count BIGINT;
  projected_count BIGINT := 0;
BEGIN
  IF p_selected_members IS NULL
     OR jsonb_typeof(p_selected_members) <> 'array'
     OR jsonb_array_length(p_selected_members) = 0 THEN
    RETURN 0;
  END IF;

  SELECT COUNT(*) INTO selected_count
  FROM jsonb_array_elements(p_selected_members) AS members(candidate)
  JOIN runtime.canonical_command_outcomes canonical
    ON canonical.partition_id = (candidate->>'partitionId')::INTEGER
   AND canonical.stream_sequence = (candidate->>'streamSequence')::BIGINT
   AND canonical.command_id = candidate->>'commandId'
   AND canonical.batch_id = candidate->>'canonicalBatchId'
   AND canonical.command_type = candidate->>'commandType'
   AND canonical.payload_hash = candidate->>'payloadHash';

  IF selected_count <> jsonb_array_length(p_selected_members) THEN
    RAISE EXCEPTION 'claimed projection batch membership no longer matches canonical facts';
  END IF;

  WITH selected AS MATERIALIZED (
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
      COALESCE(payloads.payload_json, '{}'::jsonb) AS command_payload,
      members.member_order
    FROM jsonb_array_elements(p_selected_members) WITH ORDINALITY AS members(candidate, member_order)
    JOIN runtime.canonical_command_outcomes canonical
      ON canonical.partition_id = (candidate->>'partitionId')::INTEGER
     AND canonical.stream_sequence = (candidate->>'streamSequence')::BIGINT
     AND canonical.command_id = candidate->>'commandId'
     AND canonical.batch_id = candidate->>'canonicalBatchId'
     AND canonical.command_type = candidate->>'commandType'
     AND canonical.payload_hash = candidate->>'payloadHash'
    LEFT JOIN command_log.command_payloads payloads
      ON payloads.command_id = canonical.command_id
  ),
  shaped AS (
    SELECT
      partition_id,
      stream_sequence,
      member_order,
      jsonb_build_object(
        'commandId', command_id, 'runId', COALESCE(NULLIF(order_payload->>'runId', ''), command_payload->>'runId', ''),
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
            'runId', COALESCE(NULLIF(order_payload->>'runId', ''), command_payload->>'runId', ''), 'venueSessionId', COALESCE(order_payload->>'venueSessionId', command_payload->>'venueSessionId', ''), 'clientOrderId', COALESCE(order_payload->>'clientOrderId', ''), 'instrumentId', COALESCE(order_payload->>'instrumentId', ''),
            'participantId', COALESCE(order_payload->>'participantId', ''),
            'accountId', COALESCE(order_payload->>'accountId', ''),
            'side', COALESCE(order_payload->>'side', ''),
            'orderType', COALESCE(order_payload->>'orderType', ''),
            'quantityUnits', COALESCE(order_payload->>'quantityUnits', ''),
            'limitPrice', COALESCE(order_payload->>'limitPrice', ''),
            'currency', COALESCE(order_payload->>'currency', ''),
            'timeInForce', COALESCE(order_payload->>'timeInForce', ''),
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
        'cancelled', result_payload->'cancelled',
        'matchingFacts', jsonb_build_object('executions', COALESCE(result_payload->'executions', '[]'::jsonb), 'trades', COALESCE(result_payload->'trades', '[]'::jsonb)),
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
            'traceId', COALESCE(NULLIF(command_payload->>'traceId', ''), command_id),
            'causationId', COALESCE(NULLIF(command_payload->>'causationId', ''), command_id),
            'correlationId', COALESCE(NULLIF(command_payload->>'correlationId', ''), command_id),
            'actorId', '',
            'producer', 'venue-event-batch-projector',
            'schemaVersion', 'v1',
            'occurredAt', COALESCE(NULLIF(result_payload #>> '{accepted,occurredAt}', ''), NULLIF(result_payload #>> '{rejected,occurredAt}', ''), ''),
            'payloadJson', result_payload) ) || CASE WHEN jsonb_typeof(result_payload->'cancelled') = 'object' THEN jsonb_build_array(jsonb_build_object(
                              'eventId', result_payload #>> '{cancelled,eventId}',
                              'eventType', 'OrderCancelled', 'orderId', result_payload #>> '{cancelled,orderId}',
                              'traceId', COALESCE(NULLIF(command_payload->>'traceId', ''), command_id),
                              'causationId', result_payload #>> '{accepted,eventId}',
                              'correlationId', COALESCE(NULLIF(command_payload->>'correlationId', ''), command_id),
                              'actorId', '', 'producer', 'venue-event-batch-projector', 'schemaVersion', 'v1',
                              'occurredAt', result_payload #>> '{cancelled,occurredAt}',
                              'payloadJson', jsonb_build_object('commandId', command_id, 'runId', COALESCE(NULLIF(order_payload->>'runId', ''), command_payload->>'runId', ''),
                                'cancelledQuantityUnits', result_payload #>> '{cancelled,cancelledQuantityUnits}',
                                'reason', result_payload #>> '{cancelled,reason}')
                            )) ELSE '[]'::jsonb END
      ) AS projected_payload
    FROM selected
  ),
  projected AS (
    SELECT runtime.runtime_persist_submit_outcomes(
      COALESCE(
        jsonb_agg(projected_payload ORDER BY member_order),
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
END;
$$;
