#!/usr/bin/env node
import http from "node:http";
import https from "node:https";
import { deriveDevUrls, env, loadDotEnv, waitForHttp } from "./lib/dev-utils.mjs";

loadDotEnv();

const { runtimeUrl } = deriveDevUrls();
const arenaAdminUrl = env("ARENA_ADMIN_WEB_URL", "");
const waitTimeout = Number(env("DEV_WAIT_TIMEOUT_SECONDS", "90"));

for (const key of ["PUBLIC_ARENA_LOCAL_DEV_FAKE_ADMIN", "PUBLIC_ARENA_LOCAL_DEV_FIXTURES"]) {
  if (truthy(env(key, ""))) {
    throw new Error(`${key}=true is fixture mode; disable it for live admin auth smoke`);
  }
}
if (truthy(env("LOCAL_DEV_ADMIN_AUTH_BYPASS", ""))) {
  throw new Error("LOCAL_DEV_ADMIN_AUTH_BYPASS=true is bypass mode; disable it for live admin auth smoke");
}

console.log(`waiting for platform-api health at ${runtimeUrl}...`);
await waitForHttp(`${runtimeUrl}/health`, waitTimeout);

const session = await request(`${runtimeUrl}/admin/auth/session`);
if (session.status === 200 && session.json?.authProvider === "local-dev") {
  throw new Error("/admin/auth/session returned local-dev auth; live auth smoke requires real Admin DB auth");
}
if (session.status === 503) {
  throw new Error(`/admin/auth/session returned 503: ${session.body}`);
}
if (session.status !== 401) {
  throw new Error(`unauthenticated /admin/auth/session expected 401, got ${session.status}: ${session.body}`);
}

const start = await request(`${runtimeUrl}/admin/auth/github/start?redirectPath=/admin`, { followRedirects: false });
if (start.status !== 302 || !String(start.headers.location ?? "").startsWith("https://github.com/login/oauth/authorize")) {
  throw new Error(`OAuth start expected 302 to GitHub, got ${start.status} ${start.headers.location ?? ""}`);
}

const invalidCallback = await request(
  `${runtimeUrl}/admin/auth/github/callback?code=invalid-smoke&state=invalid-smoke`,
  { followRedirects: false },
);
if (invalidCallback.status !== 401) {
  throw new Error(`invalid OAuth callback expected 401, got ${invalidCallback.status}: ${invalidCallback.body}`);
}

const adminApiToken = env("ADMIN_API_TOKEN", "").trim();
if (adminApiToken) {
  const roles = await request(`${runtimeUrl}/admin/v1/access/roles`, {
    headers: { Authorization: `Bearer ${adminApiToken}` },
  });
  if (roles.status !== 200 || !roles.body.includes('"roleId":"operator"')) {
    throw new Error(`admin access roles check failed (${roles.status}): ${roles.body}`);
  }
  console.log("admin access roles ok");
} else {
  console.log("skipping /admin/v1/access/roles token check; ADMIN_API_TOKEN is not set");
}

if (arenaAdminUrl.trim()) {
  const accessPage = await request(`${arenaAdminUrl.replace(/\/$/, "")}/admin/access`);
  if (accessPage.status !== 200 || !accessPage.body.includes("checking session")) {
    throw new Error(`arena-admin /admin/access shell check failed (${accessPage.status})`);
  }
  console.log("arena-admin /admin/access shell ok");
}

console.log("local admin auth smoke passed");
console.log("unauthenticated session 401 ok");
console.log("OAuth start 302 to GitHub ok");
console.log("invalid OAuth callback 401 ok");

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
            json: parseJson(body),
          });
        });
      },
    );
    req.on("timeout", () => {
      req.destroy(new Error(`request timeout: ${url}`));
    });
    req.on("error", reject);
    if (options.body) req.write(options.body);
    req.end();
  });
}

function parseJson(body) {
  try {
    return JSON.parse(body);
  } catch (_error) {
    return null;
  }
}

function truthy(value) {
  return ["1", "true", "yes", "on"].includes(String(value).trim().toLowerCase());
}
