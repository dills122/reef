-- Dirty queues are rebuildable projection work, not canonical truth. Keep them
-- out of WAL so repeated insert/delete churn does not tax projection freshness.

ALTER TABLE runtime.order_lifecycle_dirty SET UNLOGGED;
ALTER TABLE runtime.market_data_snapshot_dirty SET UNLOGGED;
