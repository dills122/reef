import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { copyFileSync, mkdirSync, mkdtempSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

// Dispatch tests run an isolated host stub: no cloud tools or credentials used.
const fixtureRoot = realpathSync(mkdtempSync(join(tmpdir(), "reef-do-gate-")));
try {
  const scripts = join(fixtureRoot, "scripts/dev");
  mkdirSync(scripts, { recursive: true });
  const gate = join(scripts, "do-gate.mjs");
  copyFileSync("scripts/dev/do-gate.mjs", gate);
  writeFileSync(join(scripts, "do-benchmark-host.sh"), '#!/usr/bin/env bash\nprintf "%s|%s|%s|%s\\n" "$1" "$REEF_DO_MIN_ACCEPTED_RPS" "$REEF_DO_SIZE" "$PWD"\nexit 17\n');
  for (const profile of ["materializer-10k", "materializer-scaling", "projection-freshness"]) {
    for (const command of ["plan", "run", "run-destroy", "check"]) {
      const result = spawnSync(process.execPath, [gate, profile, command], {
        cwd: tmpdir(), encoding: "utf8",
        env: { PATH: process.env.PATH, REEF_DO_MIN_ACCEPTED_RPS: "0", REEF_DO_SIZE: "" },
      });
      assert.equal(result.status, 17, result.stderr);
      assert.equal(result.stdout, `${command === "plan" ? "plan-goal" : command}|0|c-16|${fixtureRoot}\n`);
    }
  }
  for (const args of [["unknown", "run"], ["constructor", "run"], ["materializer-10k", "unknown"]]) {
    const result = spawnSync(process.execPath, [gate, ...args], { encoding: "utf8" });
    assert.equal(result.status, 2);
    assert.equal(result.stdout, "", "invalid input must not dispatch host");
  }
  for (const args of [[], ["--help"], ["materializer-10k"], ["materializer-10k", "help"]]) {
    const result = spawnSync(process.execPath, [gate, ...args], { encoding: "utf8" });
    assert.equal(result.status, 0);
    assert.match(result.stdout, /usage:/);
  }
} finally {
  rmSync(fixtureRoot, { recursive: true, force: true });
}

// materializer-10k
{
const script = "scripts/dev/do-gate.mjs";
const profile = "materializer-10k";

const short = runGatePlan({});
assert.equal(short.status, 0, short.stderr);
assert.match(short.stdout, /profile=materializer/);
assert.match(short.stdout, /target_accepted_rps=10000/);
assert.match(short.stdout, /size=c-16/);
assert.match(short.stdout, /workers=1024/);
assert.match(short.stdout, /repeat_samples=3/);
assert.match(short.stdout, /duration=60s/);
assert.match(short.stdout, /min_attempted_rps=9900/);
assert.match(short.stdout, /min_accepted_rps=9900/);
assert.match(short.stdout, /max_p95_ms=100/);
assert.match(short.stdout, /max_p99_ms=200/);
assert.match(short.stdout, /min_stream_direct_active_partitions=16/);
assert.match(short.stdout, /max_stream_direct_partition_skew=4/);
assert.match(short.stdout, /require_db_diagnostics=1/);
assert.match(short.stdout, /require_pg_stat_io=1/);

const soak5m = runGatePlan({ REEF_DO_MATERIALIZER_10K_GATE_TIER: "soak-5m" });
assert.equal(soak5m.status, 0, soak5m.stderr);
assert.match(soak5m.stdout, /repeat_samples=2/);
assert.match(soak5m.stdout, /duration=5m/);

const soak15m = runGatePlan({ REEF_DO_MATERIALIZER_10K_GATE_TIER: "soak-15m" });
assert.equal(soak15m.status, 0, soak15m.stderr);
assert.match(soak15m.stdout, /repeat_samples=1/);
assert.match(soak15m.stdout, /duration=15m/);

const invalidTier = runGatePlan({ REEF_DO_MATERIALIZER_10K_GATE_TIER: "too-hot" });
assert.equal(invalidTier.status, 2);
assert.match(invalidTier.stderr, /unsupported REEF_DO_MATERIALIZER_10K_GATE_TIER=too-hot/);

function runGatePlan(overrides) {
  return spawnSync(process.execPath, [script, profile, "plan"], {
    cwd: process.cwd(),
    env: {
      ...process.env,
      REEF_DO_BENCHMARK_PROFILE: "",
      REEF_DO_BENCHMARK_GOAL: "",
      REEF_DO_TARGET_ACCEPTED_RPS: "",
      REEF_DO_TARGET_P95_MS: "",
      REEF_DO_TARGET_P99_MS: "",
      REEF_DO_STRESS_WORKERS: "",
      REEF_DO_STRESS_REPEAT_SAMPLES: "",
      REEF_DO_STRESS_DURATION: "",
      REEF_DO_SIZE: "",
      REEF_DO_MIN_ATTEMPTED_RPS: "",
      REEF_DO_MIN_ACCEPTED_RPS: "",
      REEF_DO_MIN_STREAM_DIRECT_ACTIVE_PARTITIONS: "",
      REEF_DO_MAX_STREAM_DIRECT_PARTITION_SKEW: "",
      REEF_DO_REQUIRE_DB_DIAGNOSTICS: "",
      REEF_DO_REQUIRE_PG_STAT_IO: "",
      REEF_DO_MATERIALIZER_10K_GATE_TIER: "",
      ...overrides,
    },
    encoding: "utf8",
  });
}

}
// materializer-scaling
{
const script = "scripts/dev/do-gate.mjs";
const profile = "materializer-scaling";

const knee = runPlan({});
assert.equal(knee.status, 0, knee.stderr);
assert.match(knee.stdout, /profile=materializer/);
assert.match(knee.stdout, /goal=ceiling/);
assert.match(knee.stdout, /target_accepted_rps=20000/);
assert.match(knee.stdout, /rates=10000,12500,15000,17500,20000,25000/);
assert.match(knee.stdout, /workers=2048/);
assert.match(knee.stdout, /repeat_samples=1/);
assert.match(knee.stdout, /duration=60s/);
assert.match(knee.stdout, /min_accepted_rps=9900/);

const short = runPlan({ REEF_DO_MATERIALIZER_SCALING_TIER: "20k-short" });
assert.equal(short.status, 0, short.stderr);
assert.match(short.stdout, /goal=sustain/);
assert.match(short.stdout, /rates=20000/);
assert.match(short.stdout, /repeat_samples=3/);
assert.match(short.stdout, /min_accepted_rps=19800/);

const soak = runPlan({ REEF_DO_MATERIALIZER_SCALING_TIER: "20k-soak-5m" });
assert.equal(soak.status, 0, soak.stderr);
assert.match(soak.stdout, /duration=5m/);
assert.match(soak.stdout, /repeat_samples=2/);

const invalid = runPlan({ REEF_DO_MATERIALIZER_SCALING_TIER: "50k-hope" });
assert.equal(invalid.status, 2);
assert.match(invalid.stderr, /unsupported REEF_DO_MATERIALIZER_SCALING_TIER=50k-hope/);

function runPlan(overrides) {
  return spawnSync(process.execPath, [script, profile, "plan"], {
    cwd: process.cwd(),
    env: {
      ...process.env,
      REEF_DO_BENCHMARK_PROFILE: "",
      REEF_DO_BENCHMARK_GOAL: "",
      REEF_DO_TARGET_ACCEPTED_RPS: "",
      REEF_DO_TARGET_P95_MS: "",
      REEF_DO_TARGET_P99_MS: "",
      REEF_DO_STRESS_RATES: "",
      REEF_DO_STRESS_WORKERS: "",
      REEF_DO_STRESS_REPEAT_SAMPLES: "",
      REEF_DO_STRESS_DURATION: "",
      REEF_DO_SIZE: "",
      REEF_DO_MIN_ATTEMPTED_RPS: "",
      REEF_DO_MIN_ACCEPTED_RPS: "",
      REEF_DO_MIN_STREAM_DIRECT_ACTIVE_PARTITIONS: "",
      REEF_DO_MAX_STREAM_DIRECT_PARTITION_SKEW: "",
      REEF_DO_REQUIRE_DB_DIAGNOSTICS: "",
      REEF_DO_REQUIRE_PG_STAT_IO: "",
      REEF_DO_MATERIALIZER_SCALING_TIER: "",
      ...overrides,
    },
    encoding: "utf8",
  });
}

}
// projection-freshness
{
const script = "scripts/dev/do-gate.mjs";
const profile = "projection-freshness";

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
assert.match(short.stdout, /max_projection_db_deadlocks=0/);
assert.match(short.stdout, /max_projection_db_retries=0/);
assert.match(short.stdout, /projection_stage=full/);
assert.match(short.stdout, /stream_ack_projector_0_partitions=0,1,2,3/);
assert.match(short.stdout, /stream_ack_projector_1_partitions=4,5,6,7/);
assert.match(short.stdout, /stream_ack_projector_2_partitions=8,9,10,11/);
assert.match(short.stdout, /stream_ack_projector_3_partitions=12,13,14,15/);
assert.match(short.stdout, /order_lifecycle_projector_0_enabled=true/);
assert.match(short.stdout, /order_lifecycle_projector_1_enabled=false/);
assert.match(short.stdout, /market_data_projector_0_enabled=true/);
assert.match(short.stdout, /market_data_projector_1_enabled=false/);
assert.match(short.stdout, /required_order_lifecycle_maintainers=1/);
assert.match(short.stdout, /required_market_data_maintainers=1/);
assert.match(short.stdout, /min_stream_direct_active_partitions=16/);
assert.match(short.stdout, /max_stream_direct_partition_skew=4/);
assert.match(short.stdout, /require_db_diagnostics=1/);
assert.match(short.stdout, /require_pg_stat_io=1/);
assert.match(short.stdout, /require_pg_stat_statements=1/);
assert.match(short.stdout, /projection_downstream_instrumentation_enabled=true/);
assert.match(short.stdout, /require_projection_cohort_authority=1/);

const soak5m = runGatePlan({ REEF_DO_PROJECTION_FRESHNESS_GATE_TIER: "soak-5m" });
assert.equal(soak5m.status, 0, soak5m.stderr);
assert.match(soak5m.stdout, /duration=5m/);

const fiveKSoak5m = runGatePlan({ REEF_DO_PROJECTION_FRESHNESS_GATE_TIER: "5k-soak-5m" });
assert.equal(fiveKSoak5m.status, 0, fiveKSoak5m.stderr);
assert.match(fiveKSoak5m.stdout, /rates=5000/);
assert.match(fiveKSoak5m.stdout, /duration=5m/);
assert.match(fiveKSoak5m.stdout, /min_attempted_rps=4800/);
assert.match(fiveKSoak5m.stdout, /min_accepted_rps=4800/);
assert.match(fiveKSoak5m.stdout, /min_projected_rps=4800/);

const soak15m = runGatePlan({ REEF_DO_PROJECTION_FRESHNESS_GATE_TIER: "soak-15m" });
assert.equal(soak15m.status, 0, soak15m.stderr);
assert.match(soak15m.stdout, /duration=15m/);

const invalidTier = runGatePlan({ REEF_DO_PROJECTION_FRESHNESS_GATE_TIER: "too-hot" });
assert.equal(invalidTier.status, 2);
assert.match(invalidTier.stderr, /unsupported REEF_DO_PROJECTION_FRESHNESS_GATE_TIER=too-hot/);

function runGatePlan(overrides) {
  return spawnSync(process.execPath, [script, profile, "plan"], {
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
      REEF_DO_MAX_PROJECTION_DB_DEADLOCKS: "",
      REEF_DO_MAX_PROJECTION_DB_RETRIES: "",
      REEF_DO_PROJECTION_STAGE: "",
      STREAM_ACK_PROJECTOR_0_PARTITIONS: "",
      STREAM_ACK_PROJECTOR_1_PARTITIONS: "",
      STREAM_ACK_PROJECTOR_2_PARTITIONS: "",
      STREAM_ACK_PROJECTOR_3_PARTITIONS: "",
      ORDER_LIFECYCLE_PROJECTOR_0_ENABLED: "",
      ORDER_LIFECYCLE_PROJECTOR_1_ENABLED: "",
      ORDER_LIFECYCLE_PROJECTOR_2_ENABLED: "",
      ORDER_LIFECYCLE_PROJECTOR_3_ENABLED: "",
      MARKET_DATA_PROJECTOR_0_ENABLED: "",
      MARKET_DATA_PROJECTOR_1_ENABLED: "",
      MARKET_DATA_PROJECTOR_2_ENABLED: "",
      MARKET_DATA_PROJECTOR_3_ENABLED: "",
      REEF_DO_REQUIRED_ORDER_LIFECYCLE_MAINTAINERS: "",
      REEF_DO_REQUIRED_MARKET_DATA_MAINTAINERS: "",
      REEF_DO_MIN_STREAM_DIRECT_ACTIVE_PARTITIONS: "",
      REEF_DO_MAX_STREAM_DIRECT_PARTITION_SKEW: "",
      REEF_DO_REQUIRE_DB_DIAGNOSTICS: "",
      REEF_DO_REQUIRE_PG_STAT_IO: "",
      REEF_DO_REQUIRE_PG_STAT_STATEMENTS: "",
      PROJECTION_DOWNSTREAM_INSTRUMENTATION_ENABLED: "",
      REEF_DO_REQUIRE_PROJECTION_COHORT_AUTHORITY: "",
      REEF_DO_REQUIRE_SUSTAINED_DOWNSTREAM_FRESHNESS: "",
      REEF_DO_PROJECTION_FRESHNESS_GATE_TIER: "",
      ...overrides,
    },
    encoding: "utf8",
  });
}

assert.match(short.stdout, /require_sustained_downstream_freshness=0/, "sustained gate remains opt-in");
const sustainedPlan = runGatePlan({ REEF_DO_REQUIRE_SUSTAINED_DOWNSTREAM_FRESHNESS: "1" });
assert.equal(sustainedPlan.status, 0, sustainedPlan.stderr);
assert.match(sustainedPlan.stdout, /require_sustained_downstream_freshness=1/);

}
