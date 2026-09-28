# Settlement journal logged-write shape — 2026-09-28

Status: isolated local falsifier, not a settlement implementation or 10k/s
qualification. Checkout: `codex/ledger-authority-redesign` from
`646987cd0c7b40e3cf0770e552ab7c4635254dcc`, with uncommitted proof test.
Host: Darwin 25.6.0 arm64. Database: local `reef-settlement-postgres`,
PostgreSQL 16.15, `synchronous_commit=on`, `fsync=on`,
`full_page_writes=on`, `shared_buffers=128MB`. Test creates and drops a
per-run permanent, logged schema in the dedicated settlement `reef` database.

Command for all runs:

```sh
cd services/platform-runtime
SETTLEMENT_POSTGRES_JDBC_URL_TEST=jdbc:postgresql://127.0.0.1:5437/reef \
SETTLEMENT_POSTGRES_USER_TEST=reef \
SETTLEMENT_JOURNAL_PROOF_BENCH=1 \
./gradlew test --offline --console=plain \
  --tests com.reef.platform.infrastructure.persistence.SettlementJournalWriteShapeProofTest
```

Each numbered run below used a fresh test invocation and eight 640-result
batches. Iterations 0–1 are warm-ups; 2–7 are reported samples. Timed append
includes `SELECT ... FOR UPDATE` on the head, one header insert, JDBC-batched
typed result insert, fenced head update and commit. Evaluation is separate.
WAL byte delta is `pg_wal_lsn_diff` around the append. Only one connection and
no concurrent producer. The synthetic input and output exist in JVM memory
before timing; database schema creation and cleanup are excluded.

| Run | Fixture / code change | Outcome | Append ms, iterations 0–7 | WAL bytes, iterations 0–7 |
| --- | --- | --- | --- | --- |
| 1 | Two source accounts, compact 11-kind workflow | 2 pass, 0 skip | 40.336, 32.159, 28.417, 26.555, 27.116, 27.690, 26.585, 27.695 | 440920, 441512, 440496, 441456, 440520, 441456, 440520, 441392 |
| 2 | 160 source accounts, full 11-stage workflow strings | 2 pass, 0 skip | 38.225, 38.259, 30.522, 27.585, 27.236, 25.816, 26.387, 27.750 | 989344, 989880, 988960, 989880, 988920, 989880, 988944, 989816 |
| 3 | Length-delimited result digest, chained batch/head digest, replay/tamper check | 2 pass, 0 skip | 39.418, 32.437, 31.409, 30.673, 28.178, 27.896, 28.073, 26.510 | 989592, 990152, 989216, 990152, 989216, 990152, 989192, 990088 |
| 4 | Add pre-commit rollback injection and owner-epoch takeover | 1 fail, 1 pass | Benchmark output not retained; correctness failure before injected crash | Test appended a `PENDING` fixture result; database check rejected it before the intended failure injection. |
| 5 | Correct injected result to `SETTLED` | 2 pass, 0 skip | 46.196, 37.098, 33.446, 34.410, 32.119, 37.067, 36.101, 36.255 | 989616, 990152, 989216, 990152, 989192, 990128, 989216, 990088 |
| 6 | Verify stored rows on ambiguous duplicate, detect tampered retry | 2 pass, 0 skip | 41.228, 33.562, 31.388, 30.419, 29.652, 31.170, 28.918, 28.534 | 989592, 990152, 989216, 990168, 989216, 990152, 989216, 990064 |

The correction in run 5 changes only the test fixture. Run 4 is not a
throughput sample. The six warm append samples in run 5 span 32.12–37.07 ms
with median 36.10 ms; evaluation spans 0.71–1.00 ms. The earlier warm ranges
remain in the table because run ordering and host state visibly matter.
Run 6's six warm append samples span 28.53–31.39 ms with median 30.42 ms;
the added duplicate verification is outside the timed first-append path.

Correctness checks in the final proof: scarce security chooses the first
trade and breaks the second, identical lost-reply retry does not append twice,
changed duplicate fails, injected pre-commit error rolls back header/results/
head together, stale epoch fails after takeover, chained replay verifies rows,
and changed cash amount fails replay and duplicate verification. The test is not independent
reference parity against the existing normalized settlement path. It has no
canonical source coverage, immutable policy/opening/funding log, snapshot,
separate-target restore, public projection or sustained input. The SQL schema
is a disposable proof schema, not a migration or public read contract.
