import { readFile, access } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { resolve, dirname, posix } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const manifest = JSON.parse(await readFile(resolve(root, 'docs/records/2026-10-02-migration.json'), 'utf8'));
const fail = message => { throw new Error(message); };
const exists = async path => { try { await access(path); return true; } catch (error) { if (error.code === 'ENOENT') return false; throw error; } };
const sha256 = value => createHash('sha256').update(value).digest('hex');
const safePath = value => typeof value === 'string' && value !== '' && !value.startsWith('/') && !value.includes('\\') && !value.split('/').includes('..') && posix.normalize(value) === value;
if (manifest.schema_version !== 1 || !/^[0-9a-f]{40}$/.test(manifest.archive_commit) || !/^[0-9a-f]{40}$/.test(manifest.source_commit)) fail('Invalid migration provenance');
if (manifest.archive_repository !== 'https://github.com/dills122/reef-records') fail('Unexpected archive repository');
const entries = new Map();
for (const entry of manifest.files) {
  if (!safePath(entry.source_path) || entry.archive_path !== `records/reef/${entry.source_path}` || entries.has(entry.source_path) || !/^[0-9a-f]{64}$/.test(entry.sha256) || !/^[0-9a-f]{40}$/.test(entry.source_blob_oid) || !Number.isSafeInteger(entry.bytes) || entry.bytes < 0 || !entry.reason) fail(`Invalid relocation entry: ${entry.source_path}`);
  entries.set(entry.source_path, entry);
  if (!manifest.redirect_paths.includes(entry.source_path) && await exists(resolve(root, entry.source_path))) fail(`Historical source restored without policy update: ${entry.source_path}`);
}
const tracked = execFileSync('git', ['ls-files', '-z'], { cwd: root, encoding: 'utf8' }).split('\0').filter(Boolean);
for (const path of tracked.filter(path => path.endsWith('.md'))) {
  if (!await exists(resolve(root, path))) continue;
  const text = await readFile(resolve(root, path), 'utf8');
  for (const match of text.matchAll(/\]\(([^\s)]+)\)/g)) {
    const target = match[1];
    if (/^[a-zA-Z]+:/.test(target) || target.startsWith('#')) continue;
    const destination = posix.normalize(posix.join(posix.dirname(path), decodeURIComponent(target.split('#')[0])));
    if (entries.has(destination) && !manifest.redirect_paths.includes(destination)) fail(`Unrepaired archive link: ${path} -> ${destination}`);
  }
}
for (const fixture of [
  'docs/evidence/calcify-phase2/source-fixture.jsonl',
  'docs/evidence/calcify-phase2/hidden-run-source-fixture.jsonl',
  'docs/evidence/calcify-phase2-implementation/paired-source-fixture.jsonl',
]) if (!await exists(resolve(root, fixture))) fail(`Missing current executable fixture: ${fixture}`);

const checksumRoot = 'docs/evidence/calcify-phase2-implementation';
for (const line of (await readFile(resolve(root, checksumRoot, 'SHA256SUMS'), 'utf8')).trim().split('\n')) {
  const [hash, name] = line.split('  ');
  const path = `${checksumRoot}/${name}`;
  if (entries.has(path)) {
    if (entries.get(path).sha256 !== hash) fail(`Archived checksum mismatch: ${path}`);
  } else if (sha256(await readFile(resolve(root, path))) !== hash) fail(`Retained checksum mismatch: ${path}`);
}
if (!Array.isArray(manifest.retained_evidence) || manifest.retained_evidence.length === 0) fail('Missing retained evidence inventory');
for (const entry of manifest.retained_evidence) {
  if (!safePath(entry.path) || entries.has(entry.path)) fail(`Invalid retained evidence path: ${entry.path}`);
  if (!await exists(resolve(root, entry.path))) fail(`Incomplete retained evidence bundle: ${entry.path}`);
  const bytes = await readFile(resolve(root, entry.path));
  if (bytes.length !== entry.bytes || sha256(bytes) !== entry.sha256) fail(`Retained evidence changed without dated correction: ${entry.path}`);
}
const args = process.argv.slice(2);
if (args.length && (args.length !== 2 || args[0] !== '--records-dir')) fail('Usage: bun scripts/ci/check-records-retention.mjs [--records-dir /absolute/checkout]');
if (args.length) {
  const archiveRoot = resolve(args[1]);
  for (const entry of entries.values()) {
    const bytes = execFileSync('git', ['show', `${manifest.archive_commit}:${entry.archive_path}`], { cwd: archiveRoot, maxBuffer: Math.max(entry.bytes + 1024, 1024 * 1024) });
    if (bytes.length !== entry.bytes || sha256(bytes) !== entry.sha256) fail(`Published archive checksum mismatch: ${entry.archive_path}`);
  }
}
console.log(`Records retention passed: ${entries.size} archived files; active references, fixtures and original checksum companions verified${args.length ? '; pinned archive bytes verified' : ''}.`);
