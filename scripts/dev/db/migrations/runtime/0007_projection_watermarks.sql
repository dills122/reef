CREATE TABLE IF NOT EXISTS runtime.projection_watermarks (
  projection_name TEXT NOT NULL,
  partition_id INTEGER NOT NULL,
  last_partition_seq BIGINT NOT NULL DEFAULT 0,
  last_projected_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_error TEXT NOT NULL DEFAULT '',
  PRIMARY KEY (projection_name, partition_id)
);

CREATE OR REPLACE FUNCTION runtime.runtime_project_canonical_submit_outcomes(
  p_projection_name TEXT,
  p_batch_size INTEGER
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

  WITH eligible AS (
    SELECT
      canonical.partition_id,
      canonical.partition_seq,
      canonical.result_payload
    FROM runtime.canonical_command_results canonical
    LEFT JOIN runtime.projection_watermarks watermark
      ON watermark.projection_name = p_projection_name
     AND watermark.partition_id = canonical.partition_id
    WHERE canonical.partition_seq > COALESCE(watermark.last_partition_seq, 0)
    ORDER BY canonical.partition_id, canonical.partition_seq
    LIMIT p_batch_size
  ),
  projected AS (
    SELECT runtime.runtime_persist_submit_outcomes(
      COALESCE(
        jsonb_agg(result_payload ORDER BY partition_id, partition_seq),
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
