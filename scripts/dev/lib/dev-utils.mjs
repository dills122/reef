import { spawn } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";

export function env(name, fallback = "") {
  const value = process.env[name];
  return value == null || value === "" ? fallback : value;
}

export function loadDotEnv(paths = [".env", ".env.local"]) {
  for (const path of paths) {
    if (!existsSync(path)) continue;
    const raw = readFileSync(path, "utf8");
    for (const lineRaw of raw.split(/\r?\n/)) {
      const line = lineRaw.trim();
      if (!line || line.startsWith("#")) continue;
      const idx = line.indexOf("=");
      if (idx <= 0) continue;
      const key = line.slice(0, idx).trim();
      let value = line.slice(idx + 1).trim();
      if (
        (value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'"))
      ) {
        value = value.slice(1, -1);
      }
      if (!process.env[key]) {
        process.env[key] = value;
      }
    }
  }
}

export function deriveDevUrls() {
  const runtimeHostPort = env("REEF_PLATFORM_RUNTIME_HOST_PORT", "8080");
  const engineHostPort = env("REEF_MATCHING_ENGINE_HOST_PORT", "8081");
  return {
    runtimeUrl: env("RUNTIME_BASE_URL", `http://127.0.0.1:${runtimeHostPort}`),
    engineUrl: env("ENGINE_BASE_URL", `http://127.0.0.1:${engineHostPort}`),
  };
}

export async function run(cmd, args = [], options = {}) {
  const { cwd, passthrough = true } = options;
  return await new Promise((resolve, reject) => {
    const child = spawn(cmd, args, {
      cwd,
      stdio: passthrough ? "inherit" : "pipe",
      env: process.env,
    });
    child.on("error", reject);
    child.on("close", (code) => {
      if (code === 0) {
        resolve();
        return;
      }
      reject(new Error(`${cmd} ${args.join(" ")} failed with code ${code}`));
    });
  });
}

export async function waitForHttp(url, timeoutSeconds = 90) {
  const started = Date.now();
  const timeoutMs = timeoutSeconds * 1000;
  while (Date.now() - started < timeoutMs) {
    try {
      const res = await fetch(url, { method: "GET" });
      if (res.ok) return;
    } catch {
      // retry
    }
    await sleep(2000);
  }
  throw new Error(`timeout waiting for ${url} after ${timeoutSeconds}s`);
}

export function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
