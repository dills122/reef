import test from 'node:test';
import assert from 'node:assert/strict';
import {windowBounds,differenceBounds} from './compare-contention.mjs';

const rows=[{receivedMonotonicMs:900,records:800},{receivedMonotonicMs:1100,records:1200},
  {receivedMonotonicMs:1900,records:1900},{receivedMonotonicMs:2100,records:2200}];
test('interior lower and exterior upper contain every monotone interval count',()=>{
  const b=windowBounds(rows,1000,2000);
  assert.equal(b.countLower,700);assert.equal(b.countUpper,1400);
  for(let start=800;start<=1200;start+=25) for(let end=1900;end<=2200;end+=25) {
    assert.ok(end-start>=b.countLower&&end-start<=b.countUpper);
  }
});
test('subtracting only lower bounds cannot establish an improvement',()=>{
  const b=differenceBounds({proven:true,rateLower:100,rateUpper:110},{proven:true,rateLower:90,rateUpper:115});
  assert.equal(b.rateLower,-15);assert.equal(b.rateUpper,20);
});
test('unknown exterior sample leaves comparison unproven',()=>{
  const b=windowBounds(rows.slice(1),1000,2000);assert.equal(b.proven,false);
  assert.equal(differenceBounds(b,{proven:true,rateLower:1,rateUpper:2}).proven,false);
});
test('large identical aged population cannot change interval throughput',()=>{
  const a=windowBounds(rows,1000,2000),b=windowBounds(rows.map(r=>({...r,records:r.records+3000000})),1000,2000);
  assert.equal(a.countLower,b.countLower);assert.equal(a.countUpper,b.countUpper);
});
test('known zero baseline and exact boundary observations collapse uncertainty',()=>{
  const b=windowBounds([{receivedMonotonicMs:1000,records:10000}],0,1000,0);
  assert.equal(b.rateLower,10000);assert.equal(b.rateUpper,10000);
});
