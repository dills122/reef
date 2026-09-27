-- Preserve canonical display text for participant reads. Numeric and timestamp
-- columns remain the sortable/validated operational values; old generations
-- must be replayed before the live read route is enabled.
ALTER TABLE postmatch.live_order_state
  ADD COLUMN remaining_quantity_text TEXT,
  ADD COLUMN limit_price_text TEXT;

ALTER TABLE postmatch.live_execution_facts
  ADD COLUMN quantity_units_text TEXT,
  ADD COLUMN execution_price_text TEXT,
  ADD COLUMN occurred_at_text TEXT;
