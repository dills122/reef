#!/usr/bin/env node
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, '../..');
const codexDir = path.join(repoRoot, '.codex');
const skillsDir = path.join(codexDir, 'skills');
const steeringDir = path.join(codexDir, 'steering');
const templatesRoot = path.resolve(
  process.env.AI_CENTRAL_HOME ??
    path.join(os.homedir(), 'Documents/ai-central/templates'),
);
const dryRun = process.argv.includes('--dry-run');

async function pathExists(target) {
  try {
    await fs.lstat(target);
    return true;
  } catch (error) {
    if (error.code === 'ENOENT') {
      return false;
    }
    throw error;
  }
}

async function* walkDirs(root) {
  let entries;
  try {
    entries = await fs.readdir(root, { withFileTypes: true });
  } catch (error) {
    if (error.code === 'ENOENT') {
      return;
    }
    throw error;
  }

  yield root;

  for (const entry of entries) {
    if (!entry.isDirectory()) {
      continue;
    }

    yield* walkDirs(path.join(root, entry.name));
  }
}

async function findSkillLinks() {
  const skillRoot = path.join(templatesRoot, 'skills');
  const links = [];

  for await (const dir of walkDirs(skillRoot)) {
    if (!(await pathExists(path.join(dir, 'SKILL.md')))) {
      continue;
    }

    const relativeDir = path.relative(skillRoot, dir);
    const parts = relativeDir.split(path.sep);
    const name = parts.at(-1);
    const linkName = skillLinkName(parts, name);

    if (linkName) {
      links.push({ linkName, target: dir });
    }
  }

  links.sort((left, right) => left.linkName.localeCompare(right.linkName));
  return links;
}

function skillLinkName(parts, name) {
  if (!name) {
    return undefined;
  }

  if (parts[0] === 'adapted') {
    return name;
  }

  if (parts[0] !== 'imported') {
    return name;
  }

  if (parts[1] === 'agent-skills') {
    return name;
  }

  if (parts[1] === 'pm-skills') {
    return `pm-${name}`;
  }

  if (parts[1] === 'claude-skills') {
    return `claude-${name}`;
  }

  return name;
}

async function findSteeringLinks() {
  const root = path.join(templatesRoot, 'steering');
  let entries;
  try {
    entries = await fs.readdir(root, { withFileTypes: true });
  } catch (error) {
    if (error.code === 'ENOENT') {
      return [];
    }
    throw error;
  }

  return entries
    .filter((entry) => entry.isFile() && entry.name.endsWith('.md'))
    .map((entry) => ({
      linkName: entry.name,
      target: path.join(root, entry.name),
    }))
    .sort((left, right) => left.linkName.localeCompare(right.linkName));
}

async function ensureSymlink(directory, linkName, target) {
  const linkPath = path.join(directory, linkName);
  let existing;

  try {
    existing = await fs.lstat(linkPath);
  } catch (error) {
    if (error.code !== 'ENOENT') {
      throw error;
    }
  }

  if (existing && !existing.isSymbolicLink()) {
    return { action: 'skip-real-file', linkPath, target };
  }

  if (existing?.isSymbolicLink()) {
    const currentTarget = await fs.readlink(linkPath);
    const resolvedCurrentTarget = path.resolve(path.dirname(linkPath), currentTarget);

    if (resolvedCurrentTarget === target) {
      return { action: 'unchanged', linkPath, target };
    }

    if (!dryRun) {
      await fs.unlink(linkPath);
    }
  }

  if (!dryRun) {
    await fs.symlink(target, linkPath);
  }

  return {
    action: existing ? 'updated' : 'created',
    linkPath,
    target,
  };
}

async function main() {
  if (!(await pathExists(templatesRoot))) {
    console.error(`Template root not found: ${templatesRoot}`);
    console.error('Set AI_CENTRAL_HOME to the templates checkout root.');
    process.exitCode = 1;
    return;
  }

  if (!dryRun) {
    await fs.mkdir(skillsDir, { recursive: true });
    await fs.mkdir(steeringDir, { recursive: true });
  }

  const links = [
    ...(await findSkillLinks()).map((link) => ({ ...link, directory: skillsDir })),
    ...(await findSteeringLinks()).map((link) => ({ ...link, directory: steeringDir })),
  ];

  const results = [];
  for (const link of links) {
    results.push(await ensureSymlink(link.directory, link.linkName, link.target));
  }

  const counts = results.reduce((accumulator, result) => {
    accumulator[result.action] = (accumulator[result.action] ?? 0) + 1;
    return accumulator;
  }, {});

  for (const result of results.filter((item) => item.action !== 'unchanged')) {
    const relativeLink = path.relative(repoRoot, result.linkPath);
    console.log(`${result.action}: ${relativeLink} -> ${result.target}`);
  }

  console.log(
    `Codex links checked: ${results.length} ` +
      `(created ${counts.created ?? 0}, updated ${counts.updated ?? 0}, ` +
      `unchanged ${counts.unchanged ?? 0}, skipped ${counts['skip-real-file'] ?? 0})`,
  );
}

await main();
