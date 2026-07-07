import { env, loadDotEnv, run } from "./lib/dev-utils.mjs";
import { composeArgs } from "./lib/compose-utils.mjs";

loadDotEnv();
const profiles = env("DEV_COMPOSE_PROFILES", "");
if (profiles) {
  process.env.COMPOSE_PROFILES = profiles;
}

await run("docker", composeArgs(["down", "--remove-orphans"]));
