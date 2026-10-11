import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// An upper count at interval start and lower count at interval end prevent
// old state, delayed samples or interpolation from inflating aged throughput.
export function windowLowerBound(windows, startMs, endMs, knownStartCount) {
  if(!Number.isFinite(startMs)||!Number.isFinite(endMs)||endMs<=startMs) throw Error('invalid interval');
  let priorTime=-Infinity,priorCount=-1;
  for(const row of windows) {
    if(!Number.isFinite(row.receivedMonotonicMs)||!Number.isSafeInteger(row.records)||row.records<0 ||
       row.receivedMonotonicMs<priorTime||row.records<priorCount) throw Error('non-monotonic observation');
    priorTime=row.receivedMonotonicMs;priorCount=row.records;
  }
  if(knownStartCount!==undefined && (!Number.isSafeInteger(knownStartCount)||knownStartCount<0)) throw Error('invalid baseline');
  const first=knownStartCount===undefined?windows.find(r=>r.receivedMonotonicMs>=startMs):{receivedMonotonicMs:startMs,records:knownStartCount};
  const last=windows.filter(r=>r.receivedMonotonicMs<=endMs).at(-1);
  if(!first||!last||first.receivedMonotonicMs>last.receivedMonotonicMs) return {proven:false,reason:'interval lacks ordered bracketing observations'};
  const count=Math.max(0,last.records-first.records);
  return {proven:true,startCountUpper:first.records,endCountLower:last.records,countLower:count,
    rateLower:count*1000/(endMs-startMs),startSlackMs:first.receivedMonotonicMs-startMs,endSlackMs:endMs-last.receivedMonotonicMs};
}

function main(dir) {
  const out=resolve(dir);const evidence=JSON.parse(readFileSync(resolve(out,'capture/results.json'),'utf8'));
  const supervisor=JSON.parse(readFileSync(resolve(out,'supervisor.json'),'utf8'));
  const origin=evidence.loadSpawnMonotonicMs;
  const phases=[[0,60],[60,150],[150,180],[180,300],[60,300]];
  const result={schema:'calcify-contention-summary-v1',capturePass:evidence.pass,
    captureReasons:evidence.reasons??[],supervisorError:supervisor.error,
    scope:'Measured capture plus independent synthetic financial/SQL contention; no integrated post-trade qualification',
    actualFaultEvents:supervisor.events,phases:phases.map(([start,end])=>({startSeconds:start,endSeconds:end,
      verified:windowLowerBound(evidence.streams.verified.windows,origin+start*1000,origin+end*1000,start===0?evidence.policy.preflightContexts:undefined),
      resolved:windowLowerBound(evidence.streams.resolved.windows,origin+start*1000,origin+end*1000,start===0?evidence.policy.preflightContexts:undefined)})),
    final:{completed:evidence.load?.completedTrades,accepted:evidence.load?.acceptedOrders,
      failures:evidence.load?.failures,retries:evidence.load?.retries,oracle:evidence.oracle},
    limitations:['Bounds use received observations, not actual per-record latency','Phase boundaries fixed to planned fault schedule; actual events reported separately','Resource samples may have gaps while synchronous fault commands run','Financial/SQL audit required separately; capture pass alone cannot qualify contention']};
  writeFileSync(resolve(out,'summary.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result,null,2));
}
if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url)) main(process.argv[2]);
