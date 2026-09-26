-- Keep the command-type covering read path from 0058 and the partition-sequence
-- uniqueness from 0061 in one index. An aged table may need this exact index
-- built CONCURRENTLY as ..._next before the transaction-wrapped migration runs.
SET LOCAL lock_timeout = '2s';
SET LOCAL statement_timeout = '5s';

CREATE UNIQUE INDEX IF NOT EXISTS idx_canonical_command_outcomes_partition_seq_next
  ON runtime.canonical_command_outcomes(partition_id, stream_sequence)
  INCLUDE (command_type);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_index index_metadata
    WHERE index_metadata.indexrelid = to_regclass('runtime.idx_canonical_command_outcomes_partition_seq_next')
      AND index_metadata.indrelid = 'runtime.canonical_command_outcomes'::regclass
      AND index_metadata.indisunique AND index_metadata.indisvalid AND index_metadata.indisready
      AND index_metadata.indpred IS NULL AND index_metadata.indexprs IS NULL
      AND index_metadata.indnkeyatts = 2 AND index_metadata.indnatts = 3
      AND index_metadata.indkey[0] = (
        SELECT attnum FROM pg_attribute
        WHERE attrelid = index_metadata.indrelid AND attname = 'partition_id'
      )
      AND index_metadata.indkey[1] = (
        SELECT attnum FROM pg_attribute
        WHERE attrelid = index_metadata.indrelid AND attname = 'stream_sequence'
      )
      AND index_metadata.indkey[2] = (
        SELECT attnum FROM pg_attribute
        WHERE attrelid = index_metadata.indrelid AND attname = 'command_type'
      )
  ) THEN
    RAISE EXCEPTION 'canonical partition sequence unique covering index missing, invalid, partial, or structurally wrong';
  END IF;
END;
$$;

-- The migration runner wraps these swaps in one transaction. Existing reads
-- keep their index name; the plain unique index protects writes until commit.
DROP INDEX IF EXISTS runtime.idx_canonical_command_outcomes_partition_seq;
ALTER INDEX runtime.idx_canonical_command_outcomes_partition_seq_next
  RENAME TO idx_canonical_command_outcomes_partition_seq;
DROP INDEX IF EXISTS runtime.idx_canonical_command_outcomes_partition_sequence_unique;
