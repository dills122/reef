import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { buildDriftBaseline, evaluateReportDrift } from "./lib/scenario-drift.mjs";
import { parseKeyValueArgs } from "./lib/cli-args.mjs";
import { env, loadDotEnv } from "./lib/dev-utils.mjs";

loadDotEnv();

const args = parseKeyValueArgs(process.argv.slice(2));
const baselinePath = args.baseline ?? env("DEV_SCENARIO_DRIFT_BASELINE", "");
const reportPath = args.report ?? env("DEV_SCENARIO_DRIFT_REPORT", "");
const outPath = args.out ?? env("DEV_SCENARIO_DRIFT_OUT", "");
const writeBaselinePath = args["write-baseline"] ?? env("DEV_SCENARIO_DRIFT_WRITE_BASELINE", "");

if (!reportPath) {
  throw new Error("missing --report or DEV_SCENARIO_DRIFT_REPORT");
}

const report = JSON.parse(readFileSync(reportPath, "utf8"));

if (writeBaselinePath) {
  const baseline = buildDriftBaseline(report, { reportPath: resolve(reportPath) });
  writeFileSync(writeBaselinePath, JSON.stringify(baseline, null, 2));
  console.log(`baseline: ${writeBaselinePath}`);
  process.exit(0);
}

if (!baselinePath) {
  throw new Error("missing --baseline or DEV_SCENARIO_DRIFT_BASELINE");
}

const baseline = JSON.parse(readFileSync(baselinePath, "utf8"));
const check = evaluateReportDrift(report, baseline, {
  baselinePath: resolve(baselinePath),
  reportPath: resolve(reportPath),
});

if (outPath) {
  writeFileSync(outPath, JSON.stringify(check, null, 2));
}

for (const warning of check.warnings ?? []) {
  console.warn(`scenario drift check warning: ${warning}`);
}
if (!check.pass) {
  console.error("scenario drift check failed");
  for (const failure of check.failures) {
    console.error(`  - ${failure}`);
  }
  process.exitCode = 1;
} else {
  console.log("scenario drift check passed");
}
if (outPath) {
  console.log(`check: ${outPath}`);
}
