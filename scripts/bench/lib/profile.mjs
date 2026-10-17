// Parser for scripts/bench/profiles/*.env. See the header of any profile for the format.
import { readFileSync } from "node:fs";

const ENFORCE = new Set(["required", "if-set", "advisory"]);
const COMPARE = new Set(["max", "min"]);
const list = (value) => value.split(",").map((part) => part.trim()).filter(Boolean);

export function parseProfile(text, source = "<profile>") {
  const header = { commands: [] };
  const conflicts = [];
  const entries = [];
  let pending = {};
  let inConflicts = false;
  const lines = text.split(/\r?\n/);
  lines.forEach((raw, index) => {
    const line = raw.trim();
    const where = `${source}:${index + 1}`;
    if (line === "") {
      pending = {};
      inConflicts = false;
      return;
    }
    if (line.startsWith("#")) {
      const body = line.replace(/^#\s?/, "");
      let match;
      if ((match = /^@(\w+):\s*(.*)$/.exec(body))) {
        if (match[1] === "command") header.commands.push(match[2]);
        else header[match[1]] = match[2];
        return;
      }
      if (/^CONFLICTS\b/.test(body)) {
        inConflicts = true;
        return;
      }
      if (inConflicts) {
        conflicts.push(body.replace(/^-\s*/, "").trim());
        return;
      }
      if ((match = /^(why|default|enforce|compare|forced-by|defaulted-by):\s*(.*)$/.exec(body))) {
        pending[match[1]] = match[2].trim();
      }
      return;
    }
    const eq = line.indexOf("=");
    if (eq <= 0) throw new Error(`${where}: not KEY=value: ${line}`);
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1);
    if (/^"[^"]*"$/.test(value)) value = value.slice(1, -1);
    if (!/^[A-Za-z0-9_.-]+$/.test(key)) throw new Error(`${where}: bad key ${key}`);
    for (const field of ["why", "default", "enforce"]) {
      if (!pending[field]) throw new Error(`${where}: ${key} is missing a '# ${field}:' comment`);
    }
    if (!ENFORCE.has(pending.enforce)) throw new Error(`${where}: ${key} has bad enforce '${pending.enforce}'`);
    if (pending.compare && !COMPARE.has(pending.compare)) throw new Error(`${where}: ${key} has bad compare '${pending.compare}'`);
    if (entries.some((entry) => entry.key === key)) throw new Error(`${where}: duplicate key ${key}`);
    entries.push({
      key,
      value,
      why: pending.why,
      default: pending.default,
      enforce: pending.enforce,
      compare: pending.compare ?? null,
      forcedBy: list(pending["forced-by"] ?? ""),
      defaultedBy: list(pending["defaulted-by"] ?? ""),
      family: entryFamily(key),
      topicConfig: TOPIC_KEYS[key] ?? null,
    });
    pending = {};
  });
  for (const field of ["profile", "class", "purpose", "pass"]) {
    if (!header[field]) throw new Error(`${source}: header is missing @${field}`);
  }
  if (header.commands.length === 0) throw new Error(`${source}: header is missing @command`);
  return { header, conflicts, entries, source };
}

export const TOPIC_KEYS = { REEF_TOPIC_WRITE_CACHING: "write.caching", REEF_TOPIC_MAX_MESSAGE_BYTES: "max.message.bytes" };

// env = plain environment variable; overlay = <compose service>.<VAR>; topic = broker topic config
export function entryFamily(key) {
  if (key in TOPIC_KEYS) return "topic";
  if (/^[a-z][a-z0-9-]*\.[A-Z0-9_]+$/.test(key)) return "overlay";
  return "env";
}

export function loadProfile(path) {
  return parseProfile(readFileSync(path, "utf8"), path);
}

// Exposed for tests and for `--print-env`: the KEY=value pairs of the env family.
export function envEntries(profile) {
  return profile.entries.filter((entry) => entry.family === "env");
}
