// Every source member contributes to residence; diagnostic markers provide
// conservative post-commit upper bounds, never exact stage commit timestamps.
export function reconcileCohortResidence({ partitions, before, after, markers }) {
  try {
    requireThat(Array.isArray(partitions) && partitions.length > 0, 'missing accepted cohort');
    requireThat(Array.isArray(before) && before.length > 0 && Array.isArray(after) && before.length === after.length, 'missing timing journals');
    const starts = new Map(before.map(snapshot => [snapshot.instanceId, snapshot]));
    requireThat(starts.size === before.length, 'duplicate journal instance');
    const generations = new Set();
    for (const stage of ['lifecycle', 'marketData']) {
      requireThat(Array.isArray(markers?.[stage]) && markers[stage].length > 0, 'missing covering observations');
      for (const marker of markers[stage]) {
        requireThat(typeof marker.databaseGeneration === 'string' && Number.isFinite(Date.parse(marker.databaseGeneration)), 'missing database generation');
        generations.add(marker.databaseGeneration);
      }
    }
    requireThat(generations.size === 1, 'database restarted during cohort');
    const records = [];
    const instances = new Set();
    for (const snapshot of after) {
      const start = starts.get(snapshot.instanceId);
      requireThat(start && !instances.has(snapshot.instanceId) && snapshot.enabled === true && start.enabled === true, 'journal restart or disabled');
      instances.add(snapshot.instanceId);
      for (const counter of ['dropped', 'duplicate', 'invalid']) {
        requireThat(integer(snapshot[counter]) === integer(start[counter]), `journal ${counter}`);
      }
      const low = integer(start.lastSequence), high = integer(snapshot.lastSequence);
      requireThat(high >= low && Array.isArray(snapshot.records), 'journal sequence regressed');
      const selected = snapshot.records.filter(row => integer(row.sequence) > low);
      requireThat(BigInt(selected.length) === high - low, 'journal lost records');
      selected.forEach((row, index) => requireThat(integer(row.sequence) === low + BigInt(index) + 1n, 'journal gap'));
      records.push(...selected);
    }
    const cohort = new Map(partitions.map(row => [row.partition, row]));
    requireThat(cohort.size === partitions.length, 'duplicate cohort partition');
    const members = new Map(partitions.map(row => [row.partition, new Set()]));
    const batches = new Set(), streams = new Set();
    const stages = Object.fromEntries(['sourceToCanonical','sourceToLifecycle','sourceToMarketData','canonicalToLifecycle','canonicalToMarketData'].map(name=>[name,[]]));
    let commandCount = 0n;
    for (const record of records) {
      const part = cohort.get(record.partition);
      requireThat(part && record.batchId && !batches.has(record.batchId), 'foreign or duplicate source batch');
      batches.add(record.batchId);
      requireThat(typeof record.commandStream === 'string' && record.commandStream.length > 0, 'missing command stream');
      streams.add(record.commandStream);
      requireThat(/^[a-f0-9]{64}$/.test(record.payloadChecksum), 'missing semantic membership checksum');
      const count = integer(record.commandCount);
      requireThat(count > 0n && Array.isArray(record.streamSequences) && BigInt(record.streamSequences.length) === count, 'batch membership count mismatch');
      let frontier = 0n;
      for (const raw of record.streamSequences) {
        const sequence = integer(raw);
        requireThat(sequence >> 48n === BigInt(record.partition), 'wrong source partition');
        const offset = (sequence & ((1n << 48n) - 1n)) - 1n;
        requireThat(offset >= integer(part.startOffsetInclusive) && offset < integer(part.endOffsetExclusive), 'source member outside cohort');
        const seen = members.get(record.partition);
        requireThat(!seen.has(raw.toString()), 'duplicate source member');
        seen.add(raw.toString());
        if (sequence > frontier) frontier = sequence;
      }
      const source = instant(record.workFinishedAt), canonical = instant(record.canonicalCommitObservedAt);
      requireThat(canonical >= source, 'canonical clock reversed');
      const lifecycle = coveringObservation(markers?.lifecycle, record.partition, frontier, canonical);
      const market = coveringObservation(markers?.marketData, record.partition, frontier, canonical);
      const weight = Number(count);
      requireThat(Number.isSafeInteger(weight), 'unsafe timing weight');
      stages.sourceToCanonical.push([canonical-source,weight]);
      stages.sourceToLifecycle.push([lifecycle-source,weight]);
      stages.sourceToMarketData.push([market-source,weight]);
      stages.canonicalToLifecycle.push([lifecycle-canonical,weight]);
      stages.canonicalToMarketData.push([market-canonical,weight]);
      commandCount += count;
    }
    requireThat(streams.size === 1 && records.length > 0, 'missing or mixed command stream');
    for (const part of partitions) {
      const span = integer(part.endOffsetExclusive) - integer(part.startOffsetInclusive);
      requireThat(span > 0n && span === integer(part.accepted) && BigInt(members.get(part.partition).size) === span, 'timing cohort incomplete');
    }
    return {pass:true,authority:'source-membership-to-sampled-commit-upper-bound-v1',commandCount:commandCount.toString(),batchCount:records.length,stages:Object.fromEntries(Object.entries(stages).map(([key,values])=>[key,aggregate(values)]))};
  } catch(error) {
    return {pass:false,authority:'source-membership-to-sampled-commit-upper-bound-v1',reason:error.message};
  }
}

function coveringObservation(markers, partition, frontier, canonical) {
  requireThat(Array.isArray(markers), 'missing covering observations');
  const observations=[];
  for (const marker of markers) {
    if (marker?.postCommitObserved !== true) continue;
    const row = marker.sourceWatermarks?.find(row=>row.partition === partition);
    if (!row || integer(row.lastPartitionSequence) < frontier) continue;
    const observed = instant(marker.postCommitObservedAt), database = instant(marker.databaseSnapshotAt);
    requireThat(observed >= database, 'covering clock reversed');
    if (observed >= canonical) observations.push(observed);
  }
  requireThat(observations.length > 0, 'source member has no post-commit covering observation');
  return Math.min(...observations);
}
function aggregate(values) {
  const sorted = values.toSorted((a,b)=>a[0]-b[0]);
  const count = sorted.reduce((sum,row)=>sum+row[1],0);
  const percentile=(p)=>{let cumulative=0;for(const [value,weight] of sorted){cumulative+=weight;if(cumulative>=Math.ceil(count*p))return value;}};
  return {commandCount:String(count),meanMs:sorted.reduce((sum,[value,weight])=>sum+value*weight,0)/count,maxMs:sorted.at(-1)[0],p95Ms:percentile(.95),p99Ms:percentile(.99)};
}
function integer(value) {
  requireThat(typeof value === 'string' && /^(0|[1-9][0-9]*)$/.test(value), 'missing or invalid exact counter');
  return BigInt(value);
}
function instant(value) {const time=typeof value==='string' && value.length>0 ? Date.parse(value) : NaN;requireThat(Number.isFinite(time),'missing or invalid timestamp');return time;}
function requireThat(condition,message){if(!condition)throw new Error(message);}
