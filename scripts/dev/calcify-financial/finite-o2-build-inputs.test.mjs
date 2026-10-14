import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, writeFile, rm, realpath, readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { ROOT, Supervisor, RP_IMAGE, PG_IMAGE } from './finite-o2-proof.mjs';
import { SOURCES, BUILD_PATHS, sourceEntries, buildManifest, buildLease } from './finite-o2-build-inputs.mjs';

const project = `calcify-o2-buildtest-${process.pid}`;
const revision = 'a'.repeat(40);
const imageId = 'sha256:' + 'b'.repeat(64);

async function fixture() {
  const base = await realpath('/private/tmp');
  const out = join(base, `${project}-inputs`);
  const dist = join(out, 'platform-runtime');
  await mkdir(join(dist, 'lib'), { recursive: true }); await mkdir(join(dist, 'bin'), { recursive: true });
  await writeFile(join(dist, 'lib/a.jar'), 'jar'); await writeFile(join(dist, 'bin/platform-runtime'), '#!/bin/sh\n');
  const probe = join(out, 'finite-probe'), matcher = join(out, 'matching-engine'), r1 = join(out, 'build-probe.log'), r2 = join(out, 'build-matcher.log');
  for (const [p, c] of [[probe, 'probe'], [matcher, 'matcher'], [r1, 'r1'], [r2, 'r2']]) await writeFile(p, c);
  const roots = { proof: join(base, `${project}-proof`), secret: join(base, `${project}-secret`), state: join(base, `${project}-state`) };
  const manifest = await buildManifest({ sourceRevision: revision, sources: await sourceEntries(), probePath: probe, matcherPath: matcher, distribution: dist, receiptPaths: [r1, r2] });
  const manifestPath = join(out, 'artifact-manifest.json');
  await writeFile(manifestPath, JSON.stringify(manifest));
  const lease = buildLease({ project, sourceRevision: revision, subnet: '10.77.12.0/28', roots, images: { runtime: imageId, matcher: imageId }, artifacts: { sourceManifest: manifestPath, distribution: dist, matcherBinary: matcher, probeBinary: probe } });
  return { out, roots, manifest, lease, dist, probe };
}
const cleanup = async f => { for (const p of [f.out, f.roots.proof, f.roots.secret, f.roots.state]) await rm(p, { recursive: true, force: true }); };

test('manifest and lease from the builder pass the harness artifact verifier', async () => {
  const f = await fixture();
  try {
    assert.equal(f.lease.images.broker, RP_IMAGE); assert.equal(f.lease.images.postgres, PG_IMAGE); assert.equal(f.lease.scope, 'full');
    const receipt = await new Supervisor(f.lease).verifyArtifacts();
    assert.ok(receipt);
  } finally { await cleanup(f); }
});

test('verifier refuses when a built artifact changes after the manifest is written', async () => {
  const f = await fixture();
  try {
    await writeFile(f.probe, 'tampered');
    await assert.rejects(() => new Supervisor(f.lease).verifyArtifacts(), /drift/);
  } finally { await cleanup(f); }
});

test('verifier refuses a distribution that gained a file', async () => {
  const f = await fixture();
  try {
    await writeFile(join(f.dist, 'lib/extra.jar'), 'x');
    await assert.rejects(() => new Supervisor(f.lease).verifyArtifacts(), /drift/);
  } finally { await cleanup(f); }
});

test('source list hashes the required harness files and exceeds the verifier minimum', async () => {
  const entries = await sourceEntries();
  assert.ok(entries.length >= 10);
  for (const e of entries) assert.match(e.sha256, /^[0-9a-f]{64}$/);
  assert.ok(SOURCES.includes('scripts/dev/calcify-financial/finite-o2-proof.mjs'));
  assert.equal(new Set(SOURCES).size, SOURCES.length);
  assert.ok((await readFile(join(ROOT, SOURCES[0]))).length > 0);
});

test('lease builder refuses an unsafe subnet and expiry in the past', () => {
  const args = { project, sourceRevision: revision, subnet: '192.168.1.0/28', roots: { proof: `/private/tmp/${project}-p`, secret: `/private/tmp/${project}-s`, state: `/private/tmp/${project}-t` }, images: { runtime: imageId, matcher: imageId }, artifacts: { sourceManifest: 'm', distribution: 'd', matcherBinary: 'b', probeBinary: 'p' } };
  assert.throws(() => buildLease(args));
  assert.throws(() => buildLease({ ...args, subnet: '10.1.2.0/28', ttlMs: -1 }));
});

test('a missing manifest source fails with the path named, not a bare ENOENT', async () => {
  await assert.rejects(() => sourceEntries(ROOT, ['scripts/dev/calcify-financial/does-not-exist.mjs']), /does-not-exist\.mjs is missing/);
});

test('every path that feeds the build or the harness is checked for untracked files', () => {
  for (const p of ['services/matching-engine', 'services/platform-runtime/src', 'scripts/dev/db/migrations/boundary', 'scripts/dev/calcify-financial', 'contracts']) assert.ok(BUILD_PATHS.includes(p), p);
  for (const source of SOURCES) assert.ok(BUILD_PATHS.some(p => source === p || source.startsWith(p + '/')), `${source} is outside the checked paths`);
});
