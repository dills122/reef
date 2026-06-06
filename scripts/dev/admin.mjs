import { deriveDevUrls, loadDotEnv } from "./lib/dev-utils.mjs";

loadDotEnv();
const { runtimeUrl } = deriveDevUrls();
const internalRouteHeaders = { "X-Reef-Internal-Route": "true" };

async function post(path, payload) {
  const res = await fetch(`${runtimeUrl}${path}`, {
    method: "POST",
    headers: { "content-type": "application/json", ...internalRouteHeaders },
    body: JSON.stringify(payload),
  });
  const text = await res.text();
  if (!res.ok) {
    throw new Error(`${path} failed (${res.status}): ${text}`);
  }
  console.log(text);
}

async function get(path) {
  const res = await fetch(`${runtimeUrl}${path}`);
  const text = await res.text();
  if (!res.ok) {
    throw new Error(`${path} failed (${res.status}): ${text}`);
  }
  console.log(text);
}

function usage() {
  console.log(`admin api commands:
  instrument-upsert <instrumentId> <symbol>
  participant-upsert <participantId> <name>
  account-upsert <accountId> <participantId>
  role-upsert <roleId> <permissionCsv>
  role-assign <actorId> <roleId>
  events [limit]
  traces <traceId>`);
}

const [, , command, ...args] = process.argv;
if (!command) {
  usage();
  process.exit(1);
}

switch (command) {
  case "instrument-upsert":
    if (args.length < 2) {
      usage();
      process.exit(1);
    }
    await post("/reference/instruments", {
      instrumentId: args[0],
      symbol: args[1],
      assetClass: "US_EQ",
      currency: "USD",
    });
    break;
  case "participant-upsert":
    if (args.length < 2) {
      usage();
      process.exit(1);
    }
    await post("/reference/participants", { participantId: args[0], name: args[1] });
    break;
  case "account-upsert":
    if (args.length < 2) {
      usage();
      process.exit(1);
    }
    await post("/reference/accounts", {
      accountId: args[0],
      participantId: args[1],
      accountType: "HOUSE",
    });
    break;
  case "role-upsert":
    if (args.length < 2) {
      usage();
      process.exit(1);
    }
    await post("/auth/roles", { roleId: args[0], permissions: args[1] });
    break;
  case "role-assign":
    if (args.length < 2) {
      usage();
      process.exit(1);
    }
    await post("/auth/actor-roles", { actorId: args[0], roleId: args[1] });
    break;
  case "events": {
    const limit = args[0] ?? "20";
    await get(`/events?limit=${encodeURIComponent(limit)}`);
    break;
  }
  case "traces":
    if (args.length < 1) {
      usage();
      process.exit(1);
    }
    await get(`/traces/${encodeURIComponent(args[0])}/events`);
    break;
  default:
    usage();
    process.exit(1);
}
