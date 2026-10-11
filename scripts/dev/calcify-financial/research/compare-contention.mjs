import {readFileSync,writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {windowLowerBound} from './summarize-contention.mjs';

// Observation bounds, not confidence intervals or estimates of causal effect.
export function windowBounds(rows,startMs,endMs,knownStartCount) {
  const lower=windowLowerBound(rows,startMs,endMs,knownStartCount);
  if(!lower.proven) return lower;
  const start=knownStartCount===undefined?rows.filter(r=>r.receivedMonotonicMs<=startMs).at(-1):{records:knownStartCount};
  const end=rows.find(r=>r.receivedMonotonicMs>=endMs);
  if(!start||!end) return {...lower,proven:false,reason:'missing exterior observations for upper bound'};
  const countUpper=end.records-start.records;
  if(countUpper<lower.countLower) throw Error('inconsistent count bounds');
  return {...lower,countUpper,rateUpper:countUpper*1000/(endMs-startMs)};
}

export function differenceBounds(control,treatment) {
  if(!control.proven||!treatment.proven) return {proven:false};
  return {proven:true,rateLower:control.rateLower-treatment.rateUpper,rateUpper:control.rateUpper-treatment.rateLower};
}

function main(treatmentDir,controlDir,output) {
  if(!treatmentDir||!controlDir||!output) throw Error('Usage: compare-contention.mjs TREATMENT_DIR CONTROL_DIR NEW_OUTPUT_JSON');
  const read=(dir,name)=>JSON.parse(readFileSync(resolve(dir,name),'utf8'));
  const treatment=read(treatmentDir,'capture/results.json'),control=read(controlDir,'capture/results.json');
  const supervisors={treatment:read(treatmentDir,'supervisor.json'),control:read(controlDir,'supervisor.json')};
  if(supervisors.treatment.restartedResolver!==true||supervisors.control.restartedResolver!==false) throw Error('invalid treatment/control fault execution');
  for(const s of Object.values(supervisors)) if(s.error!==null||s.startedFinancial!==true||s.restartedFinancial!==true) throw Error('missing financial contention/recovery');
  for(const name of ['seconds','pace','workers','preflightOrders','preflightContexts','mode']) {
    if(treatment.config[name]!==control.config[name]) throw Error(`workload mismatch: ${name}`);
  }
  if(!treatment.oracle?.pass||!control.oracle?.pass) throw Error('full source/context integrity required');
  const phases=[[0,60],[60,150],[150,180],[180,300],[60,300],[0,180],[0,300]].map(([start,end])=>{
    const stages={};
    for(const stage of ['verified','resolved']) {
      const bounds={};
      for(const [name,run] of Object.entries({treatment,control})) bounds[name]=windowBounds(run.streams[stage].windows,
        run.loadSpawnMonotonicMs+start*1000,run.loadSpawnMonotonicMs+end*1000,start===0?run.policy.preflightContexts:undefined);
      stages[stage]={...bounds,controlMinusTreatment:differenceBounds(bounds.control,bounds.treatment)};
    }
    return {startSeconds:start,endSeconds:end,...stages};
  });
  const before=phases.find(p=>p.startSeconds===0&&p.endSeconds===180).resolved.controlMinusTreatment;
  const after=phases.find(p=>p.startSeconds===180&&p.endSeconds===300).resolved.controlMinusTreatment;
  const result={schema:'calcify-contention-comparison-v1',phases,
    postMinusPreDifference:differenceBounds(after,before),
    runs:Object.fromEntries(Object.entries({treatment,control}).map(([name,run])=>[name,{pass:run.pass,reasons:run.reasons,
      accepted:run.load.acceptedOrders,acceptedAtHttpDeadline:run.load.acceptedOrdersAtDeadline,dropped:run.load.droppedPairs,
      rates:run.rates,oracle:run.oracle,financialAudit:read(name==='control'?controlDir:treatmentDir,'sql-audit.log'),
      events:supervisors[name].events}])),
    limits:['Rate ranges bound observation timing, not statistical confidence or causal attribution',
      'Sequential fresh cohorts share workload/profile but not identical arrivals, cache state or physical disk occupancy',
      'Financial pacing policy matches; achieved financial progress must be compared separately',
      'Final financial audits include post-load work; no captured-trade financial integration claim']};
  writeFileSync(output,JSON.stringify(result,null,2)+'\n',{flag:'wx'});
  console.log(JSON.stringify({output,controlPass:control.pass,whole:phases.at(-1).resolved,postMinusPreDifference:result.postMinusPreDifference}));
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)) main(...process.argv.slice(2));
