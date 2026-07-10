export function createOpenBaoRuntimeSecretProvider(options) {
  const baoAddr = requiredString(options.baoAddr, "baoAddr").replace(/\/$/, "");
  const token = requiredString(options.token, "token");
  const fetchImpl = options.fetchImpl ?? globalThis.fetch;
  if (typeof fetchImpl !== "function") {
    throw new Error("OpenBao runtime config provider requires fetch.");
  }

  return {
    provider: "OpenBao",
    async readSecret(path) {
      const response = await fetchImpl(`${baoAddr}/v1/${openBaoKvV2DataPath(path)}`, {
        method: "GET",
        headers: {
          "X-Vault-Token": token,
        },
      });
      if (response.status === 404) {
        return undefined;
      }
      if (!response.ok) {
        throw new Error(`OpenBao runtime config read failed (${response.status}) for ${redactedPathLabel(path)}.`);
      }
      const payload = await response.json();
      return normalizeOpenBaoSecret(payload);
    },
  };
}

export function runtimeConfigPreflightReport(provider, descriptors, resolvedValues) {
  return {
    provider: provider.provider,
    descriptorCount: descriptors.length,
    resolvedKeys: Object.keys(resolvedValues).sort(),
  };
}

export function openBaoKvV2DataPath(path) {
  const clean = requiredString(path, "path").replace(/^\/+/, "").replace(/\/+$/, "");
  const parts = clean.split("/");
  if (parts.length < 2) {
    throw new Error("OpenBao runtime config path must include mount and secret name.");
  }
  if (parts[1] === "data") {
    return clean;
  }
  return [parts[0], "data", ...parts.slice(1)].join("/");
}

function normalizeOpenBaoSecret(payload) {
  const data = payload?.data?.data;
  if (data !== null && typeof data === "object" && !Array.isArray(data) && typeof data.config_json === "string") {
    try {
      const parsed = JSON.parse(data.config_json);
      if (parsed !== null && typeof parsed === "object" && !Array.isArray(parsed)) {
        return Object.freeze(Object.fromEntries(Object.entries(parsed).filter((entry) => isRuntimeConfigValue(entry[1]))));
      }
    } catch {
      return undefined;
    }
  }
  if (isRuntimeConfigValue(data)) {
    return data;
  }
  if (data !== null && typeof data === "object" && !Array.isArray(data)) {
    return Object.freeze(Object.fromEntries(Object.entries(data).filter((entry) => isRuntimeConfigValue(entry[1]))));
  }
  return undefined;
}

function isRuntimeConfigValue(value) {
  return typeof value === "string" || typeof value === "number" || typeof value === "boolean";
}

function redactedPathLabel(path) {
  const parts = requiredString(path, "path").split("/").filter(Boolean);
  return parts.length === 0 ? "<empty>" : `${parts[0]}/...`;
}

function requiredString(value, name) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new Error(`OpenBao runtime config ${name} is required.`);
  }
  return value;
}
