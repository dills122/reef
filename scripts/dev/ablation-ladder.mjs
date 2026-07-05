import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

import { env, loadDotEnv, run } from "./lib/dev-utils.mjs";

loadDotEnv();

const jsRuntime = env("JS_RUNTIME", "bun");
const duration = env("DEV_ABLATION_DURATION", "180s");
const rate = env("DEV_ABLATION_RATE", "10000");
const workers = env("DEV_ABLATION_WORKERS", "384");
const artifactRoot = env("DEV_ABLATION_ARTIFACT_DIR", "/tmp/reef-ablation-ladder");
mkdirSync(artifactRoot, { recursive: true });

const rungs = [
  {
    id: "canonical-append-only",
    label: "canonical batch append + command lookup rows only, no projections",
    note: "covers user-spec rungs 1 and 2: runtime.runtime_materialize_venue_event_batch() writes the venue-event-batch header and the canonical_command_outcomes lookup row in one atomic transaction, so there is no intermediate state between \"batch append only\" and \"+ command lookup rows\" to isolate in this architecture.",
    rungEnv: {
      STREAM_ACK_PROJECTOR_ENABLED: "false",
      DEV_STRESS_CAPTURE_STREAM_ACK_PROJECTOR: "0",
    },
  },
  {
    id: "lightweight-outcome-projection",
    label: "canonical + lightweight outcome projection (submit_results/orders/lifecycle event, no fills)",
    note: "user-spec rung 3.",
    rungEnv: {
      STREAM_ACK_PROJECTOR_ENABLED: "true",
      STREAM_ACK_PROJECTION_SOURCE: "venue-event-batch",
      STREAM_ACK_PROJECTOR_INCLUDE_FILLS: "false",
      DEV_STRESS_CAPTURE_STREAM_ACK_PROJECTOR: "1",
    },
  },
  {
    id: "full-order-execution-trade-projection",
    label: "canonical + full order/execution/trade projection",
    note: "covers user-spec rungs 4 and 5: this is currently also the full deployed profile, since no separate leaderboard/UI/report projector exists yet beyond the normalized order/execution/trade tables.",
    rungEnv: {
      STREAM_ACK_PROJECTOR_ENABLED: "true",
      STREAM_ACK_PROJECTION_SOURCE: "venue-event-batch",
      STREAM_ACK_PROJECTOR_INCLUDE_FILLS: "true",
      DEV_STRESS_CAPTURE_STREAM_ACK_PROJECTOR: "1",
    },
  },
];

console.log("ablation ladder: same workload/duration across projection-depth rungs");
console.log(`  rate=${rate} rps, workers=${workers}, duration=${duration}`);
console.log(`  artifactRoot=${artifactRoot}`);
for (const rung of rungs) {
  console.log(`  - ${rung.id}: ${rung.label}`);
  if (rung.note) console.log(`      note: ${rung.note}`);
}

const results = [];
for (const rung of rungs) {
  console.log(`\n=== ablation rung: ${rung.id} ===`);
  console.log(`  ${rung.label}`);

  console.log("  resetting stack (fresh Postgres/canonical state) for a clean baseline...");
  await run("docker", ["compose", "down", "--volumes", "--remove-orphans"]);

  for (const [key, value] of Object.entries(rung.rungEnv)) {
    process.env[key] = value;
  }

  const rungArtifactDir = join(artifactRoot, rung.id);
  mkdirSync(rungArtifactDir, { recursive: true });
  const reportBase = join(rungArtifactDir, rung.id);
  const reportOut = `${reportBase}-rate-${rate}-workers-${workers}.json`;
  const diagnosticsSummaryOut = `${reportBase}-diagnostics-summary.json`;
  process.env.DEV_STRESS_REPORT_OUT = `${reportBase}.json`;
  process.env.DEV_STRESS_ARTIFACT_DIR = rungArtifactDir;
  process.env.DEV_STRESS_RATES = rate;
  process.env.DEV_STRESS_SWEEP_WORKERS = workers;
  process.env.DEV_STRESS_DURATION = duration;
  process.env.DEV_STRESS_SCENARIO_ID = `ablation:${rung.id}`;
  delete process.env.DEV_STRESS_SESSION_CONFIG;

  let error = null;
  try {
    await run(jsRuntime, ["scripts/dev/venue-event-materializer-stress.mjs"]);
  } catch (caught) {
    error = String(caught?.message ?? caught);
    console.error(`  rung ${rung.id} failed: ${error}`);
  }

  results.push({
    rung: rung.id,
    label: rung.label,
    note: rung.note,
    error,
    report: readReport(reportOut),
    diagnostics: readReport(diagnosticsSummaryOut),
  });
}

const comparison = buildComparison(results);
const comparisonOutJson = join(artifactRoot, "ablation-ladder-comparison.json");
const comparisonOutMd = join(artifactRoot, "ablation-ladder-comparison.md");
writeFileSync(comparisonOutJson, JSON.stringify(comparison, null, 2));
writeFileSync(comparisonOutMd, toMarkdown(comparison));

console.log("\nablation ladder complete:");
console.log(`  ${comparisonOutJson}`);
console.log(`  ${comparisonOutMd}`);
console.log(toMarkdown(comparison));

function readReport(path) {
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch (caught) {
    return { error: String(caught?.message ?? caught) };
  }
}

function buildComparison(rungResults) {
  return {
    generatedAt: new Date().toISOString(),
    config: { rate, workers, duration },
    rungs: rungResults.map(({ rung, label, note, error, report, diagnostics }) => {
      const unit = report?.unitMetrics ?? {};
      const materializer = report?.venueEventMaterializer?.delta ?? {};
      const postgresDiagnostics = diagnostics?.services?.postgres?.unitMetrics ?? {};
      return {
        rung,
        label,
        note,
        error: error ?? report?.error ?? null,
        attemptedCommandsPerSecond: unit.attemptedCommandsPerSecond ?? null,
        acceptedCommandsPerSecond: unit.acceptedCommandsPerSecond ?? null,
        durableCanonicalCompletedPerSecond: unit.durableCanonicalCompletedPerSecond ?? null,
        durableCanonicalCompletionGap: unit.durableCanonicalCompletionGap ?? null,
        projectedWorkItemsPerSecond: unit.projectedWorkItemsPerSecond ?? null,
        projectionLagAfter: unit.projectionLagAfter ?? null,
        materializerFailedDelta: materializer.failedDelta ?? null,
        materializerAckFailedDelta: materializer.ackFailedDelta ?? null,
        p95LatencyMs: report?.latencyMs?.p95 ?? null,
        p99LatencyMs: report?.latencyMs?.p99 ?? null,
        walBytesPerAcceptedCommand: postgresDiagnostics.walBytesPerAcceptedCommand ?? null,
        commitsPerAcceptedCommand: postgresDiagnostics.commitsPerAcceptedCommand ?? null,
      };
    }),
  };
}

function toMarkdown(comparison) {
  const header = "| Rung | Accepted/sec | Durable-completed/sec | Completion gap | Projected/sec | Projection lag | p95 (ms) | WAL bytes/cmd | Commits/cmd |";
  const divider = "|---|---:|---:|---:|---:|---:|---:|---:|---:|";
  const rows = comparison.rungs.map((rung) => {
    return `| ${rung.rung}${rung.error ? " (FAILED)" : ""} | ${fmt(rung.acceptedCommandsPerSecond)} | ${fmt(rung.durableCanonicalCompletedPerSecond)} | ${fmt(rung.durableCanonicalCompletionGap)} | ${fmt(rung.projectedWorkItemsPerSecond)} | ${fmt(rung.projectionLagAfter)} | ${fmt(rung.p95LatencyMs)} | ${fmt(rung.walBytesPerAcceptedCommand)} | ${fmt(rung.commitsPerAcceptedCommand)} |`;
  });
  const notes = comparison.rungs
    .filter((rung) => rung.note)
    .map((rung) => `- **${rung.rung}**: ${rung.note}`)
    .join("\n");
  return [
    `# Ablation Ladder Comparison`,
    ``,
    `Config: rate=${comparison.config.rate} rps, workers=${comparison.config.workers}, duration=${comparison.config.duration}`,
    ``,
    header,
    divider,
    ...rows,
    ``,
    notes,
    ``,
  ].join("\n");
}

function fmt(value) {
  if (value === null || value === undefined) return "n/a";
  if (typeof value === "number") return value.toFixed(2);
  return String(value);
}
