import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { env, loadDotEnv, run } from "./lib/dev-utils.mjs";

loadDotEnv();

const artifactDir = env("DEV_CAMPAIGN_ARTIFACT_DIR", "/tmp/reef-throughput-campaign");
const duration = env("DEV_CAMPAIGN_DURATION", "90s");
const rates = env("DEV_CAMPAIGN_RATES", "300,500,800,1000,1250,1500");
const workers = env("DEV_CAMPAIGN_WORKERS", "32,64");
const traceLimit = env("DEV_CAMPAIGN_TRACE_CHECK_LIMIT", "100");
const jsRuntime = env("DEV_CAMPAIGN_JS_RUNTIME", process.execPath);

mkdirSync(artifactDir, { recursive: true });

const lanes = [
  {
    id: "quality",
    mode: "strict-lifecycle",
    profile: "strict-clean",
  },
  {
    id: "capacity",
    mode: "capacity-baseline",
    profile: "capacity-heavy",
  },
];

const laneResults = [];
for (const lane of lanes) {
  laneResults.push(await runLane(lane));
}

const campaign = summarizeCampaign(laneResults);
const summaryJson = join(artifactDir, "throughput-campaign-summary.json");
const summaryMd = join(artifactDir, "throughput-campaign-summary.md");
writeFileSync(summaryJson, JSON.stringify(campaign, null, 2));
writeFileSync(summaryMd, toMarkdown(campaign));

console.log("throughput campaign complete");
console.log(`summary json: ${summaryJson}`);
console.log(`summary md  : ${summaryMd}`);
for (const lane of campaign.lanes) {
  console.log(
    `lane=${lane.id} samples=${lane.samples.length} peakThroughput=${lane.peaks.throughputRps.toFixed(2)} peakAccepted=${lane.peaks.acceptedRps.toFixed(2)} quality90=${lane.qualityCap90?.throughputRps?.toFixed(2) ?? "n/a"} quality95=${lane.qualityCap95?.throughputRps?.toFixed(2) ?? "n/a"}`,
  );
}

async function runLane(lane) {
  const baseReport = join(artifactDir, `reef-${lane.id}.json`);
  const laneEnv = {
    DEV_STRESS_DURATION: duration,
    DEV_STRESS_RATES: rates,
    DEV_STRESS_SWEEP_WORKERS: workers,
    DEV_STRESS_TRACE_CHECK_LIMIT: traceLimit,
    DEV_STRESS_MODE: lane.mode,
    DEV_STRESS_PROFILE: lane.profile,
    DEV_STRESS_REPORT_OUT: baseReport,
    DEV_STRESS_ARTIFACT_DIR: artifactDir,
    DEV_STRESS_MIN_SUCCESS_RATE_PCT: "0",
  };

  const previous = {};
  for (const [key, value] of Object.entries(laneEnv)) {
    previous[key] = process.env[key];
    process.env[key] = value;
  }
  try {
    console.log(`running lane=${lane.id} mode=${lane.mode} profile=${lane.profile}`);
    await run(jsRuntime, ["scripts/dev/stress.mjs"]);
  } finally {
    for (const key of Object.keys(laneEnv)) {
      if (previous[key] == null) {
        delete process.env[key];
      } else {
        process.env[key] = previous[key];
      }
    }
  }

  const samples = [];
  for (const rate of parseCsvInts(rates)) {
    for (const worker of parseCsvInts(workers)) {
      const path = baseReport.replace(/\.json$/, "") + `-rate-${rate}-workers-${worker}.json`;
      try {
        const report = JSON.parse(readFileSync(path, "utf8"));
        const successRatePct =
          report.totalRequests > 0 ? (report.totalSuccess / report.totalRequests) * 100 : 0;
        samples.push({
          path,
          lane: lane.id,
          rate,
          workers: worker,
          throughputRps: Number(report.throughputRps ?? 0),
          acceptedRps: Number(report.acceptedBusinessOpsRps ?? 0),
          successRatePct,
          p95Ms: Number(report.latencyMs?.p95 ?? 0),
          p99Ms: Number(report.latencyMs?.p99 ?? 0),
        });
      } catch {
        // skip missing sample
      }
    }
  }

  return { ...lane, samples };
}

function summarizeCampaign(lanesIn) {
  const lanesOut = lanesIn.map((lane) => summarizeLane(lane));
  return {
    generatedAt: new Date().toISOString(),
    inputs: {
      duration,
      rates,
      workers,
      traceLimit,
    },
    goals: {
      minSuccessRatePct: 90,
      preferredSuccessRatePct: 95,
      minThroughputRangeRps: "1250-1500",
      preferredThroughputRps: 2000,
    },
    lanes: lanesOut,
  };
}

function summarizeLane(lane) {
  const sorted = [...lane.samples].sort((a, b) => {
    if (a.throughputRps === b.throughputRps) return a.rate - b.rate;
    return b.throughputRps - a.throughputRps;
  });
  const byAccepted = [...lane.samples].sort((a, b) => b.acceptedRps - a.acceptedRps);
  const quality90 = lane.samples
    .filter((s) => s.successRatePct >= 90)
    .sort((a, b) => b.throughputRps - a.throughputRps)[0];
  const quality95 = lane.samples
    .filter((s) => s.successRatePct >= 95)
    .sort((a, b) => b.throughputRps - a.throughputRps)[0];

  return {
    id: lane.id,
    mode: lane.mode,
    profile: lane.profile,
    samples: lane.samples,
    peaks: {
      throughputRps: sorted[0]?.throughputRps ?? 0,
      acceptedRps: byAccepted[0]?.acceptedRps ?? 0,
    },
    qualityCap90: quality90 ?? null,
    qualityCap95: quality95 ?? null,
  };
}

function toMarkdown(campaign) {
  const lines = [];
  lines.push("# Throughput Campaign Summary");
  lines.push("");
  lines.push(`Generated: ${campaign.generatedAt}`);
  lines.push("");
  lines.push("## Inputs");
  lines.push(`- duration: ${campaign.inputs.duration}`);
  lines.push(`- rates: ${campaign.inputs.rates}`);
  lines.push(`- workers: ${campaign.inputs.workers}`);
  lines.push(`- trace-check-limit: ${campaign.inputs.traceLimit}`);
  lines.push("");
  lines.push("## Goals");
  lines.push(`- min success-rate: >= ${campaign.goals.minSuccessRatePct}%`);
  lines.push(`- preferred success-rate: >= ${campaign.goals.preferredSuccessRatePct}%`);
  lines.push(`- min throughput target: ${campaign.goals.minThroughputRangeRps} rps`);
  lines.push(`- preferred throughput target: ~${campaign.goals.preferredThroughputRps} rps`);
  lines.push("");
  for (const lane of campaign.lanes) {
    lines.push(`## Lane: ${lane.id}`);
    lines.push(`- mode: ${lane.mode}`);
    lines.push(`- profile: ${lane.profile}`);
    lines.push(`- peak throughput: ${lane.peaks.throughputRps.toFixed(2)} rps`);
    lines.push(`- peak accepted: ${lane.peaks.acceptedRps.toFixed(2)} rps`);
    lines.push(
      `- quality cap (>=90%): ${
        lane.qualityCap90 ? `${lane.qualityCap90.throughputRps.toFixed(2)} rps @ rate=${lane.qualityCap90.rate}, workers=${lane.qualityCap90.workers}` : "not reached"
      }`,
    );
    lines.push(
      `- quality cap (>=95%): ${
        lane.qualityCap95 ? `${lane.qualityCap95.throughputRps.toFixed(2)} rps @ rate=${lane.qualityCap95.rate}, workers=${lane.qualityCap95.workers}` : "not reached"
      }`,
    );
    lines.push("");
  }
  return lines.join("\n");
}

function parseCsvInts(raw) {
  return raw
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean)
    .map((value) => Number(value))
    .filter((value) => Number.isFinite(value) && value > 0);
}
