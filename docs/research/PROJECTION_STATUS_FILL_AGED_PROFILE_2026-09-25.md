# Aged status/fill SQL profile — 2026-09-25

## Decision

Do not change the status/fill SQL from this diagnostic. Five writes all contribute material cost, and the stage alone processed substantially more than 10,000 synthetic outcomes/s on the aged C32 database copy. That does not establish full-pipeline headroom: the full C28/C32 controls still fail downstream freshness. The next useful experiment must capture waits, I/O, and phase timing while the *whole* materializer/projector/lifecycle/market pipeline is running, then change one supported lever. A zero-scan index is not by itself a removal candidate; C31 already rejected the submit-result time-index removal.

## Authority and fixture

The retained C32 no-profiler control accepted/materialized/projected 3,000,003 commands after drain at the 10,000/s target, but failed lifecycle/market conservative p95 freshness (6,867/7,777ms) and missed sampler authority by 1ms. C28 had valid source/cohort authority but failed the same p95 bounds (6,314/7,315ms). C30's nested profiler attributed 357.8ms mean and 8.93GB WAL to the combined status/fill statement under hosted load; profiler overhead prevents a causal latency comparison with C28/C32. No new full-pipeline qualification is claimed here.

Profiling used a task-owned copy of C32's stopped-writer projection database on the authorized c-32 host: PostgreSQL 16.15, 13GB database, 2GB shared buffers, JIT enabled, 3,000,003 submit results, 2,471,717 orders, 2,635,552 executions, and 1,317,776 trades. Original C32 database still had 3,000,003 submit results after profiling. The copied database was removed; raw scripts, plans, and counter snapshots remain in ignored `artifacts/sustained-10k-20260924/status-fill-aged-20260925/`.

Synthetic 500-outcome mixed batches contained 380 accepted and 120 rejected results, 440 executions, and 220 trades. These approximate C32's global result/execution/trade ratios. Payloads were precomputed outside measured calls, with unique command/event/order IDs. The fixture does not reproduce C32's entire order mix, timeline persistence, materializer, lifecycle and market workers, API reads, or cache pressure.

## Single-session baseline and nested plan

After one warmup of each shape, five uninstrumented batches per shape gave:

| Shape | Median per 500 outcomes | Range |
| --- | ---: | ---: |
| 500 accepted, no fills/trades | 64.976ms | 64.031–68.730ms |
| Mixed 380/120, 440 executions, 220 trades | 118.603ms | 116.834–124.711ms |

One additional mixed call with `auto_explain` nested JSON, `ANALYZE`, buffers and WAL reported 1.526ms for the separate replay-conflict check and 112.359ms for the combined status/fill statement. Its write nodes reported:

| Target | Rows | Node time | WAL bytes |
| --- | ---: | ---: | ---: |
| `submit_results` | 500 | 19.818ms | 281,716 |
| `orders` | 380 | 21.345ms | 256,252 |
| `executions` | 440 | 28.210ms | 312,192 |
| `trades` | 220 | 23.295ms | 283,238 |
| `order_lifecycle_dirty` | 720 | 18.576ms | 0 (unlogged) |

The outer call reported 123.051ms and 1,133,461 WAL bytes. Node timings and parent timings are not an additive wall-clock budget. No one write dominates, and removing any canonical fact would violate replay/business state. The plan is one idle, aged-fixture call; it does not assign C30's 357.8ms to a particular table.

## Concurrent stage-only baseline

Sixteen prepared `pgbench` clients each executed 30 unique precomputed mixed batches: 480 calls / 240,000 outcomes per run. No timeline or downstream workers ran. Both valid runs completed with zero failed transactions:

| Seller-order shape | Aggregate time per client's 30 calls | Derived stage-only rate | WAL delta | Dirty inserts |
| --- | ---: | ---: | ---: | ---: |
| Disjoint existing seller IDs across writers and batches | 4,528.202ms | ~53,001 outcomes/s | 569,322,669 bytes | 345,380 |
| Maker/seller order paired with another new accepted order in same batch | 4,517.207ms | ~53,130 outcomes/s | 567,086,209 bytes | 240,000 |

The paired case is closer to C32's dirty-insert density (2,892,695 inserts for 3,000,003 results). The ~0.2% difference between these two sequential, single-run synthetic variants is not a causal improvement estimate. Both wrote exactly 240,000 submit results, 182,400 orders, 211,200 executions, and 105,600 trades, with no updates to those four tables. WAL was about 2.36–2.37KB per outcome; the C32 full projection database generated 18.0GB WAL across *all* work, including timeline and maintenance. The stage-only rate cannot be read as full-pipeline capacity or a sustained qualification.

An initial concurrent run reused the same 220 seller orders across every writer and accidentally enabled `pgbench -d` debug logging. Eleven of 16 writers were observed waiting on transaction-ID locks, and the run was much slower. It is excluded from the performance comparison. C32's actual dirty queue recorded 45,307 updates versus 2,892,695 inserts (~1.6%); its execution count per order had p95 5 and maximum 15. Thus that initial synthetic conflict pattern materially overstated observed C32 order reuse.

## Implication and next measurement

C32 recorded zero updates to `submit_results`, `orders`, `executions`, and `trades`; optimizing the existing replay-conflict update arms would not improve this workload. All five status/fill writes have measurable cost, but the isolated stage did not reproduce C30's hosted per-call delay. Competing pipeline work, I/O, and observation/queueing remain plausible; the current evidence cannot apportion them. C28's same-prefix diagnostic attributed p95 3,701ms from durable materializer commit to lifecycle-prefix recording and 2,022ms from prefix recording to lifecycle coverage. That is the stronger lead for the failing end-to-end bound.

For a next SQL change, run one matched no-profiler full-pipeline control on the current image, then a bounded diagnostic with wait-event/I/O sampling and low-rate nested plans during the same workload shape. Keep profiler overhead outside the control comparison. Retain a single lever only if it reduces the measured full-pipeline bound while preserving exact cohort, business/replay equality, and the frozen 5s/10s/30s freshness and 20% drain gates.

Raw SHA-256: uninstrumented batch output `2e8f5372edd3ff529335f39512dffb38c78c2d5e69d23e330a65ae7b8517ea8b`; nested plan `948bece5e5e9d278b801d708a8fec05d61994d62398a78fd0ae014d14fb53e87`; disjoint concurrent output `d769797140cba922ba7365d3984782cc4381e8b820f739c01bebecc2b9f26520`; paired concurrent output `4a745bc7c93584ab9a7a3be20d56048f732776d81fb49d52f2a3b23b83dce636`.
