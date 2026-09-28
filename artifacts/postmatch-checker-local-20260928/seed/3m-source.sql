\set ON_ERROR_STOP on
UPDATE runtime.postmatch_source_generation
SET generation = '00000000-0000-4000-8000-000000000010'::uuid
WHERE singleton = TRUE;

CREATE TEMP TABLE pm10_pad AS
SELECT string_agg(md5(i::text), '' ORDER BY i) AS value
FROM generate_series(1, 32) AS i;

INSERT INTO runtime.canonical_command_outcomes (
  command_id, batch_id, shard_id, partition_id, command_stream, event_stream,
  stream_sequence, delivered_count, command_type, payload_hash, instrument_id,
  order_id, result_status, reject_code, result_payload
)
SELECT
  'cmd-pm10-' || lpad(n::text, 12, '0') || repeat('c', 24),
  'batch-pm10-' || p::text || '-' || ((local_seq - 1) / 500)::text,
  'shard-' || p::text, p, 'PM10_COMMANDS_20260928', 'PM10_CHECKER_20260928',
  (p::bigint << 48) + local_seq, 1, 'submit', repeat('a', 64),
  'STK' || lpad((p % 64)::text, 3, '0'), 'order-pm10-' || n::text,
  'accepted', '',
  jsonb_build_object(
    'trades', CASE WHEN n % 1000 < 440
      THEN jsonb_build_array(jsonb_build_object('tradeId',
        'trade-pm10-' || lpad(n::text, 12, '0') || repeat('t', 48)))
      ELSE '[]'::jsonb END,
    'padding', pm10_pad.value)
FROM (
  SELECT n, ((n - 1) % 16)::integer AS p, ((n - 1) / 16 + 1)::bigint AS local_seq
  FROM generate_series(1, 3000000) AS g(n)
) AS source
CROSS JOIN pm10_pad;

ANALYZE runtime.canonical_command_outcomes;
SELECT count(*) AS outcomes, count(*) FILTER (WHERE result_payload->'trades' <> '[]'::jsonb) AS trade_outcomes
FROM runtime.canonical_command_outcomes
WHERE event_stream = 'PM10_CHECKER_20260928';
