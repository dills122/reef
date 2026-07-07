import { deriveDevUrls, env, loadDotEnv, run, waitForHttp } from "./lib/dev-utils.mjs";
import { composeArgs } from "./lib/compose-utils.mjs";

loadDotEnv();
const { runtimeUrl } = deriveDevUrls();
const commandProcessingMode = env("DEV_SIM_COMMAND_PROCESSING_MODE", "");
const commandLogMode = env(
  "DEV_SIM_COMMAND_LOG_MODE",
  commandProcessingMode && commandProcessingMode !== "sync-result" ? "postgres" : "",
);

if (commandProcessingMode) {
  const previousProcessingMode = process.env.EXTERNAL_API_COMMAND_PROCESSING_MODE;
  const previousCommandLogMode = process.env.EXTERNAL_API_COMMAND_LOG_MODE;
  process.env.EXTERNAL_API_COMMAND_PROCESSING_MODE = commandProcessingMode;
  if (commandLogMode) {
    process.env.EXTERNAL_API_COMMAND_LOG_MODE = commandLogMode;
  }
  try {
    await run("docker", composeArgs(["up", "-d", "platform-api"]));
    await waitForHttp(`${runtimeUrl}/health`, 90, 2000);
  } finally {
    if (previousProcessingMode == null) {
      delete process.env.EXTERNAL_API_COMMAND_PROCESSING_MODE;
    } else {
      process.env.EXTERNAL_API_COMMAND_PROCESSING_MODE = previousProcessingMode;
    }
    if (previousCommandLogMode == null) {
      delete process.env.EXTERNAL_API_COMMAND_LOG_MODE;
    } else {
      process.env.EXTERNAL_API_COMMAND_LOG_MODE = previousCommandLogMode;
    }
  }
}

const extraArgs = process.argv.slice(2);
const hasBaseUrl = extraArgs.includes("--base-url");

const args = ["run", "./cmd/load-tester"];
if (!hasBaseUrl) {
  args.push("--base-url", runtimeUrl);
}
args.push(...extraArgs);

await run("go", args, { cwd: "services/simulator" });
