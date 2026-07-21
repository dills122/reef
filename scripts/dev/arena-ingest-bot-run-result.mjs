import { readFileSync } from "node:fs";
import http from "node:http";
import https from "node:https";

const args = parseArgs(process.argv.slice(2));

if (!args.summary || !args.botId || !args.versionId || !args.scoringPolicyVersion || args.finalEquity === undefined) {
  console.error(
    "usage: node scripts/dev/arena-ingest-bot-run-result.mjs --summary=<summary.json> --bot-id=<botId> --version-id=<versionId> --scoring-policy-version=<version> --scoring-policy-hash=<sha256> --policy-envelope-hash=<sha256> --final-equity=<integer> [--realized-pnl=<integer>] [--max-drawdown=<integer>] [--runtime-url=http://127.0.0.1:8080] [--admin-api-token=<token>] [--dry-run]",
  );
  process.exit(2);
}

const summary = JSON.parse(readFileSync(args.summary, "utf8"));
const payload = {
  actorId: args.actorId ?? "admin-cli",
  correlationId: args.correlationId ?? summary.runId ?? "arena-run-result",
  runId: requiredString(summary, "runId"),
  botId: args.botId,
  versionId: args.versionId,
  scoringPolicyVersion: args.scoringPolicyVersion,
  scoringPolicyHash: requiredHash(args.scoringPolicyHash ?? summary.scoringPolicyHash ?? summary.mode?.scoringPolicyHash, "scoring-policy-hash"),
  policyEnvelopeHash: requiredHash(args.policyEnvelopeHash ?? summary.policyEnvelopeHash, "policy-envelope-hash"),
  finalEquity: integerArg(args.finalEquity, "final-equity"),
  realizedPnl: integerArg(args.realizedPnl ?? 0, "realized-pnl"),
  maxDrawdown: integerArg(args.maxDrawdown ?? 0, "max-drawdown"),
  actionsProposed: integerValue(summary.actionsProposed ?? 0, "summary.actionsProposed"),
  orderActionsProposed: integerValue(summary.orderActionsProposed ?? 0, "summary.orderActionsProposed"),
  dataCalls: integerValue(summary.dataCalls ?? 0, "summary.dataCalls"),
  signalsGenerated: integerValue(summary.signalsGenerated ?? 0, "summary.signalsGenerated"),
  disqualified: booleanArg(args.disqualified ?? (summary.approvalStatus === "do_not_merge")),
};

if (args.dryRun) {
  console.log(JSON.stringify(payload, null, 2));
  process.exit(0);
}

const runtimeUrl = (args.runtimeUrl ?? "http://127.0.0.1:8080").replace(/\/$/, "");
const adminApiToken = args.adminApiToken ?? process.env.ARENA_ADMIN_API_TOKEN ?? "";
const response = await requestJson(`${runtimeUrl}/admin/v1/arena/run-bot-results`, "POST", payload);
if (!response.ok) {
  console.error(response.text);
  process.exit(1);
}
console.log(response.text);

function parseArgs(argv) {
  const out = {};
  for (const arg of argv) {
    if (arg === "--dry-run") {
      out.dryRun = true;
      continue;
    }
    const match = arg.match(/^--([^=]+)=(.*)$/);
    if (!match) {
      throw new Error(`invalid argument: ${arg}`);
    }
    out[toCamel(match[1])] = match[2];
  }
  return out;
}

function toCamel(value) {
  return value.replace(/-([a-z])/g, (_, ch) => ch.toUpperCase());
}

function requiredString(source, key) {
  const value = source[key];
  if (typeof value !== "string" || value.length === 0) {
    throw new Error(`${key} is required in hosted summary`);
  }
  return value;
}

function integerArg(value, name) {
  return integerValue(Number(value), name);
}

function requiredHash(value, name) {
  if (typeof value !== "string" || !/^sha256:[a-f0-9]{64}$/.test(value)) {
    throw new Error(`${name} must be a canonical sha256 digest`);
  }
  return value;
}

function integerValue(value, name) {
  if (!Number.isInteger(value)) {
    throw new Error(`${name} must be an integer`);
  }
  return value;
}

function booleanArg(value) {
  if (typeof value === "boolean") return value;
  return String(value).toLowerCase() === "true";
}

function requestJson(url, method, payload = undefined) {
  const parsed = new URL(url);
  const transport = parsed.protocol === "https:" ? https : http;
  const body = payload === undefined ? undefined : JSON.stringify(payload);
  return new Promise((resolve, reject) => {
    const request = transport.request(parsed, {
      method,
      headers: {
        "content-type": "application/json",
        ...(adminApiToken.trim() !== "" ? { Authorization: `Bearer ${adminApiToken}` } : {}),
        "X-Reef-Actor-Id": payload.actorId,
        "X-Correlation-Id": payload.correlationId,
        ...(body === undefined ? {} : { "content-length": Buffer.byteLength(body) }),
      },
    }, (response) => {
      let text = "";
      response.setEncoding("utf8");
      response.on("data", (chunk) => { text += chunk; });
      response.on("end", () => {
        resolve({ ok: response.statusCode >= 200 && response.statusCode < 300, status: response.statusCode, text });
      });
    });
    request.on("error", reject);
    if (body !== undefined) request.write(body);
    request.end();
  });
}
