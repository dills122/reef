import { readFileSync, readdirSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve, dirname, delimiter, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';

// Offline, explicit output directory; uses existing Gradle dependency cache only.
const [outputArg, java = 'java', count = '10000', variant = 'baseline'] = process.argv.slice(2);
if (!outputArg || !/^\d+$/.test(count) || +count < 1000 || +count > 50000) {
  throw new Error('Usage: bun run-kernel-probe.mjs NEW_OUTPUT_DIR [JAVA_BINARY] [1000..50000_TRADES] [baseline|streaming]');
}
if (!['baseline', 'streaming'].includes(variant)) throw new Error('Unknown isolated research variant');
const out = resolve(outputArg);
mkdirSync(out); // Refuse overwrite or evidence reuse.
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..');
const cache = join(process.env.GRADLE_USER_HOME || join(process.env.HOME, '.gradle'), 'caches/modules-2/files-2.1');
function jar(group, name, version) {
  const directory = join(cache, group, name, version);
  const candidates = readdirSync(directory).flatMap(hash => readdirSync(join(directory, hash))
    .filter(file => file === `${name}-${version}.jar`).map(file => join(directory, hash, file)));
  if (candidates.length !== 1) throw new Error(`Expected one cached jar: ${group}:${name}:${version}`);
  return candidates[0];
}
const stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.20');
const annotations = jar('org.jetbrains', 'annotations', '13.0');
const runtime = [stdlib, annotations,
  jar('com.fasterxml.jackson.core', 'jackson-databind', '2.22.3'),
  jar('com.fasterxml.jackson.core', 'jackson-core', '2.22.3'),
  jar('com.fasterxml.jackson.core', 'jackson-annotations', '2.22')];
const compiler = [stdlib, annotations,
  ...['kotlin-compiler-embeddable', 'kotlin-build-tools-api', 'kotlin-script-runtime', 'kotlin-daemon-embeddable']
    .map(name => jar('org.jetbrains.kotlin', name, '2.4.20')),
  jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
  jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0')];
const original = join(root, 'services/platform-runtime/src/test/kotlin/com/reef/platform/calcify/financial/FinancialKernel.kt');
const sources = [original,
  join(dirname(fileURLToPath(import.meta.url)), 'KernelProbe.kt')];
if (variant === 'streaming') {
  const source = readFileSync(original, 'utf8');
  const begin = source.indexOf('        private fun canonical(n: JsonNode): String = when {');
  const end = source.indexOf('        private fun equivalent(', begin);
  if (begin < 0 || end < begin || end - begin > 700) throw new Error('Kernel canonical seam changed');
  const fragment = readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'streaming-canonical.kt.txt'), 'utf8');
  sources[0] = join(out, 'FinancialKernel.kt');
  writeFileSync(sources[0], source.slice(0, begin) + fragment + source.slice(end));
}
const sha = path => createHash('sha256').update(readFileSync(path)).digest('hex');
writeFileSync(join(out, 'inputs.json'), JSON.stringify({ variant, original: { path: original, sha256: sha(original) }, sources: sources.map(path => ({ path, sha256: sha(path) })),
  dependencies: [...new Set([...runtime, ...compiler])].map(path => ({ path, sha256: sha(path) })), java, count }, null, 2));
function run(label, args, timeout) {
  const result = spawnSync(java, args, { encoding: 'utf8', timeout, maxBuffer: 4 * 1024 * 1024 });
  writeFileSync(join(out, `${label}.stdout`), result.stdout || '');
  writeFileSync(join(out, `${label}.stderr`), result.stderr || '');
  writeFileSync(join(out, `${label}.status.json`), JSON.stringify({ status: result.status, signal: result.signal,
    error: result.error?.message || null, args }, null, 2));
  if (result.status !== 0 || result.error) throw new Error(`${label} failed; see ${out}`);
  return result.stdout;
}
const classes = join(out, 'classes');
run('compile', ['-Xmx512m', '-cp', compiler.join(delimiter), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
  '-no-stdlib', '-no-reflect', '-jvm-target', '21', '-classpath', runtime.join(delimiter), '-d', classes, ...sources], 120000);
const result = JSON.parse(run('probe', ['-Xms128m', '-Xmx768m', '-cp', [classes, ...runtime].join(delimiter),
  'com.reef.platform.calcify.financial.KernelProbe', count], 180000));
writeFileSync(join(out, 'result.json'), JSON.stringify(result, null, 2));
console.log(JSON.stringify({ output: out, ...result }, null, 2));
