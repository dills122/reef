import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import http from "node:http";
import { env, loadDotEnv, run, waitForHttp } from "./lib/dev-utils.mjs";

loadDotEnv();

const artifactDir = env("DEV_CAMPAIGN_ARTIFACT_DIR", "/tmp/reef-throughput-campaign");
const duration = env("DEV_CAMPAIGN_DURATION", "90s");
const rates = env("DEV_CAMPAIGN_RATES", "300,500,800,1000,1250,1500");
const workers = env("DEV_CAMPAIGN_WORKERS", "32,64");
const traceLimit = env("DEV_CAMPAIGN_TRACE_CHECK_LIMIT", "100");
const jsRuntime = env("DEV_CAMPAIGN_JS_RUNTIME", process.execPath);
const resetStack = envFlag("DEV_CAMPAIGN_RESET_STACK", false);
const includeAbuseTripLane = envFlag("DEV_CAMPAIGN_INCLUDE_ABUSE_TRIP", false);
const abuseTripRates = env("DEV_CAMPAIGN_ABUSE_TRIP_RATES", "1200");
const abuseTripWorkers = env("DEV_CAMPAIGN_ABUSE_TRIP_WORKERS", "128");
const enforceAbuseTripGuardrail = envFlag("DEV_CAMPAIGN_ENFORCE_ABUSE_TRIP_GUARDRAIL", true);
const abuseTripMinTrips = Number(env("DEV_CAMPAIGN_ABUSE_TRIP_MIN_TRIPS", "1"));
const abuseTripMinBlocks = Number(env("DEV_CAMPAIGN_ABUSE_TRIP_MIN_BLOCKS", "1"));
const abuseTripMinAbuseBlockedFailPct = Number(
  env("DEV_CAMPAIGN_ABUSE_TRIP_MIN_ABUSE_BLOCKED_FAIL_PCT", "1"),
);

mkdirSync(artifactDir, { recursive: true });

if (resetStack) {
  console.log("resetting stack before campaign (DEV_CAMPAIGN_RESET_STACK=1)");
  await run("make", [`JS_RUNTIME=${jsRuntime}`, "dev-reset"]);
}

const lanes = [
  {
    id: "quality",
    mode: "strict-lifecycle",
    profile: "strict-clean",
    boundaryEnv: {
      EXTERNAL_API_ABUSE_BREAKER_MODE: "off",
    },
  },
  {
    id: "capacity",
    mode: "capacity-baseline",
    profile: "capacity-heavy",
    boundaryEnv: {
      EXTERNAL_API_ABUSE_BREAKER_MODE: "off",
    },
  },
];

if (includeAbuseTripLane) {
  lanes.push({
    id: "abuse-trip",
    mode: "chaos",
    profile: "abuse-trip",
    rates: abuseTripRates,
    workers: abuseTripWorkers,
    boundaryEnv: {
      EXTERNAL_API_ABUSE_BREAKER_MODE: "reject-rate",
      EXTERNAL_API_ABUSE_BREAKER_ENABLED: "true",
      EXTERNAL_API_ABUSE_BREAKER_REJECT_RATE_ENABLED: "true",
      EXTERNAL_API_ABUSE_BREAKER_MAX_REJECTS: "20",
      EXTERNAL_API_ABUSE_BREAKER_WINDOW_SECONDS: "30",
      EXTERNAL_API_ABUSE_BREAKER_BLOCK_SECONDS: "60",
      EXTERNAL_API_ABUSE_BREAKER_REJECT_CODES: "INVALID_STATE,NOT_FOUND,REFERENCE_DATA_ERROR,VALIDATION_ERROR",
      EXTERNAL_API_ABUSE_BREAKER_ROUTES: "/api/v1/orders/submit,/api/v1/orders/modify,/api/v1/orders/cancel",
      EXTERNAL_API_ABUSE_BREAKER_WARN_ONLY: "false",
    },
  });
}

const laneResults = [];
for (const lane of lanes) {
  laneResults.push(await runLane(lane));
}

// Return runtime config to default for local follow-up commands.
await configureRuntimeBoundary({ EXTERNAL_API_ABUSE_BREAKER_MODE: "off" });

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

if (includeAbuseTripLane && enforceAbuseTripGuardrail) {
  const guardrail = evaluateAbuseTripGuardrail(campaign.lanes.find((lane) => lane.id === "abuse-trip"));
  if (!guardrail.pass) {
    console.error("abuse-trip guardrail failed:");
    for (const failure of guardrail.failures) {
      console.error(`  - ${failure}`);
    }
    process.exitCode = 1;
  } else {
    console.log("abuse-trip guardrail passed");
  }
}

async function runLane(lane) {
  if (lane.boundaryEnv) {
    await configureRuntimeBoundary(lane.boundaryEnv);
  }
  const baseReport = join(artifactDir, `reef-${lane.id}.json`);
  const laneRates = lane.rates ?? rates;
  const laneWorkers = lane.workers ?? workers;
  const laneEnv = {
    DEV_STRESS_DURATION: duration,
    DEV_STRESS_RATES: laneRates,
    DEV_STRESS_SWEEP_WORKERS: laneWorkers,
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
  for (const rate of parseCsvInts(laneRates)) {
    for (const worker of parseCsvInts(laneWorkers)) {
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
          status429Count: statusCodeCount(report.statusCodes, 429),
          abuseBlockedCount: rejectTaxonomyCount(report.rejectTaxonomy, "ABUSE_BLOCKED"),
          abuseBlockedFailPct: rejectTaxonomyFailurePct(report.rejectTaxonomy, "ABUSE_BLOCKED"),
        });
      } catch {
        // skip missing sample
      }
    }
  }

  let abuseStats = null;
  try {
    abuseStats = await fetchJson("http://127.0.0.1:8080/internal/boundary/abuse/stats");
  } catch {
    // ignore optional snapshot failures
  }

  return { ...lane, samples, abuseStats };
}

async function configureRuntimeBoundary(overrides) {
  const previous = {};
  for (const [key, value] of Object.entries(overrides)) {
    previous[key] = process.env[key];
    process.env[key] = value;
  }
  try {
    await run("docker", ["compose", "-f", "docker-compose.yml", "up", "-d", "platform-runtime"]);
    await waitForHttp("http://127.0.0.1:8080/health", 90, 2000);
  } finally {
    for (const key of Object.keys(overrides)) {
      if (previous[key] == null) {
        delete process.env[key];
      } else {
        process.env[key] = previous[key];
      }
    }
  }
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
      includeAbuseTripLane,
      enforceAbuseTripGuardrail,
      abuseTripMinTrips,
      abuseTripMinBlocks,
      abuseTripMinAbuseBlockedFailPct,
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
    abuseStats: lane.abuseStats ?? null,
    peaks: {
      throughputRps: sorted[0]?.throughputRps ?? 0,
      acceptedRps: byAccepted[0]?.acceptedRps ?? 0,
    },
    qualityCap90: quality90 ?? null,
    qualityCap95: quality95 ?? null,
  };
}

function evaluateAbuseTripGuardrail(lane) {
  const failures = [];
  if (!lane) {
    failures.push("missing abuse-trip lane in campaign results");
    return { pass: false, failures };
  }
  const trips = Number(lane.abuseStats?.trips ?? 0);
  const blocks = Number(lane.abuseStats?.blocks ?? 0);
  if (trips < abuseTripMinTrips) {
    failures.push(`expected trips >= ${abuseTripMinTrips}, got ${trips}`);
  }
  if (blocks < abuseTripMinBlocks) {
    failures.push(`expected blocks >= ${abuseTripMinBlocks}, got ${blocks}`);
  }
  const peakAbuseBlockedFailPct = lane.samples.reduce((max, sample) => {
    return Math.max(max, Number(sample.abuseBlockedFailPct ?? 0));
  }, 0);
  if (peakAbuseBlockedFailPct < abuseTripMinAbuseBlockedFailPct) {
    failures.push(
      `expected ABUSE_BLOCKED failure share >= ${abuseTripMinAbuseBlockedFailPct}%, got ${peakAbuseBlockedFailPct.toFixed(2)}%`,
    );
  }
  return { pass: failures.length === 0, failures };
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
    if (lane.abuseStats) {
      lines.push(
        `- breaker stats: mode=${lane.abuseStats.mode}, enabled=${lane.abuseStats.enabled}, trips=${lane.abuseStats.trips}, blocks=${lane.abuseStats.blocks}, releases=${lane.abuseStats.releases}, activeBlockedClients=${lane.abuseStats.activeBlockedClients}`,
      );
    }
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

function envFlag(name, fallback) {
  const raw = env(name, fallback ? "1" : "0").toLowerCase();
  return raw === "1" || raw === "true" || raw === "yes" || raw === "on";
}

function statusCodeCount(statusCodes, code) {
  if (!statusCodes || typeof statusCodes !== "object") return 0;
  const numeric = Number(statusCodes[String(code)] ?? statusCodes[code] ?? 0);
  return Number.isFinite(numeric) ? numeric : 0;
}

function rejectTaxonomyCount(rows, code) {
  if (!Array.isArray(rows)) return 0;
  const row = rows.find((entry) => String(entry?.code ?? "").toUpperCase() === code.toUpperCase());
  const value = Number(row?.count ?? 0);
  return Number.isFinite(value) ? value : 0;
}

function rejectTaxonomyFailurePct(rows, code) {
  if (!Array.isArray(rows)) return 0;
  const row = rows.find((entry) => String(entry?.code ?? "").toUpperCase() === code.toUpperCase());
  const value = Number(row?.percentOfFailures ?? 0);
  return Number.isFinite(value) ? value : 0;
}

async function fetchJson(url, timeoutMs = 2000) {
  return await new Promise((resolve, reject) => {
    const req = http.request(url, { method: "GET", timeout: timeoutMs }, (res) => {
      let body = "";
      res.setEncoding("utf8");
      res.on("data", (chunk) => {
        body += chunk;
      });
      res.on("end", () => {
        if (!res.statusCode || res.statusCode < 200 || res.statusCode >= 300) {
          reject(new Error(`unexpected status ${res.statusCode ?? "unknown"}`));
          return;
        }
        try {
          resolve(JSON.parse(body));
        } catch (error) {
          reject(error);
        }
      });
    });
    req.on("timeout", () => {
      req.destroy(new Error(`request timeout after ${timeoutMs}ms`));
    });
    req.on("error", reject);
    req.end();
  });
}
