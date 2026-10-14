#!/usr/bin/env node
import { spawnSync } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

// Named gates share dispatch; profile defaults retain distinct measurement contracts.
const profiles = {
  "materializer-10k": {
    tierKey: "REEF_DO_MATERIALIZER_10K_GATE_TIER", defaultTier: "short",
    tiers: {
      short: { default_duration: "60s", default_repeat_samples: "3" },
      "soak-5m": { default_duration: "5m", default_repeat_samples: "2" },
      "soak-15m": { default_duration: "15m", default_repeat_samples: "1" },
    },
  },
  "materializer-scaling": {
    tierKey: "REEF_DO_MATERIALIZER_SCALING_TIER", defaultTier: "knee",
    tiers: {
      knee: { default_goal: "ceiling", default_rates: "10000,12500,15000,17500,20000,25000", default_duration: "60s", default_repeat_samples: "1", default_min_rps: "9900" },
      "20k-short": { default_goal: "sustain", default_rates: "20000", default_duration: "60s", default_repeat_samples: "3", default_min_rps: "19800" },
      "20k-soak-5m": { default_goal: "sustain", default_rates: "20000", default_duration: "5m", default_repeat_samples: "2", default_min_rps: "19800" },
    },
  },
  "projection-freshness": {
    tierKey: "REEF_DO_PROJECTION_FRESHNESS_GATE_TIER", defaultTier: "short",
    tiers: {
      short: { default_duration: "60s", default_rate: "2500", default_min_rate: "2400" },
      "soak-5m": { default_duration: "5m", default_rate: "2500", default_min_rate: "2400" },
      "5k-soak-5m": { default_duration: "5m", default_rate: "5000", default_min_rate: "4800" },
      "soak-15m": { default_duration: "15m", default_rate: "2500", default_min_rate: "2400" },
    },
  },
};

const commonDefaults = {
  "REEF_DO_STRESS_DURATION": "$default_duration",
  "REEF_DO_SIZE": "c-16",
  "REEF_DO_MIN_STREAM_DIRECT_ACTIVE_PARTITIONS": "16",
  "REEF_DO_MAX_STREAM_DIRECT_PARTITION_SKEW": "4",
  "REEF_DO_REQUIRE_DB_DIAGNOSTICS": "1",
  "REEF_DO_REQUIRE_PG_STAT_IO": "1"
};

const profileDefaults = {
  "materializer-10k": {
    "REEF_DO_BENCHMARK_PROFILE": "materializer",
    "REEF_DO_BENCHMARK_GOAL": "sustain",
    "REEF_DO_TARGET_ACCEPTED_RPS": "10000",
    "REEF_DO_TARGET_P95_MS": "100",
    "REEF_DO_TARGET_P99_MS": "200",
    "REEF_DO_STRESS_WORKERS": "1024",
    "REEF_DO_STRESS_REPEAT_SAMPLES": "$default_repeat_samples",
    "REEF_DO_MIN_ATTEMPTED_RPS": "9900",
    "REEF_DO_MIN_ACCEPTED_RPS": "9900"
  },
  "materializer-scaling": {
    "REEF_DO_BENCHMARK_PROFILE": "materializer",
    "REEF_DO_BENCHMARK_GOAL": "$default_goal",
    "REEF_DO_TARGET_ACCEPTED_RPS": "20000",
    "REEF_DO_TARGET_P95_MS": "100",
    "REEF_DO_TARGET_P99_MS": "200",
    "REEF_DO_STRESS_RATES": "$default_rates",
    "REEF_DO_STRESS_WORKERS": "2048",
    "REEF_DO_STRESS_REPEAT_SAMPLES": "$default_repeat_samples",
    "REEF_DO_MIN_ATTEMPTED_RPS": "$default_min_rps",
    "REEF_DO_MIN_ACCEPTED_RPS": "$default_min_rps"
  },
  "projection-freshness": {
    "REEF_DO_BENCHMARK_PROFILE": "materializer-projection",
    "REEF_DO_BENCHMARK_GOAL": "fixed",
    "REEF_DO_STRESS_RATES": "$default_rate",
    "REEF_DO_STRESS_WORKERS": "256",
    "REEF_DO_STRESS_REPEAT_SAMPLES": "1",
    "REEF_DO_MIN_ATTEMPTED_RPS": "$default_min_rate",
    "REEF_DO_MIN_ACCEPTED_RPS": "$default_min_rate",
    "REEF_DO_MIN_PROJECTED_RPS": "$default_min_rate",
    "REEF_DO_MAX_PROJECTION_LAG": "0",
    "REEF_DO_MAX_MATERIALIZED_TO_PROJECTED_GAP": "0",
    "REEF_DO_MAX_PROJECTION_DB_DEADLOCKS": "0",
    "REEF_DO_MAX_PROJECTION_DB_RETRIES": "0",
    "REEF_DO_PROJECTION_STAGE": "full",
    "REEF_DO_REQUIRE_PG_STAT_STATEMENTS": "1",
    "PROJECTION_DOWNSTREAM_INSTRUMENTATION_ENABLED": "true",
    "REEF_DO_REQUIRE_PROJECTION_COHORT_AUTHORITY": "1",
    "STREAM_ACK_PROJECTOR_0_PARTITIONS": "0,1,2,3",
    "STREAM_ACK_PROJECTOR_1_PARTITIONS": "4,5,6,7",
    "STREAM_ACK_PROJECTOR_2_PARTITIONS": "8,9,10,11",
    "STREAM_ACK_PROJECTOR_3_PARTITIONS": "12,13,14,15",
    "ORDER_LIFECYCLE_PROJECTOR_0_ENABLED": "true",
    "ORDER_LIFECYCLE_PROJECTOR_1_ENABLED": "false",
    "ORDER_LIFECYCLE_PROJECTOR_2_ENABLED": "false",
    "ORDER_LIFECYCLE_PROJECTOR_3_ENABLED": "false",
    "MARKET_DATA_PROJECTOR_0_ENABLED": "true",
    "MARKET_DATA_PROJECTOR_1_ENABLED": "false",
    "MARKET_DATA_PROJECTOR_2_ENABLED": "false",
    "MARKET_DATA_PROJECTOR_3_ENABLED": "false",
    "REEF_DO_REQUIRED_ORDER_LIFECYCLE_MAINTAINERS": "1",
    "REEF_DO_REQUIRED_MARKET_DATA_MAINTAINERS": "1"
  }
};

function usage() {
  console.log("usage: scripts/dev/do-gate.mjs <materializer-10k|materializer-scaling|projection-freshness> <plan|run|run-destroy|check>");
  for (const [name, profile] of Object.entries(profiles)) {
    console.log(`${name}: ${profile.tierKey}=${Object.keys(profile.tiers).join("|")} (default ${profile.defaultTier})`);
  }
  console.log("Existing REEF_DO_* overrides apply. Remote execution requires configured DigitalOcean credentials; destruction requires REEF_DO_CONFIRM_DESTROYABLE=1.");
}

const [name, command] = process.argv.slice(2);
if (!name || ["help", "-h", "--help"].includes(name)) {
  usage();
} else if (!Object.hasOwn(profiles, name)) {
  console.error(`unsupported gate profile=${name}`);
  process.exitCode = 2;
} else if (!command || ["help", "-h", "--help"].includes(command)) {
  usage();
} else if (!["plan", "run", "run-destroy", "check"].includes(command)) {
  console.error(`unsupported gate command=${command}`);
  process.exitCode = 2;
} else {
  const profile = profiles[name];
  const tier = process.env[profile.tierKey] || profile.defaultTier;
  if (!Object.hasOwn(profile.tiers, tier)) {
    console.error(`unsupported ${profile.tierKey}=${tier}`);
    process.exitCode = 2;
  } else {
    const env = { ...process.env };
    for (const [key, value] of Object.entries({ ...commonDefaults, ...profileDefaults[name] })) {
      env[key] ||= value.startsWith("$") ? profile.tiers[tier][value.slice(1)] : value;
    }
    const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
    const result = spawnSync("bash", [resolve(repoRoot, "scripts/dev/do-benchmark-host.sh"), command === "plan" ? "plan-goal" : command], {
      cwd: repoRoot, env, stdio: "inherit",
    });
    if (result.error) console.error(result.error.message);
    if (result.signal) process.kill(process.pid, result.signal);
    else process.exitCode = result.status ?? 1;
  }
}
