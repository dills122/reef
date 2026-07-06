-- Public trade tape read path: chronological last-trade data by instrument for
-- bot/UI consumption. Trade facts are already durable in runtime.trades; this adds
-- a monotonic cursor column and a by-instrument index so tape reads are cheap and
-- deterministically ordered without relying on string timestamp comparison.

ALTER TABLE runtime.trades
  ADD COLUMN IF NOT EXISTS sequence BIGINT GENERATED ALWAYS AS IDENTITY;

CREATE INDEX IF NOT EXISTS idx_trades_instrument_sequence
  ON runtime.trades(instrument_id, sequence DESC);
