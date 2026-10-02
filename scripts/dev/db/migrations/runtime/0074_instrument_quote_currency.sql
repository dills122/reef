-- Existing reference instruments use legacy USD specification. Provision any
-- non-USD instrument explicitly before opening a new run; quote units are immutable.
ALTER TABLE runtime.reference_instruments ADD COLUMN IF NOT EXISTS quote_currency TEXT NOT NULL DEFAULT 'USD';

DO $$ BEGIN
  IF EXISTS (
    SELECT 1 FROM runtime.orders o JOIN runtime.reference_instruments i USING (instrument_id)
    WHERE o.currency <> i.quote_currency AND EXISTS (
      SELECT 1 FROM runtime.submit_results result
      WHERE result.order_id = o.order_id AND result.engine_order_id = o.engine_order_id
        AND result.result_type = 'accepted'
    )
  ) THEN
    RAISE EXCEPTION 'retained order currencies contradict instrument quote specifications; provision authoritative quotes before migration';
  END IF;
END $$;
