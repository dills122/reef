# Runtime order identity verification

Latest complete R08 / [PR #463](https://github.com/dills122/reef/pull/463) verification bundle, rehomed October 2 without changing original bytes. [Provenance](provenance.json) maps files to original source commit and archive. Current behavior/rollout owner: [Runtime order identity](../../RUNTIME_ORDER_IDENTITY.md).

Final author full check passed 706 tests with five explicit DB skips; focused real-DB suite passed 75 tests without skips. Independent final review passed 12 focused tests and rollback-only canonical SQL probe. Full-check DB skips do not prove SQL behavior. [Final review](R08-review-3.md) retains scope and limits.

[CI follow-up](R08-ci-followup.md) records packaging/schema-fixture corrections and commands. Final runtime/Arena builds, schema placement, Node tooling and expected missing-resource rejection logs retained together. Original `/private/tmp` paths and pending-push statements describe execution time. PR #463 subsequently merged. This bundle makes no new qualification claim for later PR #465 changes, production cutover, rollback or throughput.

Earlier red attempts and completed plans live in [Records manifest](https://github.com/dills122/reef-records/blob/6b838e5287c5428c914f6577ff176c1cd03c44ec/manifests/2026-10-02-reef-second-pass.json). Read failures/corrections there when comparison matters.
