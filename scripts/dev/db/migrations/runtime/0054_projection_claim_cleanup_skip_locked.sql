-- Concurrent projectors must not queue behind another transaction cleaning the
-- same expired claims. Claim eligible rows without waiting; a later cleanup can
-- revisit skipped rows. Preserve completion, retry/retention deadlines, and the
-- strictly advanced frontier requirement that protects retry authority.

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
      AND claim.retain_until < clock_timestamp()
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
    FOR UPDATE OF claim SKIP LOCKED
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
