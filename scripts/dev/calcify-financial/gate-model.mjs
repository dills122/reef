import { pathToFileURL } from 'node:url';

// Symbolic exact wire bytes, not a measurement of JS heap, broker fetch or RocksDB.
// Full source history remains durable upstream. Model manifests are independently
// reachable preflight inputs, not headers hidden behind this completion FIFO.
export const DEFAULT_BOUNDS = Object.freeze({
  namespace: 'calcify-e2-v1', windows: 16, members: 4, memberBytes: 32,
  sealBytes: 16, activeWindows: 2, completionBytes: 288,
  fetchItems: 4, fetchBytes: 128, bypassItems: 8, bypassBytes: 256,
  historyItems: 256, historyBytes: 8192,
});
export const EXPERIMENT = Object.freeze({
  schema: 'calcify-e2-gates-v1', bounds: DEFAULT_BOUNDS,
  exhaustive: { windows: 2, membersPerWindow: 1, recordPermutations: 24, grantOrders: 2, takeoverCuts: [0, 1, 2], creditScenarios: 144, prefixScenarios: 12, scenarios: 156 },
  seeded: { firstSeed: 1, lastSeed: 64, windows: 4, membersPerWindow: 3, duplicates: 4, modes: ['prefix', 'credit'], scenarios: 128 },
  fairness: 'An available owner/channel is eventually serviced; drain services every enabled local transition.',
  assumptions: [
    'Immutable explicit manifests reachable independently before opening; exact membership, dependencies and bounded wire sizes.',
    'Prefix slices contiguous by manifest order; no active closure hidden behind another slice.',
    'Credit bypass stages source facts recoverably, within declared independent item/byte capacity; grant is not selective access.',
    'Finite certified source history/tombstones retained for entire experiment; no garbage collection claim.',
    'No source dependency cycles; prefix dependencies refer only to preceding slices. Missing real facts remain wait.',
    'Checkpoint is one atomic whole-model cut; broker transactions/fencing/crash behavior not proved.',
  ],
});

export function manifest(id, members, dependencies = []) {
  return { id, members: members.map(id => ({ id, bytes: 32 })), dependencies,
    completionBytes: members.length * 32, sealBytes: 16 };
}
const grantId = (namespace, id) => JSON.stringify([namespace, id]);
export function item(m, member, epoch = 1) {
  return { kind: 'member', window: m.id, member, bytes: m.members.find(x => x.id === member)?.bytes ?? 32,
    grantId: grantId(DEFAULT_BOUNDS.namespace, m.id), epoch };
}
export function seal(m, epoch = 1) {
  return { kind: 'seal', window: m.id, bytes: m.sealBytes, grantId: grantId(DEFAULT_BOUNDS.namespace, m.id), epoch };
}
const bytes = records => records.reduce((sum, r) => sum + r.bytes, 0);

export class GateModel {
  constructor(mode, manifests, history, bounds = DEFAULT_BOUNDS) {
    if (!['prefix', 'credit'].includes(mode)) throw new Error('UNKNOWN_MODE');
    this.mode = mode; this.bounds = structuredClone(bounds);
    this.manifests = structuredClone(manifests); this.history = structuredClone(history);
    this.epoch = 1; this.fetchPosition = 0; this.readPosition = 0;
    this.fetched = []; this.bypass = []; this.windows = Object.create(null); this.fault = null;
    this.duplicateRecords = 0; this.peakRetainedBytes = 0; this.transitions = 0;
    if (manifests.length > bounds.windows || new Set(manifests.map(m => m.id)).size !== manifests.length) throw new Error('MANIFEST_WINDOWS');
    for (const m of manifests) {
      if (m.members.length > bounds.members || new Set(m.members.map(x => x.id)).size !== m.members.length) throw new Error('MANIFEST_FANOUT');
      if (m.members.some(x => !Number.isSafeInteger(x.bytes) || x.bytes <= 0 || x.bytes > bounds.memberBytes)
        || m.completionBytes !== bytes(m.members) || m.sealBytes !== bounds.sealBytes) throw new Error('MANIFEST_BYTES');
      if (m.dependencies.some(id => !manifests.some(other => other.id === id) || id === m.id)) throw new Error('MANIFEST_DEPENDENCY');
    }
    const visit = (id, path = new Set()) => {
      if (path.has(id)) throw new Error('DEPENDENCY_CYCLE');
      const next = new Set([...path, id]);
      for (const dep of manifests.find(m => m.id === id).dependencies) visit(dep, next);
    };
    manifests.forEach(m => visit(m.id));
    if (mode === 'prefix' && manifests.some((m, i) => m.dependencies.some(dep => manifests.findIndex(m => m.id === dep) >= i)))
      throw new Error('NON_PREFIX_DEPENDENCY');
    if (history.some(r => !Number.isSafeInteger(r.bytes) || r.bytes <= 0 || r.bytes > bounds.memberBytes)
      || history.length > bounds.historyItems || bytes(history) > bounds.historyBytes) throw new Error('SOURCE_HISTORY_BOUNDS');
    if (mode === 'prefix') {
      let last = -1;
      for (const r of history) {
        const index = manifests.findIndex(m => m.id === r.window);
        if (index < last) throw new Error('NON_PREFIX_SLICE');
        last = index;
      }
    }
    this.metadataBytes = manifests.reduce((sum, m) => sum + 64 + m.members.length * 64 + (mode === 'credit' ? 64 : 0), 0);
    this.assertInvariant();
  }

  open(id) {
    const m = this.manifests.find(m => m.id === id);
    if (!m) return { status: 'REJECTED', reason: 'UNKNOWN_WINDOW' };
    const existing = this.windows[id];
    if (existing) return { status: existing.closed ? 'CLOSED' : 'OPEN', grantId: existing.grantId, epoch: this.epoch };
    if (this.fault) return { status: 'REJECTED', reason: 'INTEGRITY_FAULT' };
    if (m.dependencies.some(dep => !this.windows[dep]?.closed)) return { status: 'WAIT', reason: 'DEPENDENCY_WAIT' };
    const active = Object.values(this.windows).filter(w => !w.closed);
    const reserve = m.completionBytes + m.sealBytes;
    if (active.length >= this.bounds.activeWindows || this.reservedBytes() + reserve > this.bounds.completionBytes)
      return { status: 'WAIT', reason: 'HIGH_WATER' };
    if (this.mode === 'prefix' && this.manifests.find(x => !this.windows[x.id]?.closed)?.id !== id)
      return { status: 'WAIT', reason: 'SOURCE_PREFIX_REQUIRED' };
    if (this.mode === 'credit' && !this.bounds.permissionOnlyMutant && !this.closureReachable(id))
      return { status: 'WAIT', reason: 'ACCESS_PATH_UNPROVEN' };
    this.windows[id] = { grantId: grantId(this.bounds.namespace, id), seen: [], sealed: false, closed: false, reserve };
    this.assertInvariant();
    return { status: 'OPEN', grantId: this.windows[id].grantId, epoch: this.epoch };
  }

  closureReachable(newId) {
    // Certificate uses frozen finite source order. This is not a live selective
    // reader: an upstream implementation must supply an equivalent bound.
    const admitted = new Set([...Object.keys(this.windows), newId]);
    const needed = new Set([...admitted].filter(id => !this.windows[id]?.closed));
    const seen = Object.fromEntries([...needed].map(id => [id, new Set(this.windows[id]?.seen ?? [])]));
    const sealed = new Set([...needed].filter(id => this.windows[id]?.sealed));
    let bypassItems = 0, bypassBytes = 0;
    for (const r of [...this.bypass, ...this.history.slice(this.readPosition)]) {
      if (!admitted.has(r.window)) { bypassItems++; bypassBytes += r.bytes; }
      else if (needed.has(r.window)) {
        if (r.kind === 'seal') sealed.add(r.window);
        else seen[r.window].add(r.member);
        const m = this.manifests.find(m => m.id === r.window);
        if (sealed.has(r.window) && m.members.every(member => seen[r.window].has(member.id))) needed.delete(r.window);
      }
      if (bypassItems > this.bounds.bypassItems || bypassBytes > this.bounds.bypassBytes) return false;
      if (!needed.size) return true;
    }
    // Genuine missing source fact remains wait; no synthetic completion invented.
    return true;
  }

  reservedBytes() { return Object.values(this.windows).reduce((sum, w) => sum + (w.closed ? 0 : w.reserve), 0); }

  fetch() {
    // Already-fetched suffix occupies its own budget even when admission pauses.
    while (this.fetchPosition < this.history.length && this.fetched.length < this.bounds.fetchItems) {
      const next = this.history[this.fetchPosition];
      if (bytes(this.fetched) + next.bytes > this.bounds.fetchBytes) break;
      this.fetched.push(next); this.fetchPosition++;
    }
    this.assertInvariant();
  }

  accept(r) {
    const m = this.manifests.find(m => m.id === r.window);
    const w = this.windows[r.window];
    const member = m?.members.find(x => x.id === r.member);
    if (!w || r.grantId !== w.grantId || !Number.isInteger(r.epoch) || r.epoch < 1 || r.epoch > this.epoch
      || (r.kind === 'member' ? !member || member.bytes !== r.bytes : r.kind !== 'seal' || r.bytes !== m.sealBytes)) {
      this.fault = 'UNDECLARED_OR_UNAUTHORIZED_COMPLETION'; return;
    }
    // Earlier epochs remain authorized in-flight deliveries. Timeout is not fencing.
    if (r.kind === 'seal') {
      if (w.sealed) this.duplicateRecords++;
      w.sealed = true;
    } else if (w.seen.includes(r.member)) this.duplicateRecords++;
    else w.seen.push(r.member);
    if (w.sealed && w.seen.length === m.members.length) w.closed = true;
  }

  step() {
    if (this.fault) return false;
    const ready = this.bypass.findIndex(r => this.windows[r.window]);
    if (ready >= 0) { this.accept(this.bypass.splice(ready, 1)[0]); this.transitions++; this.assertInvariant(); return true; }
    this.fetch();
    if (!this.fetched.length) return false;
    const next = this.fetched[0];
    if (!this.manifests.some(m => m.id === next.window)) { this.fault = 'UNKNOWN_ROUTING'; return false; }
    if (!this.windows[next.window]) {
      if (this.mode === 'prefix' || this.bypass.length >= this.bounds.bypassItems
        || bytes(this.bypass) + next.bytes > this.bounds.bypassBytes) return false;
      this.bypass.push(this.fetched.shift());
    } else this.accept(this.fetched.shift());
    this.readPosition++; this.transitions++; this.assertInvariant(); return true;
  }

  drain() { while (this.step()) {} return this.snapshot(); }
  reassign() { this.epoch++; this.assertInvariant(); }
  expire(id) { return this.windows[id] && !this.windows[id].closed ? { status: 'WAIT', reason: 'FENCING_REQUIRED' } : { status: 'NOOP' }; }
  checkpoint() { return structuredClone({ ...this }); }
  static restore(cut) { const g = Object.assign(Object.create(GateModel.prototype), structuredClone(cut)); g.windows = Object.assign(Object.create(null), g.windows); g.assertInvariant(); return g; }

  assertInvariant() {
    if (this.reservedBytes() > this.bounds.completionBytes || this.fetched.length > this.bounds.fetchItems
      || bytes(this.fetched) > this.bounds.fetchBytes || this.bypass.length > this.bounds.bypassItems
      || bytes(this.bypass) > this.bounds.bypassBytes) throw new Error('RESOURCE_INVARIANT');
    if (this.readPosition + this.fetched.length !== this.fetchPosition) throw new Error('POSITION_INVARIANT');
    for (const [id, w] of Object.entries(this.windows)) {
      const m = this.manifests.find(m => m.id === id);
      if (w.grantId !== grantId(this.bounds.namespace, id) || new Set(w.seen).size !== w.seen.length
        || w.seen.some(id => !m.members.some(x => x.id === id))
        || w.closed !== (w.sealed && w.seen.length === m.members.length)) throw new Error('MEMBERSHIP_INVARIANT');
    }
    const retained = bytes(this.history) + this.metadataBytes + this.reservedBytes() + bytes(this.fetched) + bytes(this.bypass);
    this.peakRetainedBytes = Math.max(this.peakRetainedBytes, retained);
  }

  snapshot() {
    const active = Object.values(this.windows).filter(w => !w.closed);
    let progress = 'OPEN_WAIT';
    if (this.fault) progress = 'INTEGRITY_FAULT';
    else if (this.manifests.every(m => this.windows[m.id]?.closed)) progress = 'COMPLETE';
    else if (active.length && this.fetched.length && !this.windows[this.fetched[0].window]) progress = 'ACCESS_BLOCKED';
    else if (active.length && this.fetchPosition === this.history.length && !this.fetched.length) progress = 'SOURCE_FACT_WAIT';
    const completedMembers = Object.values(this.windows).reduce((sum, w) => sum + w.seen.length, 0);
    return { mode: this.mode, epoch: this.epoch, readPosition: this.readPosition, fetchPosition: this.fetchPosition,
      closed: Object.keys(this.windows).filter(id => this.windows[id].closed).sort(), completedMembers,
      admittedMembership: Object.fromEntries(Object.keys(this.windows).map(id => [id, this.manifests.find(m => m.id === id).members.map(m => m.id)])),
      activeWindows: active.length, reservedBytes: this.reservedBytes(),
      reservedCompletionBytes: Object.entries(this.windows).reduce((sum, [id, w]) => sum + (w.closed ? 0 : this.manifests.find(m => m.id === id).completionBytes), 0),
      reservedSealBytes: active.length * this.bounds.sealBytes,
      retainedItems: this.history.length + this.fetched.length + this.bypass.length + this.manifests.reduce((sum, m) => sum + 1 + m.members.length + (this.mode === 'credit' ? 1 : 0), 0),
      fetchedItems: this.fetched.length,
      fetchedBytes: bytes(this.fetched), bypassItems: this.bypass.length, bypassBytes: bytes(this.bypass),
      upstreamItems: this.history.length, upstreamBytes: bytes(this.history), metadataBytes: this.metadataBytes,
      duplicateRecords: this.duplicateRecords, progress, fault: this.fault, transitions: this.transitions,
      peakRetainedBytes: this.peakRetainedBytes,
      envelopeBytes: bytes(this.history) + this.metadataBytes + this.bounds.completionBytes + this.bounds.fetchBytes + this.bounds.bypassBytes };
  }
}

function permutations(a) {
  if (!a.length) return [[]];
  return a.flatMap((v, i) => permutations(a.filter((_, n) => n !== i)).map(rest => [v, ...rest]));
}

export function exploreSmall() {
  const ms = [manifest('A', ['a']), manifest('B', ['b'])];
  const records = ms.flatMap(m => [item(m, m.members[0].id), seal(m)]);
  const results = { scenarios: 0, failures: [], maxPeakBytes: 0, bounds: EXPERIMENT.exhaustive };
  // Exhaustive permutations × both grant orders × three takeover/restore cuts.
  // All windows admitted; separately named tests cover capacity/access rejection.
  for (const history of permutations(records)) for (const order of [['A', 'B'], ['B', 'A']]) for (const cut of [0, 1, 2]) {
    let g = new GateModel('credit', ms, history);
    order.forEach(id => g.open(id));
    for (let n = 0; n < cut; n++) g.step();
    g.reassign(); g = GateModel.restore(g.checkpoint()); g.drain();
    results.scenarios++;
    results.maxPeakBytes = Math.max(results.maxPeakBytes, g.snapshot().peakRetainedBytes);
    if (g.snapshot().progress !== 'COMPLETE') results.failures.push({ history, order, cut, state: g.snapshot() });
  }
  for (const history of permutations(records)) {
    try { new GateModel('prefix', ms, history); } catch { continue; }
    for (const cut of [0, 1, 2]) {
      let g = new GateModel('prefix', ms, history); g.open('A');
      for (let n = 0; n < cut; n++) g.step();
      g.reassign(); g = GateModel.restore(g.checkpoint()); g.drain(); g.open('B'); g.drain();
      results.scenarios++; results.maxPeakBytes = Math.max(results.maxPeakBytes, g.snapshot().peakRetainedBytes);
      if (g.snapshot().progress !== 'COMPLETE') results.failures.push({ history, cut, mode: 'prefix', state: g.snapshot() });
    }
  }
  return results;
}

function shuffle(a, seed) {
  let state = seed >>> 0;
  for (let n = a.length - 1; n > 0; n--) {
    state = (Math.imul(1664525, state) + 1013904223) >>> 0;
    const j = state % (n + 1); [a[n], a[j]] = [a[j], a[n]];
  }
  return a;
}

export function runSeeded() {
  const results = { scenarios: 0, failures: [], maxPeakBytes: 0, byMode: { prefix: { maxPeakBytes: 0, maxRetainedItems: 0 }, credit: { maxPeakBytes: 0, maxRetainedItems: 0 } }, bounds: EXPERIMENT.seeded };
  for (let seed = 1; seed <= 64; seed++) for (const mode of ['prefix', 'credit']) {
    const ms = Array.from({ length: 4 }, (_, i) => manifest(`W${i}`, ['fill', 'amend', 'cancel']));
    const history = ms.flatMap((m, i) => shuffle([item(m, 'fill'), item(m, 'amend'), seal(m), item(m, 'cancel'), item(m, 'fill')], seed + i));
    let g = new GateModel(mode, ms, history);
    // Same histories for both modes; serial openings exercise high water. No measured
    // independent-domain requirement is assumed in this baseline comparison.
    for (const m of ms) {
      if (g.open(m.id).status !== 'OPEN') throw new Error('SEED_ADMISSION_FAILED');
      g.step(); g.reassign(); g = GateModel.restore(g.checkpoint()); g.drain();
      results.maxPeakBytes = Math.max(results.maxPeakBytes, g.snapshot().peakRetainedBytes);
    }
    results.scenarios++;
    results.byMode[mode].maxPeakBytes = Math.max(results.byMode[mode].maxPeakBytes, g.snapshot().peakRetainedBytes);
    results.byMode[mode].maxRetainedItems = Math.max(results.byMode[mode].maxRetainedItems, g.snapshot().retainedItems);
    if (g.snapshot().progress !== 'COMPLETE' || g.snapshot().completedMembers !== 12)
      results.failures.push({ seed, mode, history, state: g.snapshot() });
  }
  return results;
}

export function report() {
  const a = manifest('A', ['a']), b = manifest('B', ['b']);
  const history = [item(a, 'a'), seal(a), item(b, 'b'), seal(b)];
  const blocked = new GateModel('credit', [a, b], history, { ...DEFAULT_BOUNDS, bypassItems: 0, permissionOnlyMutant: true });
  blocked.open('B'); blocked.drain();
  const reachable = new GateModel('credit', [a, b], history); reachable.open('B'); reachable.drain();
  return { ...EXPERIMENT, exhaustiveResult: exploreSmall(), seededResult: runSeeded(),
    counterexample: { history, grant: 'B', noBypass: blocked.snapshot(), reservedBypass: reachable.snapshot() },
    decision: 'Prefer certified source-prefix slices accepting head-of-line blocking. Credits add independent-domain progress only within proven bypass bounds; no measured need established.',
    liveGap: 'Existing upstream manifests, contiguous closure order and whole-process wire/heap/fetch budgets are not verified. Neither model certifies current gateway or broker liveness.' };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const result = report();
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
  if (result.exhaustiveResult.failures.length || result.seededResult.failures.length) process.exitCode = 1;
}
