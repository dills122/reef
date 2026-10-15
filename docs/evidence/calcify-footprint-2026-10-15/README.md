# Calcify footprint verification — October 15, 2026

Current findings: [heap](../../work/CALCIFY_HEAP_FOOTPRINT_SPIKE.md),
[bytes](../../work/CALCIFY_BYTES_FOOTPRINT_SPIKE.md).
Baseline `f09f20c7e9699dcb4156fe67eaeaa19b009452ae`; byte-identical production prototype
`447e5ea1e`; executable probes `359fdf0ea`, published-input differential `cb85ca581`. Apple M1 Max, JDK 21, Docker Redpanda v26.2.3/Postgres 16. No cloud use.

Scope: standalone live ledger state and thread CPU, both decision modes; real one-lane
300k-trade broker/projector inventory; offline 9M/18M combined-state GC diagnostics.
No financial-authority, integrated 10k/s or durable hardware capacity claim.

## Verification

- Environment: full dev doctor passed; tooling tests 71 passed. Go updated to 1.27.2;
  locked JS dependencies installed. Kotlin LSP pin updated to current official
  263.6379.0; standalone initialization passed. Existing Serena MCP connection needs
  reconnect to discard expired cached server. No trust settings changed.
- Clean-checkout default tests, coverage gate and runtime bench passed. Initial
  compiler OOM preserved; 3g compiler retry passed, no test threshold change.
- Baseline/candidate seeded byte probes match both modes, including refusals,
  decision/store encoding and periodic restore. Frozen hashes pinned in test.
- Independent state commitment/binding/startup Python encoders passed.
- Settled-residual, status-only restore and trailing-byte mutants fail tests.
  Old trie accessor shift caused nontermination; owned worker stopped after bounded
  diagnostic window. Separate out-of-range accessor mutant fails immediately.
  Restored-source focused tests passed.
- Two fresh-context reviews: no blocking findings. Inventory count assertions added.
- Broker restart/wiped-state, projector parity and final segment inventory passed.
  Combined G1/Parallel prototype and matched G1 baseline scale probes completed;
  two-step session-sized collector runs in progress.
  Final finance suite/runtime guardrail passed on `cb85ca581`.
- No Docker containers remain; two pre-existing unnamed volumes preserved.
- Final evidence review and complete raw archive publication pending.

## Retention and delivery

Complete raw attempts, failures, corrections, reviews and provenance publish to
`reef-records`; immutable link pending. This focused summary and active owner notes
remain in Reef. Handoff/store-layout proposals still contain live contract decisions;
no unrelated historical sweep. PR573 prerequisite remains open at last check, so
safe-win code PR is held rather than stacked. No contract changes implemented.
