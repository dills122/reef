import http from "node:http";
import https from "node:https";

export function runProbesConcurrently(probes, requestProbe) {
  return Promise.all(probes.map((probe) => requestProbe(probe)));
}

export function requestHttpProbe(probe) {
  const started = Date.now();
  return new Promise((resolve) => {
    const url = new URL(probe.url);
    const client = url.protocol === "https:" ? https : http;
    const headers = url.pathname.startsWith("/internal/")
      ? { "X-Reef-Internal-Route": "true" }
      : {};
    const timeoutMs = Math.max(1, Number(probe.timeoutMs ?? 2000));
    let settled = false;
    let request;
    const finish = (result) => {
      if (settled) return;
      settled = true;
      clearTimeout(deadline);
      resolve(result);
    };
    const deadline = setTimeout(() => {
      request?.destroy();
      finish({
        name: probe.name,
        ok: false,
        latencyMs: Date.now() - started,
        error: `total timeout after ${timeoutMs}ms`,
      });
    }, timeoutMs);
    request = client.request(url, { method: probe.method ?? "GET", headers }, (response) => {
      const chunks = [];
      let bytes = 0;
      response.on("data", (chunk) => {
        bytes += chunk.length;
        if (bytes <= 1024 * 1024) chunks.push(chunk);
      });
      response.on("end", () => {
        const result = {
          name: probe.name,
          status: response.statusCode,
          ok: response.statusCode >= 200 && response.statusCode < 300,
          latencyMs: Date.now() - started,
        };
        if (probe.captureJson) {
          try {
            result.json = JSON.parse(Buffer.concat(chunks).toString("utf8"));
          } catch (error) {
            result.bodyError = String(error.message || error);
          }
        }
        finish(result);
      });
    });
    request.on("error", (error) => {
      finish({
        name: probe.name,
        ok: false,
        latencyMs: Date.now() - started,
        error: String(error.message || error),
      });
    });
    request.end();
  });
}
