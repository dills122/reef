import { createHash } from "node:crypto";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, isAbsolute, join, resolve } from "node:path";

const repoRoot = new URL("../../", import.meta.url).pathname;
const sandboxPolicyUrl = new URL("../../packages/bot-sdk/src/sandbox-policy.ts", import.meta.url);
const {
  reefBotApprovedPackagesV1,
  reefBotHostedSourceSandboxPolicyV1,
  scanBotSourceForSandboxViolationsV1,
} = await import(sandboxPolicyUrl.href);
const approvedPackages = reefBotApprovedPackagesV1;

const args = process.argv.slice(2);
const entryPathArg = args.find((arg) => !arg.startsWith("--"));
const outPathArg = optionValue("--out");
const manifestPathArg = optionValue("--manifest-out");

if (!entryPathArg || !outPathArg) {
  console.error(
    "usage: bun scripts/dev/bot-sdk-build-hosted-artifact.mjs <bot-entry.ts> --out=/tmp/bot.bundle.js [--manifest-out=/tmp/bot.bundle.manifest.json]",
  );
  process.exit(2);
}

const entryPath = isAbsolute(entryPathArg) ? entryPathArg : resolve(repoRoot, entryPathArg);
const outPath = isAbsolute(outPathArg) ? outPathArg : resolve(repoRoot, outPathArg);
const manifestPath = manifestPathArg === undefined
  ? `${outPath}.manifest.json`
  : (isAbsolute(manifestPathArg) ? manifestPathArg : resolve(repoRoot, manifestPathArg));

const source = readFileSync(entryPath, "utf8");
const sourceViolations = scanBotSourceForSandboxViolationsV1(source, reefBotHostedSourceSandboxPolicyV1);
if (sourceViolations.length > 0) {
  console.error(JSON.stringify({ status: "do_not_merge", issues: sourceViolations }, null, 2));
  process.exit(1);
}

const usedApprovedPackages = usedApprovedPackageRecords(source);
const hostedSource = toHostedArtifactTypeScript(source);
const artifact = artifactHeader(entryPath) + await bundleHostedArtifactJavaScript(hostedSource, entryPath);
const artifactViolations = scanBotSourceForSandboxViolationsV1(artifact);
if (artifactViolations.length > 0) {
  console.error(JSON.stringify({ status: "do_not_merge", issues: artifactViolations }, null, 2));
  process.exit(1);
}

mkdirSync(dirname(outPath), { recursive: true });
mkdirSync(dirname(manifestPath), { recursive: true });
writeFileSync(outPath, artifact);

const manifest = {
  schemaVersion: "reef.bot.hostedArtifact.v1",
  entryPath: relativeToRepo(entryPath),
  artifactPath: relativeToRepo(outPath),
  sourceHash: sha256(source),
  artifactHash: sha256(artifact),
  sdkEndowment: "__reefBotSdk",
  moduleFormat: "commonjs-default-class",
  approvedPackages: usedApprovedPackages.map((pkg) => ({
    name: pkg.name,
    version: pkg.version,
    license: pkg.license,
  })),
};
writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
console.log(JSON.stringify(manifest, null, 2));

async function bundleHostedArtifactJavaScript(sourceText, entryPathValue) {
  const buildDir = mkdtempSync(resolve(repoRoot, ".bot-sdk-build-"));
  const buildEntry = join(buildDir, `${relativeToRepo(entryPathValue).replace(/[^a-zA-Z0-9_.-]/g, "_")}.ts`);
  writeFileSync(buildEntry, sourceText);

  try {
    const build = await Bun.build({
      entrypoints: [buildEntry],
      target: "browser",
      format: "cjs",
      packages: "bundle",
      write: false,
      minify: false,
      sourcemap: "none",
    });

    if (!build.success) {
      const logs = build.logs.map((log) => log.message).join("\n");
      throw new Error(`Hosted artifact bundle failed.${logs.length === 0 ? "" : `\n${logs}`}`);
    }

    const output = build.outputs[0];
    if (output === undefined) {
      throw new Error("Hosted artifact bundle produced no output.");
    }

    return output.text();
  } finally {
    rmSync(buildDir, { recursive: true, force: true });
  }
}

function toHostedArtifactTypeScript(sourceText) {
  const withoutSdkImports = sourceText.replace(
    /^\s*import\s+[^;]+from\s+["'](?:@reef\/bot-sdk|\.\.\/src\/index|\.\.\/\.\.\/src\/index)["'];\s*$/gm,
    "",
  );
  if (withoutSdkImports === sourceText) {
    throw new Error("Bot entry must import ReefBotV1 from @reef/bot-sdk or the local SDK index.");
  }
  if (!/\bexport\s+default\s+class\b/.test(withoutSdkImports)) {
    throw new Error("Bot entry must default-export a class.");
  }

  return `const { ReefBotV1 } = __reefBotSdk;\n${withoutSdkImports.replace(/\bexport\s+default\s+class\b/, "module.exports.default = class")}`;
}

function usedApprovedPackageRecords(sourceText) {
  const approvedByName = new Map(approvedPackages.map((pkg) => [pkg.name, pkg]));
  return [...new Set(moduleSpecifiers(sourceText))]
    .map((specifier) => approvedByName.get(specifier))
    .filter((pkg) => pkg !== undefined);
}

function moduleSpecifiers(sourceText) {
  const specifiers = [];
  const importPattern = /\bimport\s+(?:type\s+)?(?:[^'"]+\s+from\s+)?["']([^"']+)["']/g;
  for (const match of sourceText.matchAll(importPattern)) {
    if (match[1] !== undefined) {
      specifiers.push(match[1]);
    }
  }
  const requirePattern = /\brequire\s*\(\s*["']([^"']+)["']\s*\)/g;
  for (const match of sourceText.matchAll(requirePattern)) {
    if (match[1] !== undefined) {
      specifiers.push(match[1]);
    }
  }
  return specifiers;
}


function artifactHeader(entryPathValue) {
  return [
    "// Generated by scripts/dev/bot-sdk-build-hosted-artifact.mjs.",
    `// Source: ${relativeToRepo(entryPathValue)}`,
    "// Hosted bundles receive SDK capabilities through __reefBotSdk.",
    "",
  ].join("\n");
}

function sha256(value) {
  return `sha256:${createHash("sha256").update(value, "utf8").digest("hex")}`;
}

function relativeToRepo(pathValue) {
  return pathValue.startsWith(repoRoot) ? pathValue.slice(repoRoot.length) : pathValue;
}

function optionValue(name) {
  const arg = args.find((candidate) => candidate.startsWith(`${name}=`));
  return arg === undefined ? undefined : arg.slice(name.length + 1);
}
