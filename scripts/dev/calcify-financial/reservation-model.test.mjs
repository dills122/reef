import test from 'node:test';
import assert from 'node:assert/strict';
import { ReservationModel } from './reservation-model.mjs';

for (const [asset, opening, hold, used, residual, available] of [
  ['USD_NANO', 100n, 80n, 50n, 30n, 20n],
  ['ACME_SHARE', 10n, 8n, 5n, 3n, 2n]
]) test(`${asset}: own hold consumption and competing withdrawal stay bounded`, () => {
  const m = new ReservationModel({ [asset]: opening });
  assert.equal(m.reserve('order-A', asset, hold).status, 'HELD');
  assert.equal(m.reserve('order-B', asset, available + 1n).status, 'INSUFFICIENT');
  assert.equal(m.withdraw(asset, available + 1n).status, 'INSUFFICIENT');
  assert.equal(m.transfer('order-A', 'obligation-A', used).status, 'TRANSFERRED');
  const before = m.snapshot();
  assert.equal(m.consume('obligation-A', used + 1n).status, 'INSUFFICIENT');
  assert.deepEqual(m.snapshot(), before, 'failed settlement retains hold');
  assert.equal(m.consume('obligation-A', used).status, 'CONSUMED');
  assert.equal(m.hold('order-A'), residual);
  assert.equal(m.available(asset), available);
  assert.equal(m.cancelOrder('order-A').status, 'RELEASED');
  assert.equal(m.available(asset), opening - used);
});

test('cancel releases unmatched order only; captured obligation stays owned', () => {
  const m = new ReservationModel({ USD_NANO: 100n });
  m.reserve('order-A', 'USD_NANO', 80n);
  m.transfer('order-A', 'obligation-A', 50n);
  m.cancelOrder('order-A');
  assert.equal(m.hold('obligation-A'), 50n);
  assert.equal(m.available('USD_NANO'), 50n);
  assert.equal(m.cancelOrder('obligation-A').status, 'WRONG_OWNER_KIND');
  assert.equal(m.consume('obligation-A', 50n).status, 'CONSUMED');
  assert.equal(m.hold('obligation-A'), 0n);
});

test('creation priority uses acceptance then stable owner ID; competing owner cannot spend hold', () => {
  const m = new ReservationModel({ USD_NANO: 100n });
  const outcomes = m.reserveOrdered([
    { owner: 'order-z', asset: 'USD_NANO', amount: 70n, acceptance: 2 },
    { owner: 'order-b', asset: 'USD_NANO', amount: 50n, acceptance: 1 },
    { owner: 'order-a', asset: 'USD_NANO', amount: 80n, acceptance: 1 }
  ]);
  assert.deepEqual(outcomes.map(x => [x.owner, x.status]), [['order-a', 'HELD'], ['order-b', 'INSUFFICIENT'], ['order-z', 'INSUFFICIENT']]);
  assert.equal(m.consume('order-b', 1n).status, 'UNKNOWN_OWNER');
  assert.equal(m.available('USD_NANO'), 20n);
});

test('exact-unit/range errors and conflicting transfers make no changes', () => {
  const m = new ReservationModel({ USD_NANO: 100n, ACME_SHARE: 10n });
  const before = m.snapshot();
  assert.throws(() => m.reserve('order-A', 'USD_NANO', 1.5));
  assert.throws(() => m.reserve('order-A', 'USD_NANO', -1n));
  assert.throws(() => new ReservationModel({ USD_NANO: 1n << 63n }));
  assert.deepEqual(m.snapshot(), before);
  m.reserve('order-A', 'USD_NANO', 80n);
  m.reserve('order-B', 'ACME_SHARE', 8n);
  m.transfer('order-A', 'obligation-A', 50n);
  const held = m.snapshot();
  assert.equal(m.transfer('order-B', 'obligation-A', 5n).status, 'OWNER_CONFLICT');
  assert.deepEqual(m.snapshot(), held);
});
