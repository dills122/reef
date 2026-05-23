import { deriveDevUrls, env, loadDotEnv, run } from "./lib/dev-utils.mjs";

loadDotEnv();
const { runtimeUrl } = deriveDevUrls();
const duration = env("DEV_STRESS_DURATION", "30s");
const workers = env("DEV_STRESS_WORKERS", "12");
const traceLimit = env("DEV_STRESS_TRACE_CHECK_LIMIT", "100");
const out = env("DEV_STRESS_REPORT_OUT", "/tmp/reef-load-report-dev-stress.json");

const rates = [100, 200, 300, 400];
console.log(`running stepped stress profile against ${runtimeUrl}`);

for (const rate of rates) {
  console.log(`step rate=${rate} rps`);
  await run(
    "go",
    [
      "run",
      "./cmd/load-tester",
      "--base-url",
      runtimeUrl,
      "--duration",
      duration,
      "--workers",
      workers,
      "--rate",
      String(rate),
      "--mode",
      "strict-lifecycle",
      "--profile-mm-pct",
      "35",
      "--profile-inst-pct",
      "30",
      "--profile-retail-pct",
      "25",
      "--profile-noise-pct",
      "10",
      "--trace-check-limit",
      traceLimit,
      "--pretty-summary",
      "--report-out",
      `${out.replace(/\.json$/, "")}-rate-${rate}.json`,
    ],
    { cwd: "services/simulator" },
  );
}

console.log("stress run complete. reports:");
for (const rate of rates) {
  console.log(`  ${out.replace(/\.json$/, "")}-rate-${rate}.json`);
}
