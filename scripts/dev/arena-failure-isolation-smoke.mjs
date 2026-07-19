import { composeArgs } from "./lib/compose-utils.mjs";
import { deriveDevUrls, env, loadDotEnv, run, sleep, waitForHttp } from "./lib/dev-utils.mjs";

loadDotEnv();

const { runtimeUrl } = deriveDevUrls();
const waitTimeout = Number(env("DEV_WAIT_TIMEOUT_SECONDS", "90"));
const leaderboardUrl = new URL("/api/v1/arena/leaderboard", runtimeUrl);
leaderboardUrl.searchParams.set("modeId", "smoke");
leaderboardUrl.searchParams.set("scoringPolicyVersion", "v1");
const clientHeaders = { "X-Client-Id": "arena-failure-isolation-smoke" };

await waitForHttp(`${runtimeUrl}/health`, waitTimeout);
await requireStatus(200, "Arena route before simulated persistence loss");

let persistenceStopped = false;
try {
  console.log("stopping Arena persistence while Reef remains running...");
  await run("docker", composeArgs(["stop", "arena-postgres"]));
  persistenceStopped = true;
  await waitForHttp(`${runtimeUrl}/health`, waitTimeout);
  await run(env("JS_RUNTIME", "bun"), ["scripts/dev/smoke.mjs"]);

  const unavailable = await arenaResponse();
  if (unavailable?.ok) {
    throw new Error("Arena leaderboard remained successful after Arena persistence stopped");
  }
} finally {
  if (persistenceStopped) {
    console.log("restoring Arena persistence...");
    await run("docker", composeArgs(["up", "-d", "--wait", "arena-postgres"]));
  }
}
await requireStatus(200, "Arena route after persistence recovery");
console.log("Arena failure-isolation smoke passed");

async function requireStatus(expected, description) {
  const started = Date.now();
  let lastStatus = 0;
  while (Date.now() - started < waitTimeout * 1000) {
    const response = await arenaResponse();
    lastStatus = response?.status ?? 0;
    if (response?.status === expected) return;
    await sleep(1000);
  }
  throw new Error(`${description} expected ${expected}, last status was ${lastStatus}`);
}

async function arenaResponse() {
  try {
    return await fetch(leaderboardUrl, { headers: clientHeaders });
  } catch {
    return null;
  }
}
