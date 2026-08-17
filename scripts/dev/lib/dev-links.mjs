import { spawn } from "node:child_process";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  pathExists,
  ensureSymlink,
  summarizeSymlinkResults,
} from "./symlink-sync.mjs";

const repoRoot = fileURLToPath(new URL("../../..", import.meta.url));
const { aiCentralRoot, templatesRoot } = resolveAiCentralLocation(process.env.AI_CENTRAL_HOME);

export const LINK_TARGETS = new Set(["codex", "claude"]);
export const REEF_AI_CENTRAL_BUNDLES = [
  "core",
  "node",
  "jvm",
  "frontend",
  "infra",
  "workflow",
  "planning",
  "orchestration",
  "documentation",
  "brevity",
];

export function resolveAiCentralLocation(input, homeDirectory = os.homedir()) {
  const candidate = path.resolve(input ?? path.join(homeDirectory, ".ai-central"));
  const root = path.basename(candidate) === "templates" ? path.dirname(candidate) : candidate;

  return {
    aiCentralRoot: root,
    templatesRoot: path.join(root, "templates"),
  };
}

export function codexSetupArgs(dryRun = false) {
  return [
    repoRoot,
    "--yes",
    "--mode",
    "link",
    "--bundles",
    REEF_AI_CENTRAL_BUNDLES.join(","),
    "--sync",
    ...(dryRun ? ["--dry-run"] : []),
  ];
}

export function isPathWithin(root, candidate) {
  const relative = path.relative(root, candidate);
  return (
    relative !== "" &&
    !relative.startsWith(`..${path.sep}`) &&
    relative !== ".." &&
    !path.isAbsolute(relative)
  );
}

export async function runLinks(target, options = {}) {
  if (!LINK_TARGETS.has(target)) {
    throw new Error(`unknown link target: ${target ?? ""}`);
  }

  const dryRun = options.dryRun ?? false;
  if (!(await pathExists(templatesRoot))) {
    console.error(`Template root not found: ${templatesRoot}`);
    console.error("Set AI_CENTRAL_HOME to the AI Central checkout or its templates directory.");
    process.exitCode = 1;
    return;
  }

  if (target === "codex") {
    await runCodexSetup(dryRun);
    return;
  }

  const { links, skillsDir, selectedNames, canonicalSkillsDir } = await claudeLinks(dryRun);

  const results = [];
  for (const link of links) {
    results.push(await ensureSymlink(link.directory, link.linkName, link.target, { path, dryRun }));
  }
  results.push(
    ...(await pruneClaudeLinks(skillsDir, selectedNames, canonicalSkillsDir, dryRun)),
  );

  summarizeSymlinkResults(results, {
    repoRoot,
    path,
    label: target === "codex" ? "Codex links" : "Claude skill links",
  });
}

async function runCodexSetup(dryRun) {
  const setupScript = path.join(aiCentralRoot, "scripts", "setup-ai-context.sh");
  if (!(await pathExists(setupScript))) {
    throw new Error(`AI Central setup script not found: ${setupScript}`);
  }

  await new Promise((resolve, reject) => {
    const child = spawn(setupScript, codexSetupArgs(dryRun), { stdio: "inherit" });
    child.once("error", reject);
    child.once("close", (code, signal) => {
      if (code === 0) {
        resolve();
        return;
      }

      reject(
        new Error(
          signal
            ? `AI Central setup terminated by signal ${signal}`
            : `AI Central setup exited with code ${code}`,
        ),
      );
    });
  });
}

async function claudeLinks(dryRun) {
  const claudeDir = path.join(repoRoot, ".claude");
  const skillsDir = path.join(claudeDir, "skills");
  const canonicalSkillsDir = path.join(repoRoot, ".agents", "skills");

  if (!dryRun) {
    await fs.mkdir(skillsDir, { recursive: true });
  }

  const entries = await fs.readdir(canonicalSkillsDir, { withFileTypes: true });
  const links = [];
  for (const entry of entries) {
    const target = path.join(canonicalSkillsDir, entry.name);
    if (!(await pathExists(path.join(target, "SKILL.md")))) {
      continue;
    }
    links.push({ directory: skillsDir, linkName: entry.name, target });
  }
  links.sort((left, right) => left.linkName.localeCompare(right.linkName));

  return {
    canonicalSkillsDir,
    links,
    selectedNames: new Set(links.map((link) => link.linkName)),
    skillsDir,
  };
}

async function pruneClaudeLinks(skillsDir, selectedNames, canonicalSkillsDir, dryRun) {
  let entries;
  try {
    entries = await fs.readdir(skillsDir, { withFileTypes: true });
  } catch (error) {
    if (error.code === "ENOENT") {
      return [];
    }
    throw error;
  }

  const results = [];
  const templatesSkillsDir = path.join(templatesRoot, "skills");
  for (const entry of entries) {
    if (selectedNames.has(entry.name)) {
      continue;
    }

    const linkPath = path.join(skillsDir, entry.name);
    const stat = await fs.lstat(linkPath);
    if (!stat.isSymbolicLink()) {
      results.push({ action: "skip-real-file", linkPath, target: linkPath });
      continue;
    }

    const currentTarget = await fs.readlink(linkPath);
    const resolvedTarget = path.resolve(path.dirname(linkPath), currentTarget);
    if (
      !isPathWithin(templatesSkillsDir, resolvedTarget) &&
      !isPathWithin(canonicalSkillsDir, resolvedTarget)
    ) {
      results.push({ action: "skip-unmanaged-link", linkPath, target: resolvedTarget });
      continue;
    }

    if (!dryRun) {
      await fs.unlink(linkPath);
    }
    results.push({ action: dryRun ? "would-remove" : "removed", linkPath, target: resolvedTarget });
  }

  return results;
}
