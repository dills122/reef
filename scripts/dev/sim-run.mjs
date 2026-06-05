import { deriveDevUrls, env, loadDotEnv, run, waitForHttp } from "./lib/dev-utils.mjs";

loadDotEnv();
const { runtimeUrl } = deriveDevUrls();
const commandProcessingMode = env("DEV_SIM_COMMAND_PROCESSING_MODE", "");

if (commandProcessingMode) {
  const previous = process.env.EXTERNAL_API_COMMAND_PROCESSING_MODE;
  process.env.EXTERNAL_API_COMMAND_PROCESSING_MODE = commandProcessingMode;
  try {
    await run("docker", ["compose", "-f", "docker-compose.yml", "up", "-d", "platform-runtime"]);
    await waitForHttp(`${runtimeUrl}/health`, 90, 2000);
  } finally {
    if (previous == null) {
      delete process.env.EXTERNAL_API_COMMAND_PROCESSING_MODE;
    } else {
      process.env.EXTERNAL_API_COMMAND_PROCESSING_MODE = previous;
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
