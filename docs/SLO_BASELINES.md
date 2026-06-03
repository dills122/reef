# Reef Baseline SLO Targets (Sprint Baseline)

## Purpose

Provide explicit baseline service-level objectives for early-stage architecture discipline and regression detection.

## Targets

1. Order ingestion and engine round-trip
- Success rate: >= 99.0% for valid submit requests in steady-state load tests.
- Latency: p95 <= 25ms, p99 <= 75ms.

2. External boundary (`/api/v1`) validation path
- Success rate: >= 99.9% for syntactically valid authenticated/idempotent requests.
- Latency overhead vs non-boundary path: p95 delta <= 10ms.

3. Compare/Affirmation stage (post-match baseline)
- Stage transition success: >= 99.0% for happy-path scenarios.
- Time to transition (simulated clock adjusted): p95 <= 2s equivalent.

4. Clearing decisioning stage (post-match baseline)
- Stage transition success: >= 99.0% for valid affirmed inputs.
- Decision latency: p95 <= 3s equivalent.

5. Netting batch processing (post-match baseline)
- Batch completion success: >= 99.0%.
- Batch processing latency: p95 <= 5s equivalent for baseline scenario size.

6. Settlement intake enqueue
- Enqueue success: >= 99.9%.
- Enqueue latency: p95 <= 500ms, p99 <= 1s.

## Measurement Notes

- Baselines are validated by deterministic load/simulation scenarios and CI test coverage where practical.
- Targets are intentionally conservative for current architecture maturity and should be tightened after dev-env sprint stabilization.

## Single-Instance Capacity Goal

Purpose:
- establish an explicit scaling target for one runtime + one engine instance before horizontal scaling.

Targets:

1. Near-term target (after dev-env + post-match foundation hardening):
- sustained throughput: 500 to 1,000 req/s
- latency: p95 < 20ms, p99 < 75ms on core order APIs

2. Mid-term target (after persistence and rate-limit backend hardening):
- sustained throughput: 1,500 to 3,000 req/s
- latency: p95 < 30ms, p99 < 120ms

3. Stretch target (well-tuned single-node profile):
- sustained throughput: 3,000 to 5,000 req/s
- maintain stable error rates and acceptable latency envelopes

Planning anchor:
- product planning objective is 5,000 accepted req/s per runtime + engine instance.
- horizontal scale-out should multiply this per-instance unit after durability, p99 latency, and success rate are stable.
- higher single-instance throughput remains optimization upside, but 5,000 accepted req/s is the architecture target for this phase.
