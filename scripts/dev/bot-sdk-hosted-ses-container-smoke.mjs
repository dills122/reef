import { spawn } from "node:child_process";

const repoRoot = new URL("../../", import.meta.url).pathname.replace(/\/$/, "");
const image = process.env.BOT_SDK_SES_CONTAINER_IMAGE || "oven/bun:1.1.38";
const testCommand = process.argv.slice(2);
const command = testCommand.length === 0
  ? ["bun", "scripts/dev/bot-sdk-hosted-ses-e2e.test.mjs"]
  : testCommand;

const args = [
  "run",
  "--rm",
  "--network=none",
  "--cpus=1",
  "--memory=256m",
  "--pids-limit=64",
  "--read-only",
  "--tmpfs",
  "/tmp:rw,nosuid,nodev,noexec,size=64m",
  "-e",
  "HOME=/tmp",
  "-e",
  "BUN_INSTALL_CACHE_DIR=/tmp/bun-cache",
  "-v",
  `${repoRoot}:/workspace:ro`,
  "-w",
  "/workspace",
  image,
  ...command,
];

console.log(`running Bot SDK SES container smoke with ${image}`);
const child = spawn("docker", args, { stdio: "inherit" });
child.on("error", (error) => {
  console.error(error.message);
  process.exit(1);
});
child.on("close", (code) => {
  process.exit(code ?? 1);
});
