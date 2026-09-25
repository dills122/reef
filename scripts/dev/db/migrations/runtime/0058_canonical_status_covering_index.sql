-- Retain the existing partition/sequence access path, covering its command-type
-- predicate so repeated status reads do not fetch every historical payload row.
-- Canonical facts are immutable; this replaces one index rather than adding one.
DROP INDEX IF EXISTS runtime.idx_canonical_command_outcomes_partition_seq;
CREATE INDEX idx_canonical_command_outcomes_partition_seq
  ON runtime.canonical_command_outcomes(partition_id, stream_sequence)
  INCLUDE (command_type);
