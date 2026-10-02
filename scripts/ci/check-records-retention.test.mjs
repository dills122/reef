import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, mkdir, writeFile, readFile, rm } from 'node:fs/promises';
import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';

const checker = await readFile(new URL('./check-records-retention.mjs', import.meta.url));
const hash = data => createHash('sha256').update(data).digest('hex');
async function fixture(t) {
  const root = await mkdtemp(resolve(tmpdir(), 'reef-retention-test-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const put = async (path, data) => { await mkdir(resolve(root, path, '..'), { recursive: true }); await writeFile(resolve(root, path), data); };
  const entry = { source_path: 'docs/archive/old.md', archive_path: 'records/reef/docs/archive/old.md', bytes: 3, sha256: hash('old'), source_blob_oid: 'b'.repeat(40), reason: 'historic' };
  const manifest = { schema_version: 1, archive_repository: 'https://github.com/dills122/reef-records', archive_commit: 'a'.repeat(40), source_commit: 'c'.repeat(40), redirect_paths: [], files: [entry], retained_evidence: [{ path: 'docs/evidence/current.log', bytes: 7, sha256: hash('current') }] };
  await put('scripts/ci/check-records-retention.mjs', checker);
  await put('docs/records/2026-10-02-migration.json', JSON.stringify(manifest));
  await put('docs/README.md', '# Current docs\n');
  await put('docs/evidence/current.log', 'current');
  await put('docs/evidence/calcify-phase2/source-fixture.jsonl', '{}\n');
  await put('docs/evidence/calcify-phase2/hidden-run-source-fixture.jsonl', '{}\n');
  await put('docs/evidence/calcify-phase2-implementation/paired-source-fixture.jsonl', '{}\n');
  await put('docs/evidence/calcify-phase2-implementation/SHA256SUMS', `${hash('{}\n')}  paired-source-fixture.jsonl\n`);
  execFileSync('git', ['init', '-q'], { cwd: root });
  execFileSync('git', ['add', '.'], { cwd: root });
  const run = () => spawnSync(process.execPath, ['scripts/ci/check-records-retention.mjs'], { cwd: root, encoding: 'utf8' });
  return { root, put, run, manifest };
}

test('valid split passes; unrepaired active archive link fails', async t => {
  const f = await fixture(t);
  assert.equal(f.run().status, 0);
  await f.put('docs/README.md', '[Old](archive/old.md)\n');
  const result = f.run();
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Unrepaired archive link/);
});

test('restored historical bulk and missing executable fixture fail', async t => {
  const f = await fixture(t);
  await f.put('docs/archive/old.md', 'old');
  assert.match(f.run().stderr, /Historical source restored/);
  await rm(resolve(f.root, 'docs/archive/old.md'));
  await rm(resolve(f.root, 'docs/evidence/calcify-phase2/source-fixture.jsonl'));
  assert.match(f.run().stderr, /Missing current executable fixture/);
});

test('retained raw evidence tampering fails original checksum', async t => {
  const f = await fixture(t);
  await f.put('docs/evidence/calcify-phase2-implementation/paired-source-fixture.jsonl', '{"changed":true}\n');
  assert.match(f.run().stderr, /Retained checksum mismatch/);
});

test('unsafe relocation path fails before filesystem checks', async t => {
  const f = await fixture(t);
  f.manifest.files[0].source_path = '../outside';
  await f.put('docs/records/2026-10-02-migration.json', JSON.stringify(f.manifest));
  assert.match(f.run().stderr, /Invalid relocation entry/);
});

test('latest bundle inventory catches missing companion and silent correction', async t => {
  const f = await fixture(t);
  await rm(resolve(f.root, 'docs/evidence/current.log'));
  assert.match(f.run().stderr, /Incomplete retained evidence bundle/);
  await f.put('docs/evidence/current.log', 'changed');
  assert.match(f.run().stderr, /Retained evidence changed without dated correction/);
});
