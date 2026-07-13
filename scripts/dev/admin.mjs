import { deriveDevUrls, loadDotEnv } from "./lib/dev-utils.mjs";

loadDotEnv();
const { runtimeUrl } = deriveDevUrls();

async function post(path, payload) {
  const res = await fetch(`${runtimeUrl}${path}`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      ...adminGatewayHeaders(path),
    },
    body: JSON.stringify(payload),
  });
  const text = await res.text();
  if (!res.ok) {
    throw new Error(`${path} failed (${res.status}): ${text}`);
  }
  console.log(text);
}

function adminGatewayHeaders(path) {
  if (!path.startsWith("/admin/v1/")) {
    return {};
  }
  const token = process.env.ADMIN_API_TOKEN ?? "";
  return token.trim() === "" ? {} : { Authorization: `Bearer ${token}` };
}

async function get(path) {
  const res = await fetch(`${runtimeUrl}${path}`, {
    headers: adminGatewayHeaders(path),
  });
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
  account-risk-set <account|bot> <id> <allow|reject|backpressure|disabled-bot> [--max-quantity N] [--max-notional N] [--currency CCY] [--reason text]
  account-risk-list
  breaker-set <global|venue-session|instrument> <id|*> <trip|reset> [reason]
  breaker-list
  price-collar-set <instrumentId> <minPrice|*> <maxPrice|*> [--currency CCY] [--reason text]
  price-collar-list
  events [limit]
  traces <traceId>`);
}

function parseAccountRiskOptions(values) {
  const options = {
    reason: "",
    maxQuantityUnits: "",
    maxNotional: "",
    currency: "",
  };
  const reasonParts = [];
  for (let i = 0; i < values.length; i += 1) {
    const value = values[i];
    if (value === "--max-quantity") {
      options.maxQuantityUnits = values[++i] ?? "";
    } else if (value === "--max-notional") {
      options.maxNotional = values[++i] ?? "";
    } else if (value === "--currency") {
      options.currency = values[++i] ?? "";
    } else if (value === "--reason") {
      reasonParts.push(values[++i] ?? "");
    } else {
      reasonParts.push(value);
    }
  }
  options.reason = reasonParts.join(" ").trim();
  return options;
}

function parsePriceCollarOptions(values) {
  const options = {
    currency: "",
    reason: "",
  };
  const reasonParts = [];
  for (let i = 0; i < values.length; i += 1) {
    const value = values[i];
    if (value === "--currency") {
      options.currency = values[++i] ?? "";
    } else if (value === "--reason") {
      reasonParts.push(values[++i] ?? "");
    } else {
      reasonParts.push(value);
    }
  }
  options.reason = reasonParts.join(" ").trim();
  return options;
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
    await post("/admin/v1/reference/instruments", {
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
    await post("/admin/v1/reference/participants", { participantId: args[0], name: args[1] });
    break;
  case "account-upsert":
    if (args.length < 2) {
      usage();
      process.exit(1);
    }
    await post("/admin/v1/reference/accounts", {
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
    await post("/admin/v1/auth/roles", { roleId: args[0], permissions: args[1] });
    break;
  case "role-assign":
    if (args.length < 2) {
      usage();
      process.exit(1);
    }
    await post("/admin/v1/auth/actor-roles", { actorId: args[0], roleId: args[1] });
    break;
  case "account-risk-set":
    if (args.length < 3) {
      usage();
      process.exit(1);
    }
    {
      const options = parseAccountRiskOptions(args.slice(3));
      await post("/admin/v1/risk/account-controls", {
        scopeType: args[0],
        scopeId: args[1],
        decision: args[2],
        reason: options.reason,
        maxQuantityUnits: options.maxQuantityUnits,
        maxNotional: options.maxNotional,
        currency: options.currency,
        actorId: "dev-admin",
        correlationId: "dev-admin",
      });
    }
    break;
  case "account-risk-list":
    await get("/admin/v1/risk/account-controls");
    break;
  case "breaker-set":
    if (args.length < 3) {
      usage();
      process.exit(1);
    }
    await post("/admin/v1/risk/circuit-breakers", {
      scopeType: args[0],
      scopeId: args[1],
      action: args[2],
      reason: args.slice(3).join(" "),
      actorId: "dev-admin",
      correlationId: "dev-admin",
    });
    break;
  case "breaker-list":
    await get("/admin/v1/risk/circuit-breakers");
    break;
  case "price-collar-set":
    if (args.length < 3) {
      usage();
      process.exit(1);
    }
    {
      const options = parsePriceCollarOptions(args.slice(3));
      await post("/admin/v1/risk/price-collars", {
        instrumentId: args[0],
        minPrice: args[1] === "*" ? "" : args[1],
        maxPrice: args[2] === "*" ? "" : args[2],
        currency: options.currency,
        reason: options.reason,
        actorId: "dev-admin",
        correlationId: "dev-admin",
      });
    }
    break;
  case "price-collar-list":
    await get("/admin/v1/risk/price-collars");
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
