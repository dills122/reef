import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { env, loadDotEnv } from "./lib/dev-utils.mjs";

loadDotEnv();

const baselinePath = env(
  "DEV_CAMPAIGN_BASELINE_SUMMARY",
  "/tmp/reef-throughput-campaign-baseline/throughput-campaign-summary.json",
);
const candidatePath = env(
  "DEV_CAMPAIGN_CANDIDATE_SUMMARY",
  "/tmp/reef-throughput-campaign/throughput-campaign-summary.json",
);
const outJsonPath = env(
  "DEV_CAMPAIGN_COMPARATOR_OUT_JSON",
  "/tmp/reef-throughput-campaign-comparator/throughput-campaign-comparator.json",
);
const outMdPath = env(
  "DEV_CAMPAIGN_COMPARATOR_OUT_MD",
  "/tmp/reef-throughput-campaign-comparator/throughput-campaign-comparator.md",
);

const baseline = loadJson(baselinePath);
const candidate = loadJson(candidatePath);
const comparison = compareCampaigns(baseline, candidate, baselinePath, candidatePath);

mkdirSync(dirname(outJsonPath), { recursive: true });
mkdirSync(dirname(outMdPath), { recursive: true });
writeFileSync(outJsonPath, JSON.stringify(comparison, null, 2));
writeFileSync(outMdPath, toMarkdown(comparison));

console.log("throughput campaign comparator complete");
console.log(`baseline : ${resolve(baselinePath)}`);
console.log(`candidate: ${resolve(candidatePath)}`);
console.log(`json out : ${resolve(outJsonPath)}`);
console.log(`md out   : ${resolve(outMdPath)}`);

for (const lane of comparison.lanes) {
  console.log(
    `lane=${lane.id} peakThroughput=${formatSigned(lane.peakThroughputRps.delta)} (${formatPct(
      lane.peakThroughputRps.deltaPct,
    )}) peakAccepted=${formatSigned(lane.peakAcceptedRps.delta)} (${formatPct(lane.peakAcceptedRps.deltaPct)})`,
  );
}

function compareCampaigns(baselineCampaign, candidateCampaign, baselineSummaryPath, candidateSummaryPath) {
  const baselineLanes = indexById(baselineCampaign.lanes ?? []);
  const candidateLanes = indexById(candidateCampaign.lanes ?? []);
  const laneIds = Array.from(new Set([...Object.keys(baselineLanes), ...Object.keys(candidateLanes)])).sort();

  const lanes = laneIds.map((id) => compareLane(id, baselineLanes[id], candidateLanes[id]));
  return {
    generatedAt: new Date().toISOString(),
    baselineSummaryPath: resolve(baselineSummaryPath),
    candidateSummaryPath: resolve(candidateSummaryPath),
    lanes,
    totals: {
      improvedThroughputLanes: lanes.filter((lane) => lane.peakThroughputRps.delta > 0).length,
      improvedAcceptedLanes: lanes.filter((lane) => lane.peakAcceptedRps.delta > 0).length,
      improvedQuality90Lanes: lanes.filter((lane) => lane.qualityCap90ThroughputRps.delta > 0).length,
      improvedQuality95Lanes: lanes.filter((lane) => lane.qualityCap95ThroughputRps.delta > 0).length,
    },
  };
}

function compareLane(id, baselineLane, candidateLane) {
  const baselinePeakThroughput = Number(baselineLane?.peaks?.throughputRps ?? 0);
  const candidatePeakThroughput = Number(candidateLane?.peaks?.throughputRps ?? 0);
  const baselinePeakAccepted = Number(baselineLane?.peaks?.acceptedRps ?? 0);
  const candidatePeakAccepted = Number(candidateLane?.peaks?.acceptedRps ?? 0);

  const baselineQuality90 = Number(baselineLane?.qualityCap90?.throughputRps ?? 0);
  const candidateQuality90 = Number(candidateLane?.qualityCap90?.throughputRps ?? 0);
  const baselineQuality95 = Number(baselineLane?.qualityCap95?.throughputRps ?? 0);
  const candidateQuality95 = Number(candidateLane?.qualityCap95?.throughputRps ?? 0);

  return {
    id,
    mode: candidateLane?.mode ?? baselineLane?.mode ?? "unknown",
    profile: candidateLane?.profile ?? baselineLane?.profile ?? "unknown",
    peakThroughputRps: diffMetric(baselinePeakThroughput, candidatePeakThroughput),
    peakAcceptedRps: diffMetric(baselinePeakAccepted, candidatePeakAccepted),
    qualityCap90ThroughputRps: diffMetric(baselineQuality90, candidateQuality90),
    qualityCap95ThroughputRps: diffMetric(baselineQuality95, candidateQuality95),
  };
}

function diffMetric(baseline, candidate) {
  const delta = candidate - baseline;
  const deltaPct = baseline > 0 ? (delta / baseline) * 100 : candidate > 0 ? 100 : 0;
  return { baseline, candidate, delta, deltaPct };
}

function indexById(lanes) {
  const byId = {};
  for (const lane of lanes) {
    if (lane?.id) {
      byId[lane.id] = lane;
    }
  }
  return byId;
}

function loadJson(path) {
  const raw = readFileSync(path, "utf8");
  return JSON.parse(raw);
}

function toMarkdown(comparison) {
  const lines = [];
  lines.push("# Throughput Campaign Comparator");
  lines.push("");
  lines.push(`Generated: ${comparison.generatedAt}`);
  lines.push("");
  lines.push(`- baseline: ${comparison.baselineSummaryPath}`);
  lines.push(`- candidate: ${comparison.candidateSummaryPath}`);
  lines.push("");
  lines.push("| Lane | Metric | Baseline | Candidate | Delta | Delta % |");
  lines.push("|---|---:|---:|---:|---:|---:|");
  for (const lane of comparison.lanes) {
    lines.push(row(lane.id, "peak throughput rps", lane.peakThroughputRps));
    lines.push(row(lane.id, "peak accepted rps", lane.peakAcceptedRps));
    lines.push(row(lane.id, "quality cap >=90% rps", lane.qualityCap90ThroughputRps));
    lines.push(row(lane.id, "quality cap >=95% rps", lane.qualityCap95ThroughputRps));
  }
  lines.push("");
  lines.push("## Totals");
  lines.push(`- lanes with improved peak throughput: ${comparison.totals.improvedThroughputLanes}`);
  lines.push(`- lanes with improved peak accepted: ${comparison.totals.improvedAcceptedLanes}`);
  lines.push(`- lanes with improved quality cap >=90%: ${comparison.totals.improvedQuality90Lanes}`);
  lines.push(`- lanes with improved quality cap >=95%: ${comparison.totals.improvedQuality95Lanes}`);
  lines.push("");
  return lines.join("\n");
}

function row(laneId, label, metric) {
  return `| ${laneId} | ${label} | ${metric.baseline.toFixed(2)} | ${metric.candidate.toFixed(2)} | ${formatSigned(metric.delta)} | ${formatPct(metric.deltaPct)} |`;
}

function formatSigned(value) {
  return `${value >= 0 ? "+" : ""}${value.toFixed(2)}`;
}

function formatPct(value) {
  return `${value >= 0 ? "+" : ""}${value.toFixed(2)}%`;
}
