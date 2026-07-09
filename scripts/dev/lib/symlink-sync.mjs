import fs from 'node:fs/promises';
import nodePath from 'node:path';

export async function pathExists(target) {
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

export async function* walkDirs(root) {
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

    yield* walkDirs(nodePath.join(root, entry.name));
  }
}

// Shared between scripts/dev/setup-codex-links.mjs and
// scripts/dev/setup-claude-links.mjs so a skill has the same link name under
// .codex/skills/ and .claude/skills/.
export function skillLinkName(parts, name) {
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

export async function findSkillLinks(templatesRoot, path) {
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

export async function ensureSymlink(directory, linkName, target, { path, dryRun }) {
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

export function summarizeSymlinkResults(results, { repoRoot, path, label }) {
  const counts = results.reduce((accumulator, result) => {
    accumulator[result.action] = (accumulator[result.action] ?? 0) + 1;
    return accumulator;
  }, {});

  for (const result of results.filter((item) => item.action !== 'unchanged')) {
    const relativeLink = path.relative(repoRoot, result.linkPath);
    console.log(`${result.action}: ${relativeLink} -> ${result.target}`);
  }

  console.log(
    `${label} checked: ${results.length} ` +
      `(created ${counts.created ?? 0}, updated ${counts.updated ?? 0}, ` +
      `unchanged ${counts.unchanged ?? 0}, skipped ${counts['skip-real-file'] ?? 0})`,
  );
}
