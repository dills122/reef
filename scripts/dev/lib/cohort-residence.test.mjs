import assert from 'node:assert/strict';
import test from 'node:test';
import { reconcileCohortResidence } from './cohort-residence.mjs';

function fixture() {
  const record = (sequence, workFinishedAt, commit) => ({sequence:String(sequence), batchId:`b${sequence}`, payloadChecksum:'a'.repeat(64), commandStream:'commands',partition:0,commandCount:'1',streamSequences:[String(sequence)],workFinishedAt,canonicalCommitObservedAt:commit});
  const snapshot=(records)=>({enabled:true,instanceId:'m1',lastSequence:String(records.length),dropped:'0',duplicate:'0',invalid:'0',records});
  const marker=(sequence, observed)=>({databaseGeneration:'2026-09-23T00:00:00Z',sourceProjectionName:'p',markerId:`m${sequence}`,sourceWatermarks:[{partition:0,lastPartitionSequence:String(sequence)}],databaseSnapshotAt:observed,postCommitObservedAt:observed,postCommitObserved:true});
  return {partitions:[{partition:0,startOffsetInclusive:'0',endOffsetExclusive:'2',accepted:'2'}],before:[snapshot([])],after:[snapshot([record(1,'2026-09-24T00:00:00Z','2026-09-24T00:00:00.100Z'),record(2,'2026-09-24T00:00:01Z','2026-09-24T00:00:01.100Z')])],markers:{lifecycle:[marker(1,'2026-09-24T00:00:00.500Z'),marker(2,'2026-09-24T00:00:01.500Z')],marketData:[marker(2,'2026-09-24T00:00:02Z')]}};
}
test('reconciles every source member and includes early commands in downstream residence bounds',()=>{
 const result=reconcileCohortResidence(fixture());
 assert.equal(result.pass,true);
 assert.equal(result.commandCount,'2');
 assert.equal(result.stages.sourceToLifecycle.maxMs,500);
 assert.equal(result.stages.sourceToMarketData.maxMs,2000);
 assert.equal(result.stages.sourceToMarketData.meanMs,1500);
 assert.equal(result.stages.canonicalToMarketData.maxMs,1900);
});
for (const defect of ['gap','duplicate','restart','drop','missingClock','missingMarker','preCommitMarker','corruptCounter']) test(`rejects incomplete residence authority: ${defect}`,()=>{
 const f=fixture();
 if(defect==='gap') f.after[0].records.pop();
 if(defect==='duplicate') f.after[0].records[1].streamSequences=['1'];
 if(defect==='restart') f.after[0].instanceId='new';
 if(defect==='drop') f.after[0].dropped='1';
 if(defect==='missingClock') f.after[0].records[0].workFinishedAt='';
 if(defect==='missingMarker') f.markers.marketData=[];
 if(defect==='preCommitMarker') f.markers.marketData[0].postCommitObservedAt='2026-09-23T23:59:59Z';
 if(defect==='corruptCounter') delete f.after[0].invalid;
 assert.equal(reconcileCohortResidence(f).pass,false);
});
test('excludes pre-window records without losing exact cohort membership',()=>{
 const f=fixture();f.before[0]=structuredClone(f.after[0]);
 f.before[0].records=f.before[0].records.slice(0,1);f.before[0].lastSequence='1';
 f.partitions[0].startOffsetInclusive='1';f.partitions[0].accepted='1';
 const result=reconcileCohortResidence(f);
 assert.equal(result.pass,true);assert.equal(result.commandCount,'1');assert.equal(result.stages.sourceToMarketData.maxMs,1000);
});

test('mixed or absent database generations invalidate residence',()=>{
 for(const value of ['',undefined,'2026-09-24T00:00:00Z']) {
  const f=fixture();f.markers.marketData[0].databaseGeneration=value;
  assert.equal(reconcileCohortResidence(f).pass,false);
 }
});
