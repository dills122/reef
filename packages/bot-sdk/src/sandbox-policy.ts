export type BotSandboxViolationSeverityV1 = "error" | "warning";

export interface BotSandboxPolicyV1 {
  readonly deniedGlobals: readonly string[];
  readonly deniedImportSpecifiers: readonly string[];
  readonly allowedImportSpecifiers: readonly string[];
  readonly allowDynamicImport: boolean;
  readonly allowTimers: boolean;
  readonly allowNetwork: boolean;
  readonly allowFilesystem: boolean;
}

export interface BotSandboxViolationV1 {
  readonly code: string;
  readonly message: string;
  readonly severity: BotSandboxViolationSeverityV1;
  readonly pattern: string;
}

export const reefBotHostedSandboxPolicyV1: BotSandboxPolicyV1 = {
  deniedGlobals: ["fetch", "WebSocket", "setTimeout", "setInterval", "process", "Buffer", "Function", "eval"],
  deniedImportSpecifiers: [
    "node:",
    "fs",
    "node:fs",
    "net",
    "node:net",
    "http",
    "node:http",
    "https",
    "node:https",
    "child_process",
    "node:child_process",
    "worker_threads",
    "node:worker_threads",
  ],
  allowedImportSpecifiers: ["@reef/bot-sdk", "../src/index", "../../src/index"],
  allowDynamicImport: false,
  allowTimers: false,
  allowNetwork: false,
  allowFilesystem: false,
};

export function scanBotSourceForSandboxViolationsV1(
  source: string,
  policy: BotSandboxPolicyV1 = reefBotHostedSandboxPolicyV1,
): readonly BotSandboxViolationV1[] {
  const violations: BotSandboxViolationV1[] = [];

  for (const deniedGlobal of policy.deniedGlobals) {
    if ((deniedGlobal === "setTimeout" || deniedGlobal === "setInterval") && policy.allowTimers) {
      continue;
    }
    if ((deniedGlobal === "fetch" || deniedGlobal === "WebSocket") && policy.allowNetwork) {
      continue;
    }

    const pattern = new RegExp(`\\b${escapeRegExp(deniedGlobal)}\\b`);
    if (pattern.test(source)) {
      violations.push({
        code: "sandbox_denied_global",
        message: `Hosted bot source references denied global ${deniedGlobal}.`,
        severity: "error",
        pattern: deniedGlobal,
      });
    }
  }

  for (const specifier of importSpecifiers(source)) {
    if (policy.allowedImportSpecifiers.includes(specifier)) {
      continue;
    }

    const denied = policy.deniedImportSpecifiers.some((deniedSpecifier) =>
      deniedSpecifier.endsWith(":") ? specifier.startsWith(deniedSpecifier) : specifier === deniedSpecifier,
    );
    if (denied || !policy.allowedImportSpecifiers.includes(specifier)) {
      violations.push({
        code: "sandbox_denied_import",
        message: `Hosted bot source imports denied or unapproved module ${specifier}.`,
        severity: "error",
        pattern: specifier,
      });
    }
  }

  if (!policy.allowDynamicImport && /\bimport\s*\(/.test(source)) {
    violations.push({
      code: "sandbox_dynamic_import",
      message: "Hosted bot source uses dynamic import.",
      severity: "error",
      pattern: "import(",
    });
  }

  return violations;
}

function importSpecifiers(source: string): readonly string[] {
  const specifiers: string[] = [];
  const importPattern = /\bimport\s+(?:type\s+)?(?:[^'"]+\s+from\s+)?["']([^"']+)["']/g;
  for (const match of source.matchAll(importPattern)) {
    const specifier = match[1];
    if (specifier !== undefined) {
      specifiers.push(specifier);
    }
  }
  return specifiers;
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
