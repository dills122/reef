-- Make canonical batch ingestion insert-first on the normal path, reject a
-- second batch claiming an existing command, and remove an index duplicated by
-- the event_stream/batch_id/stream_sequence unique constraint.

DROP INDEX IF EXISTS runtime.idx_canonical_command_outcomes_batch_seq;

CREATE OR REPLACE FUNCTION runtime.runtime_materialize_venue_event_batch(
  p_batch JSONB
)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
  v_batch_id TEXT;
  v_event_stream TEXT;
  v_payload_checksum TEXT;
  v_existing_checksum TEXT;
  v_header_inserted BOOLEAN := FALSE;
  v_outcome_count BIGINT := 0;
  inserted_count BIGINT := 0;
BEGIN
  IF p_batch IS NULL OR jsonb_typeof(p_batch) <> 'object' THEN
    RAISE EXCEPTION 'venue event batch payload must be a JSON object';
  END IF;

  v_batch_id := COALESCE(p_batch->>'batchId', '');
  v_event_stream := COALESCE(p_batch->>'eventStream', '');
  v_payload_checksum := COALESCE(p_batch->>'payloadChecksum', '');

  IF v_batch_id = '' THEN
    RAISE EXCEPTION 'venue event batch payload missing batchId';
  END IF;

  INSERT INTO runtime.canonical_venue_event_batches(
    batch_id,
    shard_id,
    partition_id,
    command_stream,
    event_stream,
    first_sequence,
    last_sequence,
    command_count,
    payload_checksum,
    payload_format,
    payload_version,
    payload_json,
    created_at
  )
  VALUES (
    v_batch_id,
    COALESCE(p_batch->>'shardId', ''),
    COALESCE((p_batch->>'partition')::INTEGER, -1),
    COALESCE(p_batch->>'commandStream', ''),
    v_event_stream,
    COALESCE((p_batch->>'firstSequence')::BIGINT, 0),
    COALESCE((p_batch->>'lastSequence')::BIGINT, 0),
    COALESCE((p_batch->>'commandCount')::INTEGER, 0),
    v_payload_checksum,
    COALESCE(NULLIF(p_batch->>'payloadFormat', ''), 'venue-event-batch-json'),
    COALESCE(NULLIF(p_batch->>'payloadVersion', ''), 'v1'),
    p_batch,
    COALESCE(p_batch->>'createdAt', '')
  )
  ON CONFLICT (event_stream, batch_id) DO NOTHING
  RETURNING TRUE INTO v_header_inserted;

  IF NOT COALESCE(v_header_inserted, FALSE) THEN
    SELECT payload_checksum
      INTO v_existing_checksum
      FROM runtime.canonical_venue_event_batches
     WHERE event_stream = v_event_stream
       AND batch_id = v_batch_id;

    IF NOT FOUND THEN
      RAISE EXCEPTION 'venue event batch identity conflict for eventStream %, batchId %', v_event_stream, v_batch_id;
    END IF;
    IF v_existing_checksum <> v_payload_checksum THEN
      RAISE EXCEPTION 'venue event batch checksum conflict for eventStream %, batchId %', v_event_stream, v_batch_id;
    END IF;
    RETURN 0;
  END IF;

  WITH outcomes AS MATERIALIZED (
    SELECT outcome
    FROM jsonb_array_elements(
      CASE
        WHEN jsonb_typeof(p_batch->'outcomes') = 'array' THEN p_batch->'outcomes'
        ELSE '[]'::jsonb
      END
    ) AS outcome
  ),
  inserted AS (
    INSERT INTO runtime.canonical_command_outcomes(
      command_id,
      batch_id,
      shard_id,
      partition_id,
      command_stream,
      event_stream,
      stream_sequence,
      delivered_count,
      command_type,
      payload_hash,
      instrument_id,
      order_id,
      result_status,
      reject_code,
      result_payload
    )
    SELECT
      outcome->>'commandId',
      v_batch_id,
      COALESCE(p_batch->>'shardId', ''),
      COALESCE((p_batch->>'partition')::INTEGER, -1),
      COALESCE(p_batch->>'commandStream', ''),
      v_event_stream,
      COALESCE((outcome->>'streamSequence')::BIGINT, 0),
      COALESCE((outcome->>'deliveredCount')::BIGINT, 0),
      COALESCE(outcome->>'commandType', ''),
      COALESCE(outcome->>'payloadHash', ''),
      COALESCE(outcome->>'instrumentId', ''),
      COALESCE(outcome->>'orderId', ''),
      COALESCE(outcome->>'status', ''),
      COALESCE(outcome->>'rejectCode', outcome#>>'{result,rejected,code}', ''),
      COALESCE(outcome->'result', '{}'::jsonb)
    FROM outcomes
    ON CONFLICT (command_id) DO NOTHING
    RETURNING 1
  )
  SELECT
    (SELECT COUNT(*) FROM outcomes),
    (SELECT COUNT(*) FROM inserted)
    INTO v_outcome_count, inserted_count;

  IF v_outcome_count <> COALESCE((p_batch->>'commandCount')::BIGINT, 0) THEN
    RAISE EXCEPTION 'venue event batch command count mismatch for eventStream %, batchId %', v_event_stream, v_batch_id;
  END IF;

  IF v_outcome_count <> (
    SELECT COUNT(DISTINCT outcome->>'commandId')
    FROM jsonb_array_elements(p_batch->'outcomes') AS source(outcome)
  ) THEN
    RAISE EXCEPTION 'duplicate commandId in venue event batch for eventStream %, batchId %', v_event_stream, v_batch_id;
  END IF;

  IF inserted_count <> v_outcome_count AND EXISTS (
    SELECT 1
    FROM jsonb_array_elements(p_batch->'outcomes') AS source(outcome)
    JOIN runtime.canonical_command_outcomes existing
      ON existing.command_id = source.outcome->>'commandId'
    WHERE existing.batch_id IS DISTINCT FROM v_batch_id
       OR existing.shard_id IS DISTINCT FROM COALESCE(p_batch->>'shardId', '')
       OR existing.partition_id IS DISTINCT FROM COALESCE((p_batch->>'partition')::INTEGER, -1)
       OR existing.command_stream IS DISTINCT FROM COALESCE(p_batch->>'commandStream', '')
       OR existing.event_stream IS DISTINCT FROM v_event_stream
       OR existing.stream_sequence IS DISTINCT FROM COALESCE((source.outcome->>'streamSequence')::BIGINT, 0)
       OR existing.command_type IS DISTINCT FROM COALESCE(source.outcome->>'commandType', '')
       OR existing.payload_hash IS DISTINCT FROM COALESCE(source.outcome->>'payloadHash', '')
       OR existing.instrument_id IS DISTINCT FROM COALESCE(source.outcome->>'instrumentId', '')
       OR existing.order_id IS DISTINCT FROM COALESCE(source.outcome->>'orderId', '')
       OR existing.result_status IS DISTINCT FROM COALESCE(source.outcome->>'status', '')
       OR existing.reject_code IS DISTINCT FROM COALESCE(source.outcome->>'rejectCode', source.outcome#>>'{result,rejected,code}', '')
       OR existing.result_payload IS DISTINCT FROM COALESCE(source.outcome->'result', '{}'::jsonb)
  ) THEN
    RAISE EXCEPTION 'canonical command outcome conflict for eventStream %, batchId %', v_event_stream, v_batch_id;
  END IF;

  RETURN inserted_count;
END;
$$;
