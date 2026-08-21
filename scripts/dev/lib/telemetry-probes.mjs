export function runProbesConcurrently(probes, requestProbe) {
  return Promise.all(probes.map((probe) => requestProbe(probe)));
}
