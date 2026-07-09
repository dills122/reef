#!/usr/bin/env node
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  pathExists,
  findSkillLinks,
  ensureSymlink,
  summarizeSymlinkResults,
} from './lib/symlink-sync.mjs';

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
    ...(await findSkillLinks(templatesRoot, path)).map((link) => ({ ...link, directory: skillsDir })),
    ...(await findSteeringLinks()).map((link) => ({ ...link, directory: steeringDir })),
  ];

  const results = [];
  for (const link of links) {
    results.push(await ensureSymlink(link.directory, link.linkName, link.target, { path, dryRun }));
  }

  summarizeSymlinkResults(results, { repoRoot, path, label: 'Codex links' });
}

await main();
