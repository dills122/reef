// E1b proposed fixture policy only: one declared owner's cash/share resource pools.
const max = (1n << 63n) - 1n;
const units = value => {
  if (typeof value !== 'bigint' || value < 0n || value > max) throw new RangeError('expected nonnegative exact signed64 units');
  return value;
};
export class ReservationModel {
  #balances;
  #holds = new Map();
  constructor(balances) { this.#balances = new Map(Object.entries(balances).map(([asset, value]) => [asset, units(value)])); }
  hold(owner) { return this.#holds.get(owner)?.amount ?? 0n; }
  available(asset) {
    if (!this.#balances.has(asset)) throw new Error(`undeclared asset: ${asset}`);
    let held = 0n;
    for (const h of this.#holds.values()) if (h.asset === asset) held += h.amount;
    return this.#balances.get(asset) - held;
  }
  reserve(owner, asset, amount) {
    units(amount);
    if (!owner || !owner.startsWith('order-') || amount === 0n) throw new Error('positive hold requires declared order owner');
    if (this.#holds.has(owner)) return { status: 'OWNER_CONFLICT' };
    if (this.available(asset) < amount) return { status: 'INSUFFICIENT' };
    this.#holds.set(owner, { kind: 'ORDER', asset, amount });
    return { status: 'HELD' };
  }
  reserveOrdered(requests) {
    for (const r of requests) if (!Number.isSafeInteger(r.acceptance) || r.acceptance < 0) throw new Error('invalid acceptance order');
    return [...requests].sort((a, b) => a.acceptance - b.acceptance || (a.owner < b.owner ? -1 : a.owner > b.owner ? 1 : 0))
      .map(r => ({ owner: r.owner, ...this.reserve(r.owner, r.asset, r.amount) }));
  }
  transfer(from, to, amount) {
    units(amount);
    const source = this.#holds.get(from);
    if (!source) return { status: 'UNKNOWN_OWNER' };
    if (source.kind !== 'ORDER' || !to.startsWith('obligation-') || amount === 0n) return { status: 'WRONG_OWNER_KIND' };
    const target = this.#holds.get(to);
    if (target && (target.asset !== source.asset || target.parent !== from)) return { status: 'OWNER_CONFLICT' };
    if (source.amount < amount) return { status: 'INSUFFICIENT' };
    source.amount -= amount;
    this.#holds.set(to, { kind: 'OBLIGATION', parent: from, asset: source.asset, amount: (target?.amount ?? 0n) + amount });
    return { status: 'TRANSFERRED' };
  }
  consume(owner, amount) {
    units(amount);
    const h = this.#holds.get(owner);
    if (!h) return { status: 'UNKNOWN_OWNER' };
    if (h.kind !== 'OBLIGATION') return { status: 'WRONG_OWNER_KIND' };
    if (h.amount < amount || amount === 0n) return { status: 'INSUFFICIENT' };
    h.amount -= amount;
    this.#balances.set(h.asset, this.#balances.get(h.asset) - amount);
    return { status: 'CONSUMED' };
  }
  cancelOrder(owner) {
    const h = this.#holds.get(owner);
    if (!h) return { status: 'UNKNOWN_OWNER' };
    if (h.kind !== 'ORDER') return { status: 'WRONG_OWNER_KIND' };
    this.#holds.delete(owner);
    return { status: 'RELEASED' };
  }
  withdraw(asset, amount) {
    units(amount);
    if (this.available(asset) < amount) return { status: 'INSUFFICIENT' };
    this.#balances.set(asset, this.#balances.get(asset) - amount);
    return { status: 'WITHDRAWN' };
  }
  snapshot() {
    return { balances: Object.fromEntries([...this.#balances].map(([k, v]) => [k, v.toString()])), holds: Object.fromEntries([...this.#holds].map(([k, v]) => [k, { ...v, amount: v.amount.toString() }])) };
  }
}
