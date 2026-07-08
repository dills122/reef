import { readFileSync } from "node:fs";
import http from "node:http";
import https from "node:https";
import { env, loadDotEnv } from "./lib/dev-utils.mjs";

loadDotEnv();

// Gate 6 (WORK_PLAN.md "Run short local durable gates before any long soak"):
// "bot/user read-surface claims match /api/v1/data/availability and the
// read-surface inventory in TRADING_MARKET_DATA_BOUNDARIES.md."
//
// This cross-checks the live runtime's `/api/v1/data/availability` surface
// inventory against the documented "Read Surface Inventory" table so a
// surface cannot silently drift (added/removed/renamed/rescoped) without the
// doc catching up, and so the doc cannot claim an "active" surface that the
// live runtime does not actually expose.

const readApiUrl = env(
  "DEV_READ_SURFACE_CHECK_READ_API_URL",
  `http://127.0.0.1:${env("REEF_PLATFORM_PROJECTOR_0_HOST_PORT", "8084")}`,
);
const venueProjectionName = env("DEV_READ_SURFACE_CHECK_VENUE_PROJECTION_NAME", "");
const marketDataProjectionName = env("DEV_READ_SURFACE_CHECK_MARKET_DATA_PROJECTION_NAME", "");
const source = env("DEV_READ_SURFACE_CHECK_SOURCE", "");
const docPath = env("DEV_READ_SURFACE_CHECK_DOC_PATH", "docs/TRADING_MARKET_DATA_BOUNDARIES.md");

const query = new URLSearchParams();
if (venueProjectionName) query.set("venueProjectionName", venueProjectionName);
if (marketDataProjectionName) query.set("marketDataProjectionName", marketDataProjectionName);
if (source) query.set("source", source);
const availabilityUrl = `${readApiUrl}/api/v1/data/availability${query.toString() ? `?${query.toString()}` : ""}`;

const availability = await getJson(availabilityUrl);
const liveSurfaces = Array.isArray(availability.surfaces) ? availability.surfaces : [];
const docRows = parseInventoryTable(readFileSync(docPath, "utf8"));

const failures = [];
const warnings = [];

// Every live surface must be documented as an "active" (or "active but
// conservative") row, with the endpoint, source, and visibility scope
// matching the doc's claim.
for (const surface of liveSurfaces) {
  const docRow = docRows.find((row) => row.endpoint === surface.endpoint);
  if (!docRow) {
    failures.push(`live surface "${surface.name}" (${surface.endpoint}) has no matching row in ${docPath}`);
    continue;
  }
  if (!/^active/i.test(docRow.gateStatus)) {
    failures.push(
      `live surface "${surface.name}" (${surface.endpoint}) is served by the runtime but ${docPath} marks it "${docRow.gateStatus}"`,
    );
  }
  if (normalizeCell(docRow.source) !== normalizeCell(surface.source)) {
    failures.push(
      `source mismatch for "${surface.name}" (${surface.endpoint}): live="${surface.source}" doc="${docRow.source}"`,
    );
  }
  if (normalizeScope(docRow.visibility) !== normalizeScope(surface.scope)) {
    failures.push(
      `visibility scope mismatch for "${surface.name}" (${surface.endpoint}): live="${surface.scope}" doc="${docRow.visibility}"`,
    );
  }
}

// Every doc row documented as "active" for a bot/user `/api/v1/*` surface
// (excluding the availability endpoint itself, which reports on the other
// surfaces rather than being one of them) must actually be present live, so
// the doc cannot claim a surface is active after it has been removed/renamed.
for (const row of docRows) {
  if (!/^active/i.test(row.gateStatus)) continue;
  if (!row.endpoint.startsWith("/api/v1/")) continue;
  if (row.endpoint === "/api/v1/data/availability") continue;
  const liveSurface = liveSurfaces.find((surface) => surface.endpoint === row.endpoint);
  if (!liveSurface) {
    failures.push(`${docPath} claims "${row.endpoint}" is active but the live runtime does not report it in /api/v1/data/availability`);
  }
}

const output = {
  pass: failures.length === 0,
  checkedAt: new Date().toISOString(),
  availabilityUrl,
  liveSurfaceCount: liveSurfaces.length,
  docRowCount: docRows.length,
  failures,
  warnings,
};

console.log(JSON.stringify(output, null, 2));

if (failures.length > 0) {
  process.exitCode = 1;
}

function normalizeCell(value) {
  return String(value ?? "")
    .replace(/`/g, "")
    .trim()
    .toLowerCase();
}

function normalizeScope(value) {
  return normalizeCell(value).replace(/-/g, " ").replace(/\s+/g, " ");
}

function parseInventoryTable(markdown) {
  const lines = markdown.split(/\r?\n/);
  const headerIndex = lines.findIndex((line) => /^\|\s*Endpoint\s*\|/.test(line));
  if (headerIndex < 0) {
    throw new Error(`could not find "Read Surface Inventory" table header in ${docPath}`);
  }
  const rows = [];
  for (let i = headerIndex + 2; i < lines.length; i += 1) {
    const line = lines[i];
    if (!line.startsWith("|")) break;
    const cells = line
      .split("|")
      .slice(1, -1)
      .map((cell) => cell.trim());
    if (cells.length < 8) continue;
    rows.push({
      endpoint: normalizeCell(cells[0]),
      audience: cells[1],
      source: cells[2],
      sourceType: cells[3],
      freshness: cells[4],
      lag: cells[5],
      visibility: cells[6],
      gateStatus: cells[7],
    });
  }
  return rows;
}

async function getJson(url) {
  const body = await getText(url);
  return JSON.parse(body || "{}");
}

function getText(url) {
  return new Promise((resolve, reject) => {
    const parsed = new URL(url);
    const transport = parsed.protocol === "https:" ? https : http;
    const req = transport.request(parsed, { method: "GET", timeout: 5000 }, (res) => {
      let data = "";
      res.setEncoding("utf8");
      res.on("data", (chunk) => {
        data += chunk;
      });
      res.on("end", () => {
        if ((res.statusCode ?? 0) < 200 || (res.statusCode ?? 0) >= 300) {
          reject(new Error(`GET ${url} failed (${res.statusCode}): ${data}`));
          return;
        }
        resolve(data);
      });
    });
    req.on("timeout", () => req.destroy(new Error(`request timeout for ${url}`)));
    req.on("error", reject);
    req.end();
  });
}
