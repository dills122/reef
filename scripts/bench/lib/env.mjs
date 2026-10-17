// Environment resolution with the same precedence as scripts/dev/lib/dev-utils.mjs loadDotEnv():
// a non-empty process variable wins, then the first env file that sets it.
export function parseEnvFile(text) {
  const out = {};
  for (const lineRaw of text.split(/\r?\n/)) {
    const line = lineRaw.trim();
    if (!line || line.startsWith("#")) continue;
    const idx = line.indexOf("=");
    if (idx <= 0) continue;
    const key = line.slice(0, idx).trim();
    let value = line.slice(idx + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    out[key] = value;
  }
  return out;
}

// files: [{ path, text }] in precedence order. Returns { KEY: { value, source } }.
export function resolveEnv(processEnv, files = []) {
  const resolved = {};
  for (const [key, value] of Object.entries(processEnv)) {
    if (value !== undefined && value !== "") resolved[key] = { value: String(value), source: "process env" };
  }
  for (const file of files) {
    for (const [key, value] of Object.entries(parseEnvFile(file.text))) {
      if (value === "") continue;
      if (!resolved[key]) resolved[key] = { value, source: file.path };
    }
  }
  return resolved;
}

// Static `KEY: "${KEY:-default}"` defaults of a compose file (works without Docker).
export function composeDefaults(text) {
  const out = {};
  for (const match of text.matchAll(/^\s*([A-Z][A-Z0-9_]*):\s*"?\$\{([A-Z][A-Z0-9_]*):-([^}]*)\}"?\s*$/gm)) {
    if (match[1] === match[2] && !(match[1] in out)) out[match[1]] = match[3];
  }
  return out;
}
