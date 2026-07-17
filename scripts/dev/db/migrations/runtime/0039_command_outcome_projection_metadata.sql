-- Preserve command correlation metadata when projecting canonical command outcomes
-- into runtime lifecycle events.
DROP FUNCTION IF EXISTS runtime.runtime_project_canonical_command_outcomes(TEXT, INTEGER, INTEGER[], BOOLEAN);

CREATE OR REPLACE FUNCTION runtime.runtime_project_canonical_command_outcomes(
  p_projection_name TEXT,
  p_batch_size INTEGER,
  p_partitions INTEGER[] DEFAULT NULL,
  p_include_fills BOOLEAN DEFAULT TRUE,
  p_event_stream TEXT DEFAULT NULL
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
      FROM runtime.canonical_command_outcomes canonical_partitions
      WHERE (p_partitions IS NULL OR cardinality(p_partitions) = 0)
        AND (p_event_stream IS NULL OR canonical_partitions.event_stream = p_event_stream)
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
      COALESCE(payloads.payload_json, '{}'::jsonb) AS command_payload,
      COALESCE(watermark.last_partition_seq, 0) AS previous_partition_seq,
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
      AND (p_event_stream IS NULL OR canonical.event_stream = p_event_stream)
  ),
  eligible AS (
    SELECT *
    FROM ranked
    CROSS JOIN partition_budget
    WHERE partition_row <= partition_budget.per_partition_limit
      AND (
        NOT (
          partition_id = 0
          OR (stream_sequence / 281474976710656)::INTEGER = partition_id
        )
        OR stream_sequence = CASE
          WHEN previous_partition_seq > 0 THEN previous_partition_seq + partition_row
          ELSE (partition_id::BIGINT * 281474976710656) + partition_row
        END
      )
    ORDER BY partition_row, partition_id, stream_sequence
    LIMIT effective_batch_size
  ),
  shaped AS (
    SELECT
      partition_id,
      stream_sequence,
      jsonb_build_object(
        'commandId', command_id,
        'streamSequence', stream_sequence::TEXT,
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
            'traceId', COALESCE(NULLIF(command_payload->>'traceId', ''), command_id),
            'causationId', COALESCE(NULLIF(command_payload->>'causationId', ''), command_id),
            'correlationId', COALESCE(NULLIF(command_payload->>'correlationId', ''), command_id),
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
