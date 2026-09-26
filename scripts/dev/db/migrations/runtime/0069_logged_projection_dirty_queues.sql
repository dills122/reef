-- Dirty markers must survive an unclean PostgreSQL restart. Canonical
-- projection facts and watermarks are durable; losing either queue after
-- their commit can leave lifecycle or market reads stale with no retry work.
-- This rewrites both tables under an exclusive lock during migration.
-- This is a forward-only durability fix. An interrupted transaction leaves
-- both tables unchanged; after commit, older application images can keep
-- using LOGGED queues. Do not downgrade to UNLOGGED on image rollback,
-- because doing so restores the crash-loss fault.

ALTER TABLE runtime.order_lifecycle_dirty SET LOGGED;
ALTER TABLE runtime.market_data_snapshot_dirty SET LOGGED;
