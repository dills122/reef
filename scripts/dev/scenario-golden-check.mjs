import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { compareGoldenJsonFiles, writeDiffText } from "./lib/scenario-golden.mjs";
import { env, loadDotEnv, run } from "./lib/dev-utils.mjs";

loadDotEnv();
process.env.GOCACHE ||= "/tmp/reef-go-build-cache";

const args = parseArgs(process.argv.slice(2));
const artifactDir = resolve(args["artifact-dir"] ?? env("DEV_SCENARIO_GOLDEN_ARTIFACT_DIR", "/tmp/reef-scenario-golden"));
const update = booleanArg(args.update ?? env("DEV_SCENARIO_GOLDEN_UPDATE", ""));
const maxFailures = Number(args["max-failures"] ?? env("DEV_SCENARIO_GOLDEN_MAX_FAILURES", "50"));
mkdirSync(artifactDir, { recursive: true });

const checks = [];
for (const scenario of goldenScenarios()) {
  const actualPath = resolve(artifactDir, `${scenario.name}.actual.json`);
  const diffPath = resolve(artifactDir, `${scenario.name}.diff.txt`);
  await runScenarioSmoke(scenario, actualPath);

  const expectedPath = resolve(scenario.goldenPath);
  if (update) {
    writeFileSync(expectedPath, readFileSync(actualPath));
    checks.push({ ...scenario, goldenPath: expectedPath, actualPath, diffPath: "", pass: true, updated: true, failures: [] });
    continue;
  }

  const check = compareGoldenJsonFiles(expectedPath, actualPath, { maxFailures });
  if (!check.pass) {
    writeDiffText(diffPath, check);
  }
  checks.push({ ...scenario, goldenPath: expectedPath, actualPath, diffPath: check.pass ? "" : diffPath, pass: check.pass, failures: check.failures });
}

const report = {
  pass: checks.every((check) => check.pass),
  checkedAt: new Date().toISOString(),
  artifactDir,
  update,
  checks,
};
const reportPath = resolve(artifactDir, "scenario-golden.check.json");
writeFileSync(reportPath, JSON.stringify(report, null, 2));

for (const check of checks) {
  if (check.updated) {
    console.log(`updated golden: ${check.goldenPath}`);
  } else if (check.pass) {
    console.log(`golden check passed: ${check.name}`);
  } else {
    console.error(`golden check failed: ${check.name}`);
    for (const failure of check.failures) console.error(`  - ${failure}`);
    console.error(`  diff: ${check.diffPath}`);
  }
}
console.log(`check: ${reportPath}`);
if (!report.pass) {
  process.exitCode = 1;
}

function goldenScenarios() {
  return [
    {
      name: "p1-golden-hidden-cross",
      scenarioPath: "../../packages/scenario-definitions/scenarios/v1/P1_GOLDEN_HIDDEN_CROSS_T1.yaml",
      scenarioRunId: "p1-golden-hidden-cross-golden",
      start: "2026-03-14T18:00:00Z",
      goldenPath: "services/simulator/replay/golden/p1-golden-hidden-cross.smoke.json",
    },
    {
      name: "p2-settlement-break-repair",
      scenarioPath: "../../packages/scenario-definitions/scenarios/v1/P2_SETTLEMENT_BREAK_REPAIR.yaml",
      scenarioRunId: "p2-settlement-break-repair-golden",
      start: "2026-03-14T18:00:00Z",
      goldenPath: "services/simulator/replay/golden/p2-settlement-break-repair.smoke.json",
    },
  ];
}

async function runScenarioSmoke(scenario, reportOut) {
  await run(
    "go",
    [
      "run",
      "./cmd/scenario-smoke",
      "--scenario",
      scenario.scenarioPath,
      "--scenario-run-id",
      scenario.scenarioRunId,
      "--start",
      scenario.start,
      "--pretty",
      "--report-out",
      reportOut,
    ],
    { cwd: "services/simulator", passthrough: false },
  );
}

function parseArgs(argv) {
  const out = {};
  for (let index = 0; index < argv.length; index++) {
    const arg = argv[index];
    if (!arg.startsWith("--")) {
      throw new Error(`unexpected positional argument: ${arg}`);
    }
    const keyValue = arg.slice(2);
    const [key, inlineValue] = keyValue.split("=", 2);
    if (inlineValue != null) {
      out[key] = inlineValue;
      continue;
    }
    const next = argv[index + 1];
    if (!next || next.startsWith("--")) {
      out[key] = "true";
      continue;
    }
    out[key] = next;
    index++;
  }
  return out;
}

function booleanArg(value) {
  return ["1", "true", "yes"].includes(String(value).toLowerCase());
}
