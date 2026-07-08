import { spawn } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import http from "node:http";
import https from "node:https";

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
  const runtimeHostPort = env("REEF_PLATFORM_API_HOST_PORT", env("REEF_PLATFORM_RUNTIME_HOST_PORT", "8080"));
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
    let stdout = "";
    let stderr = "";
    if (!passthrough) {
      child.stdout?.on("data", (chunk) => {
        stdout += chunk.toString();
      });
      child.stderr?.on("data", (chunk) => {
        stderr += chunk.toString();
      });
    }
    child.on("error", reject);
    child.on("close", (code) => {
      if (code === 0) {
        resolve({ stdout, stderr });
        return;
      }
      const output = [stderr.trim(), stdout.trim()].filter(Boolean).join("\n");
      reject(new Error(`${cmd} ${args.join(" ")} failed with code ${code}${output ? `\n${output}` : ""}`));
    });
  });
}

export async function waitForHttp(url, timeoutSeconds = 90, requestTimeoutMs = 2000) {
  const started = Date.now();
  const timeoutMs = timeoutSeconds * 1000;
  let lastStatus = null;
  let lastError = "";
  while (Date.now() - started < timeoutMs) {
    const probe = await probeHttp(url, requestTimeoutMs);
    if (probe.ok) {
      return;
    }
    if (probe.status != null) {
      lastStatus = probe.status;
      lastError = "";
    } else if (probe.error) {
      lastError = probe.error;
    }
    await sleep(2000);
  }
  const tail = lastStatus != null ? ` (last status: ${lastStatus})` : (lastError ? ` (last error: ${lastError})` : "");
  throw new Error(`timeout waiting for ${url} after ${timeoutSeconds}s${tail}`);
}

export function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function probeHttp(url, timeoutMs) {
  return new Promise((resolve) => {
    let parsed;
    try {
      parsed = new URL(url);
    } catch (error) {
      resolve({ ok: false, error: error?.message ?? "invalid url" });
      return;
    }
    const transport = parsed.protocol === "https:" ? https : http;
    const req = transport.request(
      parsed,
      { method: "GET", timeout: timeoutMs },
      (res) => {
        res.resume();
        resolve({ ok: res.statusCode >= 200 && res.statusCode < 300, status: res.statusCode ?? 0 });
      },
    );
    req.on("timeout", () => {
      req.destroy(new Error(`request timeout after ${timeoutMs}ms`));
    });
    req.on("error", (error) => {
      resolve({ ok: false, error: error?.message ?? "request error" });
    });
    req.end();
  });
}
