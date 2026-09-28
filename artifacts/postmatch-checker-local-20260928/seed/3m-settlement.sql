\set ON_ERROR_STOP on
CREATE TEMP TABLE pm10_trades AS
SELECT n, ((n - 1) % 16)::integer AS p,
  ((n - 1) / 16 + 1)::bigint AS local_seq,
  'trade-pm10-' || lpad(n::text, 12, '0') || repeat('t', 48) AS trade_id,
  'event-pm10-' || lpad(n::text, 12, '0') AS event_id
FROM generate_series(1, 3000000) AS g(n)
WHERE n % 1000 < 440;
CREATE INDEX ON pm10_trades (p, local_seq);
ANALYZE pm10_trades;

INSERT INTO settlement.canonical_trade_intake (
  event_stream, source_generation, partition_id, stream_sequence, effect_ordinal,
  event_id, trade_id, execution_id, run_id, venue_session_id, buy_order_id,
  sell_order_id, buyer_participant_id, seller_participant_id, buyer_account_id,
  seller_account_id, instrument_id, quantity_units, price, currency, occurred_at_text
)
SELECT 'PM10_CHECKER_20260928', '00000000-0000-4000-8000-000000000010',
  p, (p::bigint << 48) + local_seq, 3, event_id, trade_id,
  'execution-' || n::text, 'pm10-run', 'pm10-session', 'buy-' || n::text,
  'sell-' || n::text, 'buyer-participant', 'seller-participant',
  'buyer-account', 'seller-account', 'STK' || lpad((p % 64)::text, 3, '0'),
  '1', '10', 'USD', '2026-09-28T00:00:00Z'
FROM pm10_trades;

INSERT INTO settlement.canonical_settlement_obligations (
  event_stream, source_generation, partition_id, stream_sequence, effect_ordinal,
  trade_id, event_id, run_id, venue_session_id, post_trade_profile_id,
  post_trade_policy_version, post_trade_mode, settlement_cycle, netting_mode,
  ledger_posting_mode, selection_source, buyer_participant_id,
  seller_participant_id, buyer_account_id, seller_account_id, instrument_id,
  quantity_units, cash_amount, currency, occurred_at, status
)
SELECT 'PM10_CHECKER_20260928', '00000000-0000-4000-8000-000000000010',
  p, (p::bigint << 48) + local_seq, 3, trade_id, event_id, 'pm10-run',
  'pm10-session', 'instant-post-trade-v1', 1, 'instant-post-trade', 'T+0',
  'gross', 'four-leg-dvp', 'pm10-checker', 'buyer-participant',
  'seller-participant', 'buyer-account', 'seller-account',
  'STK' || lpad((p % 64)::text, 3, '0'), 1, 10, 'USD',
  '2026-09-28T00:00:00Z'::timestamptz, 'SETTLED'
FROM pm10_trades;

INSERT INTO settlement.canonical_transition_attempts (
  event_stream, source_generation, trade_id, attempt_number, partition_id,
  stream_sequence, run_id, post_trade_profile_id, post_trade_policy_version,
  outcome, break_reason, workflow_facts, workflow_digest, occurred_at
)
SELECT 'PM10_CHECKER_20260928', '00000000-0000-4000-8000-000000000010',
  trade_id, 1, p, (p::bigint << 48) + local_seq, 'pm10-run',
  'instant-post-trade-v1', 1, 'SETTLED', NULL, '{}', repeat('c', 64),
  '2026-09-28T00:00:00Z'::timestamptz
FROM pm10_trades;

INSERT INTO settlement.canonical_transition_ledger_entries (
  event_stream, source_generation, trade_id, attempt_number, entry_kind,
  run_id, participant_id, account_id, asset_type, asset_id, direction,
  quantity, occurred_at
)
SELECT 'PM10_CHECKER_20260928', '00000000-0000-4000-8000-000000000010',
  t.trade_id, 1, leg.entry_kind, 'pm10-run', leg.participant_id,
  leg.account_id, leg.asset_type, leg.asset_id, leg.direction, leg.quantity,
  '2026-09-28T00:00:00Z'::timestamptz
FROM pm10_trades AS t
CROSS JOIN LATERAL (VALUES
  ('BUYER_CASH_DEBIT', 'buyer-participant', 'buyer-account', 'CASH', 'USD', 'DEBIT', 10::numeric),
  ('SELLER_CASH_CREDIT', 'seller-participant', 'seller-account', 'CASH', 'USD', 'CREDIT', 10::numeric),
  ('SELLER_SECURITY_DEBIT', 'seller-participant', 'seller-account', 'SECURITY', 'STK' || lpad((t.p % 64)::text, 3, '0'), 'DEBIT', 1::numeric),
  ('BUYER_SECURITY_CREDIT', 'buyer-participant', 'buyer-account', 'SECURITY', 'STK' || lpad((t.p % 64)::text, 3, '0'), 'CREDIT', 1::numeric)
) AS leg(entry_kind, participant_id, account_id, asset_type, asset_id, direction, quantity);

INSERT INTO settlement.canonical_transition_admissions (
  event_stream, source_generation, admission_rank, partition_id,
  from_exclusive_sequence, through_inclusive_sequence, obligation_count,
  obligation_digest, account_set_digest, opening_digest, dependency_digest
)
SELECT 'PM10_CHECKER_20260928', '00000000-0000-4000-8000-000000000010',
  ((w - 1) * 16 + p + 1)::bigint, p, (p::bigint << 48) + (w - 1) * 100,
  (p::bigint << 48) + w * 100, 44, repeat('d', 64), repeat('e', 64),
  repeat('f', 64), repeat('0', 64)
FROM generate_series(1, 1875) AS windows(w)
CROSS JOIN generate_series(0, 15) AS partitions(p);

INSERT INTO settlement.canonical_transition_admission_completions (
  event_stream, source_generation, admission_rank, obligation_digest
)
SELECT event_stream, source_generation, admission_rank, obligation_digest
FROM settlement.canonical_transition_admissions
WHERE event_stream = 'PM10_CHECKER_20260928';

INSERT INTO settlement.canonical_intake_frontiers (
  event_stream, partition_id, source_generation, last_stream_sequence
)
SELECT 'PM10_CHECKER_20260928', p,
  '00000000-0000-4000-8000-000000000010', (p::bigint << 48) + 187500
FROM generate_series(0, 15) AS partitions(p);
INSERT INTO settlement.canonical_obligation_frontiers (
  event_stream, partition_id, source_generation, last_stream_sequence
)
SELECT 'PM10_CHECKER_20260928', p,
  '00000000-0000-4000-8000-000000000010', (p::bigint << 48) + 187500
FROM generate_series(0, 15) AS partitions(p);
INSERT INTO settlement.canonical_transition_admission_frontiers (
  event_stream, partition_id, source_generation, last_stream_sequence, last_rank
)
SELECT 'PM10_CHECKER_20260928', p,
  '00000000-0000-4000-8000-000000000010', (p::bigint << 48) + 187500,
  (1874 * 16 + p + 1)::bigint
FROM generate_series(0, 15) AS partitions(p);
INSERT INTO settlement.canonical_transition_frontiers (
  event_stream, partition_id, source_generation, last_stream_sequence
)
SELECT 'PM10_CHECKER_20260928', p,
  '00000000-0000-4000-8000-000000000010', (p::bigint << 48) + 187500
FROM generate_series(0, 15) AS partitions(p);

ANALYZE settlement.canonical_trade_intake;
ANALYZE settlement.canonical_settlement_obligations;
ANALYZE settlement.canonical_transition_attempts;
ANALYZE settlement.canonical_transition_ledger_entries;
SELECT count(*) AS trades FROM pm10_trades;
