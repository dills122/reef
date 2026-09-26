-- A normal migration runs in one transaction. Keep its lock and work bounded
-- on aged databases; build this exact index CONCURRENTLY during an online
-- rollout before applying migrations if the table cannot finish in five seconds.
SET LOCAL lock_timeout = '2s';
-- Preflight is read-only and may scan aged history. The later index build is
-- separately capped at five seconds to avoid holding an aged writer lock.
SET LOCAL statement_timeout = '120s';

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM (
      SELECT partition_id, stream_sequence FROM runtime.canonical_command_outcomes
      UNION ALL
      SELECT partition_id, stream_sequence FROM runtime.canonical_command_outcomes_archive
    ) canonical_history
    GROUP BY partition_id, stream_sequence
    HAVING COUNT(*) > 1
    LIMIT 1
  ) THEN
    RAISE EXCEPTION 'duplicate canonical partition sequence in live or archived facts; preserve evidence and repair before indexing';
  END IF;
  IF EXISTS (
    SELECT 1 FROM (
      SELECT partition_id, first_sequence, last_sequence, batch_id FROM runtime.canonical_venue_event_batches
      UNION ALL
      SELECT partition_id, first_sequence, last_sequence, batch_id FROM runtime.canonical_venue_event_batches_archive
    ) batches
    WHERE first_sequence > last_sequence
    LIMIT 1
  ) THEN
    RAISE EXCEPTION 'invalid canonical batch sequence range; preserve evidence and repair before indexing';
  END IF;
  IF EXISTS (
    SELECT 1 FROM (
      SELECT partition_id, first_sequence,
        MAX(last_sequence) OVER (
          PARTITION BY partition_id ORDER BY first_sequence, last_sequence, batch_id
          ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
        ) AS prior_last_sequence
      FROM (
        SELECT partition_id, first_sequence, last_sequence, batch_id FROM runtime.canonical_venue_event_batches
        UNION ALL
        SELECT partition_id, first_sequence, last_sequence, batch_id FROM runtime.canonical_venue_event_batches_archive
      ) batches
    ) ordered_batches
    WHERE first_sequence <= prior_last_sequence
    LIMIT 1
  ) THEN
    RAISE EXCEPTION 'overlapping canonical batch ranges; preserve evidence and repair before indexing';
  END IF;
END;
$$;

-- Projection watermarks are partition-scoped even when event_stream is empty.
-- Enforce the same identity across streams, not merely within one batch.
SET LOCAL statement_timeout = '5s';
CREATE UNIQUE INDEX IF NOT EXISTS idx_canonical_command_outcomes_partition_sequence_unique
  ON runtime.canonical_command_outcomes(partition_id, stream_sequence);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_index index_metadata
    WHERE index_metadata.indexrelid = to_regclass('runtime.idx_canonical_command_outcomes_partition_sequence_unique')
      AND index_metadata.indrelid = 'runtime.canonical_command_outcomes'::regclass
      AND index_metadata.indisunique AND index_metadata.indisvalid AND index_metadata.indisready
      AND index_metadata.indpred IS NULL AND index_metadata.indexprs IS NULL
      AND index_metadata.indnkeyatts = 2 AND index_metadata.indnatts = 2
      AND index_metadata.indkey[0] = (
        SELECT attnum FROM pg_attribute
        WHERE attrelid = index_metadata.indrelid AND attname = 'partition_id'
      )
      AND index_metadata.indkey[1] = (
        SELECT attnum FROM pg_attribute
        WHERE attrelid = index_metadata.indrelid AND attname = 'stream_sequence'
      )
  ) THEN
    RAISE EXCEPTION 'canonical partition sequence unique index missing, invalid, partial, or structurally wrong';
  END IF;
END;
$$;
