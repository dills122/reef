-- Refresh stale market metadata after catch-up without rebuilding unchanged books.
-- Price/quantity, dirty claims, snapshot ordering and processed-count semantics
-- remain those of0055. Idle polls rewrite only changed metadata.

CREATE OR REPLACE FUNCTION runtime.runtime_project_market_data_snapshots(
  p_projection_name TEXT,
  p_source_projection_name TEXT,
  p_last_partition_seq BIGINT,
  p_lag BIGINT,
  p_batch_size INTEGER
)
RETURNS BIGINT
LANGUAGE plpgsql
VOLATILE
AS $$
DECLARE
  projected_count BIGINT := 0;
  effective_batch_size INTEGER := 0;
  claimed_ids TEXT[];
BEGIN
  IF p_batch_size IS NULL OR p_batch_size <= 0 THEN
    RETURN 0;
  END IF;

  effective_batch_size := LEAST(p_batch_size, 5000);

  -- Claim locks first. The next SQL statement receives a fresh READ COMMITTED
  -- snapshot in this VOLATILE function, including source facts committed before
  -- these locks were acquired.
  SELECT array_agg(claimed.instrument_id) INTO claimed_ids
  FROM (
    SELECT instrument_id
    FROM runtime.market_data_snapshot_dirty
    ORDER BY instrument_id
    LIMIT effective_batch_size
    FOR UPDATE SKIP LOCKED
  ) claimed;

  IF claimed_ids IS NULL THEN
    -- Source status is captured before this call. A fresh snapshot with neither
    -- dirty queue pending proves that older frontier has drained through market
    -- data. SKIP LOCKED alone is insufficient: another caller may own dirty rows.
    -- Refresh only this source/projection and never move a newer snapshot back.
    IF p_lag = 0
      AND NOT EXISTS (SELECT 1 FROM runtime.order_lifecycle_dirty)
      AND NOT EXISTS (SELECT 1 FROM runtime.market_data_snapshot_dirty)
    THEN
      UPDATE runtime.market_data_snapshots
      SET last_partition_seq = p_last_partition_seq, lag = 0, updated_at = now()
      WHERE projection_name = p_projection_name
        AND source_projection_name = p_source_projection_name
        AND last_partition_seq <= p_last_partition_seq
        AND (last_partition_seq IS DISTINCT FROM p_last_partition_seq OR lag <> 0);
    END IF;
    RETURN 0;
  END IF;

  WITH selected_dirty AS (
    SELECT unnest(claimed_ids) AS instrument_id
  ),
  present_instruments AS (
    -- MAX(currency) and presence across all eligible sides, including orders
    -- away from best price. An indexed top value avoids scanning the whole book.
    SELECT selected.instrument_id, current_currency.currency
    FROM selected_dirty selected
    CROSS JOIN LATERAL (
      SELECT currency
      FROM runtime.order_lifecycle_state
      WHERE instrument_id = selected.instrument_id
        AND order_type = 'LIMIT'
        AND status IN ('OPEN', 'PARTIALLY_FILLED')
        AND limit_price_num IS NOT NULL
        AND remaining_quantity_units_num > 0
      ORDER BY currency DESC NULLS LAST
      LIMIT 1
    ) current_currency
  ),
  shaped AS (
    SELECT p.instrument_id,
      COALESCE(bt.price::text,'') best_bid_price,COALESCE(bt.quantity::text,'') best_bid_quantity,
      COALESCE(at.price::text,'') best_ask_price,COALESCE(at.quantity::text,'') best_ask_quantity,
      COALESCE(p.currency,'') currency,
      bt.price best_bid_price_num,bt.quantity best_bid_quantity_num,
      at.price best_ask_price_num,at.quantity best_ask_quantity_num
    FROM present_instruments p
    LEFT JOIN LATERAL (
    SELECT limit_price_num AS price FROM runtime.order_lifecycle_state
    WHERE instrument_id=p.instrument_id
      AND side='BUY'
      AND order_type='LIMIT'
      AND status IN ('OPEN','PARTIALLY_FILLED')
      AND limit_price_num IS NOT NULL
      AND remaining_quantity_units_num>0
      ORDER BY limit_price_num DESC,order_id LIMIT 1
    ) b ON TRUE
    LEFT JOIN LATERAL (
    SELECT MAX(limit_price_num) AS price, SUM(remaining_quantity_units_num) AS quantity FROM runtime.order_lifecycle_state
    WHERE instrument_id=p.instrument_id
      AND side='BUY'
      AND limit_price_num=b.price
      AND order_type='LIMIT'
      AND status IN ('OPEN','PARTIALLY_FILLED')
      AND limit_price_num IS NOT NULL
      AND remaining_quantity_units_num>0
    ) bt ON TRUE
    LEFT JOIN LATERAL (
    SELECT limit_price_num AS price FROM runtime.order_lifecycle_state
    WHERE instrument_id=p.instrument_id
      AND side='SELL'
      AND order_type='LIMIT'
      AND status IN ('OPEN','PARTIALLY_FILLED')
      AND limit_price_num IS NOT NULL
      AND remaining_quantity_units_num>0
      ORDER BY limit_price_num ASC,order_id LIMIT 1
    ) a ON TRUE
    LEFT JOIN LATERAL (
    SELECT MIN(limit_price_num) AS price, SUM(remaining_quantity_units_num) AS quantity FROM runtime.order_lifecycle_state
    WHERE instrument_id=p.instrument_id
      AND side='SELL'
      AND limit_price_num=a.price
      AND order_type='LIMIT'
      AND status IN ('OPEN','PARTIALLY_FILLED')
      AND limit_price_num IS NOT NULL
      AND remaining_quantity_units_num>0
    ) at ON TRUE
  ),
  upsert_present AS (
    INSERT INTO runtime.market_data_snapshots(
      projection_name,
      source_projection_name,
      instrument_id,
      best_bid_price,
      best_bid_quantity,
      best_ask_price,
      best_ask_quantity,
      currency,
      last_partition_seq,
      lag,
      updated_at,
      best_bid_price_num,
      best_bid_quantity_num,
      best_ask_price_num,
      best_ask_quantity_num
    )
    SELECT
      p_projection_name,
      p_source_projection_name,
      shaped.instrument_id,
      shaped.best_bid_price,
      shaped.best_bid_quantity,
      shaped.best_ask_price,
      shaped.best_ask_quantity,
      shaped.currency,
      p_last_partition_seq,
      p_lag,
      now(),
      shaped.best_bid_price_num,
      shaped.best_bid_quantity_num,
      shaped.best_ask_price_num,
      shaped.best_ask_quantity_num
    FROM shaped
    ON CONFLICT (projection_name, instrument_id) DO UPDATE SET
      source_projection_name = EXCLUDED.source_projection_name,
      best_bid_price = EXCLUDED.best_bid_price,
      best_bid_quantity = EXCLUDED.best_bid_quantity,
      best_ask_price = EXCLUDED.best_ask_price,
      best_ask_quantity = EXCLUDED.best_ask_quantity,
      currency = EXCLUDED.currency,
      last_partition_seq = EXCLUDED.last_partition_seq,
      lag = EXCLUDED.lag,
      updated_at = EXCLUDED.updated_at,
      best_bid_price_num = EXCLUDED.best_bid_price_num,
      best_bid_quantity_num = EXCLUDED.best_bid_quantity_num,
      best_ask_price_num = EXCLUDED.best_ask_price_num,
      best_ask_quantity_num = EXCLUDED.best_ask_quantity_num
    RETURNING 1
  ),
  delete_absent AS (
    DELETE FROM runtime.market_data_snapshots snapshots
    USING selected_dirty
    WHERE snapshots.projection_name = p_projection_name
      AND snapshots.instrument_id = selected_dirty.instrument_id
      AND NOT EXISTS (
        SELECT 1
        FROM present_instruments present
        WHERE present.instrument_id = selected_dirty.instrument_id
      )
    RETURNING 1
  ),
  cleared AS (
    DELETE FROM runtime.market_data_snapshot_dirty dirty
    USING selected_dirty
    WHERE dirty.instrument_id = selected_dirty.instrument_id
    RETURNING 1
  )
  SELECT COUNT(*) INTO projected_count FROM selected_dirty;

  RETURN projected_count;
END;
$$;
