export type BotSandboxViolationSeverityV1 = "error" | "warning";

export interface BotSandboxPolicyV1 {
  readonly deniedGlobals: readonly string[];
  readonly deniedImportSpecifiers: readonly string[];
  readonly allowedImportSpecifiers: readonly string[];
  readonly allowDynamicImport: boolean;
  readonly allowDynamicRequire: boolean;
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

export interface BotApprovedPackageV1 {
  readonly name: string;
  readonly version: string;
  readonly license: string;
  readonly status: "approved";
  readonly reason: string;
}

export const reefBotApprovedPackagesV1: readonly BotApprovedPackageV1[] = [
  {
    name: "trading-signals",
    version: "7.4.3",
    license: "MIT",
    status: "approved",
    reason: "Focused TypeScript technical indicators for strategy authoring.",
  },
  {
    name: "simple-statistics",
    version: "7.9.3",
    license: "ISC",
    status: "approved",
    reason: "Pure JavaScript statistics helpers for strategy calculations.",
  },
  {
    name: "decimal.js",
    version: "10.6.0",
    license: "MIT",
    status: "approved",
    reason: "Deterministic decimal arithmetic helper.",
  },
  {
    name: "lodash-es",
    version: "4.18.1",
    license: "MIT",
    status: "approved",
    reason: "Tree-shakable utility helpers for author ergonomics.",
  },
];

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
  allowDynamicRequire: false,
  allowTimers: false,
  allowNetwork: false,
  allowFilesystem: false,
};

export const reefBotHostedSourceSandboxPolicyV1: BotSandboxPolicyV1 = {
  ...reefBotHostedSandboxPolicyV1,
  allowedImportSpecifiers: [
    ...reefBotHostedSandboxPolicyV1.allowedImportSpecifiers,
    ...reefBotApprovedPackagesV1.map((pkg) => pkg.name),
  ],
};

export function scanBotSourceForSandboxViolationsV1(
  source: string,
  policy: BotSandboxPolicyV1 = reefBotHostedSandboxPolicyV1,
): readonly BotSandboxViolationV1[] {
  const violations: BotSandboxViolationV1[] = [];
  const scan = scanSourceSyntax(source);

  for (const deniedGlobal of policy.deniedGlobals) {
    if ((deniedGlobal === "setTimeout" || deniedGlobal === "setInterval") && policy.allowTimers) {
      continue;
    }
    if ((deniedGlobal === "fetch" || deniedGlobal === "WebSocket") && policy.allowNetwork) {
      continue;
    }

    if (scan.globalReferences.has(deniedGlobal)) {
      violations.push({
        code: "sandbox_denied_global",
        message: `Hosted bot source references denied global ${deniedGlobal}.`,
        severity: "error",
        pattern: deniedGlobal,
      });
    }
  }

  for (const specifier of scan.moduleSpecifiers) {
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

  if (!policy.allowDynamicImport && scan.usesDynamicImport) {
    violations.push({
      code: "sandbox_dynamic_import",
      message: "Hosted bot source uses dynamic import.",
      severity: "error",
      pattern: "import(",
    });
  }

  if (!policy.allowDynamicRequire && scan.usesDynamicRequire) {
    violations.push({
      code: "sandbox_dynamic_require",
      message: "Hosted bot source uses dynamic require().",
      severity: "error",
      pattern: "require(",
    });
  }

  return violations;
}

interface SandboxSourceSyntaxScan {
  readonly moduleSpecifiers: readonly string[];
  readonly globalReferences: ReadonlySet<string>;
  readonly usesDynamicImport: boolean;
  readonly usesDynamicRequire: boolean;
}

function scanSourceSyntax(source: string): SandboxSourceSyntaxScan {
  const commentFreeSource = stripComments(source, false);
  const executableSource = stripComments(source, true);
  const moduleSpecifiers: string[] = [];
  const globalReferences = new Set<string>();

  const importPattern = /\bimport\s+(?:type\s+)?(?:[^'"]+\s+from\s+)?["']([^"']+)["']/g;
  for (const match of commentFreeSource.matchAll(importPattern)) {
    const specifier = match[1];
    if (specifier !== undefined) moduleSpecifiers.push(specifier);
  }

  const exportPattern = /\bexport\s+(?:type\s+)?(?:[^'"]+\s+from\s+)?["']([^"']+)["']/g;
  for (const match of commentFreeSource.matchAll(exportPattern)) {
    const specifier = match[1];
    if (specifier !== undefined) moduleSpecifiers.push(specifier);
  }

  const requirePattern = /\brequire\s*\(\s*["']([^"']+)["']\s*\)/g;
  for (const match of commentFreeSource.matchAll(requirePattern)) {
    const specifier = match[1];
    if (specifier !== undefined) moduleSpecifiers.push(specifier);
  }

  for (const match of executableSource.matchAll(/\b[A-Za-z_$][\w$]*\b/g)) {
    const token = match[0];
    if (!isLikelyPropertyName(executableSource, match.index, token.length)) {
      globalReferences.add(token);
    }
  }

  return {
    moduleSpecifiers,
    globalReferences,
    usesDynamicImport: /\bimport\s*\(/.test(executableSource),
    usesDynamicRequire: /\brequire\s*\((?!\s*["'][^"']+["']\s*\))/.test(commentFreeSource),
  };
}

function stripComments(source: string, blankStrings: boolean): string {
  let output = "";
  let index = 0;
  while (index < source.length) {
    const current = source[index];
    const next = source[index + 1];
    if (current === "/" && next === "/") {
      const end = source.indexOf("\n", index + 2);
      const stop = end === -1 ? source.length : end;
      output += " ".repeat(stop - index);
      index = stop;
      continue;
    }
    if (current === "/" && next === "*") {
      const end = source.indexOf("*/", index + 2);
      const stop = end === -1 ? source.length : end + 2;
      output += source.slice(index, stop).replace(/[^\n]/g, " ");
      index = stop;
      continue;
    }
    if (blankStrings && (current === "\"" || current === "'" || current === "`")) {
      const quote = current;
      let stop = index + 1;
      while (stop < source.length) {
        const char = source[stop];
        if (char === "\\") {
          stop += 2;
          continue;
        }
        stop += 1;
        if (char === quote) break;
      }
      output += source.slice(index, stop).replace(/[^\n]/g, " ");
      index = stop;
      continue;
    }
    output += current;
    index += 1;
  }
  return output;
}

function isLikelyPropertyName(source: string, index: number | undefined, length: number): boolean {
  if (index === undefined) return false;
  let before = index - 1;
  while (before >= 0 && /\s/.test(source[before] ?? "")) before -= 1;
  if (source[before] === ".") return true;

  let after = index + length;
  while (after < source.length && /\s/.test(source[after] ?? "")) after += 1;
  return source[after] === ":";
}
