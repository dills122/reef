\set ON_ERROR_STOP on
INSERT INTO postmatch.consumer_outcome_receipts (
  consumer_name, event_stream, partition_id, source_generation, stream_sequence,
  batch_id, command_id, command_payload_hash, result_digest, effect_count
)
SELECT 'live-v1', 'PM10_CHECKER_20260928', p,
  '00000000-0000-4000-8000-000000000010', (p::bigint << 48) + local_seq,
  'batch-pm10-' || p::text || '-' || ((local_seq - 1) / 500)::text,
  'cmd-pm10-' || lpad(n::text, 12, '0') || repeat('c', 24),
  repeat('a', 64), repeat('b', 64), 1
FROM (
  SELECT n, ((n - 1) % 16)::integer AS p, ((n - 1) / 16 + 1)::bigint AS local_seq
  FROM generate_series(1, 512000) AS g(n)
) AS source;

INSERT INTO postmatch.consumer_frontiers (
  consumer_name, event_stream, partition_id, source_generation, last_stream_sequence
)
SELECT consumer_name, 'PM10_CHECKER_20260928', p,
  '00000000-0000-4000-8000-000000000010', (p::bigint << 48) + 32000
FROM generate_series(0, 15) AS p
CROSS JOIN (VALUES ('live-v1'), ('live-market-v1')) AS names(consumer_name);

ANALYZE postmatch.consumer_outcome_receipts;
SELECT count(*) AS live_receipts FROM postmatch.consumer_outcome_receipts
WHERE event_stream = 'PM10_CHECKER_20260928';
