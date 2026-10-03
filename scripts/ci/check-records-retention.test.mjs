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
  await put('docs/records/retained-evidence.json', JSON.stringify({ schema_version: 1, updated_date: '2026-10-02', files: manifest.retained_evidence }));
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


test('all migrations are enforced; historical retained selection can be superseded', async t => {
  const f = await fixture(t);
  const next = { ...f.manifest, archive_commit: 'd'.repeat(40), files: [{ ...f.manifest.files[0], source_path: 'docs/evidence/current.log', archive_path: 'records/reef/docs/evidence/current.log', bytes: 7, sha256: hash('current') }] };
  await f.put('docs/records/2026-10-03-migration.json', JSON.stringify(next));
  await rm(resolve(f.root, 'docs/evidence/current.log'));
  await f.put('docs/evidence/latest.log', 'latest');
  await f.put('docs/records/retained-evidence.json', JSON.stringify({ schema_version: 1, updated_date: '2026-10-03', files: [{ path: 'docs/evidence/latest.log', bytes: 6, sha256: hash('latest') }] }));
  assert.equal(f.run().status, 0, f.run().stderr);
  await f.put('docs/README.md', '[Prior bundle](evidence/current.log)\n');
  assert.match(f.run().stderr, /Unrepaired archive link/);
  await f.put('docs/README.md', '# Current docs\n');
  await f.put('docs/evidence/current.log', 'current');
  assert.match(f.run().stderr, /Historical source restored/);
});

test('duplicate relocation and unsafe redirect in later migration fail', async t => {
  const f = await fixture(t);
  await f.put('docs/records/2026-10-03-migration.json', JSON.stringify(f.manifest));
  assert.match(f.run().stderr, /Invalid relocation entry/);
  await f.put('docs/records/2026-10-03-migration.json', JSON.stringify({ ...f.manifest, redirect_paths: ['../outside'] }));
  assert.match(f.run().stderr, /Invalid archive redirect/);
});

test('missing maintained selection fails even when historical manifest includes one', async t => {
  const f = await fixture(t);
  await rm(resolve(f.root, 'docs/records/retained-evidence.json'));
  assert.notEqual(f.run().status, 0);
});

test('destination verification uses each migration pinned commit', async t => {
  const f = await fixture(t);
  const archive = resolve(f.root, 'records-checkout');
  await mkdir(archive);
  const git = args => execFileSync('git', args, { cwd: archive, encoding: 'utf8' }).trim();
  git(['init', '-q']);
  await f.put('records-checkout/records/reef/docs/archive/old.md', 'old');
  git(['add', '.']);
  const commit = () => {
    git(['-c', 'commit.gpgsign=false', '-c', 'user.name=Retention test', '-c', 'user.email=retention@example.invalid', 'commit', '-qm', 'Import']);
    return git(['rev-parse', 'HEAD']);
  };
  f.manifest.archive_commit = commit();
  await f.put('docs/records/2026-10-02-migration.json', JSON.stringify(f.manifest));
  await f.put('records-checkout/records/reef/docs/archive/later.md', 'new');
  git(['add', '.']);
  const next = { ...f.manifest, archive_commit: commit(), files: [{ ...f.manifest.files[0], source_path: 'docs/archive/later.md', archive_path: 'records/reef/docs/archive/later.md', sha256: hash('new') }] };
  await f.put('docs/records/2026-10-03-migration.json', JSON.stringify(next));
  const run = () => spawnSync(process.execPath, ['scripts/ci/check-records-retention.mjs', '--records-dir', archive], { cwd: f.root, encoding: 'utf8' });
  assert.equal(run().status, 0, run().stderr);
  next.archive_commit = f.manifest.archive_commit;
  await f.put('docs/records/2026-10-03-migration.json', JSON.stringify(next));
  assert.notEqual(run().status, 0);
});
