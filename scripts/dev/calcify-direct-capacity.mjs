// Prepared, fresh direct-path stack only. No setup, receipt SQL, or database reconciliation.
import { spawn } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createInterface } from 'node:readline';
import { createHash, randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';

export function summarizeDirectRun(evidence) {
  const { policy, load, streams, oracle } = evidence;
  const reasons = [];
  const requireGate = (passed, reason) => { if (!passed) reasons.push(reason); };
  const count = value => Number.isSafeInteger(value) && value >= 0;
  requireGate(policy.seconds >= 300, 'diagnostic duration below 300 seconds');
  requireGate(evidence.loadExitCode === 0 && load.gatePassed === true, 'loader gate failed');
  // Go omits receiptGeneration when zero; runner always passes explicit zero.
  requireGate((load.receiptGeneration ?? 0) === 0, 'loader must disable receipt SQL');
  requireGate(load.failures === 0 && load.retries === 0, 'HTTP failures or retries');
  const mode = policy.workloadMode ?? 'paired';
  const ordersPerTrade = mode === 'aggressor' ? 1 : 2;
  const trades = load.completedTrades ?? load.completedPairs;
  requireGate(['paired', 'aggressor'].includes(mode) && (policy.ordersPerTrade ?? 2) === ordersPerTrade &&
    (load.workloadMode ?? 'paired') === mode && (load.ordersPerTrade ?? 2) === ordersPerTrade, 'workload mode/units mismatch');
  requireGate(count(trades) && trades > 0 && (load.completedPairs === undefined || load.completedPairs === trades) &&
    load.acceptedOrders === trades * ordersPerTrade, 'HTTP trade/order accounting mismatch');
  requireGate(load.deadlineAccounting?.includes('fixed monotonic') && load.scheduledDeadlineEpochMs === load.loadStartedEpochMs + policy.seconds * 1000, 'fixed-deadline loader required');
  requireGate(Number.isFinite(load.elapsedMs) && load.elapsedMs <= policy.seconds * 1000 + policy.maxLoadOverrunMs, 'HTTP load overrun exceeded');
  const expected = trades + policy.preflightContexts;
  const deadlineMs = evidence.loadSpawnMonotonicMs + policy.seconds * 1000;
  const completionLowerMs = evidence.loadSpawnMonotonicMs + load.elapsedMs;
  const rates = {};
  for (const name of ['verified', 'resolved']) {
    const stream = streams[name];
    const before = stream.windows.filter(row => row.receivedMonotonicMs <= deadlineMs && count(row.records)).at(-1);
    const covered = Math.max(0, (before?.records ?? 0) - policy.preflightContexts);
    const finalObservedMs = stream.windows.find(row => row.records >= expected)?.receivedMonotonicMs ?? stream.resultReceivedMonotonicMs;
    const drainUpperMs = Math.max(0, finalObservedMs - completionLowerMs);
    const scheduledDeadlineDrainUpperMs = Math.max(0, finalObservedMs - deadlineMs);
    rates[name] = { coveredBeforeConservativeDeadline: covered, contextsPerSecond: covered / policy.seconds,
      endGapUpperContexts: Math.max(0, trades - covered), drainUpperMs, scheduledDeadlineDrainUpperMs };
    requireGate(stream.exitCode === 0 && stream.result?.pass === true && stream.result.records === expected && stream.result.expected === expected, `${name} final accounting failed`);
    requireGate(rates[name].contextsPerSecond >= policy.targetContextsPerSecond, `${name} below target contexts/s`);
    requireGate(Number.isFinite(drainUpperMs) && drainUpperMs <= policy.maxDrainMs, `${name} drain exceeded`);
  }
  requireGate(oracle?.acceptedOrders === load.acceptedOrders + (policy.preflightOrders ?? policy.preflightContexts * 2), 'source/HTTP accepted order accounting mismatch');
  requireGate(evidence.baselinePass === true, 'fresh-topic baseline unproven');
  requireGate(evidence.oracleExitCode === 0 && oracle?.pass === true && oracle.records === expected && oracle.expected === expected && oracle.fullFactParity === true && oracle.orderedUnique === true, 'independent full-fact parity failed');
  return { pass: reasons.length === 0, capacityClaim: reasons.length === 0, reasons, expectedContexts: expected, rates,
    acceptedOrdersAtDeadlinePerSecond: load.acceptedOrdersAtDeadline / policy.seconds,
    completedTradesPerSecondIncludingHttpDrain: trades * 1000 / load.elapsedMs };
}

export function directConfig(env = process.env) {
  const required = name => { const value = env[`CALCIFY_DIRECT_${name}`]; if (!value) throw Error(`CALCIFY_DIRECT_${name} required`); return value; };
  const integer = (name, fallback, minimum = 1) => {
    const value = Number(env[`CALCIFY_DIRECT_${name}`] ?? fallback);
    if (!Number.isSafeInteger(value) || value < minimum) throw Error(`invalid CALCIFY_DIRECT_${name}`);
    return value;
  };
  const smokeId = required('SMOKE_ID');
  if (!/^[A-Za-z0-9_-]+$/.test(smokeId)) throw Error('invalid smoke ID');
  const mode = env.CALCIFY_DIRECT_WORKLOAD_MODE ?? 'paired';
  if (!['paired', 'aggressor'].includes(mode)) throw Error('CALCIFY_DIRECT_WORKLOAD_MODE must be paired or aggressor');
  const preflightContexts = integer('PREFLIGHT_CONTEXTS', 0, 0);
  const preflightOrders = integer('PREFLIGHT_ORDERS', preflightContexts * 2, 0);
  const prefix = `REEF_${smokeId.toUpperCase()}`;
  const config = { smokeId, mode, ordersPerTrade: mode === 'aggressor' ? 1 : 2, preflightOrders, generation: integer('GENERATION', required('GENERATION')), seconds: integer('SECONDS', 300),
    pace: integer('PAIRS_PER_SECOND', 10500), workers: integer('WORKERS', 512), preflightContexts,
    out: resolve(required('OUT_DIR')), image: required('IMAGE'), network: required('NETWORK'), binary: resolve(required('LOAD_BINARY')),
    scope: required('SCOPE'), bootstrap: env.CALCIFY_DIRECT_BOOTSTRAP ?? 'redpanda:9092', baseUrl: env.CALCIFY_DIRECT_BASE_URL ?? 'http://127.0.0.1:8080',
    instruments: env.CALCIFY_DIRECT_INSTRUMENT_IDS, source: env.CALCIFY_DIRECT_SOURCE_TOPIC ?? `${prefix}_EVENTS`,
    verified: env.CALCIFY_DIRECT_VERIFIED_TOPIC ?? `${prefix}_VERIFIED`, resolved: env.CALCIFY_DIRECT_RESOLVED_TOPIC ?? `${prefix}_RESOLVED` };
  if (!Number.isSafeInteger(config.pace * config.seconds) || config.pace * config.seconds + config.preflightContexts > 2147483647) throw Error('observer count exceeds Int range');
  return config;
}

export function directBaselineMatches(baseline, config) {
  return baseline?.verifiedRecords === config.preflightContexts && baseline?.resolvedRecords === config.preflightContexts &&
    baseline?.sourceTrades === config.preflightContexts && baseline?.sourceAcceptedOrders === config.preflightOrders;
}

async function main() {
  const cfg = directConfig();
  const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));
  try { if ((await readdir(cfg.out)).length) throw Error('output directory must be fresh'); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  await mkdir(cfg.out, { recursive: true });
  const clockOrigin = process.hrtime.bigint();
  const monotonicMs = () => Number(process.hrtime.bigint() - clockOrigin) / 1e6;
  const sha256 = async path => createHash('sha256').update(await readFile(path)).digest('hex');
  const runId = randomUUID().slice(0, 8);
  const policy = { seconds: cfg.seconds, targetContextsPerSecond: 10000, workloadMode: cfg.mode, ordersPerTrade: cfg.ordersPerTrade,
    offeredTradesPerSecond: cfg.pace, offeredOrdersPerSecond: cfg.pace * cfg.ordersPerTrade,
    preflightContexts: cfg.preflightContexts, preflightOrders: cfg.preflightOrders, maxLoadOverrunMs: 1000, maxDrainMs: 5000,
    units: cfg.mode === 'aggressor'
      ? 'one timed SELL = one trade = one verified commitment = one resolved context; seeded BUY makers excluded from timed rates'
      : 'one crossing pair = one trade = one verified commitment = one resolved context = two submitted orders',
    deadlineMeasurement: 'read_committed counts received before parent monotonic spawn+duration, minus preflight; conservative lower bound',
    drainMeasurement: 'last observed full count minus parent spawn+Go HTTP elapsed; conservative upper bound, not per-trade latency',
    fullFactParityRequired: true, receiptSqlEnabled: false };
  const evidence = { runId, config: cfg, policy, harnessSha256: await sha256(fileURLToPath(import.meta.url)), loaderSha256: await sha256(cfg.binary),
    commands: [], streams: {}, pass: false, capacityClaim: false };
  await writeFile(resolve(cfg.out, 'frozen-policy.json'), JSON.stringify(evidence, null, 2) + '\n');
  const active = [], names = [];
  const launch = (name, command, args, visit, timeoutMs) => {
    evidence.commands.push({ name, command, args });
    const log = createWriteStream(resolve(cfg.out, `${name}.log`));
    const child = spawn(command, args, { cwd: root, stdio: ['pipe', 'pipe', 'pipe'], env: process.env });
    active.push(child);
    let protocolError;
    let readyResolve;
    const ready = new Promise(r => { readyResolve = r; });
    child.stdout.pipe(log, { end: false }); child.stderr.pipe(log, { end: false });
    child.stdin.on('error', error => { (evidence.stdinErrors ??= []).push(String(error)); });
    const timer = setTimeout(() => child.kill('SIGKILL'), timeoutMs);
    createInterface({ input: child.stdout }).on('line', line => {
      if (line === 'OBSERVER_READY') readyResolve();
      if (visit && line.startsWith('{')) {
        try { visit(JSON.parse(line), { receivedEpochMs: Date.now(), receivedMonotonicMs: monotonicMs() }); }
        catch (error) { protocolError = String(error); child.kill('SIGTERM'); }
      }
    });
    const done = new Promise((resolveDone, reject) => {
      child.on('error', error => { clearTimeout(timer); log.end(); reject(error); });
      child.on('close', code => { clearTimeout(timer); log.end(); resolveDone(protocolError ? -1 : code); });
    });
    done.catch(() => {});
    return { child, done, ready };
  };
  const java = (name, className, args, visit, timeoutMs) => {
    const container = `calcify-direct-${runId}-${name}`; names.push(container);
    return launch(name, 'docker', ['run', '--rm', '-i', '--name', container, '--label', 'reef.test=calcify-direct', '--network', cfg.network,
      '-v', `${resolve(root, 'services/platform-runtime/build/classes/kotlin/test')}:/tmp/calcify-test-classes:ro`, '--entrypoint', 'java', cfg.image,
      '-Xms128m', '-Xmx2g', '-cp', '/tmp/calcify-test-classes:/app/platform-runtime/lib/*', className, ...args.map(String)], visit, timeoutMs);
  };
  const awaitReady = async process => {
    let timer;
    try { await Promise.race([process.ready, process.done.then(code => { throw Error(`observer exited before load: ${code}`); }),
      new Promise((_, reject) => { timer = setTimeout(() => reject(Error('observer readiness timeout')), 60000); })]); }
    finally { clearTimeout(timer); }
  };
  const finish = async (process, expected) => {
    if (process.child.exitCode !== null || process.child.signalCode !== null) throw Error('observer exited before completion target');
    await new Promise((ok, fail) => process.child.stdin.write(`finish ${expected}\n`, error => error ? fail(error) : ok()));
  };
  try {
    const observe = name => {
      const stream = evidence.streams[name] = { windows: [] };
      return (value, received) => {
        if (value.ready) stream.ready = value.ready;
        if (value.window) stream.windows.push({ ...value.window, ...received });
        if (value.result) { stream.result = value.result; stream.resultReceivedMonotonicMs = received.receivedMonotonicMs; stream.resultReceivedEpochMs = received.receivedEpochMs; }
      };
    };
    const observerTimeoutMs = (cfg.seconds + 120) * 1000;
    const verified = java('verified', 'com.reef.platform.calcify.CalcifyVerifiedVisibilityObserver',
      [cfg.bootstrap, cfg.source, cfg.verified, cfg.resolved, cfg.generation, cfg.preflightContexts, observerTimeoutMs, 1000, cfg.preflightOrders], observe('verified'), observerTimeoutMs + 30000);
    const resolved = java('resolved', 'com.reef.platform.calcify.CalcifyResolverBrokerProbe',
      ['actual-measure', cfg.bootstrap, cfg.resolved, cfg.seconds * cfg.pace + cfg.preflightContexts, observerTimeoutMs, 'false', 1000, cfg.generation], observe('resolved'), observerTimeoutMs + 30000);
    await Promise.all([awaitReady(verified), awaitReady(resolved)]);
    const baseline = evidence.streams.verified.ready;
    evidence.baselinePass = directBaselineMatches(baseline, cfg);
    if (!evidence.baselinePass) throw Error('observer did not prove expected preflight baseline');
    evidence.observationStartEpochMs = Date.now();
    await new Promise((ok, fail) => verified.child.stdin.write(`start ${evidence.observationStartEpochMs} ${cfg.seconds * 1000}\n`, error => error ? fail(error) : ok()));
    const loadPath = resolve(cfg.out, 'load.json');
    evidence.loadSpawnEpochMs = Date.now(); evidence.loadSpawnMonotonicMs = monotonicMs();
    const load = launch('load', cfg.binary, ['--base-url', cfg.baseUrl, '--smoke-id', cfg.smokeId, '--receipt-generation', '0', '--workload-mode', cfg.mode,
      ...(cfg.instruments ? ['--instrument-ids', cfg.instruments] : []), '--duration', `${cfg.seconds}s`, '--pairs-per-second', String(cfg.pace),
      '--workers', String(cfg.workers), '--report-out', loadPath], null, (cfg.seconds + 60) * 1000);
    evidence.loadExitCode = await load.done;
    evidence.load = JSON.parse(await readFile(loadPath, 'utf8'));
    const expected = (evidence.load.completedTrades ?? evidence.load.completedPairs) + cfg.preflightContexts;
    if (!Number.isSafeInteger(expected) || expected < 0 || expected > 2147483647) throw Error('invalid final count');
    await Promise.all([finish(verified, expected), finish(resolved, expected)]);
    [evidence.streams.verified.exitCode, evidence.streams.resolved.exitCode] = await Promise.all([verified.done, resolved.done]);
    if (!evidence.streams.verified.result || !evidence.streams.resolved.result) throw Error('observer omitted final result');
    // Full source/output decode happens only after timed HTTP and live observers finish.
    const oracle = java('full-facts', 'com.reef.platform.calcify.CalcifyActualFullFactObserver',
      [cfg.bootstrap, cfg.source, cfg.resolved, cfg.generation, expected, 900], value => { if (value.result) evidence.oracle = value.result; }, 930000);
    evidence.oracleExitCode = await oracle.done;
    Object.assign(evidence, summarizeDirectRun(evidence));
  } catch (error) { evidence.error = String(error); }
  finally {
    for (const child of active) if (child.exitCode === null) child.kill('SIGTERM');
    for (const name of names) {
      const child = spawn('docker', ['rm', '-f', name], { stdio: 'ignore' });
      await new Promise(ok => { child.on('close', ok); child.on('error', ok); });
    }
    await writeFile(resolve(cfg.out, 'results.json'), JSON.stringify(evidence, null, 2) + '\n');
  }
  console.log(JSON.stringify({ out: cfg.out, pass: evidence.pass, capacityClaim: evidence.capacityClaim, rates: evidence.rates, reasons: evidence.reasons, error: evidence.error }));
  if (!evidence.pass) process.exitCode = 1;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
