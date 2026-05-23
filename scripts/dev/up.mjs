import { env, loadDotEnv, run } from "./lib/dev-utils.mjs";

loadDotEnv();
const profiles = env("DEV_COMPOSE_PROFILES", "");
const waitTimeoutSeconds = env("DEV_COMPOSE_WAIT_TIMEOUT_SECONDS", "300");
if (profiles) {
  process.env.COMPOSE_PROFILES = profiles;
}
await run("docker", ["compose", "up", "-d", "--build", "--wait", "--wait-timeout", waitTimeoutSeconds]);
