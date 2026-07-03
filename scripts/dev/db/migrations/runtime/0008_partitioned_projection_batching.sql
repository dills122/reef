DROP FUNCTION IF EXISTS runtime.runtime_project_canonical_submit_outcomes(TEXT, INTEGER);

CREATE OR REPLACE FUNCTION runtime.runtime_project_canonical_submit_outcomes(
  p_projection_name TEXT,
  p_batch_size INTEGER,
  p_partitions INTEGER[] DEFAULT NULL
)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
  projected_count BIGINT := 0;
BEGIN
  IF p_batch_size IS NULL OR p_batch_size <= 0 THEN
    RETURN 0;
  END IF;

  WITH selected_partitions AS (
    SELECT DISTINCT partition_id
    FROM (
      SELECT unnest(p_partitions) AS partition_id
      WHERE p_partitions IS NOT NULL AND cardinality(p_partitions) > 0
      UNION ALL
      SELECT DISTINCT partition_id
      FROM runtime.canonical_command_results
      WHERE p_partitions IS NULL OR cardinality(p_partitions) = 0
    ) partitions
  ),
  partition_budget AS (
    SELECT GREATEST(
      1,
      CEIL(p_batch_size::NUMERIC / GREATEST((SELECT COUNT(*) FROM selected_partitions), 1))::INTEGER
    ) AS per_partition_limit
  ),
  ranked AS (
    SELECT
      canonical.partition_id,
      canonical.partition_seq,
      canonical.result_payload,
      row_number() OVER (
        PARTITION BY canonical.partition_id
        ORDER BY canonical.partition_seq
      ) AS partition_row
    FROM runtime.canonical_command_results canonical
    JOIN selected_partitions selected
      ON selected.partition_id = canonical.partition_id
    LEFT JOIN runtime.projection_watermarks watermark
      ON watermark.projection_name = p_projection_name
     AND watermark.partition_id = canonical.partition_id
    WHERE canonical.partition_seq > COALESCE(watermark.last_partition_seq, 0)
  ),
  eligible AS (
    SELECT partition_id, partition_seq, result_payload
    FROM ranked
    CROSS JOIN partition_budget
    WHERE partition_row <= partition_budget.per_partition_limit
    ORDER BY partition_row, partition_id, partition_seq
    LIMIT p_batch_size
  ),
  projected AS (
    SELECT runtime.runtime_persist_submit_outcomes(
      COALESCE(
        jsonb_agg(result_payload ORDER BY partition_seq, partition_id),
        '[]'::jsonb
      )
    ) AS count
    FROM eligible
  ),
  partition_max AS (
    SELECT partition_id, MAX(partition_seq) AS last_partition_seq
    FROM eligible
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
