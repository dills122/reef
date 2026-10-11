// Executable research model, NOT runtime policy or a scalable financial authority.
// One run, currency, instrument and serialized owner. Values are integer minor units.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

export const POLICY = 'arena-receivable-credit-research-v1';
const max = (a, b) => a > b ? a : b;
const min = (a, b) => a < b ? a : b;
const sum = (items) => items.reduce((a, b) => a + b, 0n);
const demand = (ok, reason) => { if (!ok) throw new Error(reason); };
const units = (value) => {
  demand(typeof value === 'string' && /^(0|[1-9][0-9]*)$/.test(value), 'INVALID_UNITS');
  return BigInt(value);
};
const name = (value) => {
  demand(typeof value === 'string' && /^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(value)
    && !Object.hasOwn(Object.prototype, value), 'INVALID_ID');
  return value;
};
export function canonical(value) {
  if (typeof value === 'bigint') return JSON.stringify(value.toString());
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') return `{${Object.keys(value).sort()
    .map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
  return JSON.stringify(value);
}
export const digest = (value) => createHash('sha256').update(canonical(value)).digest('hex');

export function genesis(config) {
  demand(config.policy === POLICY, 'POLICY_MISMATCH');
  const accounts = {};
  for (const row of config.accounts) {
    const id = name(row.id);
    demand(!accounts[id], 'DUPLICATE_ACCOUNT');
    const cash = units(row.cash);
    accounts[id] = { cash, shares: units(row.shares), debt: 0n,
      limit: id === config.house ? 0n : cash / 4n, frozen: false };
  }
  demand(accounts[config.house], 'HOUSE_MISSING');
  const pool = sum(Object.entries(accounts).filter(([id]) => id !== config.house)
    .map(([, a]) => a.cash)) / 10n;
  const initialCash = sum(Object.values(accounts).map((a) => a.cash));
  demand(accounts[config.house].cash >= pool, 'HOUSE_FUNDING_SHORTFALL');
  accounts[config.house].cash -= pool;
  const state = { policy: POLICY, configDigest: digest(config), now: 0,
    accounts, pool, poolCash: pool, initialCash,
    initialShares: sum(Object.values(accounts).map((a) => a.shares)),
    orders: {}, trades: {}, decisions: {}, closed: false };
  verify(state);
  return state;
}

export function exposure(state, id) {
  const a = state.accounts[id];
  demand(a, 'ACCOUNT_MISSING');
  const pending = Object.values(state.trades).filter((t) => t.status !== 'SETTLED');
  const orders = Object.values(state.orders).filter((o) => o.account === id);
  const receivable = sum(pending.filter((t) => t.seller === id && t.eligible).map((t) => t.amount));
  const payable = sum(pending.filter((t) => t.buyer === id).map((t) => t.amount));
  const hold = sum(orders.filter((o) => o.side === 'BUY').map((o) => o.remaining * o.price));
  const shareHold = sum(orders.filter((o) => o.side === 'SELL').map((o) => o.remaining))
    + sum(pending.filter((t) => t.seller === id).map((t) => t.quantity));
  const credit = a.debt + max(0n, payable + hold - a.cash);
  return { receivable, payable, hold, shareHold, credit };
}

export function verify(state) {
  let totalCredit = 0n;
  for (const [id, a] of Object.entries(state.accounts)) {
    const e = exposure(state, id);
    assert(a.cash >= 0n && a.shares >= e.shareHold && a.debt >= 0n, `ASSET_BOUND:${id}`);
    assert(e.credit <= a.limit, `ACCOUNT_CAP:${id}`);
    assert(a.frozen || e.credit <= e.receivable, `COLLATERAL:${id}`);
    totalCredit += e.credit;
  }
  assert(totalCredit <= state.pool, 'POOL_CAP');
  assert.equal(state.poolCash + sum(Object.values(state.accounts).map((a) => a.debt)), state.pool);
  assert(state.poolCash >= 0n, 'POOL_LIQUIDITY');
  assert.equal(state.poolCash + sum(Object.values(state.accounts).map((a) => a.cash)), state.initialCash);
  assert.equal(sum(Object.values(state.accounts).map((a) => a.shares)), state.initialShares);
  return { totalCredit, poolCash: state.poolCash };
}

function admit(state, c) {
  const id = name(c.order);
  demand(!state.orders[id], 'ORDER_EXISTS');
  const account = name(c.account), a = state.accounts[account];
  demand(a, 'ACCOUNT_MISSING');
  demand(!state.closed && !a.frozen, 'ADMISSION_FROZEN');
  demand(c.side === 'BUY' || c.side === 'SELL', 'INVALID_SIDE');
  const quantity = units(c.quantity), price = units(c.price);
  demand(quantity > 0n && price > 0n, 'INVALID_SIZE');
  state.orders[id] = { account, side: c.side, remaining: quantity, price };
  const e = exposure(state, account);
  demand(e.shareHold <= a.shares, 'INSUFFICIENT_SETTLED_SHARES');
  demand(e.credit <= a.limit, 'ACCOUNT_CREDIT_LIMIT');
  demand(e.credit <= e.receivable, 'INSUFFICIENT_ELIGIBLE_RECEIVABLE');
  demand(sum(Object.keys(state.accounts).map((key) => exposure(state, key).credit)) <= state.pool,
    'RUN_CREDIT_LIMIT');
}

function book(state, c) {
  const id = name(c.trade);
  const identity = digest({ buy: c.buy, sell: c.sell, quantity: c.quantity, price: c.price });
  if (state.trades[id]) {
    demand(state.trades[id].identity === identity, 'TRADE_ID_CONFLICT');
    return 'ALREADY_BOOKED';
  }
  const buy = state.orders[name(c.buy)], sell = state.orders[name(c.sell)];
  demand(buy?.side === 'BUY' && sell?.side === 'SELL', 'ORDERS_MISSING');
  demand(buy.account !== sell.account, 'SELF_TRADE');
  const quantity = units(c.quantity), price = units(c.price);
  demand(quantity > 0n && price > 0n && quantity <= buy.remaining
    && quantity <= sell.remaining && price <= buy.price && price >= sell.price, 'FILL_OUTSIDE_HOLD');
  buy.remaining -= quantity;
  sell.remaining -= quantity;
  state.trades[id] = { identity, buyer: buy.account, seller: sell.account,
    quantity, amount: quantity * price, eligible: true, eligibleAt: state.now, status: 'BOOKED' };
  return 'BOOKED';
}

function settle(state, t) {
  if (t.status === 'SETTLED') return 'ALREADY_SETTLED';
  demand(t.status !== 'FAILED', 'TRADE_FAILED');
  const buyer = state.accounts[t.buyer], seller = state.accounts[t.seller];
  const draw = max(0n, t.amount - buyer.cash);
  demand(draw <= state.poolCash, 'POOL_LIQUIDITY');
  state.poolCash -= draw;
  buyer.debt += draw;
  buyer.cash += draw - t.amount;
  seller.cash += t.amount;
  buyer.shares += t.quantity;
  seller.shares -= t.quantity;
  const repay = min(t.amount, seller.debt);
  seller.debt -= repay;
  seller.cash -= repay;
  state.poolCash += repay;
  t.status = 'SETTLED';
  t.eligible = false;
  return 'SETTLED';
}

function mutate(state, c) {
  if (c.type === 'admit') { admit(state, c); return 'RESERVED'; }
  if (c.type === 'book') return book(state, c);
  if (c.type === 'cancelConfirmed') {
    demand(state.orders[name(c.order)], 'ORDER_MISSING');
    state.orders[c.order].remaining = 0n;
    return 'RELEASED';
  }
  if (c.type === 'cancelRequested') {
    demand(state.orders[name(c.order)], 'ORDER_MISSING');
    return 'CANCEL_PENDING'; // A request is not the matching lane's final disposition.
  }
  if (c.type === 'tick') {
    demand(Number.isSafeInteger(c.time) && c.time >= state.now, 'INVALID_CLOCK');
    state.now = c.time;
    for (const t of Object.values(state.trades)) {
      if (t.status !== 'SETTLED' && t.eligible && state.now - t.eligibleAt >= 30000) {
        t.eligible = false;
        state.accounts[t.seller].frozen = true;
        state.accounts[t.buyer].frozen = true;
      }
    }
    return 'CLOCK_ADVANCED';
  }
  if (c.type === 'resume') {
    const a = state.accounts[name(c.account)];
    demand(a, 'ACCOUNT_MISSING');
    demand(!Object.values(state.trades).some((t) => t.status !== 'SETTLED' && !t.eligible
      && (t.seller === c.account || t.buyer === c.account)), 'UNRESOLVED_EXCEPTION');
    demand(exposure(state, c.account).credit <= exposure(state, c.account).receivable, 'COLLATERAL_SHORTFALL');
    a.frozen = false;
    return 'RESUMED';
  }
  if (c.type === 'close') { state.closed = true; return 'ADMISSION_CLOSED'; }
  if (c.type === 'finalize') {
    demand(state.closed, 'SESSION_OPEN');
    demand(Object.values(state.orders).every((o) => o.remaining === 0n)
      && Object.values(state.trades).every((t) => t.status === 'SETTLED')
      && Object.values(state.accounts).every((a) => a.debt === 0n), 'RECONCILIATION_INCOMPLETE');
    return 'RECONCILED';
  }
  demand(['settle', 'fail', 'repair'].includes(c.type), 'INVALID_COMMAND');
  const t = state.trades[name(c.trade)];
  demand(t, 'TRADE_MISSING');
  if (c.type === 'settle') return settle(state, t);
  demand(t.status !== 'SETTLED', 'FINAL_TRADE');
  if (c.type === 'fail') {
    t.status = 'FAILED';
    t.eligible = false;
    state.accounts[t.seller].frozen = true;
    state.accounts[t.buyer].frozen = true;
    return 'EXCEPTION_RETAINED';
  }
  t.status = 'BOOKED';
  t.eligible = state.now - t.eligibleAt < 30000;
  return 'REPAIRED'; // Admission requires separate explicit resume.
}

export function apply(state, command) {
  // Input shape is trusted by the research harness; auth and wire validation are not modeled.
  const id = name(command.id), fingerprint = digest(command);
  const prior = state.decisions[id];
  if (prior) {
    if (prior.fingerprint !== fingerprint) return { state, result: 'COMMAND_ID_CONFLICT' };
    return { state, result: prior.result };
  }
  let next = structuredClone(state), result;
  try { result = mutate(next, command); }
  catch (error) { next = structuredClone(state); result = error.message; }
  // Invariant failures deliberately escape rather than becoming business rejections.
  verify(next);
  next.decisions[id] = { fingerprint, result };
  return { state: next, result };
}
