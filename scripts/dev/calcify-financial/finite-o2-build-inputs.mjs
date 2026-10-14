#!/usr/bin/env node
// Builds the fresh artifacts, source/build manifest and manager lease that finite-o2-proof.mjs requires for a full-scope live phase.
// Builds only; it starts no container and touches no broker or database. The lease it writes is for ONE owner-approved attempt.
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile, readFile, copyFile, cp, realpath, chmod } from 'node:fs/promises';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { ROOT, PG_IMAGE, RP_IMAGE, freezeArtifacts, hashArtifactFile, validateLease } from './finite-o2-proof.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const HARNESS = ['finite-o2-proof.mjs', 'finite-o2-engine-adverse.mjs', 'finite-o2-resource-monitor.mjs', 'finite-o2/compose.yml', 'finite-o2/fixture.json', 'finite-o2/redpanda.yaml'].map(f => `scripts/dev/calcify-financial/${f}`);
export const SOURCES = [
  ...HARNESS,
  'services/matching-engine/cmd/calcify-finite-probe/main.go',
  'scripts/dev/db/migrations/boundary/0013_calcify_finite_ingress.sql',
  'services/platform-runtime/src/main/kotlin/com/reef/platform/calcify/FiniteRuntime.kt',
  'contracts/calcify/finite-binding-v1.json',
  'contracts/calcify/finite-binding-v1.hex',
];

const sha = b => createHash('sha256').update(b).digest('hex');

export async function sourceEntries(root = ROOT, paths = SOURCES) {
  const out = [];
  for (const path of paths) { const b = await readFile(join(root, path)); out.push({ path, bytes: b.length, sha256: sha(b) }); }
  return out;
}

export async function buildManifest({ sourceRevision, sources, probePath, matcherPath, distribution, receiptPaths }) {
  const probe = { path: probePath, ...(await hashArtifactFile(probePath)) };
  const matcher = { path: matcherPath, ...(await hashArtifactFile(matcherPath)) };
  const buildReceipts = [];
  for (const path of receiptPaths) buildReceipts.push({ path, sha256: (await hashArtifactFile(path)).sha256 });
  return { schema: 'calcify-finite-o2-artifact-manifest-v1', sourceRevision, sources, matcher, probe, distribution: await freezeArtifacts(distribution), buildReceipts };
}

export function buildLease({ project, sourceRevision, subnet, roots, images, artifacts, now = Date.now(), ttlMs = 2 * 3600e3 }) {
  return validateLease({
    schema: 'calcify-finite-o2-lease-v1', project, prefix: project, scope: 'full', sourceRevision,
    expiresAt: new Date(now + ttlMs).toISOString(), networkSubnet: subnet,
    proofRoot: roots.proof, secretRoot: roots.secret, stateRoot: roots.state,
    images: { broker: RP_IMAGE, postgres: PG_IMAGE, ...images }, artifacts,
  }, now);
}

const run = (cmd, args, opts = {}) => execFileSync(cmd, args, { cwd: ROOT, encoding: 'utf8', ...opts });

function imageId(ref) { return run('docker', ['image', 'inspect', '--format', '{{.Id}}', ref]).trim(); }

async function main() {
  const o = {}; const a = process.argv.slice(2);
  for (let i = 0; i < a.length; i += 2) { if (!a[i].startsWith('--') || !a[i + 1]) throw Error('usage: --id NAME --subnet 10.x.y.z/28 [--runtime-image REF] [--matcher-image REF]'); o[a[i].slice(2)] = a[i + 1]; }
  if (!/^[a-z0-9-]{1,40}$/.test(o.id ?? '') || !o.subnet) throw Error('usage: --id NAME --subnet 10.x.y.z/28 [--runtime-image REF] [--matcher-image REF]');
  if (run('git', ['status', '--porcelain', '--untracked-files=no']).trim()) throw Error('tracked changes present; the replay must run on a clean checkout');
  const sourceRevision = run('git', ['rev-parse', 'HEAD']).trim();
  const project = `calcify-o2-${o.id}`;
  const base = await realpath('/private/tmp');
  const roots = { proof: join(base, `${project}-proof`), secret: join(base, `${project}-secret`), state: join(base, `${project}-state`) };
  const out = join(base, `${project}-inputs`);
  await mkdir(out, { recursive: true, mode: 0o700 });
  const probePath = join(out, 'finite-probe'), matcherPath = join(out, 'matching-engine'), distribution = join(out, 'platform-runtime');
  const goEnv = { ...process.env, CGO_ENABLED: '0', GOOS: 'linux', GOARCH: process.arch === 'arm64' ? 'arm64' : 'amd64' };
  const logs = [];
  const build = async (name, cmd, args, opts) => { const text = run(cmd, args, { ...opts, stdio: ['ignore', 'pipe', 'pipe'] }); const p = join(out, `build-${name}.log`); await writeFile(p, `$ ${cmd} ${args.join(' ')}\n${text}`); logs.push(p); };
  await build('probe', 'go', ['build', '-trimpath', '-o', probePath, './cmd/calcify-finite-probe'], { cwd: join(ROOT, 'services/matching-engine'), env: goEnv });
  await build('matcher', 'go', ['build', '-trimpath', '-o', matcherPath, './cmd/matching-engine'], { cwd: join(ROOT, 'services/matching-engine'), env: goEnv });
  await build('runtime', './gradlew', ['installDist', '--no-daemon'], { cwd: join(ROOT, 'services/platform-runtime') });
  await cp(join(ROOT, 'services/platform-runtime/build/install/platform-runtime'), distribution, { recursive: true });
  for (const f of [probePath, matcherPath]) await chmod(f, 0o755);
  const manifest = await buildManifest({ sourceRevision, sources: await sourceEntries(), probePath, matcherPath, distribution, receiptPaths: logs });
  const manifestPath = join(out, 'artifact-manifest.json');
  await writeFile(manifestPath, JSON.stringify(manifest, null, 2));
  const lease = buildLease({
    project, sourceRevision, subnet: o.subnet, roots,
    images: { runtime: imageId(o['runtime-image'] ?? 'reef-platform-runtime:latest'), matcher: imageId(o['matcher-image'] ?? 'alpine:latest') },
    artifacts: { sourceManifest: manifestPath, distribution, matcherBinary: matcherPath, probeBinary: probePath },
  });
  const leasePath = join(out, 'lease.json');
  await writeFile(leasePath, JSON.stringify(lease, null, 2), { mode: 0o600 });
  console.log(JSON.stringify({ lease: leasePath, manifest: manifestPath, sourceRevision, expiresAt: lease.expiresAt }));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main().catch(e => { console.error(String(e.message ?? e)); process.exit(1); });
