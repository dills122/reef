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
const claudeDir = path.join(repoRoot, '.claude');
const skillsDir = path.join(claudeDir, 'skills');
const templatesRoot = path.resolve(
  process.env.AI_CENTRAL_HOME ??
    path.join(os.homedir(), 'Documents/ai-central/templates'),
);
const dryRun = process.argv.includes('--dry-run');

async function main() {
  if (!(await pathExists(templatesRoot))) {
    console.error(`Template root not found: ${templatesRoot}`);
    console.error('Set AI_CENTRAL_HOME to the templates checkout root.');
    process.exitCode = 1;
    return;
  }

  if (!dryRun) {
    await fs.mkdir(skillsDir, { recursive: true });
  }

  const links = (await findSkillLinks(templatesRoot, path)).map((link) => ({
    ...link,
    directory: skillsDir,
  }));

  const results = [];
  for (const link of links) {
    results.push(await ensureSymlink(link.directory, link.linkName, link.target, { path, dryRun }));
  }

  summarizeSymlinkResults(results, { repoRoot, path, label: 'Claude skill links' });
}

await main();
