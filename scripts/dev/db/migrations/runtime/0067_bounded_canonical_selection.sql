-- 0060 ranked and prefix-checked the entire post-watermark backlog per
-- partition before slicing to the per-partition budget: a Seq Scan plus an
-- external-merge sort plus two full-backlog WindowAggs, regardless of how
-- small the budget was. Measured locally against a 500,000-row synthetic
-- backlog (16 partitions, empty watermarks): 839ms per selection call versus
-- 1.05ms below, and the cost grows with backlog size, not with budget size.
--
-- This bounds the per-partition read with a LATERAL join before ranking, the
-- same as `.planning/sustained-10k/bounded-canonical-selection.candidate.sql`,
-- but keeps every prefix-validity branch from 0060 verbatim (including the
-- pre-partition-encoding compatibility branches for aged data) plus the
-- adjacent-duplicate tie guard, evaluated over the bounded fetch instead of
-- the full backlog. `0061`/`0062` made (partition_id, stream_sequence)
-- globally unique, so the tie guard is now defense in depth rather than a
-- load-bearing check, but nothing here relies on that assumption.
CREATE OR REPLACE FUNCTION runtime.runtime_project_canonical_command_outcomes(
  p_projection_name TEXT,
  p_batch_size INTEGER,
  p_partitions INTEGER[],
  p_include_fills BOOLEAN,
  p_event_stream TEXT,
  p_retry_horizon_ms BIGINT
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
  retry_deadline_at TIMESTAMPTZ;
BEGIN
  IF p_batch_size IS NULL OR p_batch_size <= 0 THEN
    RETURN 0;
  END IF;

  IF p_retry_horizon_ms IS NULL OR p_retry_horizon_ms <= 0 THEN
    RAISE EXCEPTION 'projection batch retry horizon must be positive';
  END IF;

  retry_deadline_at := clock_timestamp() + (p_retry_horizon_ms * INTERVAL '1 millisecond');

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
  bounded_candidates AS (
    SELECT
      selected.partition_id,
      canonical.stream_sequence,
      canonical.command_id,
      canonical.batch_id,
      canonical.command_type,
      canonical.payload_hash,
      COALESCE(watermark.last_partition_seq, 0) AS previous_partition_seq
    FROM selected_partitions selected
    CROSS JOIN partition_budget budget
    LEFT JOIN runtime.projection_watermarks watermark
      ON watermark.projection_name = p_projection_name
     AND watermark.partition_id = selected.partition_id
    CROSS JOIN LATERAL (
      SELECT
        canonical.stream_sequence,
        canonical.command_id,
        canonical.batch_id,
        canonical.command_type,
        canonical.payload_hash
      FROM runtime.canonical_command_outcomes canonical
      WHERE canonical.partition_id = selected.partition_id
        AND canonical.command_type IN ('SubmitOrder', 'ModifyOrder', 'CancelOrder')
        AND canonical.stream_sequence > COALESCE(watermark.last_partition_seq, 0)
        AND (p_event_stream IS NULL OR canonical.event_stream = p_event_stream)
      ORDER BY canonical.stream_sequence
      -- one row past the budget so the last in-budget row can still see
      -- whether an adjacent duplicate sequence follows it (tie guard below).
      LIMIT budget.per_partition_limit + 1
    ) canonical
  ),
  ranked AS (
    SELECT
      bounded.partition_id,
      bounded.stream_sequence,
      bounded.command_id,
      bounded.batch_id,
      bounded.command_type,
      bounded.payload_hash,
      bounded.previous_partition_seq,
      row_number() OVER (
        PARTITION BY bounded.partition_id
        ORDER BY bounded.stream_sequence, bounded.command_id
      ) AS partition_row,
      first_value(bounded.stream_sequence) OVER (
        PARTITION BY bounded.partition_id
        ORDER BY bounded.stream_sequence, bounded.command_id
      ) AS first_partition_sequence,
      lead(bounded.stream_sequence) OVER (
        PARTITION BY bounded.partition_id
        ORDER BY bounded.stream_sequence, bounded.command_id
      ) AS next_stream_sequence
    FROM bounded_candidates bounded
  ),
  checked AS (
    SELECT ranked.*,
      bool_and(
        next_stream_sequence IS DISTINCT FROM stream_sequence AND
        CASE
          WHEN partition_id = 0 THEN
            stream_sequence = previous_partition_seq + partition_row
          WHEN previous_partition_seq > 0 AND previous_partition_seq / 281474976710656 = partition_id THEN
            stream_sequence / 281474976710656 = partition_id
            AND stream_sequence = previous_partition_seq + partition_row
          WHEN previous_partition_seq > 0 AND previous_partition_seq < 281474976710656 THEN
            stream_sequence < 281474976710656
          WHEN previous_partition_seq = 0 AND first_partition_sequence / 281474976710656 = partition_id THEN
            stream_sequence / 281474976710656 = partition_id
            AND stream_sequence = (partition_id::BIGINT * 281474976710656) + partition_row
          WHEN previous_partition_seq = 0 AND first_partition_sequence < 281474976710656 THEN
            stream_sequence < 281474976710656
          ELSE FALSE
        END
      ) OVER (
        PARTITION BY partition_id
        ORDER BY partition_row ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
      ) AS prefix_valid
    FROM ranked
  ),
  eligible AS (
    SELECT checked.*
    FROM checked
    CROSS JOIN partition_budget
    WHERE partition_row <= partition_budget.per_partition_limit
      AND prefix_valid
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
    ORDER BY partition_row, partition_id, stream_sequence
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
    retry_deadline_at,
    p_retry_horizon_ms
  ) claim;

  IF NOT claim_is_new THEN
    RETURN 0;
  END IF;

  projected_count := runtime.runtime_project_canonical_command_outcome_members(
    p_projection_name,
    selected_members,
    p_include_fills
  );

  IF projected_count <> jsonb_array_length(selected_members) THEN
    RAISE EXCEPTION 'claimed projection batch membership changed before effects';
  END IF;

  PERFORM runtime.runtime_complete_projection_batch_v1(batch_identity, projected_count);
  RETURN projected_count;
END;
$$;
