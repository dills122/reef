import { deriveDevUrls, env, loadDotEnv, waitForHttp } from "./lib/dev-utils.mjs";

loadDotEnv();

const { runtimeUrl } = deriveDevUrls();
const profile = env("DEV_OPTIONAL_PRODUCT_PROFILE", "reef");
const waitTimeout = Number(env("DEV_WAIT_TIMEOUT_SECONDS", "90"));
const leaderboardUrl = new URL("/api/v1/arena/leaderboard", runtimeUrl);
leaderboardUrl.searchParams.set("modeId", "smoke");
leaderboardUrl.searchParams.set("scoringPolicyVersion", "v1");

if (!["reef", "arena"].includes(profile)) {
  throw new Error(`DEV_OPTIONAL_PRODUCT_PROFILE must be reef or arena, got ${profile}`);
}

await waitForHttp(`${runtimeUrl}/health`, waitTimeout);
const response = await fetch(leaderboardUrl);

if (profile === "reef") {
  if (response.status !== 404) {
    throw new Error(`Reef-only runtime exposed an Arena route (${response.status}): ${await response.text()}`);
  }
  console.log("Reef-only optional product route smoke passed");
} else {
  if (!response.ok) {
    throw new Error(`Arena-enabled runtime did not expose its leaderboard (${response.status}): ${await response.text()}`);
  }
  console.log("Arena-enabled optional product route smoke passed");
}
