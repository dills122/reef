ALTER TABLE runtime.canonical_venue_event_batches
  DROP CONSTRAINT IF EXISTS canonical_venue_event_batches_pkey;

ALTER TABLE runtime.canonical_venue_event_batches
  ADD CONSTRAINT canonical_venue_event_batches_event_stream_batch_id_key
  UNIQUE (event_stream, batch_id);

ALTER TABLE runtime.canonical_command_outcomes
  DROP CONSTRAINT IF EXISTS canonical_command_outcomes_batch_id_stream_sequence_key;

ALTER TABLE runtime.canonical_command_outcomes
  ADD CONSTRAINT canonical_command_outcomes_event_stream_batch_id_stream_sequence_key
  UNIQUE (event_stream, batch_id, stream_sequence);

DROP INDEX IF EXISTS runtime.idx_canonical_command_outcomes_batch_seq;

CREATE INDEX IF NOT EXISTS idx_canonical_command_outcomes_batch_seq
  ON runtime.canonical_command_outcomes(event_stream, batch_id, stream_sequence);

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

  SELECT payload_checksum
    INTO v_existing_checksum
    FROM runtime.canonical_venue_event_batches
   WHERE event_stream = v_event_stream
     AND batch_id = v_batch_id;

  IF FOUND THEN
    IF v_existing_checksum <> v_payload_checksum THEN
      RAISE EXCEPTION 'venue event batch checksum conflict for eventStream %, batchId %', v_event_stream, v_batch_id;
    END IF;
    RETURN 0;
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
  );

  WITH outcomes AS (
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
  SELECT COUNT(*) INTO inserted_count FROM inserted;

  RETURN inserted_count;
END;
$$;
