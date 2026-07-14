import { spawn } from "node:child_process";
import { hostedBotContainerArgs } from "./lib/bot-isolation.mjs";

const repoRoot = new URL("../../", import.meta.url).pathname.replace(/\/$/, "");
const testCommand = process.argv.slice(2);
const command = testCommand.length === 0
  ? ["bun", "scripts/dev/bot-sdk-hosted-ses-e2e.test.mjs"]
  : testCommand;

const image = process.env.BOT_SDK_SES_CONTAINER_IMAGE || "oven/bun:1.1.38";
const args = hostedBotContainerArgs({ repoRoot, command, image });

console.log(`running Bot SDK SES container smoke with ${image}`);
const child = spawn("docker", args, { stdio: "inherit" });
child.on("error", (error) => {
  console.error(error.message);
  process.exit(1);
});
child.on("close", (code) => {
  process.exit(code ?? 1);
});
