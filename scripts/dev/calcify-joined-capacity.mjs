// Local joined-path capacity evidence. Build load binary/test observers before invoking.
import { spawn } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createInterface } from 'node:readline';
import { randomUUID, createHash } from 'node:crypto';

const root = resolve(import.meta.dirname, '../..');
const required = name => { const value = process.env[name]; if (!value) throw Error(`${name} required`); return value; };
const smokeId = required('CALCIFY_JOINED_SMOKE_ID');
if (!/^[A-Za-z0-9_-]+$/.test(smokeId)) throw Error('invalid smoke ID');
const generation = Number(required('CALCIFY_JOINED_GENERATION'));
const seconds = Number(process.env.CALCIFY_JOINED_SECONDS ?? 300);
const pace = Number(process.env.CALCIFY_JOINED_PAIRS_PER_SECOND ?? 5250);
const workers = Number(process.env.CALCIFY_JOINED_WORKERS ?? 512);
for (const value of [generation, seconds, pace, workers]) if (!Number.isSafeInteger(value) || value < 1) throw Error('positive integer settings required');
const out = resolve(required('CALCIFY_JOINED_OUT_DIR'));
const image = required('CALCIFY_JOINED_IMAGE');
const network = required('CALCIFY_JOINED_NETWORK');
const binary = resolve(required('CALCIFY_JOINED_LOAD_BINARY'));
const source = `REEF_${smokeId.toUpperCase()}_EVENTS`;
const output = `REEF_${smokeId.toUpperCase()}_RESOLVED`;
const runId = randomUUID().slice(0, 8);
const scope = required('CALCIFY_JOINED_SCOPE');
try { if ((await readdir(out)).length) throw Error('output directory must be fresh; preserve prior evidence'); } catch (error) { if (error.code !== 'ENOENT') throw error; }
await mkdir(out, { recursive: true });
const policy = { seconds, offeredOrderCommandsPerSecond: pace * 2, minimumAcceptedAtDeadlinePerSecond: 10000,
  maxLoadOverrunMs: 1000, maxReceiptDrainMs: 5000, maxResolvedDrainMs: 5000, maxResolvedEndGapTrades: 10500, fullFactParityRequired: true };
const commands = [], windows = []; let observerResult, oracleResult, readyResolve, readinessTimer;
const ready = new Promise(r => readyResolve = r);
const testClasses = resolve(root, 'services/platform-runtime/build/classes/kotlin/test');
const baseDocker = ['run', '--rm', '-i', '--network', network, '-v', `${testClasses}:/tmp/calcify-test-classes:ro`,
  '--entrypoint', 'java', image, '-Xms128m', '-Xmx2g', '-cp', '/tmp/calcify-test-classes:/app/platform-runtime/lib/*'];
const active = [];
const containerNames = [`calcify-joined-${runId}-count`, `calcify-joined-${runId}-facts`];
const dockerArgs = (name, javaArgs) => [...baseDocker.slice(0, 1), '--name', name, '--label', 'reef.test=calcify-joined', ...baseDocker.slice(1), ...javaArgs];
function launch(name, command, args, visit = null, envOverrides = {}) {
  commands.push({ name, command, args });
  const log = createWriteStream(resolve(out, `${name}.log`));
  const child = spawn(command, args, { cwd: root, stdio: ['pipe', 'pipe', 'pipe'], env: { ...process.env, ...envOverrides } }); active.push(child);
  child.stdin.on('error', error => { (evidence.stdinErrors ??= []).push(String(error)); });
  const timer = setTimeout(() => child.kill('SIGKILL'), (name === 'full-facts' ? 660 : seconds + 150) * 1000);
  child.stdout.pipe(log, { end: false }); child.stderr.pipe(log, { end: false });
  createInterface({ input: child.stdout }).on('line', line => {
    if (line === 'OBSERVER_READY') readyResolve();
    if (visit && line.startsWith('{')) { try { visit(JSON.parse(line), Date.now()); } catch (error) { console.error(error); child.kill('SIGTERM'); } }
  });
  const done = new Promise((resolveDone, reject) => {
    child.on('error', error => { clearTimeout(timer); reject(error); });
    child.on('close', code => { clearTimeout(timer); log.end(); resolveDone(code); });
  });
  done.catch(() => {});
  return { child, done };
}
const evidence = { runId, smokeId, generation, source, output, scope, policy,
  harnessSha256: createHash('sha256').update(await readFile(import.meta.filename)).digest('hex'), pass: false };
await writeFile(resolve(out, 'frozen-policy.json'), JSON.stringify(evidence, null, 2) + '\n');
try {
  const observer = launch('resolved-count', 'docker', dockerArgs(containerNames[0], ['com.reef.platform.calcify.CalcifyResolverBrokerProbe',
    'actual-measure', 'redpanda:9092', output, String(seconds * pace + 1), String((seconds + 90) * 1000), 'false', '1000', String(generation)]),
    (value, receivedEpochMs) => {
      if (value.window) windows.push({ ...value.window, receivedEpochMs });
      if (value.mark) evidence.observerMark = { ...value.mark, receivedEpochMs };
      if (value.result) { observerResult = value.result; evidence.observerResultReceivedEpochMs = receivedEpochMs; }
    });
  await Promise.race([ready, observer.done.then(code => { throw Error(`observer exited before ready: ${code}`); }),
    new Promise((_, reject) => { readinessTimer = setTimeout(() => reject(Error('observer readiness timeout')), 60000); })]);
  clearTimeout(readinessTimer);
  const loadPath = resolve(out, 'load.json');
  evidence.loadSpawnEpochMs = Date.now();
  const load = launch('load', binary, ['--smoke-id', smokeId, '--receipt-generation', String(generation),
    ...(process.env.CALCIFY_JOINED_INSTRUMENT_IDS ? ['--instrument-ids', process.env.CALCIFY_JOINED_INSTRUMENT_IDS] : []),
    '--duration', `${seconds}s`, '--pairs-per-second', String(pace), '--workers', String(workers), '--report-out', loadPath]);
  evidence.loadExitCode = await load.done;
  evidence.loadResultReceivedEpochMs = Date.now();
  evidence.load = JSON.parse(await readFile(loadPath, 'utf8'));
  if (!evidence.load.deadlineAccounting?.includes('fixed monotonic') || evidence.load.scheduledDeadlineEpochMs !== evidence.load.loadStartedEpochMs + seconds * 1000) throw Error('corrected per-ack deadline loader required');
  evidence.goStartAfterParentSpawnMs = evidence.load.loadStartedEpochMs - evidence.loadSpawnEpochMs;
  if (observer.child.exitCode !== null || observer.child.signalCode !== null) throw Error('observer exited before actual completion target');
  await new Promise((resolveWrite, reject) => observer.child.stdin.write(`mark\nfinish ${evidence.load.completedPairs + 1}\n`, error => error ? reject(error) : resolveWrite()));
  evidence.observerExitCode = await observer.done;
  if (!observerResult) throw Error('observer produced no result');
  evidence.observer = observerResult; evidence.windows = windows;
  // Parent spawn precedes Go load clock; using this earlier deadline conservatively undercounts resolved coverage.
  const deadline = evidence.loadSpawnEpochMs + seconds * 1000;
  const covered = windows.filter(window => window.receivedEpochMs <= deadline).at(-1)?.records ?? 0;
  evidence.resolvedCoveredBeforeConservativeDeadline = covered;
  evidence.resolvedEndGapUpperTrades = Math.max(0, evidence.load.completedPairs + 1 - covered);
  const allObserved = windows.find(window => window.records >= evidence.load.completedPairs + 1)?.receivedEpochMs ?? evidence.observerResultReceivedEpochMs;
  // Count is sampled at1s; endpoint includes observation/transport time and is a conservative drain bound, not per-trade latency.
  evidence.resolvedDrainUpperMs = Math.max(0, allObserved - (evidence.loadSpawnEpochMs + evidence.load.elapsedMs));
  evidence.resolvedDrainFromScheduledDeadlineUpperMs = Math.max(0, allObserved - deadline);
  evidence.acceptedAtDeadlinePerSecond = evidence.load.acceptedOrdersAtDeadline / seconds;
  const oracle = launch('full-facts', 'docker', dockerArgs(containerNames[1], ['com.reef.platform.calcify.CalcifyActualFullFactObserver',
    'redpanda:9092', source, output, String(generation), String(evidence.load.completedPairs + 1), '600']), value => { oracleResult = value.result ?? value; });
  evidence.oracleExitCode = await oracle.done; evidence.oracle = oracleResult;
  const accountingPath = resolve(out, 'phase1-accounting.json');
  const accounting = launch('phase1-accounting', 'bun', ['scripts/dev/calcify-verify-existing.mjs'], null,
    { DEV_CALCIFY_LOAD_REPORT: loadPath, DEV_CALCIFY_VERIFY_REPORT: accountingPath });
  evidence.accountingExitCode = await accounting.done;
  evidence.accounting = JSON.parse(await readFile(accountingPath, 'utf8'));
  evidence.pass = seconds >= 300 && evidence.acceptedAtDeadlinePerSecond >= policy.minimumAcceptedAtDeadlinePerSecond &&
    evidence.loadExitCode === 0 && evidence.load.elapsedMs <= seconds * 1000 + policy.maxLoadOverrunMs && evidence.load.gatePassed && evidence.load.failures === 0 && evidence.load.drainMs <= policy.maxReceiptDrainMs &&
    evidence.observerExitCode === 0 && observerResult.pass && evidence.resolvedDrainUpperMs <= policy.maxResolvedDrainMs &&
    evidence.resolvedEndGapUpperTrades <= policy.maxResolvedEndGapTrades && evidence.oracleExitCode === 0 && oracleResult?.pass === true && evidence.accountingExitCode === 0 && evidence.accounting.pass;
} catch (error) { evidence.error = String(error); }
finally {
  clearTimeout(readinessTimer);
  for (const child of active) if (child.exitCode === null) child.kill('SIGTERM');
  for (const name of containerNames) {
    const cleanup = spawn('docker', ['rm', '-f', name], { stdio: 'ignore' });
    await new Promise(resolveCleanup => { cleanup.on('close', resolveCleanup); cleanup.on('error', resolveCleanup); });
  }
  evidence.commands = commands;
  await writeFile(resolve(out, 'results.json'), JSON.stringify(evidence, null, 2) + '\n');
}
console.log(JSON.stringify({ out, acceptedAtDeadlinePerSecond: evidence.acceptedAtDeadlinePerSecond,
  resolvedEndGapUpperTrades: evidence.resolvedEndGapUpperTrades, resolvedDrainUpperMs: evidence.resolvedDrainUpperMs,
  exact: evidence.oracle?.pass, pass: evidence.pass, error: evidence.error }));
if (!evidence.pass) process.exitCode = 1;
