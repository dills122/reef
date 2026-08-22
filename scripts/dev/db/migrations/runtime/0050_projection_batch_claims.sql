-- Guard canonical command-outcome projection batches against duplicate effects
-- after rollback, retry, or an ambiguous commit result.

CREATE TABLE runtime.projection_batch_claims (
  batch_identity TEXT PRIMARY KEY,
  identity_version SMALLINT NOT NULL CHECK (identity_version = 1),
  projection_name TEXT NOT NULL,
  event_stream TEXT NOT NULL,
  projection_stage TEXT NOT NULL CHECK (projection_stage IN ('full', 'command-status', 'timeline')),
  include_fills BOOLEAN NOT NULL,
  candidate_count INTEGER NOT NULL CHECK (candidate_count > 0),
  status TEXT NOT NULL CHECK (status IN ('in-progress', 'completed')),
  result_count BIGINT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  retry_deadline_at TIMESTAMPTZ NOT NULL,
  completed_at TIMESTAMPTZ,
  retain_until TIMESTAMPTZ,
  CHECK (batch_identity ~ '^[0-9a-f]{64}$'),
  CHECK (
    (status = 'in-progress' AND result_count IS NULL AND completed_at IS NULL)
    OR (status = 'completed' AND result_count IS NOT NULL AND completed_at IS NOT NULL)
  )
);

CREATE TABLE runtime.projection_batch_claim_frontiers (
  batch_identity TEXT NOT NULL REFERENCES runtime.projection_batch_claims(batch_identity) ON DELETE CASCADE,
  partition_id INTEGER NOT NULL,
  max_stream_sequence BIGINT NOT NULL,
  PRIMARY KEY (batch_identity, partition_id)
);

CREATE INDEX idx_projection_batch_claims_cleanup
  ON runtime.projection_batch_claims(retry_deadline_at, batch_identity)
  WHERE status = 'completed';

CREATE OR REPLACE FUNCTION runtime.runtime_projection_batch_identity_v1(
  p_projection_name TEXT,
  p_event_stream TEXT,
  p_projection_stage TEXT,
  p_include_fills BOOLEAN,
  p_candidates JSONB
)
RETURNS TEXT
LANGUAGE SQL
IMMUTABLE
STRICT
AS $$
  SELECT encode(
    sha256(
      int4send(1)
      || int4send(octet_length(convert_to(p_projection_name, 'UTF8')))
      || convert_to(p_projection_name, 'UTF8')
      || int4send(octet_length(convert_to(p_event_stream, 'UTF8')))
      || convert_to(p_event_stream, 'UTF8')
      || int4send(octet_length(convert_to(p_projection_stage, 'UTF8')))
      || convert_to(p_projection_stage, 'UTF8')
      || CASE WHEN p_include_fills THEN decode('01', 'hex') ELSE decode('00', 'hex') END
      || int4send(jsonb_array_length(p_candidates))
      || COALESCE(
        (
          SELECT string_agg(
            int4send((candidate->>'partitionId')::INTEGER)
            || int8send((candidate->>'streamSequence')::BIGINT)
            || int4send(octet_length(convert_to(candidate->>'commandId', 'UTF8')))
            || convert_to(candidate->>'commandId', 'UTF8')
            || int4send(octet_length(convert_to(candidate->>'canonicalBatchId', 'UTF8')))
            || convert_to(candidate->>'canonicalBatchId', 'UTF8')
            || int4send(octet_length(convert_to(candidate->>'commandType', 'UTF8')))
            || convert_to(candidate->>'commandType', 'UTF8')
            || int4send(octet_length(convert_to(candidate->>'payloadHash', 'UTF8')))
            || convert_to(candidate->>'payloadHash', 'UTF8'),
            ''::BYTEA ORDER BY ordinality
          )
          FROM jsonb_array_elements(p_candidates) WITH ORDINALITY AS members(candidate, ordinality)
        ),
        ''::BYTEA
      )
    ),
    'hex'
  );
$$;

CREATE OR REPLACE FUNCTION runtime.runtime_claim_projection_batch_v1(
  p_batch_identity TEXT,
  p_projection_name TEXT,
  p_event_stream TEXT,
  p_projection_stage TEXT,
  p_include_fills BOOLEAN,
  p_candidates JSONB,
  p_retry_deadline_at TIMESTAMPTZ
)
RETURNS TABLE(is_new BOOLEAN, stored_result_count BIGINT)
LANGUAGE plpgsql
AS $$
DECLARE
  inserted_identity TEXT;
  existing_claim runtime.projection_batch_claims%ROWTYPE;
  expected_count INTEGER;
BEGIN
  IF p_candidates IS NULL OR jsonb_typeof(p_candidates) <> 'array' THEN
    RAISE EXCEPTION 'projection batch candidates must be a JSON array';
  END IF;

  expected_count := jsonb_array_length(p_candidates);
  IF expected_count <= 0 THEN
    RAISE EXCEPTION 'projection batch claim requires at least one candidate';
  END IF;

  IF p_retry_deadline_at <= clock_timestamp() THEN
    RAISE EXCEPTION 'projection batch retry deadline has expired';
  END IF;

  IF p_batch_identity IS DISTINCT FROM runtime.runtime_projection_batch_identity_v1(
    p_projection_name,
    p_event_stream,
    p_projection_stage,
    p_include_fills,
    p_candidates
  ) THEN
    RAISE EXCEPTION 'projection batch identity does not match canonical membership encoding';
  END IF;

  INSERT INTO runtime.projection_batch_claims(
    batch_identity,
    identity_version,
    projection_name,
    event_stream,
    projection_stage,
    include_fills,
    candidate_count,
    status,
    retry_deadline_at
  )
  VALUES (
    p_batch_identity,
    1,
    p_projection_name,
    p_event_stream,
    p_projection_stage,
    p_include_fills,
    expected_count,
    'in-progress',
    p_retry_deadline_at
  )
  ON CONFLICT (batch_identity) DO NOTHING
  RETURNING batch_identity INTO inserted_identity;

  IF inserted_identity IS NOT NULL THEN
    INSERT INTO runtime.projection_batch_claim_frontiers(batch_identity, partition_id, max_stream_sequence)
    SELECT
      p_batch_identity,
      (candidate->>'partitionId')::INTEGER,
      MAX((candidate->>'streamSequence')::BIGINT)
    FROM jsonb_array_elements(p_candidates) AS members(candidate)
    GROUP BY (candidate->>'partitionId')::INTEGER;

    RETURN QUERY SELECT TRUE, NULL::BIGINT;
    RETURN;
  END IF;

  SELECT * INTO existing_claim
  FROM runtime.projection_batch_claims
  WHERE batch_identity = p_batch_identity;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'projection batch claim disappeared after identity conflict';
  END IF;

  IF existing_claim.identity_version <> 1
     OR existing_claim.projection_name IS DISTINCT FROM p_projection_name
     OR existing_claim.event_stream IS DISTINCT FROM p_event_stream
     OR existing_claim.projection_stage IS DISTINCT FROM p_projection_stage
     OR existing_claim.include_fills IS DISTINCT FROM p_include_fills
     OR existing_claim.candidate_count IS DISTINCT FROM expected_count THEN
    RAISE EXCEPTION 'projection batch claim conflicts with immutable configuration';
  END IF;

  IF EXISTS (
    WITH expected_frontiers AS (
      SELECT
        (candidate->>'partitionId')::INTEGER AS partition_id,
        MAX((candidate->>'streamSequence')::BIGINT) AS max_stream_sequence
      FROM jsonb_array_elements(p_candidates) AS members(candidate)
      GROUP BY (candidate->>'partitionId')::INTEGER
    )
    SELECT 1
    FROM expected_frontiers expected
    FULL JOIN (
      SELECT partition_id, max_stream_sequence
      FROM runtime.projection_batch_claim_frontiers
      WHERE batch_identity = p_batch_identity
    ) stored
      ON stored.partition_id = expected.partition_id
    WHERE expected.partition_id IS NULL
       OR stored.partition_id IS NULL
       OR stored.max_stream_sequence IS DISTINCT FROM expected.max_stream_sequence
  ) THEN
    RAISE EXCEPTION 'projection batch claim conflicts with immutable member frontiers';
  END IF;

  IF existing_claim.status <> 'completed' OR existing_claim.result_count IS NULL THEN
    RAISE EXCEPTION 'projection batch claim is incomplete';
  END IF;

  RETURN QUERY SELECT FALSE, existing_claim.result_count;
END;
$$;

CREATE OR REPLACE FUNCTION runtime.runtime_complete_projection_batch_v1(
  p_batch_identity TEXT,
  p_result_count BIGINT
)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
  completed_count BIGINT;
BEGIN
  UPDATE runtime.projection_batch_claims
  SET
    status = 'completed',
    result_count = p_result_count,
    completed_at = clock_timestamp()
  WHERE batch_identity = p_batch_identity
    AND status = 'in-progress'
  RETURNING result_count INTO completed_count;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'projection batch claim cannot be completed from its current state';
  END IF;

  RETURN completed_count;
END;
$$;

CREATE OR REPLACE FUNCTION runtime.runtime_cleanup_projection_batch_claims(
  p_limit INTEGER DEFAULT 1000
)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
  deleted_count BIGINT := 0;
BEGIN
  WITH eligible AS (
    SELECT claim.batch_identity
    FROM runtime.projection_batch_claims claim
    WHERE claim.status = 'completed'
      AND claim.retry_deadline_at < clock_timestamp()
      AND (claim.retain_until IS NULL OR claim.retain_until < clock_timestamp())
      AND NOT EXISTS (
        SELECT 1
        FROM runtime.projection_batch_claim_frontiers frontier
        LEFT JOIN runtime.projection_watermarks watermark
          ON watermark.projection_name = claim.projection_name
         AND watermark.partition_id = frontier.partition_id
        WHERE frontier.batch_identity = claim.batch_identity
          AND (
            watermark.partition_id IS NULL
            OR watermark.last_partition_seq <= frontier.max_stream_sequence
          )
      )
    ORDER BY claim.retry_deadline_at, claim.batch_identity
    LIMIT GREATEST(COALESCE(p_limit, 0), 0)
  ),
  deleted AS (
    DELETE FROM runtime.projection_batch_claims claim
    USING eligible
    WHERE claim.batch_identity = eligible.batch_identity
    RETURNING 1
  )
  SELECT COUNT(*) INTO deleted_count FROM deleted;

  RETURN deleted_count;
END;
$$;

ALTER FUNCTION runtime.runtime_project_canonical_command_outcomes(TEXT, INTEGER, INTEGER[], BOOLEAN, TEXT)
  RENAME TO runtime_project_canonical_command_outcomes_unclaimed;

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
  effective_batch_size INTEGER;
  selected_members JSONB;
  batch_identity TEXT;
  claim_is_new BOOLEAN;
  stored_result_count BIGINT;
  projected_count BIGINT;
BEGIN
  IF p_batch_size IS NULL OR p_batch_size <= 0 THEN
    RETURN 0;
  END IF;

  PERFORM runtime.runtime_cleanup_projection_batch_claims(1000);
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
      canonical.batch_id,
      canonical.command_type,
      canonical.payload_hash,
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
  )
  SELECT jsonb_agg(
    jsonb_build_object(
      'partitionId', partition_id,
      'streamSequence', stream_sequence,
      'commandId', command_id,
      'canonicalBatchId', batch_id,
      'commandType', command_type,
      'payloadHash', payload_hash
    )
    ORDER BY stream_sequence, partition_id
  ) INTO selected_members
  FROM eligible;

  IF selected_members IS NULL OR jsonb_array_length(selected_members) = 0 THEN
    RETURN 0;
  END IF;

  batch_identity := runtime.runtime_projection_batch_identity_v1(
    p_projection_name,
    COALESCE(p_event_stream, ''),
    'full',
    p_include_fills,
    selected_members
  );

  SELECT claim.is_new, claim.stored_result_count
  INTO claim_is_new, stored_result_count
  FROM runtime.runtime_claim_projection_batch_v1(
    batch_identity,
    p_projection_name,
    COALESCE(p_event_stream, ''),
    'full',
    p_include_fills,
    selected_members,
    clock_timestamp() + INTERVAL '60 seconds'
  ) claim;

  IF NOT claim_is_new THEN
    RETURN 0;
  END IF;

  projected_count := runtime.runtime_project_canonical_command_outcomes_unclaimed(
    p_projection_name,
    p_batch_size,
    p_partitions,
    p_include_fills,
    p_event_stream
  );

  IF projected_count <> jsonb_array_length(selected_members) THEN
    RAISE EXCEPTION 'claimed projection batch membership changed before effects';
  END IF;

  PERFORM runtime.runtime_complete_projection_batch_v1(batch_identity, projected_count);
  RETURN projected_count;
END;
$$;
