import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  pathExists,
  findSkillLinks,
  ensureSymlink,
  summarizeSymlinkResults,
} from "./symlink-sync.mjs";

const repoRoot = fileURLToPath(new URL("../../..", import.meta.url));
const templatesRoot = path.resolve(
  process.env.AI_CENTRAL_HOME ??
    path.join(os.homedir(), "Documents/ai-central/templates"),
);

export const LINK_TARGETS = new Set(["codex", "claude"]);

export async function runLinks(target, options = {}) {
  if (!LINK_TARGETS.has(target)) {
    throw new Error(`unknown link target: ${target ?? ""}`);
  }

  const dryRun = options.dryRun ?? false;
  if (!(await pathExists(templatesRoot))) {
    console.error(`Template root not found: ${templatesRoot}`);
    console.error("Set AI_CENTRAL_HOME to the templates checkout root.");
    process.exitCode = 1;
    return;
  }

  const links = target === "codex"
    ? await codexLinks(dryRun)
    : await claudeLinks(dryRun);

  const results = [];
  for (const link of links) {
    results.push(await ensureSymlink(link.directory, link.linkName, link.target, { path, dryRun }));
  }

  summarizeSymlinkResults(results, {
    repoRoot,
    path,
    label: target === "codex" ? "Codex links" : "Claude skill links",
  });
}

async function codexLinks(dryRun) {
  const codexDir = path.join(repoRoot, ".codex");
  const skillsDir = path.join(codexDir, "skills");
  const steeringDir = path.join(codexDir, "steering");

  if (!dryRun) {
    await fs.mkdir(skillsDir, { recursive: true });
    await fs.mkdir(steeringDir, { recursive: true });
  }

  return [
    ...(await findSkillLinks(templatesRoot, path)).map((link) => ({ ...link, directory: skillsDir })),
    ...(await findSteeringLinks()).map((link) => ({ ...link, directory: steeringDir })),
  ];
}

async function claudeLinks(dryRun) {
  const claudeDir = path.join(repoRoot, ".claude");
  const skillsDir = path.join(claudeDir, "skills");

  if (!dryRun) {
    await fs.mkdir(skillsDir, { recursive: true });
  }

  return (await findSkillLinks(templatesRoot, path)).map((link) => ({
    ...link,
    directory: skillsDir,
  }));
}

async function findSteeringLinks() {
  const root = path.join(templatesRoot, "steering");
  let entries;
  try {
    entries = await fs.readdir(root, { withFileTypes: true });
  } catch (error) {
    if (error.code === "ENOENT") {
      return [];
    }
    throw error;
  }

  return entries
    .filter((entry) => entry.isFile() && entry.name.endsWith(".md"))
    .map((entry) => ({
      linkName: entry.name,
      target: path.join(root, entry.name),
    }))
    .sort((left, right) => left.linkName.localeCompare(right.linkName));
}
