import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";

const script = "scripts/dev/do-materializer-scaling-gate.sh";

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
  return spawnSync("bash", [script, "plan"], {
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
