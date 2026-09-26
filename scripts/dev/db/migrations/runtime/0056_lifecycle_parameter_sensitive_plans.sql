-- The claimed-order array changes cardinality/value distribution per batch.
-- On the sustained5k fixture the generic lifecycle plan took195-233ms per500
-- orders versus41-42ms for a custom plan, with no spill or JIT in either arm.
-- Keep planning sensitive to this batch, scoped to the lifecycle function;
-- PostgreSQL restores the caller's setting on exit. No SQL/body/locking change.
ALTER FUNCTION runtime.runtime_project_order_lifecycle_state(INTEGER)
  SET plan_cache_mode = force_custom_plan;
