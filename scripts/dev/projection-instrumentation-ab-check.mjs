import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { buildInstrumentationPerturbationComparison } from "./lib/instrumentation-perturbation.mjs";

const [controlTarget, instrumentedTarget, outputTarget] = process.argv.slice(2);
if (!controlTarget || !instrumentedTarget) {
  console.error(
    "usage: node scripts/dev/projection-instrumentation-ab-check.mjs <control-report-or-dir> <instrumented-report-or-dir> [output.json]",
  );
  process.exit(2);
}

const controlReports = loadReports(controlTarget);
const instrumentedReports = loadReports(instrumentedTarget);
const comparison = buildInstrumentationPerturbationComparison({
  controlReports,
  instrumentedReports,
  limitPercent: Number(process.env.REEF_PROJECTION_INSTRUMENTATION_MAX_PERTURBATION_PCT ?? 1),
  minimumSamples: Number(process.env.REEF_PROJECTION_INSTRUMENTATION_MIN_SAMPLES ?? 3),
});
const rendered = `${JSON.stringify(comparison, null, 2)}\n`;
if (outputTarget) writeFileSync(resolve(outputTarget), rendered);
process.stdout.write(rendered);
process.exit(comparison.pass ? 0 : 1);

function loadReports(target) {
  const path = resolve(target);
  if (!existsSync(path)) throw new Error(`report target does not exist: ${path}`);
  const files = statSync(path).isDirectory() ? walk(path).filter((candidate) => candidate.endsWith(".json")) : [path];
  return files
    .map((candidate) => JSON.parse(readFileSync(candidate, "utf8")))
    .filter((report) => report?.config && Number(report?.durationSeconds ?? 0) > 0);
}

function walk(directory) {
  return readdirSync(directory).flatMap((entry) => {
    const path = join(directory, entry);
    return statSync(path).isDirectory() ? walk(path) : [path];
  });
}
