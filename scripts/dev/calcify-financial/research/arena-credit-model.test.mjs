import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { POLICY, apply, canonical, digest, exposure, genesis, verify } from './arena-credit-model.mjs';
import { replay, transact } from './arena-credit-journal.mjs';

const config = { policy: POLICY, house: 'house', accounts: [
  ...['A', 'B', 'C', 'D'].map((id) => ({ id, cash: '100', shares: '1000' })),
  { id: 'house', cash: '1000', shares: '10000' },
] };
function harness() {
  let state = genesis(config), serial = 0;
  const commands = [];
  const run = (input, expected) => {
    const command = { id: `c${++serial}`, ...input };
    const next = apply(state, command);
    if (expected) assert.equal(next.result, expected, canonical(command));
    state = next.state;
    commands.push(command);
    return next.result;
  };
  const order = (order, account, side, quantity, price = '1', expected = 'RESERVED') =>
    run({ type: 'admit', order, account, side, quantity, price }, expected);
  const trade = (trade, buyer, seller, quantity, settled = false) => {
    order(`${trade}Buy`, buyer, 'BUY', quantity);
    order(`${trade}Sell`, seller, 'SELL', quantity);
    run({ type: 'book', trade, buy: `${trade}Buy`, sell: `${trade}Sell`, quantity, price: '1' }, 'BOOKED');
    if (settled) run({ type: 'settle', trade }, 'SETTLED');
  };
  return { run, order, trade, commands, get state() { return state; } };
}
function creditFixture() {
  const h = harness();
  h.trade('spendA', 'A', 'house', '100', true);
  h.trade('spendB', 'B', 'house', '100', true);
  h.trade('sourceA', 'C', 'A', '25');
  h.trade('sourceB', 'D', 'B', '25');
  return h;
}

test('genesis transfers pool funding; caps cannot grow with profit or be minted', () => {
  const h = harness();
  assert.equal(h.state.pool, 40n);
  assert.equal(h.state.accounts.house.cash, 960n);
  assert.equal(h.state.accounts.A.limit, 25n);
  h.trade('profit', 'house', 'A', '100', true);
  assert.equal(h.state.accounts.A.limit, 25n);
  const missing = structuredClone(config);
  missing.accounts.at(-1).cash = '39';
  assert.throws(() => genesis(missing), /HOUSE_FUNDING_SHORTFALL/);
  assert.throws(() => genesis({ ...config, policy: 'wrong' }), /POLICY_MISMATCH/);
});

test('unsettled sale finances purchase, reserved pool pays first, proceeds repay debt', () => {
  const h = creditFixture();
  h.trade('reuse', 'A', 'house', '25');
  assert.equal(exposure(h.state, 'A').credit, 25n);
  h.run({ type: 'settle', trade: 'reuse' }, 'SETTLED');
  assert.equal(h.state.accounts.A.cash, 0n);
  assert.equal(h.state.accounts.A.debt, 25n);
  assert.equal(h.state.poolCash, 15n);
  h.run({ type: 'settle', trade: 'sourceA' }, 'SETTLED');
  assert.equal(h.state.accounts.A.debt, 0n);
  assert.equal(h.state.poolCash, 40n);
  assert.equal(exposure(h.state, 'A').receivable, 0n);
  h.order('doubleReuse', 'A', 'BUY', '1', '1', 'INSUFFICIENT_ELIGIBLE_RECEIVABLE');
});

test('both settlement orders produce same financial state', () => {
  const finals = [];
  for (const order of [['reuse', 'sourceA'], ['sourceA', 'reuse']]) {
    const h = creditFixture();
    h.trade('reuse', 'A', 'house', '25');
    for (const trade of order) h.run({ type: 'settle', trade }, 'SETTLED');
    finals.push({ accounts: h.state.accounts, poolCash: h.state.poolCash, trades: h.state.trades });
  }
  assert.equal(canonical(finals[0]), canonical(finals[1]));
});

test('account and shared pool holds stop double spending before cash is drawn', () => {
  const h = creditFixture();
  h.order('first', 'A', 'BUY', '25');
  h.order('again', 'A', 'BUY', '1', '1', 'ACCOUNT_CREDIT_LIMIT');
  h.order('global', 'B', 'BUY', '25', '1', 'RUN_CREDIT_LIMIT');
  assert.equal(h.state.accounts.A.debt, 0n);
  h.order('fits', 'B', 'BUY', '15');
  assert.equal(verify(h.state).totalCredit, 40n);
  h.order('over', 'B', 'BUY', '1', '1', 'RUN_CREDIT_LIMIT');
});

test('partial fill converts hold; only confirmed cancellation releases remainder', () => {
  const h = creditFixture();
  h.order('buy', 'A', 'BUY', '20');
  h.order('sell', 'house', 'SELL', '10');
  h.run({ type: 'book', trade: 'partial', buy: 'buy', sell: 'sell', quantity: '10', price: '1' }, 'BOOKED');
  assert.equal(exposure(h.state, 'A').hold, 10n);
  assert.equal(exposure(h.state, 'A').payable, 10n);
  h.run({ type: 'cancelRequested', order: 'buy' }, 'CANCEL_PENDING');
  h.order('tooSoon', 'A', 'BUY', '10', '1', 'ACCOUNT_CREDIT_LIMIT');
  h.run({ type: 'cancelConfirmed', order: 'buy' }, 'RELEASED');
  assert.equal(exposure(h.state, 'A').credit, 10n);
  h.order('nowFits', 'A', 'BUY', '15');
});

test('settled securities reserved once; unsettled purchases cannot be resold', () => {
  const h = harness();
  h.order('all', 'A', 'SELL', '1000');
  h.order('extra', 'A', 'SELL', '1', '1', 'INSUFFICIENT_SETTLED_SHARES');
  h.trade('purchase', 'A', 'house', '10');
  h.order('unsettled', 'A', 'SELL', '10', '1', 'INSUFFICIENT_SETTLED_SHARES');
  h.run({ type: 'settle', trade: 'purchase' }, 'SETTLED');
  h.order('settled', 'A', 'SELL', '10');
});

test('failed source freezes risk, retains commitments and allows funded downstream settlement', () => {
  const h = creditFixture();
  h.trade('reuse', 'A', 'house', '25');
  h.run({ type: 'fail', trade: 'sourceA' }, 'EXCEPTION_RETAINED');
  h.order('frozen', 'A', 'BUY', '1', '1', 'ADMISSION_FROZEN');
  assert.equal(exposure(h.state, 'A').receivable, 0n);
  assert.equal(exposure(h.state, 'A').credit, 25n);
  h.run({ type: 'settle', trade: 'reuse' }, 'SETTLED');
  h.run({ type: 'settle', trade: 'sourceA' }, 'TRADE_FAILED');
  h.run({ type: 'resume', account: 'A' }, 'UNRESOLVED_EXCEPTION');
  h.run({ type: 'repair', trade: 'sourceA' }, 'REPAIRED');
  assert.equal(h.state.accounts.A.frozen, true);
  h.run({ type: 'settle', trade: 'sourceA' }, 'SETTLED');
  h.run({ type: 'resume', account: 'A' }, 'RESUMED');
  assert.equal(h.state.accounts.A.debt, 0n);
});

test('ordered 30s age fence freezes admission without blocking late settlement', () => {
  const h = creditFixture();
  h.run({ type: 'tick', time: 29999 }, 'CLOCK_ADVANCED');
  assert.equal(h.state.accounts.A.frozen, false);
  h.run({ type: 'tick', time: 30000 }, 'CLOCK_ADVANCED');
  assert.equal(h.state.accounts.A.frozen, true);
  assert.equal(exposure(h.state, 'A').receivable, 0n);
  h.run({ type: 'tick', time: 29999 }, 'INVALID_CLOCK');
  h.run({ type: 'repair', trade: 'sourceA' }, 'REPAIRED');
  assert.equal(h.state.trades.sourceA.eligibleAt, 0);
  assert.equal(h.state.trades.sourceA.eligible, false);
  h.run({ type: 'resume', account: 'A' }, 'UNRESOLVED_EXCEPTION');
  h.run({ type: 'settle', trade: 'sourceA' }, 'SETTLED');
});

test('price improvement releases excess hold and out-of-hold fill changes no assets', () => {
  const h = harness();
  h.order('buy', 'A', 'BUY', '20', '2');
  h.order('sell', 'house', 'SELL', '20');
  h.run({ type: 'book', trade: 'invalid', buy: 'buy', sell: 'sell', quantity: '21', price: '1' }, 'FILL_OUTSIDE_HOLD');
  assert.equal(exposure(h.state, 'A').hold, 40n);
  h.run({ type: 'book', trade: 'improved', buy: 'buy', sell: 'sell', quantity: '20', price: '1' }, 'BOOKED');
  assert.equal(exposure(h.state, 'A').payable, 20n);
  assert.equal(exposure(h.state, 'A').hold, 0n);
});

test('stable command/trade identities prevent replayed economic effects and payload substitution', () => {
  const h = creditFixture();
  const book = h.commands.find((c) => c.type === 'book' && c.trade === 'sourceA');
  const prior = digest(h.state);
  assert.equal(apply(h.state, book).result, 'BOOKED');
  assert.equal(digest(apply(h.state, book).state), prior);
  assert.equal(apply(h.state, { ...book, price: '2' }).result, 'COMMAND_ID_CONFLICT');
  h.run({ ...book, id: 'anotherID' }, 'ALREADY_BOOKED');
  h.run({ ...book, id: 'conflictingTrade', quantity: '1' }, 'TRADE_ID_CONFLICT');
  h.run({ type: 'settle', trade: 'sourceA' }, 'SETTLED');
  h.run({ type: 'settle', trade: 'sourceA' }, 'ALREADY_SETTLED');
  h.run({ type: 'fail', trade: 'sourceA' }, 'FINAL_TRADE');
});

test('close needs confirmed cancels and complete settlement/debt reconciliation', () => {
  const h = creditFixture();
  h.order('resting', 'A', 'BUY', '25');
  h.run({ type: 'close' }, 'ADMISSION_CLOSED');
  h.order('closed', 'C', 'BUY', '1', '1', 'ADMISSION_FROZEN');
  h.run({ type: 'finalize' }, 'RECONCILIATION_INCOMPLETE');
  h.run({ type: 'cancelConfirmed', order: 'resting' }, 'RELEASED');
  for (const trade of ['sourceA', 'sourceB']) h.run({ type: 'settle', trade }, 'SETTLED');
  h.run({ type: 'finalize' }, 'RECONCILED');
});

test('676 sequential competing-order schedules satisfy exact account/pool admission oracle', () => {
  const base = creditFixture().state;
  for (let a = 1; a <= 26; a++) for (let b = 1; b <= 26; b++) {
    let state = base;
    for (const [i, amount] of [a, b].entries()) {
      const account = i === 0 ? 'A' : 'B';
      const next = apply(state, { id: `test${i}`, type: 'admit', order: `order${i}`,
        account, side: 'BUY', quantity: String(amount), price: '1' });
      const firstAccepted = a <= 25 ? a : 0;
      const expected = amount > 25 ? 'ACCOUNT_CREDIT_LIMIT'
        : i === 1 && firstAccepted + amount > 40 ? 'RUN_CREDIT_LIMIT' : 'RESERVED';
      assert.equal(next.result, expected);
      state = next.state;
      verify(state);
    }
  }
});

test('deliberate naive counterexamples: unreserved credit, duplicate proceeds, unfunded settlement', () => {
  // These red witnesses target alternative designs, not failures hidden in the corrected model.
  const cash = 0n, receivable = 25n, limit = 25n;
  const naiveAvailable = cash + (receivable < limit ? receivable : limit);
  assert(20n <= naiveAvailable && 20n <= naiveAvailable && 40n > naiveAvailable);
  const mistakenAfterSettlement = 25n + receivable; // Did not remove pending receivable.
  assert.equal(mistakenAfterSettlement, 50n);
  assert(cash - 25n < 0n); // Credit admission alone cannot fund DvP cash debit.
  assert(25n + 25n > 40n); // Reserving only drawn debt oversubscribes shared liquidity.
});

const root = process.env.CALCIFY_CREDIT_PROOF_DIR
  ?? mkdtempSync(join(tmpdir(), 'calcify-credit-proof-'));
mkdirSync(root, { recursive: true });
const journalCli = fileURLToPath(new URL('./arena-credit-journal.mjs', import.meta.url));
const journey = creditFixture();
journey.trade('reuse', 'A', 'house', '25');
journey.run({ type: 'settle', trade: 'reuse' }, 'SETTLED');
journey.run({ type: 'settle', trade: 'sourceA' }, 'SETTLED');
journey.run({ type: 'fail', trade: 'sourceB' }, 'EXCEPTION_RETAINED');
const targets = journey.commands.filter((c) => (c.type === 'admit' && c.order === 'reuseBuy')
  || (c.type === 'book' && c.trade === 'reuse') || c.type === 'settle' && ['reuse', 'sourceA'].includes(c.trade)
  || c.type === 'fail');

for (const command of targets) for (const cut of ['before-write', 'torn-write', 'after-sync', 'after-ack']) {
  test(`process SIGKILL ${command.type}/${command.trade ?? command.order} at ${cut}`, () => {
    const caseDir = join(root, `${command.id}-${cut}`);
    mkdirSync(caseDir, { recursive: true });
    const journal = join(caseDir, 'journal.jsonl'), input = join(caseDir, 'input.json');
    for (const c of journey.commands.slice(0, journey.commands.indexOf(command))) transact(journal, config, c);
    const before = replay(journal, config).state;
    const expected = apply(before, command);
    writeFileSync(input, canonical({ config, command }));
    const child = spawnSync(process.execPath, [journalCli, journal, input, cut], { encoding: 'utf8', timeout: 15000 });
    assert.equal(child.signal, 'SIGKILL', child.stderr);
    assert.equal(Boolean(child.stdout.trim()), cut === 'after-ack');
    const crashedBytes = readFileSync(journal);
    writeFileSync(join(caseDir, 'journal-at-crash.jsonl'), crashedBytes);
    if (cut === 'torn-write') {
      assert.throws(() => replay(journal, config), /INCOMPLETE_JOURNAL/);
      assert.throws(() => transact(journal, config, command), /INCOMPLETE_JOURNAL/);
      assert.equal(readFileSync(journal).compare(crashedBytes), 0);
    } else {
      assert.equal(digest(replay(journal, config).state), digest(cut === 'before-write' ? before : expected.state));
      assert.equal(transact(journal, config, command).result, expected.result);
      assert.equal(digest(replay(journal, config).state), digest(expected.state));
    }
    writeFileSync(join(caseDir, 'result.json'), `${canonical({ cut, command, signal: child.signal,
      acknowledgement: child.stdout.trim() || null, outcome: cut === 'torn-write' ? 'FAIL_CLOSED' : 'REPLAY_RETRY_PARITY' })}\n`);
  });
}

test('journal corruption, missing frame, policy change and forged result fail closed', () => {
  const file = join(root, 'tamper.jsonl');
  for (const c of journey.commands) transact(file, config, c);
  const bytes = readFileSync(file, 'utf8');
  const committed = replay(file, config);
  const lines = bytes.trimEnd().split('\n');
  writeFileSync(file, lines.slice(0, -1).join('\n') + '\n');
  assert.throws(() => replay(file, config, committed), /COMMITTED_HEAD_MISMATCH/);
  for (const content of [lines.slice(1).join('\n') + '\n', bytes.replace('RESERVED', 'FORGED')]) {
    writeFileSync(file, content);
    assert.throws(() => replay(file, config), /JOURNAL_CHAIN_MISMATCH/);
  }
  const frame = JSON.parse(lines[0]);
  delete frame.hash;
  frame.result = 'FORGED';
  writeFileSync(file, `${canonical({ ...frame, hash: digest(frame) })}\n`);
  assert.throws(() => replay(file, config), /REPLAY_MISMATCH/);
  writeFileSync(file, bytes);
  const changed = structuredClone(config);
  changed.accounts[0].cash = '101';
  assert.throws(() => replay(file, changed), /JOURNAL_CHAIN_MISMATCH/);
});
