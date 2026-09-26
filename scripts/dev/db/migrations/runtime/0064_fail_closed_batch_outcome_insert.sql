-- A newly inserted batch header cannot have committed outcomes: both writes
-- share one transaction. Reject any omitted outcome insert, whether caused by
-- an intra-batch duplicate, an existing command, or a concurrent claim.
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

  v_outcome_count := jsonb_array_length(
    CASE
      WHEN jsonb_typeof(p_batch->'outcomes') = 'array' THEN p_batch->'outcomes'
      ELSE '[]'::jsonb
    END
  );

  IF v_outcome_count <> COALESCE((p_batch->>'commandCount')::BIGINT, 0) THEN
    RAISE EXCEPTION 'venue event batch command count mismatch for eventStream %, batchId %', v_event_stream, v_batch_id;
  END IF;

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
  FROM jsonb_array_elements(
    CASE
      WHEN jsonb_typeof(p_batch->'outcomes') = 'array' THEN p_batch->'outcomes'
      ELSE '[]'::jsonb
    END
  ) AS outcome
  ON CONFLICT (command_id) DO NOTHING;

  GET DIAGNOSTICS inserted_count = ROW_COUNT;
  IF inserted_count <> v_outcome_count THEN
    RAISE EXCEPTION 'canonical command outcome conflict for eventStream %, batchId %', v_event_stream, v_batch_id;
  END IF;

  RETURN inserted_count;
END;
$$;
