import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

// E0 input/golden contract only. No financial kernel or accounting oracle here.
const root = resolve(import.meta.dirname, '../../..');
const output = resolve(root, 'docs/evidence/calcify-financial-sprint1/fixtures.json');
const cash = n => (BigInt(n) * 1_000_000_000n).toString();
const max = '9223372036854775807';
const policy = { kernel: 'e1-gross-v1', schema: 'e1-v1', policy: 'gross-p1', reference: 'assets-r1', rounding: 'EXACT', fees: 'NONE', reservation: 'UNRESERVED' };
const trade = { executionId: 'exec-synthetic-1', runId: 'run-é', venueSessionId: 'session-1', instrumentId: 'ACME', buyOrderId: 'a-b', sellOrderId: 'c', buyerAccount: 'buyer', sellerAccount: 'seller', cashAsset: 'USD_NANO', securityAsset: 'ACME_SHARE', quantity: '5', priceNanos: cash(10), dueTick: '0' };
const action = (id, kind, payload = {}) => ({ namespace: 'sprint1', domain: 'domain-1', actionId: id, kind, payload });
const capture = (id = 'capture-1', fields = {}) => action(id, 'CAPTURE', { ...trade, ...fields });
const settle = (id = 'settle-1', attempt = '1', fields = {}) => action(id, 'SETTLE', { executionId: trade.executionId, attempt, ...fields });
const fund = (id, asset, amount) => action(id, 'FUND', { account: asset === 'USD_NANO' ? 'buyer' : 'seller', asset, amount, authority: 'opening-resource-owner' });
const balances = (buyerCash = 100, sellerCash = 0, buyerShares = 0, sellerShares = 10) => ({ buyerCash: cash(buyerCash), sellerCash: cash(sellerCash), buyerShares: String(buyerShares), sellerShares: String(sellerShares) });
const opening = balances();
const paid = balances(50, 50, 5, 5);
const expected = (disposition, balancesValue, extra = {}) => ({ disposition, balances: balancesValue, ...extra });
const pending = { obligationStatus: 'PENDING', cashResidual: cash(50), shareResidual: '5' };
const settled = { obligationStatus: 'SETTLED', cashResidual: '0', shareResidual: '0' };
const step = (input, want) => ({ input, expected: want });
const cases = [];
const add = (id, steps, genesisBalances = opening) => cases.push({ id, genesisBalances, steps });
const captured = step(capture(), expected('CAPTURED', opening, pending));
const discharged = step(settle(), expected('SETTLED', paid, { ...settled, attempt: '1', journalLegs: 4 }));
add('two-asset-success', [captured, discharged]);
add('insufficient-cash', [step(capture(), expected('CAPTURED', balances(40), pending)), step(settle(), expected('INSUFFICIENT_CASH', balances(40), { ...pending, failedAttempt: '1', journalLegs: 0 }))], balances(40));
add('insufficient-shares', [step(capture(), expected('CAPTURED', balances(100, 0, 0, 4), pending)), step(settle(), expected('INSUFFICIENT_SHARES', balances(100, 0, 0, 4), { ...pending, failedAttempt: '1', journalLegs: 0 }))], balances(100, 0, 0, 4));
add('identical-retry', [captured, discharged, step(settle(), expected('PRIOR_RESULT', paid, { ...settled, originalDisposition: 'settle-1', noNewAttempt: true, journalLegs: 0 }))]);
add('changed-request-same-key', [captured, discharged, step(settle('settle-1', '2'), expected('ACTION_CONFLICT', paid, { ...settled, journalLegs: 0 }))]);
add('paid-obligation-new-action', [captured, discharged, step(settle('new-action', '2'), expected('ALREADY_SETTLED', paid, { ...settled, journalLegs: 0 }))]);
add('repeated-execution-capture', [captured, step(capture('capture-again'), expected('EXECUTION_ALREADY_CAPTURED', opening, { ...pending, obligationCount: 1 }))]);
add('conflicting-execution-capture', [captured, step(capture('capture-changed', { quantity: '6' }), expected('EXECUTION_CONFLICT', opening, { ...pending, obligationCount: 1 }))]);
add('retry-after-policy-activation', [captured, discharged, step(action('activate-p2', 'ACTIVATE_POLICY', { policy: 'gross-p2' }), expected('POLICY_ACTIVATED', paid, { activePolicy: 'gross-p2' })), step(settle(), expected('PRIOR_RESULT', paid, { selectedPolicy: 'gross-p1', originalDisposition: 'settle-1', journalLegs: 0 }))]);
add('cash-funding-new-attempt', [step(capture(), expected('CAPTURED', balances(40), pending)), step(settle(), expected('INSUFFICIENT_CASH', balances(40), { ...pending, failedAttempt: '1' })), step(fund('fund-cash', 'USD_NANO', cash(20)), expected('FUNDED', balances(60), { ...pending, journalLegs: 2 })), step(settle('repair-2', '2'), expected('SETTLED', balances(10, 50, 5, 5), { ...settled, attempt: '2', journalLegs: 4 }))], balances(40));
add('security-funding-new-attempt', [step(capture(), expected('CAPTURED', balances(100, 0, 0, 4), pending)), step(settle(), expected('INSUFFICIENT_SHARES', balances(100, 0, 0, 4), { ...pending, failedAttempt: '1' })), step(fund('fund-shares', 'ACME_SHARE', '2'), expected('FUNDED', balances(100, 0, 0, 6), { ...pending, journalLegs: 2 })), step(settle('repair-2', '2'), expected('SETTLED', balances(50, 50, 5, 1), { ...settled, attempt: '2' }))], balances(100, 0, 0, 4));
add('failed-attempt-retry', [step(capture(), expected('CAPTURED', balances(40), pending)), step(settle(), expected('INSUFFICIENT_CASH', balances(40), { ...pending, failedAttempt: '1' })), step(fund('fund-cash', 'USD_NANO', cash(20)), expected('FUNDED', balances(60), pending)), step(settle(), expected('PRIOR_RESULT', balances(60), { ...pending, originalDisposition: 'settle-1', noNewAttempt: true }))], balances(40));
add('wide-multiply-overflow', [step(capture('overflow', { quantity: max, priceNanos: '2' }), expected('AMOUNT_OVERFLOW', opening, { obligationCount: 0, journalLegs: 0 }))]);
const overflowBalances = { ...balances(50), sellerCash: (BigInt(max) - BigInt(cash(50)) + 1n).toString() };
add('credit-overflow-atomic', [step(capture(), expected('CAPTURED', overflowBalances, pending)), step(settle(), expected('BALANCE_OVERFLOW', overflowBalances, { ...pending, journalLegs: 0 }))], overflowBalances);
const missing = { ...trade }; delete missing.buyerAccount;
add('missing-required-payload', [step(action('missing', 'CAPTURE', missing), expected('INVALID_INPUT', opening, { obligationCount: 0, journalLegs: 0 }))]);
add('missing-policy', [step({ ...capture(), requestedPolicy: 'absent-p9' }, expected('MISSING_POLICY', opening, { obligationCount: 0, journalLegs: 0 }))]);
add('zero-quantity', [step(capture('zero', { quantity: '0' }), expected('INVALID_INPUT', opening, { obligationCount: 0, journalLegs: 0 }))]);
add('negative-quantity', [step(capture('negative', { quantity: '-1' }), expected('INVALID_INPUT', opening, { obligationCount: 0, journalLegs: 0 }))]);
add('exact-unit-boundary', [step(capture('one-nano', { quantity: '1', priceNanos: '1' }), expected('CAPTURED', opening, { cashResidual: '1', shareResidual: '1' })), step(settle(), expected('SETTLED', { buyerCash: '99999999999', sellerCash: '1', buyerShares: '1', sellerShares: '9' }, settled))]);
add('due-order-and-staging', [
  step(capture('due-a', { executionId: 'due-a', dueTick: '10' }), expected('CAPTURED', opening, { obligationCount: 1 })),
  step(capture('due-b', { executionId: 'due-b', quantity: '6', dueTick: '10' }), expected('CAPTURED', opening, { obligationCount: 2 })),
  step(action('clock-10', 'CLOCK', { tick: '10' }), expected('CLOCK_ADVANCED', opening, { dueWork: ['due-a', 'due-b'], continuationPhase: 'DRAIN_DUE' })),
  step(action('continue-a', 'CONTINUE', { clockAction: 'clock-10', workId: 'due-a' }), expected('SETTLED', paid, { dueWork: ['due-b'], settledExecutions: ['due-a'] })),
  step(action('continue-b', 'CONTINUE', { clockAction: 'clock-10', workId: 'due-b' }), expected('INSUFFICIENT_CASH', paid, { dueWork: [], pendingExecutions: ['due-b'], failedAttempt: '1' })),
  step(fund('later-funding', 'USD_NANO', cash(20)), expected('FUNDED', balances(70, 50, 5, 5), { pendingExecutions: ['due-b'] })),
  step(fund('later-shares', 'ACME_SHARE', '2'), expected('FUNDED', balances(70, 50, 5, 7), { pendingExecutions: ['due-b'] })),
  step(settle('repair-due-b', '2', { executionId: 'due-b' }), expected('SETTLED', balances(10, 110, 11, 1), { settledExecutions: ['due-a', 'due-b'], dueWork: [] }))
]);

const contract = {
  schema: 'calcify-e0-fixtures-v1', scope: 'synthetic unreserved gross DvP; no production authority',
  policy, units: { cash: 'USD nanos', security: 'whole ACME shares', price: 'USD nanos per whole share', signedStorageBits: 64, intermediate: 'arbitrary precision; checked before int64 storage', rounding: 'exact; no fractional shares' },
  identity: { actionKey: ['namespace', 'domain', 'actionId'], executionKey: ['runId', 'venueSessionId', 'instrumentId', 'executionId'], obligation: 'executionKey plus DVP obligation kind', attempt: 'obligationKey plus explicit ordinal', decision: 'domain plus action/attempt/continuation key', journal: 'decisionKey plus local leg ordinal', framing: 'ordered JSON string arrays, UTF-8, no whitespace; identifiers exact, no trimming/Unicode normalization' },
  normalization: { version: 'request-v1', fields: ['kind', 'requestedPolicy-or-null', 'payload-sorted-by-field'], integers: 'decimal strings normalized through BigInt; reject fractions, exponent, plus sign, whitespace', enums: 'uppercase ASCII', optional: 'absent optional requestedPolicy equals null; required absence rejects; empty identifiers reject', repeatedSets: 'none in v1; future sets require sorted unique canonical members', excluded: ['source locator', 'wall-clock delivery timestamp', 'resolved policy defaults', 'worker identity'] },
  evaluationContext: { selectedOnce: ['kernel', 'schema', 'policy', 'reference', 'rounding', 'fees', 'reservation', 'logicalTick', 'domain'], retry: 'retain original selected context; normalized digest computed before default selection' },
  kernelReady: Object.keys(trade), sourceCausation: ['generation', 'topicUUID', 'partition', 'offset', 'outcomeOrdinal', 'tradeOrdinal', 'originalChecksum'],
  genesis: { logicalTick: '0', historySeq: '0', businessSeq: '0', openingResourceAccount: 'opening', funding: 'two legs per account/asset against explicit opening; opening may be negative as balancing account only', ownerStateFields: ['balances', 'reservations', 'executions', 'workflows', 'obligations', 'instructions', 'attempts', 'exceptions', 'policyActivation', 'logicalTick', 'dueQueue', 'continuations', 'dedup', 'effects', 'entityVersions', 'stagedInputs', 'deliveryCursor', 'historySeq', 'businessSeq'], emptyFields: ['reservations', 'executions', 'workflows', 'obligations', 'instructions', 'attempts', 'exceptions', 'dueQueue', 'continuations', 'dedup', 'effects', 'stagedInputs'] },
  history: { recordKinds: ['GENESIS', 'STAGE', 'BUSINESS'], priorSequenceRequired: true, monotonicPerDomain: true, checkpoint: 'full owner state + certified history sequence/checksum + versions + input membership; replay only strict suffix', deltasRequired: ['journalGroups/legs', 'workflow/obligation/instruction/attempt/exception', 'reservation', 'dueQueue', 'continuation', 'account/entityVersion', 'dedup/context/disposition', 'externalIntent/status', 'policyActivation', 'logicalTick', 'stagedInput/cursor/completedCoverage'], missingHistory: 'fail closed; never highest-seen-as-prefix' },
  comparison: { business: ['balances', 'executions', 'obligationResiduals', 'workflow', 'attempts', 'reservations', 'logicalTick', 'dueQueue', 'continuations', 'dedup/context/disposition', 'effects', 'entityVersions', 'policies', 'businessSeq', 'decisionId', 'semanticDigest'], excludes: ['historySeq', 'deliveryCursor', 'stagedInputs', 'transportTimestamp', 'sourceOffset'], exactHistory: 'compare every owner state field including staged inputs and delivery cursor; exact-history checksum distinct from semantic digest', cut: 'after every business prefix, including failed dispositions; duplicate retry adds no business decision', barrier: 'clock continuation drains canonical due order before later funding can affect economics; staging later input never exposes it to earlier decisions' },
  generation: { prng: 'xorshift32; unsigned shifts 13,17,5; reject zero seed', seeds: [1, 42, 20261003], tracesPerSeed: 100, maxInputsPerTrace: 64, crashAfterEveryHistoryRecord: true, retainMinimalCounterexample: true, mutationControls: ['duplicate-discharge', 'one-leg-mutation', 'missing-staging-delta'] },
  deliverySchedules: [{ id: 'one-at-a-time', yieldBudget: 1, transactionGroup: 1, stageAhead: 0 }, { id: 'stage-all', yieldBudget: 2, transactionGroup: 4, stageAhead: 64 }, { id: 'large-yield', yieldBudget: 64, transactionGroup: 64, stageAhead: 3 }],
  cases,
  reconstructionFixture: {
    id: 'genesis-stage-capture-settle',
    genesis: { balances: opening, openingCash: cash(-100), openingShares: '-10', policies: policy, logicalTick: '0', historySeq: '0', businessSeq: '0', reservations: {}, executions: {}, workflows: {}, obligations: {}, instructions: {}, attempts: {}, exceptions: {}, dueQueue: [], continuations: {}, dedup: {}, effects: {}, entityVersions: { buyer: '1', seller: '1', opening: '1' }, stagedInputs: [], deliveryCursor: null },
    records: [
      { historySeq: '1', priorHistorySeq: '0', kind: 'STAGE', input: capture(), delta: { stagedInputsAdd: ['capture-1'], deliveryCursor: ['synthetic-topic', '0', '10'] }, expected: { stagedInputs: ['capture-1'], businessSeq: '0', balances: opening } },
      { historySeq: '2', priorHistorySeq: '1', kind: 'BUSINESS', businessSeq: '1', priorBusinessSeq: '0', decisionId: ['domain-1', 'capture-1'], delta: { executionAdd: trade, obligationAdd: { id: [trade.executionId, 'DVP'], ...pending }, instructionAdd: { id: [trade.executionId, 'DVP'], status: 'READY' }, workflow: 'PENDING', dedupAdd: { actionId: 'capture-1', disposition: 'CAPTURED', selectedContext: { ...policy, logicalTick: '0', domain: 'domain-1' } }, stagedInputsRemove: ['capture-1'] }, expected: { stagedInputs: [], businessSeq: '1', balances: opening, obligationCount: 1 } },
      { historySeq: '3', priorHistorySeq: '2', kind: 'STAGE', input: settle(), delta: { stagedInputsAdd: ['settle-1'], deliveryCursor: ['synthetic-topic', '0', '12'] }, expected: { stagedInputs: ['settle-1'], businessSeq: '1', balances: opening } },
      { historySeq: '4', priorHistorySeq: '3', kind: 'BUSINESS', businessSeq: '2', priorBusinessSeq: '1', decisionId: ['domain-1', 'settle-1'], delta: { journalGroups: [{ asset: 'USD_NANO', legs: [['buyer', cash(-50)], ['seller', cash(50)]] }, { asset: 'ACME_SHARE', legs: [['seller', '-5'], ['buyer', '5']] }], obligationUpdate: settled, instructionStatus: 'SETTLED', workflow: 'SETTLED', attemptAdd: { ordinal: '1', disposition: 'SETTLED' }, entityVersions: { buyer: '2', seller: '2' }, dedupAdd: { actionId: 'settle-1', disposition: 'SETTLED', selectedContext: { ...policy, logicalTick: '0', domain: 'domain-1' } }, stagedInputsRemove: ['settle-1'] }, expected: { stagedInputs: [], businessSeq: '2', balances: paid, ...settled } }
    ],
    checkpointCuts: ['0', '1', '2', '3', '4'],
    crashCuts: ['0', '1', '2', '3', '4'],
    requiredAssertions: ['rebuild all owner fields from genesis', 'checkpoint at each cut plus strict suffix matches full replay', 'STAGE is durable but not financially complete', 'nonconsecutive physical offsets 10/12 are explicit membership', 'remove staging delta mutant must fail owner-state parity'],
    semanticDigest: 'normalize semantic deltas under schema; exclude delivery/history fields; complete every required workflow/dedup context transition in E1 encoding',
    expectedFullState: 'genesis plus specified ordered deltas; unspecified fields retain prior value; fixture is shape contract, no E0 reconstruction implementation claim'
  },
  reservationPolicyModel: { status: 'PROPOSED; E1b owner review; never activated live', owner: 'order remainder transfers to captured obligation; no implicit release', priority: 'ordered business acceptance then stable owner ID', ownHoldConsumer: 'only named owner up to hold and obligation residual', cashExample: { cashBefore: '100', holdBefore: '80', settles: '50', cashAfter: '50', holdAfter: '30', unrelatedAvailable: '20' }, securityExample: { sharesBefore: '10', holdBefore: '8', settles: '5', sharesAfter: '5', holdAfter: '3', unrelatedAvailable: '2' }, failure: 'retain hold until explicit repair/cancel rule', withdrawal: 'only unheld available; cannot consume another owner hold', cancellation: 'release unmatched remainder only; captured obligation hold stays', casesRequired: ['own-hold', 'competing-owner', 'partial-fill', 'closure-release', 'failed-attempt', 'withdrawal', 'ownership-transfer'] }
};

function normalizeInteger(x) { assert.equal(typeof x, 'string'); assert.match(x, /^-?[0-9]+$/); return BigInt(x).toString(); }
function normalized(a) {
  const numeric = new Set(['quantity', 'priceNanos', 'dueTick', 'attempt', 'amount', 'tick']);
  const payload = Object.keys(a.payload).sort().map(k => [k, numeric.has(k) ? normalizeInteger(a.payload[k]) : a.payload[k]]);
  return JSON.stringify([a.kind.toUpperCase(), a.requestedPolicy ?? null, payload]);
}
function digest(a) { return createHash('sha256').update(normalized(a), 'utf8').digest('hex'); }
assert.notEqual(JSON.stringify(['a-b', 'c']), JSON.stringify(['a', 'b-c']));
assert.equal(digest(settle()), digest({ ...settle(), sourceOffset: '99', worker: 'new', selectedPolicy: 'gross-p2' }));
assert.notEqual(digest(settle()), digest(settle('settle-1', '2')));
assert.equal(digest(capture()), digest(capture('other-id', { quantity: '05' })));
assert.throws(() => normalizeInteger('1e9'));
assert.throws(() => normalizeInteger('1.0'));
assert.equal(new Set(cases.map(x => x.id)).size, cases.length);
for (const c of cases) {
  const b = c.genesisBalances;
  const openingCash = -(BigInt(b.buyerCash) + BigInt(b.sellerCash));
  const openingShares = -(BigInt(b.buyerShares) + BigInt(b.sellerShares));
  assert.ok(openingCash >= -(1n << 63n) && openingShares >= -(1n << 63n), 'opening account overflow');
  c.genesisOpeningAccounts = { cash: openingCash.toString(), shares: openingShares.toString() };
  c.genesisJournalGroups = [
    { asset: 'USD_NANO', legs: [['opening', openingCash.toString()], ['buyer', b.buyerCash], ['seller', b.sellerCash]] },
    { asset: 'ACME_SHARE', legs: [['opening', openingShares.toString()], ['buyer', b.buyerShares], ['seller', b.sellerShares]] }
  ];
}
for (const c of cases) for (const s of c.steps) {
  for (const value of Object.values(s.expected.balances)) assert.match(value, /^-?[0-9]+$/);
  s.normalizedDigest = digest(s.input);
  s.actionKey = JSON.stringify([s.input.namespace, s.input.domain, s.input.actionId]);
}
const text = JSON.stringify(contract, null, 2) + '\n';
if (process.argv.includes('--check')) assert.equal(await readFile(output, 'utf8'), text, 'frozen fixture drift');
else await writeFile(output, text);
console.log(JSON.stringify({ cases: cases.length, inputs: cases.reduce((n, c) => n + c.steps.length, 0), sha256: createHash('sha256').update(text).digest('hex'), checks: 'input normalization/framing/golden stability only; financial correctness requires separate kernel/oracle suite' }));
