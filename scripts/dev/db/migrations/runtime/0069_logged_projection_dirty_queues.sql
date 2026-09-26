-- Dirty markers must survive an unclean PostgreSQL restart. Canonical
-- projection facts and watermarks are durable; losing either queue after
-- their commit can leave lifecycle or market reads stale with no retry work.
-- This rewrites both tables under an exclusive lock during migration.

ALTER TABLE runtime.order_lifecycle_dirty SET LOGGED;
ALTER TABLE runtime.market_data_snapshot_dirty SET LOGGED;
