import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";

const script = "scripts/dev/do-projection-freshness-gate.sh";

const short = runGatePlan({});
assert.equal(short.status, 0, short.stderr);
assert.match(short.stdout, /profile=materializer-projection/);
assert.match(short.stdout, /goal=fixed/);
assert.match(short.stdout, /size=c-16/);
assert.match(short.stdout, /rates=2500/);
assert.match(short.stdout, /workers=256/);
assert.match(short.stdout, /repeat_samples=1/);
assert.match(short.stdout, /duration=60s/);
assert.match(short.stdout, /min_attempted_rps=2400/);
assert.match(short.stdout, /min_accepted_rps=2400/);
assert.match(short.stdout, /min_projected_rps=2400/);
assert.match(short.stdout, /max_projection_lag=0/);
assert.match(short.stdout, /max_materialized_to_projected_gap=0/);
assert.match(short.stdout, /min_stream_direct_active_partitions=16/);
assert.match(short.stdout, /max_stream_direct_partition_skew=4/);
assert.match(short.stdout, /require_db_diagnostics=1/);
assert.match(short.stdout, /require_pg_stat_io=1/);

const soak5m = runGatePlan({ REEF_DO_PROJECTION_FRESHNESS_GATE_TIER: "soak-5m" });
assert.equal(soak5m.status, 0, soak5m.stderr);
assert.match(soak5m.stdout, /duration=5m/);

const soak15m = runGatePlan({ REEF_DO_PROJECTION_FRESHNESS_GATE_TIER: "soak-15m" });
assert.equal(soak15m.status, 0, soak15m.stderr);
assert.match(soak15m.stdout, /duration=15m/);

const invalidTier = runGatePlan({ REEF_DO_PROJECTION_FRESHNESS_GATE_TIER: "too-hot" });
assert.equal(invalidTier.status, 2);
assert.match(invalidTier.stderr, /unsupported REEF_DO_PROJECTION_FRESHNESS_GATE_TIER=too-hot/);

function runGatePlan(overrides) {
  return spawnSync("bash", [script, "plan"], {
    cwd: process.cwd(),
    env: {
      ...process.env,
      REEF_DO_BENCHMARK_PROFILE: "",
      REEF_DO_BENCHMARK_GOAL: "",
      REEF_DO_STRESS_RATES: "",
      REEF_DO_STRESS_WORKERS: "",
      REEF_DO_STRESS_REPEAT_SAMPLES: "",
      REEF_DO_STRESS_DURATION: "",
      REEF_DO_SIZE: "",
      REEF_DO_MIN_ATTEMPTED_RPS: "",
      REEF_DO_MIN_ACCEPTED_RPS: "",
      REEF_DO_MIN_PROJECTED_RPS: "",
      REEF_DO_MAX_PROJECTION_LAG: "",
      REEF_DO_MAX_MATERIALIZED_TO_PROJECTED_GAP: "",
      REEF_DO_MIN_STREAM_DIRECT_ACTIVE_PARTITIONS: "",
      REEF_DO_MAX_STREAM_DIRECT_PARTITION_SKEW: "",
      REEF_DO_REQUIRE_DB_DIAGNOSTICS: "",
      REEF_DO_REQUIRE_PG_STAT_IO: "",
      REEF_DO_PROJECTION_FRESHNESS_GATE_TIER: "",
      ...overrides,
    },
    encoding: "utf8",
  });
}
