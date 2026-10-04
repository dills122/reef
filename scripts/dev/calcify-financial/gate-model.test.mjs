import test from 'node:test';
import assert from 'node:assert/strict';
import { GateModel, DEFAULT_BOUNDS, manifest, item, seal, exploreSmall, runSeeded } from './gate-model.mjs';

const A = manifest('A', ['fill', 'amend']);
const B = manifest('B', ['cancel']);
const log = [item(A, 'fill'), item(A, 'amend'), seal(A), item(B, 'cancel'), seal(B)];

for (const mode of ['prefix', 'credit']) {
  test(`${mode}: complete manifest, duplicate and out-of-order members close once`, () => {
    const g = new GateModel(mode, [A], [item(A, 'amend'), item(A, 'fill'), item(A, 'fill'), seal(A)]);
    const id = g.open('A').grantId;
    g.drain();
    assert.deepEqual(g.snapshot().closed, ['A']);
    assert.equal(g.snapshot().completedMembers, 2);
    assert.equal(g.snapshot().duplicateRecords, 1);
    assert.equal(g.snapshot().reservedBytes, 0);
    assert.equal(g.open('A').grantId, id);
    g.assertInvariant();
  });
  test(`${mode}: unresolved prior fill prevents opening until dependency closure`, () => {
    const dependent = manifest('B', ['cancel'], ['A']);
    const g = new GateModel(mode, [A, dependent], log);
    assert.equal(g.open('B').reason, 'DEPENDENCY_WAIT');
    g.open('A'); g.drain();
    assert.equal(g.open('B').status, 'OPEN'); g.drain();
    assert.deepEqual(g.snapshot().closed, ['A', 'B']);
  });
  test(`${mode}: zero-trade amendment/cancel are explicit coverage members`, () => {
    const m = manifest('lifecycle', ['amend', 'cancel']);
    const g = new GateModel(mode, [m], [item(m, 'amend'), seal(m), item(m, 'cancel')]);
    g.open(m.id); g.drain();
    assert.equal(g.snapshot().completedMembers, 2);
    assert.deepEqual(g.snapshot().closed, [m.id]);
  });
  test(`${mode}: high water leaves fetched suffix in separate bounded budget`, () => {
    const g = new GateModel(mode, [A, B], log, { ...DEFAULT_BOUNDS, activeWindows: 1, fetchItems: 5 });
    g.open('A');
    assert.equal(g.open('B').reason, 'HIGH_WATER');
    g.fetch();
    assert.equal(g.snapshot().fetchedItems, 5);
    g.drain(); g.open('B'); g.drain();
    assert.deepEqual(g.snapshot().closed, ['A', 'B']);
    assert.ok(g.snapshot().peakRetainedBytes <= g.snapshot().envelopeBytes);
  });
  test(`${mode}: reassignment preserves grant membership; timeout never reclaims`, () => {
    const g = new GateModel(mode, [A], [item(A, 'fill'), item(A, 'amend'), seal(A)]);
    const first = g.open('A'); g.step();
    const before = g.snapshot().reservedBytes;
    g.reassign();
    const retry = g.open('A');
    assert.equal(retry.grantId, first.grantId);
    assert.equal(retry.epoch, 2);
    assert.equal(g.expire('A').reason, 'FENCING_REQUIRED');
    assert.equal(g.snapshot().reservedBytes, before);
    const restored = GateModel.restore(g.checkpoint());
    restored.drain();
    assert.equal(restored.snapshot().completedMembers, 2);
    assert.deepEqual(restored.snapshot().closed, ['A']);
  });
  test(`${mode}: closed tombstone rejects late old duplicate without extra capacity`, () => {
    const g = new GateModel(mode, [A], [item(A, 'fill'), item(A, 'amend'), seal(A), item(A, 'fill', 1)]);
    g.open('A'); g.drain();
    assert.equal(g.snapshot().duplicateRecords, 1);
    assert.equal(g.snapshot().completedMembers, 2);
    assert.equal(g.snapshot().reservedBytes, 0);
  });
  test(`${mode}: missing source fact remains waiting, not algorithmic deadlock`, () => {
    const g = new GateModel(mode, [A], [item(A, 'fill'), seal(A)]);
    g.open('A'); g.drain();
    assert.equal(g.snapshot().progress, 'SOURCE_FACT_WAIT');
    assert.deepEqual(g.snapshot().closed, []);
  });
}

test('source-prefix refuses B grant while ungranted A occupies FIFO; credits need bounded bypass', () => {
  const prefix = new GateModel('prefix', [A, B], log);
  assert.equal(prefix.open('B').reason, 'SOURCE_PREFIX_REQUIRED');
  const stuck = new GateModel('credit', [A, B], log, { ...DEFAULT_BOUNDS, bypassItems: 0, permissionOnlyMutant: true });
  stuck.open('B'); stuck.drain();
  assert.equal(stuck.snapshot().progress, 'ACCESS_BLOCKED');
  assert.equal(stuck.snapshot().readPosition, 0);
  const bypass = new GateModel('credit', [A, B], log);
  bypass.open('B'); bypass.drain();
  assert.deepEqual(bypass.snapshot().closed, ['B']);
  assert.equal(bypass.snapshot().bypassItems, 3);
  bypass.open('A'); bypass.drain();
  assert.deepEqual(bypass.snapshot().closed, ['A', 'B']);
  assert.equal(bypass.snapshot().bypassItems, 0);
});

test('seal behind future burst: prefix excludes ordering, credit reserved bypass reaches closure', () => {
  const burst = [item(A, 'fill'), item(B, 'cancel'), seal(B), item(A, 'amend'), seal(A)];
  assert.throws(() => new GateModel('prefix', [A, B], burst), /NON_PREFIX_SLICE/);
  const g = new GateModel('credit', [A, B], burst);
  g.open('A'); g.drain();
  assert.deepEqual(g.snapshot().closed, ['A']);
  assert.equal(g.snapshot().bypassItems, 2);
});

test('future burst beyond bypass envelope produces retained counterexample, no lost admitted membership', () => {
  const burst = [item(A, 'fill'), item(B, 'cancel'), seal(B), item(A, 'amend'), seal(A)];
  const g = new GateModel('credit', [A, B], burst, { ...DEFAULT_BOUNDS, bypassItems: 1, permissionOnlyMutant: true });
  g.open('A'); g.drain();
  assert.equal(g.snapshot().progress, 'ACCESS_BLOCKED');
  assert.equal(g.snapshot().completedMembers, 1);
  assert.equal(g.snapshot().bypassItems, 1);
  assert.equal(g.snapshot().reservedBytes, A.completionBytes + A.sealBytes);
});

test('declared fanout overflow rejected before opening; unknown membership faults without release', () => {
  assert.throws(() => new GateModel('credit', [manifest('huge', ['a', 'b', 'c', 'd', 'e'])], []), /MANIFEST_FANOUT/);
  const g = new GateModel('credit', [A], [{ ...item(A, 'fill'), member: 'unknown' }]);
  g.open('A'); g.drain();
  assert.equal(g.snapshot().progress, 'INTEGRITY_FAULT');
  assert.equal(g.snapshot().completedMembers, 0);
  assert.equal(g.snapshot().reservedBytes, A.completionBytes + A.sealBytes);
});

test('bounded exhaustive and seeded schedule families close all supplied facts within resources', () => {
  const small = exploreSmall();
  assert.equal(small.failures.length, 0);
  assert.equal(small.scenarios, 156);
  const seeded = runSeeded();
  assert.equal(seeded.failures.length, 0);
  assert.equal(seeded.scenarios, 128);
  assert.ok(seeded.maxPeakBytes > 0);
});

test('healthy credit rejects unproved closure path before reserving capacity', () => {
  const g = new GateModel('credit', [A, B], log, { ...DEFAULT_BOUNDS, bypassItems: 0 });
  assert.equal(g.open('B').reason, 'ACCESS_PATH_UNPROVEN');
  assert.equal(g.snapshot().reservedBytes, 0);
  const burst = [item(A, 'fill'), item(B, 'cancel'), seal(B), item(A, 'amend'), seal(A)];
  const short = new GateModel('credit', [A, B], burst, { ...DEFAULT_BOUNDS, bypassItems: 1 });
  assert.equal(short.open('A').reason, 'ACCESS_PATH_UNPROVEN');
  assert.equal(short.snapshot().completedMembers, 0);
});

test('cyclic or forward prefix dependency manifests fail preflight instead of creating deadlock', () => {
  assert.throws(() => new GateModel('credit', [manifest('A', ['a'], ['B']), manifest('B', ['b'], ['A'])], []), /DEPENDENCY_CYCLE/);
  assert.throws(() => new GateModel('prefix', [manifest('A', ['a'], ['B']), manifest('B', ['b'])], []), /NON_PREFIX_DEPENDENCY/);
});
