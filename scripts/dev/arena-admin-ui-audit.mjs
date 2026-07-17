#!/usr/bin/env node
import http from "node:http";
import https from "node:https";
import { deriveDevUrls, env, loadDotEnv, waitForHttp } from "./lib/dev-utils.mjs";

loadDotEnv();

const { runtimeUrl } = deriveDevUrls();
const adminUrl = env("ARENA_ADMIN_WEB_URL", env("LOCAL_DEV_ADMIN_UI_BASE_URL", "http://localhost:5174")).replace(/\/$/, "");
const waitTimeout = Number(env("DEV_WAIT_TIMEOUT_SECONDS", "90"));
const requireBrowser = truthy(env("ARENA_ADMIN_UI_AUDIT_REQUIRE_BROWSER", ""));

const shellRoutes = [
  { path: "/", expected: ["bot arena"] },
  { path: "/game-types", expected: ["game types"] },
  { path: "/leaderboard", expected: ["leaderboard"] },
  { path: "/bot-admin", expected: ["bot arena"] },
  { path: "/admin", expected: ["bot arena"] },
  { path: "/admin/access", expected: ["bot arena"] },
];

console.log(`waiting for platform-api health at ${runtimeUrl}...`);
await waitForHttp(`${runtimeUrl}/health`, waitTimeout);
console.log(`waiting for arena-admin shell at ${adminUrl}...`);
await waitForHttp(`${adminUrl}/`, waitTimeout);

await auditHttpShells();
await auditLeaderboardCors();
await auditBrowserIfAvailable();

console.log("arena-admin UI audit passed");

async function auditHttpShells() {
  for (const route of shellRoutes) {
    const response = await request(`${adminUrl}${route.path}`);
    assertStatus(response, 200, `${adminUrl}${route.path}`);
    for (const expected of route.expected) {
      if (!response.body.toLowerCase().includes(expected.toLowerCase())) {
        throw new Error(`${route.path} shell missing expected text: ${expected}`);
      }
    }
  }
  console.log(`arena-admin shell routes ok (${shellRoutes.length})`);
}

async function auditLeaderboardCors() {
  const url = `${runtimeUrl}/api/v1/arena/leaderboard?modeId=weekly-major&scoringPolicyVersion=score-v1&limit=50`;
  const preflight = await request(url, {
    method: "OPTIONS",
    headers: {
      Origin: adminUrl,
      "Access-Control-Request-Method": "GET",
      "Access-Control-Request-Headers": "x-client-id",
    },
  });
  assertStatus(preflight, 204, "leaderboard CORS preflight");
  if (preflight.headers["access-control-allow-origin"] !== adminUrl) {
    throw new Error(
      `leaderboard CORS preflight did not allow ${adminUrl}: ${preflight.headers["access-control-allow-origin"] ?? ""}`,
    );
  }
  if (!String(preflight.headers["access-control-allow-headers"] ?? "").toLowerCase().includes("x-client-id")) {
    throw new Error("leaderboard CORS preflight did not allow x-client-id");
  }

  const read = await request(url, {
    headers: {
      Origin: adminUrl,
      "X-Client-Id": "arena-admin-ui-audit",
    },
  });
  assertStatus(read, 200, "leaderboard public read");
  if (!read.body.includes('"entries"')) {
    throw new Error(`leaderboard public read missing entries payload: ${read.body}`);
  }
  console.log("leaderboard CORS and public read ok");
}

async function auditBrowserIfAvailable() {
  let playwright;
  try {
    playwright = await import("playwright");
  } catch (_error) {
    if (requireBrowser) {
      throw new Error("ARENA_ADMIN_UI_AUDIT_REQUIRE_BROWSER=true but playwright is not installed");
    }
    console.log("browser audit skipped (playwright not installed)");
    return;
  }

  let browser;
  try {
    browser = await playwright.chromium.launch({ headless: true });
  } catch (error) {
    if (requireBrowser) {
      throw error;
    }
    console.log(`browser audit skipped (${error.message})`);
    return;
  }
  const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
  const page = await context.newPage();
  const consoleErrors = [];
  page.on("console", (message) => {
    if (["error", "warning"].includes(message.type())) {
      consoleErrors.push(`${message.type()}: ${message.text()}`);
    }
  });
  page.on("pageerror", (error) => {
    consoleErrors.push(`pageerror: ${error.message}`);
  });

  try {
    for (const route of shellRoutes) {
      await page.goto(`${adminUrl}${route.path}`, { waitUntil: "domcontentloaded" });
      await page.waitForTimeout(250);
      const state = await page.evaluate(() => ({
        text: document.body.innerText,
        overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
      }));
      if (state.overflow) {
        throw new Error(`${route.path} has horizontal overflow at 1280px`);
      }
      if (route.path === "/leaderboard" && state.text.includes("Failed to fetch")) {
        throw new Error("/leaderboard rendered Failed to fetch");
      }
    }

    await page.setViewportSize({ width: 390, height: 844 });
    for (const route of ["/", "/bot-admin", "/admin", "/admin/access"]) {
      await page.goto(`${adminUrl}${route}`, { waitUntil: "domcontentloaded" });
      await page.waitForTimeout(250);
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
      );
      if (overflow) {
        throw new Error(`${route} has horizontal overflow at 390px`);
      }
    }

    if (consoleErrors.length > 0) {
      throw new Error(`browser console errors:\n${consoleErrors.join("\n")}`);
    }
    console.log("browser route audit ok");
  } finally {
    await browser.close();
  }
}

async function request(url, options = {}) {
  const parsed = new URL(url);
  const transport = parsed.protocol === "https:" ? https : http;
  return await new Promise((resolve, reject) => {
    const req = transport.request(
      parsed,
      {
        method: options.method ?? "GET",
        headers: options.headers ?? {},
        timeout: 5000,
      },
      (res) => {
        let body = "";
        res.setEncoding("utf8");
        res.on("data", (chunk) => {
          body += chunk;
        });
        res.on("end", () => {
          resolve({
            status: res.statusCode ?? 0,
            headers: res.headers,
            body,
          });
        });
      },
    );
    req.on("timeout", () => {
      req.destroy(new Error(`request timeout: ${url}`));
    });
    req.on("error", reject);
    req.end();
  });
}

function assertStatus(response, expected, label) {
  if (response.status !== expected) {
    throw new Error(`${label} expected ${expected}, got ${response.status}: ${response.body}`);
  }
}

function truthy(value) {
  return ["1", "true", "yes", "on"].includes(String(value).trim().toLowerCase());
}
