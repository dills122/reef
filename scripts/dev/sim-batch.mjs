import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { aggregateReports } from "./lib/report-taxonomy.mjs";
import { deriveDevUrls, env, loadDotEnv, run } from "./lib/dev-utils.mjs";

loadDotEnv();
const { runtimeUrl } = deriveDevUrls();

const seeds = parseSeeds(env("DEV_SIM_BATCH_SEEDS", "101,202,303"));
const artifactDir = resolve(env("DEV_SIM_BATCH_ARTIFACT_DIR", "/tmp/reef-sim-batch"));
const aggregateOut = resolve(env("DEV_SIM_BATCH_REPORT_OUT", `${artifactDir}/aggregate.json`));
const extraArgs = process.argv.slice(2);
const hasBaseUrl = extraArgs.includes("--base-url");
const hasReportOut = extraArgs.includes("--report-out");
const hasSeed = extraArgs.includes("--seed");

if (hasReportOut) {
  throw new Error("do not pass --report-out to sim-batch; set DEV_SIM_BATCH_ARTIFACT_DIR or DEV_SIM_BATCH_REPORT_OUT");
}
if (hasSeed) {
  throw new Error("do not pass --seed to sim-batch; set DEV_SIM_BATCH_SEEDS");
}

mkdirSync(artifactDir, { recursive: true });

const reports = [];
for (const seed of seeds) {
  const reportOut = resolve(artifactDir, `seed-${seed}.report.json`);
  const args = ["run", "./cmd/load-tester"];
  if (!hasBaseUrl) {
    args.push("--base-url", runtimeUrl);
  }
  args.push("--seed", String(seed), "--report-out", reportOut, ...extraArgs);
  console.log(`running simulator seed ${seed}`);
  await run("go", args, { cwd: "services/simulator" });
  reports.push({ path: reportOut, data: JSON.parse(readFileSync(reportOut, "utf8")) });
}

const aggregate = {
  generatedAt: new Date().toISOString(),
  artifactDir,
  seeds,
  ...aggregateReports(reports),
};
writeFileSync(aggregateOut, JSON.stringify(aggregate, null, 2));
console.log(`aggregate: ${aggregateOut}`);

function parseSeeds(raw) {
  const out = raw
    .split(",")
    .map((value) => Number(value.trim()))
    .filter((value) => Number.isInteger(value) && value !== 0);
  if (out.length === 0) {
    throw new Error("DEV_SIM_BATCH_SEEDS must contain at least one non-zero integer seed");
  }
  return out;
}
