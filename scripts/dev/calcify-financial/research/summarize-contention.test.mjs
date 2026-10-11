import test from 'node:test';
import assert from 'node:assert/strict';
import {windowLowerBound} from './summarize-contention.mjs';
const rows=[{receivedMonotonicMs:0,records:0},{receivedMonotonicMs:1100,records:1100},{receivedMonotonicMs:1900,records:2000},{receivedMonotonicMs:2100,records:2300}];
test('uses upper start and lower end counts, never interpolated rate',()=>{
  const value=windowLowerBound(rows,1000,2000);
  assert.equal(value.countLower,900);assert.equal(value.rateLower,900);
  assert.equal(value.startSlackMs,100);assert.equal(value.endSlackMs,100);
});
test('late completion cannot improve deadline coverage',()=>{
  assert.equal(windowLowerBound([...rows,{receivedMonotonicMs:3000,records:9999999}],1000,2000).countLower,900);
});
test('aged population is excluded from measured interval',()=>{
  assert.equal(windowLowerBound(rows.map(r=>({...r,records:r.records+5000000})),1000,2000).countLower,900);
});
test('missing bracketing observations stays unproven',()=>{
  assert.equal(windowLowerBound(rows,2200,2300).proven,false);
  assert.equal(windowLowerBound(rows,1200,1800).proven,false);
});
test('regressing counters or clocks fail closed',()=>{
  assert.throws(()=>windowLowerBound([...rows,{receivedMonotonicMs:2200,records:2}],1000,2000));
  assert.throws(()=>windowLowerBound([...rows,{receivedMonotonicMs:5,records:2400}],1000,2000));
  assert.throws(()=>windowLowerBound(rows,1000,1000));
});
test('known initial baseline gives conservative fresh interval count',()=>{
  assert.equal(windowLowerBound(rows,0,2000,0).countLower,2000);
});
